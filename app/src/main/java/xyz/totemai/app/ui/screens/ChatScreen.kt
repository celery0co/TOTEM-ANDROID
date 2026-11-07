package xyz.totemai.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.totemai.app.R
import xyz.totemai.app.SessionStore
import xyz.totemai.app.TotemAgentToolApproval
import xyz.totemai.app.TotemApi
import xyz.totemai.app.TotemChatMessage
import xyz.totemai.app.TotemChatStreamEvent
import xyz.totemai.app.TotemChatThread
import xyz.totemai.app.TotemInferenceOption
import xyz.totemai.app.TotemWorkspace
import xyz.totemai.app.ui.theme.AppBackground
import xyz.totemai.app.ui.theme.InkElevated
import xyz.totemai.app.ui.theme.IsDarkTotemTheme
import xyz.totemai.app.ui.theme.Lime
import xyz.totemai.app.ui.theme.Outline
import xyz.totemai.app.ui.theme.Surface1
import xyz.totemai.app.ui.theme.TextPrimary
import xyz.totemai.app.ui.theme.TextSecondary
import xyz.totemai.app.ui.theme.Violet
import android.widget.Toast
import java.util.UUID

@Composable
fun ChatScreen(
    store: SessionStore,
    signedIn: Boolean,
    launchId: String,
    onSignIn: () -> Unit,
    onCreditsChanged: () -> Unit
) {
    val api = remember(store) { TotemApi(store) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val listState = rememberLazyListState()
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    var workspace by remember { mutableStateOf<TotemWorkspace?>(null) }
    var threads by remember { mutableStateOf<List<TotemChatThread>>(emptyList()) }
    var activeThreadSlug by rememberSaveable { mutableStateOf("") }
    var messages by remember { mutableStateOf<List<TotemChatMessage>>(emptyList()) }
    var input by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var inferenceOptions by remember { mutableStateOf<List<TotemInferenceOption>>(emptyList()) }
    var selectedInferenceOption by rememberSaveable { mutableStateOf<String?>(null) }
    var changingInferenceOption by remember { mutableStateOf(false) }
    var pendingToolApproval by remember { mutableStateOf<TotemAgentToolApproval?>(null) }
    var approvalDetailsExpanded by remember { mutableStateOf(false) }

    suspend fun loadChatWorkspace() {
        val available = api.workspaces()
        if (available.isEmpty()) {
            workspace = null
            threads = emptyList()
            activeThreadSlug = TotemApi.DEFAULT_THREAD
            messages = emptyList()
            error = "No workspace is available for this account."
            return
        }

        val selectedWorkspace = available.first()
        // Load the existing server history before exposing the chat composer.
        // Do not create a blank thread automatically on every app launch.
        val snapshot = api.workspaceContent(selectedWorkspace.slug)
        workspace = selectedWorkspace
        threads = snapshot.threads

        // The hosted API is the source of truth for option availability and selection.
        val inferenceConfig = runCatching {
            api.inferenceOptions(selectedWorkspace.slug)
        }.getOrNull()
        inferenceOptions = inferenceConfig?.options?.filter { it.available }.orEmpty()
        selectedInferenceOption = inferenceConfig?.selected

        val savedSelection = store.activeChat()
        val savedThread = savedSelection.second?.takeIf {
            savedSelection.first == selectedWorkspace.slug &&
                it.isNotBlank() &&
                snapshot.messagesByThread.containsKey(it)
        }
        val targetSlug = savedThread
            ?: snapshot.threads.firstOrNull {
                snapshot.messagesByThread[it.slug]?.isNotEmpty() == true
            }?.slug
            ?: snapshot.threads.firstOrNull()?.slug
            ?: TotemApi.DEFAULT_THREAD

        activeThreadSlug = targetSlug
        messages = snapshot.messagesByThread[targetSlug].orEmpty()
        store.saveActiveChat(selectedWorkspace.slug, targetSlug)
        error = null
    }

    suspend fun refreshHistory(keepSelection: Boolean = true) {
        val currentWorkspace = workspace ?: return
        val oldSlug = activeThreadSlug
        val snapshot = api.workspaceContent(currentWorkspace.slug)
        threads = snapshot.threads
        val targetSlug = if (keepSelection && snapshot.messagesByThread.containsKey(oldSlug)) {
            oldSlug
        } else {
            snapshot.threads.firstOrNull {
                snapshot.messagesByThread[it.slug]?.isNotEmpty() == true
            }?.slug ?: snapshot.threads.firstOrNull()?.slug ?: TotemApi.DEFAULT_THREAD
        }
        activeThreadSlug = targetSlug
        messages = snapshot.messagesByThread[targetSlug].orEmpty()
        store.saveActiveChat(currentWorkspace.slug, targetSlug)
    }

    LaunchedEffect(launchId, signedIn) {
        if (!signedIn) {
            error = "Sign in to your TOTEM account to use chat."
            loading = false
        } else {
            loading = true
            error = null
            try {
                loadChatWorkspace()
            } catch (e: Exception) {
                workspace = null
                error = e.message ?: "Could not connect to the TOTEM chat service."
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(messages.size, messages.lastOrNull()?.text) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    LaunchedEffect(pendingToolApproval?.requestId) {
        val approval = pendingToolApproval ?: return@LaunchedEffect
        delay(approval.timeoutMs)
        if (pendingToolApproval?.requestId == approval.requestId) {
            pendingToolApproval = null
            approvalDetailsExpanded = false
            Toast.makeText(
                context,
                "Agent approval timed out and was denied",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun createNewChat() {
        val currentWorkspace = workspace ?: return
        scope.launch {
            loading = true
            error = null
            try {
                val fresh = api.newThread(currentWorkspace.slug)
                val snapshot = api.workspaceContent(currentWorkspace.slug)
                activeThreadSlug = fresh.slug
                store.saveActiveChat(currentWorkspace.slug, fresh.slug)
                messages = emptyList()
                threads = if (snapshot.threads.any { it.slug == fresh.slug }) {
                    snapshot.threads
                } else {
                    listOf(fresh) + snapshot.threads
                }
                drawerState.close()
            } catch (e: Exception) {
                error = e.message ?: "Could not create a new chat."
            } finally {
                loading = false
            }
        }
    }

    fun selectThread(thread: TotemChatThread) {
        val currentWorkspace = workspace ?: return
        scope.launch {
            try {
                val snapshot = api.workspaceContent(currentWorkspace.slug)
                threads = snapshot.threads
                activeThreadSlug = thread.slug
                store.saveActiveChat(currentWorkspace.slug, thread.slug)
                messages = snapshot.messagesByThread[thread.slug].orEmpty()
                drawerState.close()
                error = null
            } catch (e: Exception) {
                error = e.message ?: "Could not load this conversation."
                drawerState.close()
            }
        }
    }

    fun refreshCurrentChat() {
        if (workspace == null) return
        scope.launch {
            loading = true
            error = null
            try {
                refreshHistory(keepSelection = true)
            } catch (e: Exception) {
                error = e.message ?: "Could not refresh the conversation."
            } finally {
                loading = false
            }
        }
    }

    fun respondToToolApproval(approval: TotemAgentToolApproval, approved: Boolean) {
        val sent = api.respondToAgentToolApproval(approval.requestId, approved)
        pendingToolApproval = null
        approvalDetailsExpanded = false
        val notice = when {
            !sent -> "Could not send the agent approval response"
            approved -> "Agent action approved"
            else -> "Agent action denied"
        }
        Toast.makeText(context, notice, Toast.LENGTH_SHORT).show()
    }

    fun selectInferenceOption(option: TotemInferenceOption) {
        val currentWorkspace = workspace ?: return
        if (sending || changingInferenceOption) return

        changingInferenceOption = true
        scope.launch {
            try {
                api.setInferenceOption(currentWorkspace.slug, option.key)
                selectedInferenceOption = option.key
                error = null
            } catch (e: Exception) {
                error = e.message ?: "Could not change the inference option."
            } finally {
                changingInferenceOption = false
            }
        }
    }

    fun sendMessage() {
        val message = input.trim()
        val currentWorkspace = workspace ?: return
        if (message.isEmpty() || sending) return

        input = ""
        error = null
        store.saveActiveChat(
            currentWorkspace.slug,
            activeThreadSlug.ifBlank { TotemApi.DEFAULT_THREAD }
        )
        val assistantId = "assistant-${UUID.randomUUID()}"
        messages = messages + TotemChatMessage(
            id = "user-${UUID.randomUUID()}",
            fromUser = true,
            text = message
        ) + TotemChatMessage(
            id = assistantId,
            fromUser = false,
            text = ""
        )
        sending = true

        scope.launch {
            var responseText = ""
            try {
                api.streamChat(
                    workspaceSlug = currentWorkspace.slug,
                    threadSlug = activeThreadSlug.ifBlank { TotemApi.DEFAULT_THREAD },
                    message = message
                ).collect { event ->
                    when (event) {
                        is TotemChatStreamEvent.Text -> {
                            responseText = if (event.replace) event.value else responseText + event.value
                            messages = messages.map { item ->
                                if (item.id == assistantId) item.copy(text = responseText) else item
                            }
                        }
                        is TotemChatStreamEvent.ToolApprovalRequested -> {
                            pendingToolApproval = event.approval
                            approvalDetailsExpanded = false
                        }
                        is TotemChatStreamEvent.Failed -> {
                            pendingToolApproval = null
                            error = event.message
                            messages = messages.map { item ->
                                if (item.id == assistantId && item.text.isBlank()) {
                                    item.copy(text = event.message)
                                } else item
                            }
                            sending = false
                        }
                        TotemChatStreamEvent.Finished -> {
                            sending = false
                        }
                    }
                }

                if (responseText.isBlank() && error.isNullOrBlank()) {
                    val fallback = "TOTEM returned an empty response. Please try again."
                    messages = messages.map { item ->
                        if (item.id == assistantId && item.text.isBlank()) item.copy(text = fallback) else item
                    }
                } else if (responseText.isNotBlank()) {
                    runCatching { refreshHistory(keepSelection = true) }
                }
                onCreditsChanged()
            } catch (e: Exception) {
                val detail = e.message ?: "The message could not be sent."
                error = detail
                messages = messages.map { item ->
                    if (item.id == assistantId && item.text.isBlank()) item.copy(text = detail) else item
                }
            } finally {
                sending = false
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        scrimColor = Color.Black.copy(alpha = 0.62f),
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = InkElevated,
                drawerContentColor = TextPrimary
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 18.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "CHAT HISTORY",
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                        IconButton(onClick = { scope.launch { drawerState.close() } }) {
                            Icon(Icons.Default.ChatBubbleOutline, contentDescription = "Close history", tint = TextSecondary)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    TextButton(
                        onClick = { createNewChat() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = Violet)
                        Spacer(Modifier.width(8.dp))
                        Text("New chat", color = Violet)
                    }
                    Divider(color = Outline)
                    Spacer(Modifier.height(10.dp))
                    if (threads.isEmpty()) {
                        Text("No previous chats yet.", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(threads, key = { it.slug }) { thread ->
                                val selected = activeThreadSlug == thread.slug
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (selected) Violet.copy(alpha = 0.14f) else Color.Transparent)
                                        .border(
                                            1.dp,
                                            if (selected) Violet.copy(alpha = 0.34f) else Color.Transparent,
                                            RoundedCornerShape(12.dp)
                                        )
                                        .padding(horizontal = 12.dp, vertical = 11.dp)
                                        .then(Modifier)
                                ) {
                                    TextButton(
                                        onClick = { selectThread(thread) },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(Modifier.fillMaxWidth()) {
                                            Text(
                                                thread.title.ifBlank { "New chat" },
                                                color = if (selected) Violet else TextPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (thread.preview.isNotBlank()) {
                                                Text(
                                                    thread.preview,
                                                    color = TextSecondary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    ) {
        Box(Modifier.fillMaxSize()) {
            Image(
                painter = painterResource(R.drawable.totem_chat_wallpaper),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        AppBackground.copy(alpha = if (IsDarkTotemTheme) 0.38f else 0.76f)
                    )
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
            ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                    Icon(Icons.Default.Menu, contentDescription = "Chat history", tint = TextPrimary)
                }
                Column(Modifier.weight(1f)) {
                    Text("TOTEM CHAT", color = TextPrimary, fontWeight = FontWeight.Bold)
                    Text(
                        workspace?.name ?: "Your AI workspace",
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                IconButton(onClick = { createNewChat() }, enabled = signedIn && workspace != null) {
                    Icon(Icons.Default.Add, contentDescription = "New chat", tint = Violet)
                }
                IconButton(onClick = { refreshCurrentChat() }, enabled = signedIn && workspace != null && !sending) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh current chat", tint = TextSecondary)
                }
            }

            Divider(color = Outline)

            if (signedIn && inferenceOptions.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "AI MODE",
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        inferenceOptions.forEach { option ->
                            val selected = option.key == selectedInferenceOption
                            val chipShape = RoundedCornerShape(11.dp)
                            Box(
                                modifier = Modifier
                                    .clip(chipShape)
                                    .background(
                                        if (selected) Violet.copy(alpha = 0.17f)
                                        else InkElevated.copy(alpha = 0.78f)
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = if (selected) Violet.copy(alpha = 0.92f) else Outline,
                                        shape = chipShape
                                    )
                                    .clickable(
                                        enabled = !sending && !changingInferenceOption,
                                        onClick = { selectInferenceOption(option) }
                                    )
                                    .padding(horizontal = 12.dp, vertical = 9.dp)
                            ) {
                                Text(
                                    text = option.label,
                                    color = if (selected) Violet else TextPrimary,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                    if (changingInferenceOption) {
                        CircularProgressIndicator(
                            color = Violet,
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
                Divider(color = Outline)
            }

            if (!signedIn) {
                EmptyState(
                    title = "Sign in required",
                    detail = "Sign in to your TOTEM account before opening your chats.",
                    action = "Sign in",
                    onAction = onSignIn
                )
            } else if (loading && workspace == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Violet)
                }
            } else if (workspace == null && !error.isNullOrBlank()) {
                EmptyState(
                    title = "Chat unavailable",
                    detail = error ?: "The workspace could not be loaded.",
                    action = "Retry",
                    onAction = {
                        scope.launch {
                            loading = true
                            error = null
                            try {
                                loadChatWorkspace()
                            } catch (e: Exception) {
                                workspace = null
                                error = e.message ?: "Could not connect to TOTEM."
                            } finally {
                                loading = false
                            }
                        }
                    }
                )
            } else {
                if (!error.isNullOrBlank()) {
                    Text(
                        text = error.orEmpty(),
                        color = Color(0xFFFF8B8B),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }

                if (messages.isEmpty() && !loading) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(58.dp)
                                    .clip(CircleShape)
                                    .background(Violet.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.ChatBubbleOutline, contentDescription = null, tint = Violet, modifier = Modifier.size(26.dp))
                            }
                            Spacer(Modifier.height(14.dp))
                            Text("Start a new conversation", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(6.dp))
                            Text("Your previous chats are available from the menu.", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(messages, key = { it.id }) { message ->
                            ChatBubble(message = message, waiting = sending && !message.fromUser && message.text.isBlank())
                        }
                        if (loading) {
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    CircularProgressIndicator(color = Violet, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                }
                            }
                        }
                    }
                }

                pendingToolApproval?.let { approval ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = InkElevated)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Agent action requires approval",
                                color = Violet,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = approval.skillName,
                                color = TextPrimary,
                                fontWeight = FontWeight.SemiBold
                            )
                            approval.description?.let { description ->
                                Text(
                                    text = description,
                                    color = TextSecondary,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (approval.payloadJson.isNotBlank()) {
                                TextButton(
                                    onClick = { approvalDetailsExpanded = !approvalDetailsExpanded }
                                ) {
                                    Text(
                                        if (approvalDetailsExpanded) "Hide request details"
                                        else "View request details",
                                        color = Violet
                                    )
                                }
                                if (approvalDetailsExpanded) {
                                    Text(
                                        text = approval.payloadJson,
                                        color = TextSecondary,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 150.dp)
                                            .verticalScroll(rememberScrollState())
                                    )
                                }
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(onClick = { respondToToolApproval(approval, true) }) {
                                    Text("Approve")
                                }
                                TextButton(onClick = { respondToToolApproval(approval, false) }) {
                                    Text("Reject", color = TextSecondary)
                                }
                            }
                            Text(
                                text = "Only approve actions you understand. This request expires automatically.",
                                color = TextSecondary,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(InkElevated.copy(alpha = 0.92f))
                        .padding(
                            start = 10.dp,
                            end = 10.dp,
                            top = 10.dp,
                            bottom = if (imeVisible) 0.dp else 10.dp
                        ),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Message TOTEM…", color = TextSecondary) },
                        shape = RoundedCornerShape(22.dp),
                        minLines = 1,
                        maxLines = 5,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { sendMessage() }),
                        enabled = signedIn && workspace != null && !sending,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary)
                    )
                    IconButton(
                        onClick = { sendMessage() },
                        enabled = signedIn && workspace != null && !sending && input.isNotBlank(),
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(if (input.isNotBlank() && !sending) Violet else Surface1)
                    ) {
                        if (sending) {
                            CircularProgressIndicator(
                                color = TextPrimary,
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.Send, contentDescription = "Send message", tint = TextPrimary)
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun ChatBubble(message: TotemChatMessage, waiting: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .clip(RoundedCornerShape(18.dp))
                .background(if (message.fromUser) Violet.copy(alpha = 0.19f) else Surface1)
                .border(
                    1.dp,
                    if (message.fromUser) Violet.copy(alpha = 0.28f) else Outline,
                    RoundedCornerShape(18.dp)
                )
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Text(
                text = if (message.fromUser) "YOU" else "TOTEM",
                color = if (message.fromUser) Violet else Lime,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(5.dp))
            if (waiting) {
                CircularProgressIndicator(color = Violet, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text(
                    text = message.text,
                    color = TextPrimary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun EmptyState(
    title: String,
    detail: String,
    action: String,
    onAction: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(title, color = TextPrimary, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(detail, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onAction) { Text(action, color = Violet) }
    }
}
