package xyz.totemai.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

data class TotemWorkspace(
    val slug: String,
    val name: String
)

data class TotemChatThread(
    val id: Long,
    val slug: String,
    val title: String,
    val preview: String
)

data class TotemChatMessage(
    val id: String,
    val fromUser: Boolean,
    val text: String
)

data class TotemWorkspaceSnapshot(
    val threads: List<TotemChatThread>,
    val messagesByThread: Map<String, List<TotemChatMessage>>
)

data class TotemWalletBalance(
    val available: Boolean,
    val noWallet: Boolean,
    val amount: String?,
    val symbol: String?,
    val chainId: Int?,
    val error: String?
)

data class TotemInferenceOption(
    val key: String,
    val label: String,
    val description: String,
    val available: Boolean
)

data class TotemInferenceOptions(
    val options: List<TotemInferenceOption>,
    val selected: String?
)

data class TotemLoginResult(
    val valid: Boolean,
    val token: String?,
    val userJson: String?,
    val recoveryCodes: List<String>,
    val message: String?
)

data class TotemAgentToolApproval(
    val requestId: String,
    val skillName: String,
    val description: String?,
    val payloadJson: String,
    val timeoutMs: Long
)

sealed interface TotemChatStreamEvent {
    data class ToolApprovalRequested(val approval: TotemAgentToolApproval) : TotemChatStreamEvent
    data class Text(val value: String, val replace: Boolean = false) : TotemChatStreamEvent
    data class Failed(val message: String) : TotemChatStreamEvent
    data object Finished : TotemChatStreamEvent
}

private data class ParsedSseEvent(
    val event: TotemChatStreamEvent? = null,
    val agentInvocationId: String? = null
)

private class AgentSession(
    val workspaceSlug: String,
    val threadSlug: String,
    val accountToken: String,
    @Volatile var sink: SendChannel<TotemChatStreamEvent>?,
    @Volatile var turnCompletion: CompletableDeferred<Unit>?
) {
    @Volatile var socket: WebSocket? = null
    @Volatile var awaitingFeedback: Boolean = false
    @Volatile var streamingSeen: Boolean = false
    @Volatile var pendingToolApprovalRequestId: String? = null
}

/**
 * Thin Android client for the official TOTEM hosted API.
 *
 * All account-scoped operations, including chat streaming, use the official
 * signed-in account session token and the hosted platform's normal user APIs.
 */
class TotemApi(private val store: SessionStore) {
    data class AuthConfig(val multiUser: Boolean, val requiresAuth: Boolean)

