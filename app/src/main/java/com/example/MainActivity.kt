package com.example

import android.content.Context
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.example.ui.theme.MyApplicationTheme
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

enum class AppTab(val title: String, val icon: ImageVector) {
    Browser("Browser", Icons.Default.Language),
    Chat("Chat", Icons.Default.Chat)
}

data class ChatMessage(
    val role: String,
    val content: String,
    val isCode: Boolean = false,
    val attachedFileNames: List<String> = emptyList()
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class JsCallbackHandler {
    var onSuccess: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    @JavascriptInterface
    fun onResult(result: String) {
        onSuccess?.invoke(result)
    }

    @JavascriptInterface
    fun onError(error: String) {
        onError?.invoke(error)
    }
}

class MainActivity : ComponentActivity() {
    private val db by lazy { AppDatabase.getDatabase(this) }
    private val chatDao by lazy { db.chatDao() }
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val systemPrompt = """
        You are an AI assistant integrated into an Android app. The app contains a WebView that is currently logged into a website. The WebView has valid cookies and session state.

        Your only task is to generate **JavaScript code** that runs inside that WebView. You must output only a JavaScript code block (marked with ```javascript ... ```). No explanations, no extra text.

        The code must:
        - Use `fetch()` to call the provided API endpoint.
        - Rely on the WebView's existing cookies.
        - Wait for the response, parse it as JSON, and extract the required data.
        - Send the result back to the Android app by calling `Android.onResult(JSON.stringify(data))`.
        - **Always** wrap the entire logic in a `try-catch` block. If any error occurs (network failure, JSON parsing error, missing data), call `Android.onError(JSON.stringify({message: error.message, stack: error.stack}))` instead of `onResult`.

        When you receive an error report in a subsequent prompt, fix the code based on the error message and return a corrected version. Never give up – keep iterating until the code works.
    """.trimIndent()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MainApp()
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainApp() {
        var showSettings by remember { mutableStateOf(false) }
        var currentTab by remember { mutableStateOf(AppTab.Browser) }
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        
        val sessions by chatDao.getAllSessions().collectAsState(initial = emptyList())
        var currentSessionId by remember { mutableStateOf<Long?>(null) }
        
        LaunchedEffect(sessions) {
            if (currentSessionId == null && sessions.isNotEmpty()) {
                currentSessionId = sessions.first().id
            }
        }
        
        val context = LocalContext.current
        
        val activeKeyIndexFlow = context.dataStore.data.map { it[intPreferencesKey("active_key_index")] ?: 0 }
        val activeKeyIndex by activeKeyIndexFlow.collectAsState(initial = 0)
        
        val apiKeysListFlow = context.dataStore.data.map { prefs ->
            List(5) { i -> prefs[stringPreferencesKey("api_key_$i")] ?: "" }
        }
        val apiKeysList by apiKeysListFlow.collectAsState(initial = List(5) { "" })
        val activeApiKey = apiKeysList.getOrNull(activeKeyIndex) ?: ""

        val jsHandler = remember { JsCallbackHandler() }
        var currentUrl by remember { mutableStateOf("https://www.google.com") }

        val webView = remember {
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                
                val cookieManager = android.webkit.CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(this, true)

                webViewClient = object : WebViewClient() {
                    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                        if (url != null) {
                            currentUrl = url
                        }
                        super.doUpdateVisitedHistory(view, url, isReload)
                    }
                }
                addJavascriptInterface(jsHandler, "Android")
                loadUrl(currentUrl)
            }
        }

        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(modifier = Modifier.width(300.dp)) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Chat History", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)
                    Divider()
                    NavigationDrawerItem(
                        label = { Text("New Chat") },
                        selected = false,
                        onClick = {
                            scope.launch {
                                currentSessionId = null
                                drawerState.close()
                                currentTab = AppTab.Chat
                            }
                        },
                        icon = { Icon(Icons.Default.Add, contentDescription = "New Chat") }
                    )
                    Divider()
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(sessions) { session ->
                            NavigationDrawerItem(
                                label = { Text(session.title) },
                                selected = (session.id == currentSessionId),
                                onClick = {
                                    scope.launch {
                                        currentSessionId = session.id
                                        drawerState.close()
                                        currentTab = AppTab.Chat
                                    }
                                }
                            )
                        }
                    }
                }
            }
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(if (showSettings) "Settings" else "Texter App") },
                        navigationIcon = {
                            if (showSettings) {
                                IconButton(onClick = { showSettings = false }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                }
                            } else {
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    Icon(Icons.Default.Menu, contentDescription = "Menu")
                                }
                            }
                        },
                    actions = {
                        if (!showSettings) {
                            IconButton(onClick = { showSettings = true }) {
                                Icon(Icons.Default.Settings, contentDescription = "Settings")
                            }
                        }
                    }
                )
            },
            bottomBar = {
                if (!showSettings) {
                    NavigationBar {
                        AppTab.entries.forEach { tab ->
                            NavigationBarItem(
                                icon = { Icon(tab.icon, contentDescription = tab.title) },
                                label = { Text(tab.title) },
                                selected = currentTab == tab,
                                onClick = { currentTab = tab }
                            )
                        }
                    }
                }
            }
        ) { paddingValues ->
            Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
                Box(modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (currentTab == AppTab.Browser && !showSettings) 1f else 0f)
                    .zIndex(if (currentTab == AppTab.Browser && !showSettings) 1f else 0f)
                ) {
                    BrowserScreen(webView = webView, currentUrl = currentUrl, onNavigate = { url ->
                        var loadUrl = url
                        if (!loadUrl.startsWith("http://") && !loadUrl.startsWith("https://")) {
                            loadUrl = "https://$loadUrl"
                        }
                        webView.loadUrl(loadUrl)
                    })
                }

                if (currentTab == AppTab.Chat && !showSettings) {
                    Box(modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .pointerInput(Unit) {}
                        .zIndex(2f)
                    ) {
                        ChatScreen(
                            webView = webView,
                            jsHandler = jsHandler,
                            activeApiKey = activeApiKey,
                            currentSessionId = currentSessionId,
                            onSessionChanged = { currentSessionId = it },
                            chatDao = chatDao
                        )
                    }
                }

                if (showSettings) {
                    Box(modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .pointerInput(Unit) {}
                        .zIndex(3f)
                    ) {
                        SettingsScreen(
                            apiKeys = apiKeysList,
                            activeKeyIndex = activeKeyIndex,
                            onSaveKey = { index, value ->
                                scope.launch {
                                    context.dataStore.edit { prefs ->
                                        prefs[stringPreferencesKey("api_key_$index")] = value
                                    }
                                }
                            },
                            onSetActive = { index ->
                                scope.launch {
                                    context.dataStore.edit { prefs ->
                                        prefs[intPreferencesKey("active_key_index")] = index
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

    @Composable
    fun BrowserScreen(webView: WebView, currentUrl: String, onNavigate: (String) -> Unit) {
        var urlInput by remember(currentUrl) { mutableStateOf(currentUrl) }

        Column(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("URL") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { onNavigate(urlInput) })
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = { onNavigate(urlInput) }) {
                    Text("Go")
                }
            }
            
            AndroidView(
                factory = { webView },
                modifier = Modifier.weight(1f)
            )
        }
    }

    @Composable
    fun SettingsScreen(
        apiKeys: List<String>,
        activeKeyIndex: Int,
        onSaveKey: (Int, String) -> Unit,
        onSetActive: (Int) -> Unit
    ) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Text("Gemini API Keys", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "You can add up to 5 keys. Select one to act as the active key.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                items(5) { index ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (index == activeKeyIndex) MaterialTheme.colorScheme.primaryContainer
                                             else MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = (index == activeKeyIndex),
                                    onClick = { onSetActive(index) }
                                )
                                Text(text = "Key ${index + 1}", modifier = Modifier.weight(1f))
                            }
                            
                            var textValue by remember(apiKeys[index]) { mutableStateOf(apiKeys[index]) }
                            
                            OutlinedTextField(
                                value = textValue,
                                onValueChange = { textValue = it },
                                label = { Text("API Key") },
                                modifier = Modifier.fillMaxWidth(),
                                trailingIcon = {
                                    if (textValue != apiKeys[index]) {
                                        TextButton(onClick = { onSaveKey(index, textValue) }) {
                                            Text("Save")
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun ChatScreen(
        webView: WebView,
        jsHandler: JsCallbackHandler,
        activeApiKey: String,
        currentSessionId: Long?,
        onSessionChanged: (Long) -> Unit,
        chatDao: ChatDao
    ) {
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        
        val dbMessages by remember(currentSessionId) {
            currentSessionId?.let { chatDao.getMessagesForSession(it) }
                ?: kotlinx.coroutines.flow.flowOf(emptyList())
        }.collectAsState(initial = emptyList())
        
        var messages by remember { mutableStateOf(listOf<ChatMessage>()) }
        
        LaunchedEffect(dbMessages) {
            messages = dbMessages.map { 
                val fileNames = if (it.attachedFileNames.isNotBlank()) it.attachedFileNames.split(",") else emptyList()
                ChatMessage(it.role, it.content, it.isCode, fileNames)
            }
        }
        
        fun saveMessage(msg: ChatMessage) {
            scope.launch {
                var sId = currentSessionId
                if (sId == null) {
                    val firstWords = msg.content.take(30).replace("\n", " ") + "..."
                    val session = ChatSession(title = firstWords.ifBlank { "New Chat" })
                    sId = chatDao.insertSession(session)
                    onSessionChanged(sId)
                } else {
                    chatDao.updateSessionTime(sId)
                }
                
                chatDao.insertMessage(
                    ChatMessageEntity(
                        sessionId = sId,
                        role = msg.role,
                        content = msg.content,
                        isCode = msg.isCode,
                        attachedFileNames = msg.attachedFileNames.joinToString(",")
                    )
                )
            }
        }
        
        var inputText by remember { mutableStateOf("") }
        var isLoading by remember { mutableStateOf(false) }

        var pendingPrompt by remember { mutableStateOf<String?>(null) }
        var retryCount by remember { mutableStateOf(0) }
        val maxRetries = 6

        var attachedFiles by remember { mutableStateOf(listOf<Triple<String, String, String>>()) } // Name, MimeType, Data

        val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            scope.launch(Dispatchers.IO) {
                val newAttachments = mutableListOf<Triple<String,String,String>>()
                for (uri in uris) {
                    try {
                        val contentResolver = context.contentResolver
                        var name = "unknown_file"
                        val cursor = contentResolver.query(uri, null, null, null, null)
                        if (cursor != null && cursor.moveToFirst()) {
                            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (nameIndex != -1) name = cursor.getString(nameIndex)
                            cursor.close()
                        }
                        
                        var mimeType = contentResolver.getType(uri) ?: "text/plain"
                        if (name.endsWith(".json")) mimeType = "application/json"
                        if (name.endsWith(".js")) mimeType = "application/javascript"
                        if (name.endsWith(".har")) mimeType = "application/json"
                        
                        val inputStream = contentResolver.openInputStream(uri)
                        if (inputStream != null) {
                            if (mimeType.startsWith("text/") || mimeType.contains("json") || mimeType.contains("javascript")) {
                                val content = java.io.BufferedReader(java.io.InputStreamReader(inputStream)).use { it.readText() }
                                newAttachments.add(Triple(name, "text/plain", content))
                            } else {
                                val bytes = inputStream.readBytes()
                                val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                                newAttachments.add(Triple(name, mimeType, base64))
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                withContext(Dispatchers.Main) {
                    attachedFiles = attachedFiles + newAttachments
                }
            }
        }

        DisposableEffect(currentSessionId) {
            jsHandler.onSuccess = { result ->
                webView.post {
                    val msg = ChatMessage("System", "✅ Script succeeded. Result: ${result.take(500)}${if (result.length > 500) "..." else ""}")
                    messages = messages + msg
                    saveMessage(msg)
                    isLoading = false
                    pendingPrompt = null
                    retryCount = 0
                }
            }
            jsHandler.onError = { errorJson ->
                webView.post {
                    val errorMsg = "❌ Script error: $errorJson"
                    val msg = ChatMessage("System", errorMsg)
                    messages = messages + msg
                    saveMessage(msg)
                    if (pendingPrompt != null && retryCount < maxRetries) {
                        retryCount++
                        val retryMsg = ChatMessage("System", "Retrying (attempt $retryCount/$maxRetries)...")
                        messages = messages + retryMsg
                        saveMessage(retryMsg)
                        scope.launch {
                            val correctedCode = (context as MainActivity).askGeminiForFix(messages, pendingPrompt!!, errorJson, activeApiKey)
                            if (correctedCode != null) {
                                val codeMsg = ChatMessage("Gemini (fixed)", correctedCode, true)
                                messages = messages + codeMsg
                                saveMessage(codeMsg)
                                webView.evaluateJavascript(correctedCode, null)
                            } else {
                                val failMsg = ChatMessage("System", "Failed to get corrected code. Aborting.")
                                messages = messages + failMsg
                                saveMessage(failMsg)
                                isLoading = false
                                pendingPrompt = null
                                retryCount = 0
                            }
                        }
                    } else if (retryCount >= maxRetries) {
                        val limitMsg = ChatMessage("System", "Max retries reached. The current problem is:\n$errorJson\n\nYou can provide this error and the original task to another AI model, then paste the fixed script back here.")
                        messages = messages + limitMsg
                        saveMessage(limitMsg)
                        isLoading = false
                        pendingPrompt = null
                        retryCount = 0
                    } else {
                        isLoading = false
                        pendingPrompt = null
                    }
                }
            }
            onDispose {
                jsHandler.onSuccess = null
                jsHandler.onError = null
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(8.dp),
                reverseLayout = true
            ) {
                items(messages.reversed()) { message ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = when (message.role) {
                                "User" -> MaterialTheme.colorScheme.primaryContainer
                                "System" -> MaterialTheme.colorScheme.secondaryContainer
                                "Error" -> MaterialTheme.colorScheme.errorContainer
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            }
                        )
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(text = message.role, style = MaterialTheme.typography.labelSmall)
                            if (message.attachedFileNames.isNotEmpty()) {
                                Text("Attachments: ${message.attachedFileNames.joinToString()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                            }
                            if (message.isCode) {
                                Text(
                                    text = message.content,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                            } else {
                                Text(text = message.content, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
            
            if (attachedFiles.isNotEmpty()) {
                Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Attached: ${attachedFiles.size} files", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = { attachedFiles = emptyList() }) { Text("Clear") }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { filePicker.launch("*/*") }) {
                    Icon(Icons.Default.AttachFile, contentDescription = "Attach File")
                }
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("Describe what to fetch...") },
                    enabled = !isLoading,
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = {
                    if ((inputText.isNotBlank() || attachedFiles.isNotEmpty()) && !isLoading) {
                        if (activeApiKey.isBlank()) {
                            messages = messages + ChatMessage("System", "Error: No API key set in Settings.")
                            return@Button
                        }
                        val userText = inputText
                        val fileNames = attachedFiles.map { it.first }
                        
                        val userMsg = ChatMessage("User", userText, false, fileNames)
                        messages = messages + userMsg
                        saveMessage(userMsg)
                        val prompt = userText
                        pendingPrompt = prompt
                        retryCount = 0
                        inputText = ""
                        val currentFiles = attachedFiles.toList()
                        attachedFiles = emptyList()
                        
                        scope.launch {
                            isLoading = true
                            val code = (context as MainActivity).askGemini(messages.dropLast(1), prompt, activeApiKey, currentFiles)
                            if (code != null) {
                                val codeMsg = ChatMessage("Gemini (code)", code, true)
                                messages = messages + codeMsg
                                saveMessage(codeMsg)
                                webView.post {
                                    webView.evaluateJavascript(code, null)
                                }
                            } else {
                                val failMsg = ChatMessage("System", "Failed to get code from Gemini.", false)
                                messages = messages + failMsg
                                saveMessage(failMsg)
                                isLoading = false
                                pendingPrompt = null
                            }
                        }
                    }
                }, enabled = !isLoading) {
                    Text("Send")
                }
            }
        }
    }

    suspend fun askGemini(history: List<ChatMessage>, prompt: String, apiKey: String, files: List<Triple<String, String, String>> = emptyList()): String? = withContext(Dispatchers.IO) {
        try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$apiKey"
            
            val contentsArray = mutableListOf<Map<String, Any>>()
            
            for (m in history) {
                val role = when {
                    m.role.startsWith("Gemini") -> "model"
                    else -> "user"
                }
                contentsArray.add(mapOf(
                    "role" to role,
                    "parts" to listOf(mapOf("text" to "${if (m.role == "System") "System Msg: " else ""}${m.content}"))
                ))
            }

            val partsList = mutableListOf<Map<String, Any>>()
            if (prompt.isNotBlank()) {
                partsList.add(mapOf("text" to prompt))
            }
            
            for (file in files) {
                if (file.second.startsWith("text/")) {
                    partsList.add(mapOf("text" to "--- File: ${file.first} ---\n${file.third}\n-----------------\n"))
                } else {
                    partsList.add(mapOf("inlineData" to mapOf(
                        "mimeType" to file.second,
                        "data" to file.third
                    )))
                }
            }
            
            contentsArray.add(mapOf(
                "role" to "user",
                "parts" to partsList
            ))
            
            val requestBody = mapOf(
                "system_instruction" to mapOf("parts" to listOf(mapOf("text" to systemPrompt))),
                "contents" to contentsArray,
                "tools" to listOf(mapOf("googleSearch" to emptyMap<String, Any>()))
            )
            val json = gson.toJson(requestBody)
            val body = json.toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url(url).post(body).build()
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: return@withContext null
            
            if (!response.isSuccessful) {
                return@withContext null
            }
            
            val jsonResponse = JSONObject(responseBody)
            val text = jsonResponse
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
            return@withContext extractJavaScript(text)
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }
    }

    suspend fun askGeminiForFix(history: List<ChatMessage>, originalPrompt: String, errorJson: String, apiKey: String): String? {
        val fixPrompt = """
The previous JavaScript code you generated failed with this error:
$errorJson

Original request: $originalPrompt

Please generate a corrected version of the JavaScript code that fixes the error. Output only the code block.
        """.trimIndent()
        return askGemini(history, fixPrompt, apiKey)
    }

    private fun extractJavaScript(response: String): String? {
        val regex = Regex("```(?:javascript)?\\s*([\\s\\S]*?)\\s*```")
        val match = regex.find(response)
        return match?.groupValues?.get(1)?.trim()
    }
}