    private val apiBase = TotemConfig.ORIGIN + "/api"
    private val webSocketClient = OkHttpClient.Builder()
        // OkHttp's default connection timeout is 10 seconds. The hosted
        // endpoint may take longer to complete a WebSocket upgrade on mobile
        // networks, so give the initial handshake a little more room.
        .connectTimeout(30, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
    private val agentLock = Any()
    @Volatile private var activeAgentSession: AgentSession? = null

    /** Reads only the public authentication flags required to choose the login form. */
    suspend fun authConfig(): AuthConfig {
        val response = request("GET", "/setup-complete")
        val settings = response.optJSONObject("results")
            ?: throw IllegalStateException("The TOTEM server did not return its sign-in settings.")
        return AuthConfig(
            multiUser = settings.optBoolean("MultiUserMode", false),
            requiresAuth = settings.optBoolean("RequiresAuth", false)
        )
    }

    /** Uses the same official session endpoint as the hosted TOTEM login page. */
    suspend fun signIn(
        username: String,
        password: String,
        multiUser: Boolean
    ): TotemLoginResult {
        val body = JSONObject().put("password", password)
        if (multiUser) body.put("username", username)
        val response = request("POST", "/request-token", body = body)
        val valid = response.optBoolean("valid", false)
        val token = response.optString("token").takeIf { it.isNotBlank() && it != "null" }
        val user = response.optJSONObject("user")
        val codes = response.optJSONArray("recoveryCodes")
        val recoveryCodes = buildList {
            if (codes != null) {
                for (index in 0 until codes.length()) {
                    val code = codes.optString(index)
                    if (code.isNotBlank()) add(code)
                }
            }
        }
        return TotemLoginResult(
            valid = valid && !token.isNullOrBlank(),
            token = token,
            userJson = user?.toString(),
            recoveryCodes = recoveryCodes,
            message = response.optString("message").takeIf { it.isNotBlank() && it != "null" }
        )
    }

    /** Public signup is exposed only when the server admin enables it. */
    suspend fun publicRegistrationEnabled(): Boolean {
        val response = request("GET", "/system/public-registration")
        return response.optBoolean("publicRegistration", false)
    }

    /** Creates a new account through the same public endpoint as the hosted login page. */
    suspend fun registerUser(username: String, email: String, password: String) {
        val response = request(
            method = "POST",
            path = "/system/new-user",
            body = JSONObject()
                .put("username", username)
                .put("email", email)
                .put("password", password)
        )
        if (!response.optBoolean("success", false)) {
            throw IllegalStateException(
                response.optString("error").takeIf { it.isNotBlank() }
                    ?: "Account registration failed."
            )
        }
    }

    /** Exchanges two saved recovery codes for a short-lived password-reset token. */
    suspend fun recoverAccount(username: String, recoveryCodes: List<String>): String {
        val codes = JSONArray()
        recoveryCodes.forEach { codes.put(it) }
        val response = request(
            method = "POST",
            path = "/system/recover-account",
            body = JSONObject()
                .put("username", username)
                .put("recoveryCodes", codes)
        )
        val token = response.optString("resetToken")
        if (!response.optBoolean("success", false) || token.isBlank()) {
            throw IllegalStateException(
                response.optString("message").takeIf { it.isNotBlank() }
                    ?: "Account recovery failed."
            )
        }
        return token
    }

    /** Completes password reset using the single-use token from account recovery. */
    suspend fun resetPassword(token: String, newPassword: String, confirmPassword: String) {
        val response = request(
            method = "POST",
            path = "/system/reset-password",
            body = JSONObject()
                .put("token", token)
                .put("newPassword", newPassword)
                .put("confirmPassword", confirmPassword)
        )
        if (!response.optBoolean("success", false)) {
            throw IllegalStateException(
                response.optString("error").takeIf { it.isNotBlank() }
                    ?: response.optString("message").takeIf { it.isNotBlank() }
                    ?: "Password reset failed."
            )
        }
    }

    /**
     * Uses the authenticated account session directly. The normal workspace
     * API supports every signed-in user; mobile-device registration is admin
     * restricted on multi-user deployments and must not be a prerequisite.
     */
    suspend fun workspaces(): List<TotemWorkspace> {
        val session = requireSession()
        val response = request("GET", "/workspaces", bearer = session)
        val array = response.optJSONArray("workspaces") ?: JSONArray()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val slug = item.optString("slug")
                if (slug.isBlank()) continue
                add(TotemWorkspace(slug, item.optString("name", "Workspace")))
            }
        }
    }

    suspend fun workspaceContent(workspaceSlug: String): TotemWorkspaceSnapshot {
        val session = requireSession()

        // The hosted client stores default-thread history separately from
        // each named thread. These are the same account-scoped endpoints the
        // official TOTEM web client uses.
        val rawThreads = request(
            "GET",
            "/workspace/$workspaceSlug/threads",
            bearer = session
        ).optJSONArray("threads") ?: JSONArray()

        val defaultHistory = request(
            "GET",
            "/workspace/$workspaceSlug/chats",
            bearer = session
        ).optJSONArray("history")
        val messages = LinkedHashMap<String, List<TotemChatMessage>>()
        messages[DEFAULT_THREAD] = parseHistory(defaultHistory, DEFAULT_THREAD)

        val threads = ArrayList<TotemChatThread>()
        val defaultPreview = messages[DEFAULT_THREAD]
            ?.firstOrNull { it.fromUser }?.text.orEmpty()
        threads.add(
            TotemChatThread(
                id = 0L,
                slug = DEFAULT_THREAD,
                title = if (defaultPreview.isNotBlank()) defaultPreview.take(44) else "Default Thread",
                preview = defaultPreview
            )
        )

        for (index in 0 until rawThreads.length()) {
            val item = rawThreads.optJSONObject(index) ?: continue
            val slug = item.optString("slug")
            if (slug.isBlank()) continue

            val history = request(
                "GET",
                "/workspace/$workspaceSlug/thread/$slug/chats",
                bearer = session
            ).optJSONArray("history")
            val threadMessages = parseHistory(history, slug)
            messages[slug] = threadMessages
            val preview = threadMessages.firstOrNull { it.fromUser }?.text.orEmpty()
            threads.add(
                TotemChatThread(
                    id = item.optLong("id", 0L),
                    slug = slug,
                    title = if (preview.isNotBlank()) preview.take(44)
                        else item.optString("name", "New chat"),
                    preview = preview
                )
            )
        }

        return TotemWorkspaceSnapshot(
            threads = threads.sortedByDescending { it.id },
            messagesByThread = messages
        )
    }

    suspend fun newThread(workspaceSlug: String): TotemChatThread {
        val session = requireSession()
        val response = request(
            method = "POST",
            path = "/workspace/$workspaceSlug/thread/new",
            bearer = session
        )
        val thread = response.optJSONObject("thread")
            ?: throw IllegalStateException("The TOTEM server did not return the new chat.")
        val slug = thread.optString("slug")
        if (slug.isBlank()) throw IllegalStateException("The new chat has no ID.")
        return TotemChatThread(
            id = thread.optLong("id", 0L),
            slug = slug,
            title = thread.optString("name", "New chat"),
            preview = ""
        )
    }

    /** Loads the official server-provided inference choices for this workspace. */
    suspend fun inferenceOptions(workspaceSlug: String): TotemInferenceOptions {
        val session = requireSession()
        val response = request(
            "GET",
            "/workspace/$workspaceSlug/inference-options",
            bearer = session
        )
        val array = response.optJSONArray("options") ?: JSONArray()
        val options = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val key = item.optString("key")
                if (key.isBlank()) continue
                add(
                    TotemInferenceOption(
                        key = key,
                        label = item.optString("label").takeIf { it.isNotBlank() } ?: key,
                        description = item.optString("description"),
                        available = item.optBoolean("available", false)
                    )
                )
            }
        }
        val selected = response.optString("selected")
            .takeIf { it.isNotBlank() && it != "null" }
        return TotemInferenceOptions(options = options, selected = selected)
    }

    /** Saves the selected option on the server so it also applies after app restarts. */
    suspend fun setInferenceOption(workspaceSlug: String, optionKey: String) {
        val session = requireSession()
        val response = request(
            method = "POST",
            path = "/workspace/$workspaceSlug/inference-option",
            bearer = session,
            body = JSONObject().put("option", optionKey)
        )
        if (!response.optBoolean("success", false)) {
            throw IllegalStateException(
                response.optString("message").takeIf { it.isNotBlank() }
                    ?: response.optString("error").takeIf { it.isNotBlank() }
                    ?: "Could not change the TOTEM inference option."
            )
        }

        // An agent WebSocket keeps the model chosen when that turn began.
        // Close an idle session after switching so the next message uses the new option.
        resetAgentSessionForInferenceChange()
    }

    private fun resetAgentSessionForInferenceChange() {
        val previous = synchronized(agentLock) {
            activeAgentSession.also { activeAgentSession = null }
        } ?: return

        previous.awaitingFeedback = false
        previous.socket?.close(1000, "Inference option changed")
        previous.sink = null
        previous.turnCompletion?.complete(Unit)
        previous.turnCompletion = null
    }

    fun streamChat(
        workspaceSlug: String,
        threadSlug: String,
        message: String
    ): Flow<TotemChatStreamEvent> = channelFlow {
        val producer = this
        val session = requireSession()
        val normalizedThread = threadSlug.ifBlank { DEFAULT_THREAD }

        // Agent mode keeps a WebSocket open while it waits for the next user
        // turn. Reuse that socket for this same workspace/thread instead of
        // starting a second HTTP request that the server will route elsewhere.
        val existingAgent = synchronized(agentLock) { activeAgentSession }
        if (existingAgent != null &&
            existingAgent.workspaceSlug == workspaceSlug &&
            existingAgent.threadSlug == normalizedThread &&
            existingAgent.accountToken == session &&
            existingAgent.awaitingFeedback &&
            existingAgent.socket != null
        ) {
            val completion = CompletableDeferred<Unit>()
            synchronized(agentLock) {
                if (activeAgentSession !== existingAgent || !existingAgent.awaitingFeedback) {
                    throw IllegalStateException("The TOTEM agent session is no longer waiting for a message.")
                }
                existingAgent.sink = producer
                existingAgent.turnCompletion = completion
                existingAgent.awaitingFeedback = false
            }

            val feedback = JSONObject()
                .put("type", "awaitingFeedback")
                .put("feedback", message)
                .put("attachments", JSONArray())
                .toString()
            if (existingAgent.socket?.send(feedback) != true) {
                closeAgentSession(existingAgent, "Could not send the next message to the TOTEM agent.")
                throw IllegalStateException("The TOTEM agent connection was lost. Please send your message again.")
            }
            awaitAgentTurn(existingAgent, completion)
            return@channelFlow
        }

        // An agent session belonging to another conversation must never
        // receive a message intended for this one.
        if (existingAgent != null) {
            closeAgentSession(existingAgent, "Conversation changed.")
        }

        val chatPath = if (normalizedThread == DEFAULT_THREAD) {
            "/workspace/" + workspaceSlug + "/stream-chat"
        } else {
            "/workspace/" + workspaceSlug + "/thread/" + normalizedThread + "/stream-chat"
        }
        val body = JSONObject()
            .put("message", message)
            .put("attachments", JSONArray())

        val result = withContext(Dispatchers.IO) {
            val connection = (URL(apiBase + chatPath)
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20_000
                readTimeout = 180_000
                doOutput = true
                setRequestProperty("Accept", "text/event-stream")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("Authorization", "Bearer " + session)
            }

            var agentInvocationId: String? = null
            var finished = false

            suspend fun consumeSseData(raw: String) {
                val parsed = parseStreamEvent(raw)
                if (!parsed.agentInvocationId.isNullOrBlank()) {
                    agentInvocationId = parsed.agentInvocationId
                }
                parsed.event?.let { event ->
                    producer.send(event)
                    if (event is TotemChatStreamEvent.Finished) finished = true
                }
            }

            try {
                connection.outputStream.use {
                    it.write(body.toString().toByteArray(Charsets.UTF_8))
                }
                val status = connection.responseCode
                if (status !in 200..299) {
                    val errorBody = readStream(connection.errorStream)
                    throw IllegalStateException(extractError(errorBody, status))
                }

                val reader = connection.inputStream.bufferedReader(Charsets.UTF_8)
                val data = StringBuilder()
                while (true) {
                    val line = reader.readLine() ?: break
                    when {
                        line.startsWith("data:") -> {
                            if (data.isNotEmpty()) data.append('\n')
                            data.append(line.removePrefix("data:").trimStart())
                        }
                        line.isBlank() -> {
                            if (data.isNotEmpty()) {
                                consumeSseData(data.toString())
                                data.setLength(0)
                            }
                        }
                    }
                }
                if (data.isNotEmpty()) consumeSseData(data.toString())
            } finally {
                connection.disconnect()
            }
            agentInvocationId to finished
        }

        val invocationId = result.first
        if (!invocationId.isNullOrBlank()) {
            startAgentSession(invocationId, workspaceSlug, normalizedThread, session, producer)
        } else if (!result.second) {
            producer.send(TotemChatStreamEvent.Finished)
        }
    }

    private suspend fun startAgentSession(
        invocationId: String,
        workspaceSlug: String,
        threadSlug: String,
        accountToken: String,
        producer: SendChannel<TotemChatStreamEvent>
    ) {
        val completion = CompletableDeferred<Unit>()
        val agent = AgentSession(workspaceSlug, threadSlug, accountToken, producer, completion)
        val socketBase = apiBase
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
        val request = Request.Builder()
            .url(socketBase + "/agent-invocation/" + invocationId)
            // Match the Origin header sent by the official hosted web client.
            .header("Origin", TotemConfig.ORIGIN)
            .build()

        synchronized(agentLock) {
            activeAgentSession?.let { previous ->
                previous.socket?.close(1000, "Replacing agent session")
            }
            activeAgentSession = agent
        }

        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                handleAgentSocketMessage(agent, text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val detail = t.message?.takeIf { it.isNotBlank() }
                    ?: "The TOTEM agent WebSocket connection failed."
                finishAgentTurn(agent, TotemChatStreamEvent.Failed(detail), keepSession = false)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                finishAgentTurn(agent, null, keepSession = false)
            }
        }

        agent.socket = webSocketClient.newWebSocket(request, listener)
        awaitAgentTurn(agent, completion)
    }

    private suspend fun awaitAgentTurn(
        agent: AgentSession,
        completion: CompletableDeferred<Unit>
    ) {
        try {
            withTimeout(10 * 60 * 1000L) {
                completion.await()
            }
        } catch (e: TimeoutCancellationException) {
            closeAgentSession(agent, "The TOTEM agent took too long to respond.")
            throw IllegalStateException("The TOTEM agent took too long to respond. Please try again.")
        } catch (e: CancellationException) {
            closeAgentSession(agent, "Chat screen closed.")
            throw e
        }
    }

    /** Sends the user's explicit decision for the currently pending tool request. */
    fun respondToAgentToolApproval(requestId: String, approved: Boolean): Boolean {
        return synchronized(agentLock) {
            val agent = activeAgentSession
            if (agent == null || agent.pendingToolApprovalRequestId != requestId) {
                false
            } else {
                val sent = agent.socket?.send(
                    JSONObject()
                        .put("type", "toolApprovalResponse")
                        .put("requestId", requestId)
                        .put("approved", approved)
                        .toString()
                ) == true
                if (sent) agent.pendingToolApprovalRequestId = null
                sent
            }
        }
    }

    private fun handleAgentSocketMessage(agent: AgentSession, raw: String) {
        if (synchronized(agentLock) { activeAgentSession !== agent }) return
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val type = json.optString("type")

        when (type) {
            "WAITING_ON_INPUT" -> {
                finishAgentTurn(agent, null, keepSession = true)
            }
            "reportStreamEvent" -> {
                agent.streamingSeen = true
                val content = json.optJSONObject("content") ?: return
                val eventType = content.optString("type")
                val text = if (content.isNull("content")) "" else content.optString("content")
                val sink = agent.sink
                when (eventType) {
                    "textResponseChunk" -> if (text.isNotEmpty()) {
                        sink?.trySend(TotemChatStreamEvent.Text(text))
                    }
                    "fullTextResponse" -> if (text.isNotBlank()) {
                        sink?.trySend(TotemChatStreamEvent.Text(text, replace = true))
                    }
                }
            }
            "wssFailure" -> {
                val detail = json.optString("content")
                    .takeIf { it.isNotBlank() && it != "null" }
                    ?: "The TOTEM agent failed."
                finishAgentTurn(agent, TotemChatStreamEvent.Failed(detail), keepSession = false)
                agent.socket?.close(1011, "Agent failed")
            }
            "toolApprovalRequest" -> {
                val requestId = json.optString("requestId")
                if (requestId.isBlank()) return

                val skillName = json.optString("skillName").ifBlank { "requested tool" }
                val payloadJson = when (val payload = json.opt("payload")) {
                    is JSONObject -> runCatching { payload.toString(2) }.getOrDefault(payload.toString())
                    is JSONArray -> runCatching { payload.toString(2) }.getOrDefault(payload.toString())
                    null, JSONObject.NULL -> ""
                    else -> payload.toString()
                }
                val approval = TotemAgentToolApproval(
                    requestId = requestId,
                    skillName = skillName,
                    description = json.optString("description")
                        .takeIf { it.isNotBlank() && it != "null" },
                    payloadJson = payloadJson,
                    timeoutMs = json.optLong("timeoutMs", 120_000L).coerceIn(1_000L, 600_000L)
                )

                // The official agent protocol waits for an explicit response.
                // Keep the request pending until the user approves or rejects it.
                agent.pendingToolApprovalRequestId = requestId
                val delivered = agent.sink?.trySend(
                    TotemChatStreamEvent.ToolApprovalRequested(approval)
                )?.isSuccess == true

                // Fail closed if the chat UI cannot receive the approval prompt.
                if (!delivered) {
                    agent.pendingToolApprovalRequestId = null
                    agent.socket?.send(
                        JSONObject()
                            .put("type", "toolApprovalResponse")
                            .put("requestId", requestId)
                            .put("approved", false)
                            .toString()
                    )
                    agent.sink?.trySend(
                        TotemChatStreamEvent.Text("[Agent action denied: approval prompt unavailable.]\n")
                    )
                }
            }
            "clarificationRequest" -> {
                val requestId = json.optString("requestId")
                if (requestId.isNotBlank() && json.optBoolean("allowSkip", true)) {
                    agent.socket?.send(
                        JSONObject()
                            .put("type", "clarificationResponse")
                            .put("requestId", requestId)
                            .put("skipped", true)
                            .toString()
                    )
                } else {
                    finishAgentTurn(
                        agent,
                        TotemChatStreamEvent.Failed(
                            "The agent needs an interactive clarification that this mobile chat screen does not currently support."
                        ),
                        keepSession = false
                    )
                    agent.socket?.close(1000, "Clarification requires UI")
                }
            }
            "statusResponse", "rename_thread", "fileDownloadCard", "rechartVisualize" -> Unit
            else -> {
                // Some providers return a single non-streaming agent message
                // rather than reportStreamEvent chunks.
                if (!agent.streamingSeen && !json.isNull("content")) {
                    val text = json.optString("content")
                    if (text.isNotBlank() && text != "null") {
                        agent.sink?.trySend(TotemChatStreamEvent.Text(text))
                    }
                }
            }
        }
    }

    private fun finishAgentTurn(
        agent: AgentSession,
        event: TotemChatStreamEvent?,
        keepSession: Boolean
    ) {
        val sink: SendChannel<TotemChatStreamEvent>?
        val completion: CompletableDeferred<Unit>?
        synchronized(agentLock) {
            if (activeAgentSession !== agent) return
            sink = agent.sink
            completion = agent.turnCompletion
            agent.sink = null
            agent.turnCompletion = null
            agent.awaitingFeedback = keepSession
            if (!keepSession) {
                agent.pendingToolApprovalRequestId = null
                activeAgentSession = null
            }
        }

        event?.let { sink?.trySend(it) }
        if (event !is TotemChatStreamEvent.Failed) {
            sink?.trySend(TotemChatStreamEvent.Finished)
        }
        completion?.complete(Unit)
    }

    private fun closeAgentSession(agent: AgentSession, reason: String) {
        synchronized(agentLock) {
            if (activeAgentSession === agent) activeAgentSession = null
            agent.awaitingFeedback = false
            agent.pendingToolApprovalRequestId = null
        }
        agent.socket?.close(1000, reason.take(120))
        val sink = agent.sink
        agent.sink = null
        agent.turnCompletion?.complete(Unit)
        agent.turnCompletion = null
        sink?.trySend(TotemChatStreamEvent.Failed(reason))
    }

    suspend fun credits(): Long {
        val session = requireSession()
        val response = request("GET", "/credits", bearer = session)
        return response.optLong("balance", -1L)
    }

    suspend fun walletBalance(): TotemWalletBalance {
        val session = requireSession()
        val response = request("GET", "/wallet/balance", bearer = session)
        return TotemWalletBalance(
            available = response.optBoolean("available", false),
            noWallet = response.optBoolean("noWallet", false),
            amount = response.optString("amount").takeIf { it.isNotBlank() && it != "null" },
            symbol = response.optString("symbol").takeIf { it.isNotBlank() && it != "null" },
            chainId = if (response.has("chainId")) response.optInt("chainId") else null,
            error = response.optString("error").takeIf { it.isNotBlank() && it != "null" }
        )
    }

    private suspend fun requireSession(): String =
        store.token()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Please sign in to your TOTEM account first.")

    private suspend fun request(
        method: String,
        path: String,
        bearer: String? = null,
        body: JSONObject? = null
    ): JSONObject = withContext(Dispatchers.IO) {
        val connection = (URL(apiBase + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("Accept", "application/json")
            bearer?.let { setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }

        try {
            if (body != null) {
                connection.outputStream.use {
                    it.write(body.toString().toByteArray(Charsets.UTF_8))
                }
            }
            val status = connection.responseCode
            val raw = readStream(if (status in 200..299) connection.inputStream else connection.errorStream)
            if (status !in 200..299) {
                throw IllegalStateException(extractError(raw, status))
            }
            if (raw.isBlank()) JSONObject() else JSONObject(raw)
        } finally {
            connection.disconnect()
        }
    }

    private fun readStream(stream: java.io.InputStream?): String =
        stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

    private fun extractError(raw: String, status: Int): String {
        val detail = runCatching {
            val json = JSONObject(raw)
            json.optString("error").takeIf { it.isNotBlank() }
                ?: json.optString("message").takeIf { it.isNotBlank() }
        }.getOrNull()
        return detail ?: "TOTEM request failed (HTTP $status)."
    }

    private fun parseStreamEvent(raw: String): ParsedSseEvent {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return ParsedSseEvent()
        val type = json.optString("type")

        return when (type) {
            "agentInitWebsocketConnection" -> {
                val id = json.optString("websocketUUID")
                    .takeIf { it.isNotBlank() && it != "null" }
                ParsedSseEvent(agentInvocationId = id)
            }
            "textResponseChunk", "textResponse" -> {
                val text = if (json.isNull("textResponse")) "" else json.optString("textResponse")
                if (text.isBlank() || text == "null") ParsedSseEvent()
                else ParsedSseEvent(TotemChatStreamEvent.Text(text))
            }
            "abort" -> {
                val message = if (json.isNull("error")) "" else json.optString("error")
                ParsedSseEvent(
                    TotemChatStreamEvent.Failed(
                        message.takeIf { it.isNotBlank() && it != "false" && it != "null" }
                            ?: "The TOTEM server stopped before completing the answer."
                    )
                )
            }
            "finalizeResponseStream" -> ParsedSseEvent(TotemChatStreamEvent.Finished)
            // Agent handoff/status events are control messages, not assistant text.
            else -> ParsedSseEvent()
        }
    }

    private fun parseHistory(
        history: JSONArray?,
        threadKey: String
    ): List<TotemChatMessage> {
        if (history == null) return emptyList()
        return buildList {
            for (index in 0 until history.length()) {
                val item = history.optJSONObject(index) ?: continue
                val role = item.optString("role")
                val text = item.optString("content")
                if (text.isBlank()) continue
                when (role) {
                    "user" -> add(
                        TotemChatMessage(
                            id = "user-$threadKey-$index",
                            fromUser = true,
                            text = text
                        )
                    )
                    "assistant" -> add(
                        TotemChatMessage(
                            id = "assistant-$threadKey-$index",
                            fromUser = false,
                            text = text
                        )
                    )
                }
            }
        }
    }

    companion object {
        const val DEFAULT_THREAD = "default-thread"
    }
}
