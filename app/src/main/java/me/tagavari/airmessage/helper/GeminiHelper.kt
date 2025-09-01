package me.tagavari.airmessage.helper

import android.content.Context
import android.util.Log
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlinx.coroutines.rx3.rxSingle
import me.tagavari.airmessage.messaging.MessageInfo
import me.tagavari.airmessage.messaging.ConversationInfo
import me.tagavari.airmessage.activity.Preferences
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException


/**
 * Helper class for integrating Ollama AI features into AirMessage
 * Provides smart replies, message enhancement, and conversation intelligence using Ollama server
 */
abstract class GeminiHelper protected constructor() {
    
    /**
     * Check if the user is authenticated with Google Services
     * Implementation varies by product flavor
     */
    abstract fun isUserAuthenticated(): Boolean
    
    companion object {
        private const val MAX_CONVERSATION_CONTEXT = 10
        private const val MAX_SMART_REPLIES = 3
        
        @Volatile
        private var instance: GeminiHelper? = null
        
        @JvmStatic
        fun getInstance(): GeminiHelper {
            return instance ?: synchronized(this) {
                instance ?: createFlavorSpecificInstance().also { instance = it }
            }
        }
        
        /**
         * Create flavor-specific implementation using reflection
         */
        private fun createFlavorSpecificInstance(): GeminiHelper {
            return try {
                // Try to create full flavor implementation first
                val fullClass = Class.forName("me.tagavari.airmessage.helper.GeminiHelperFull")
                fullClass.getDeclaredConstructor().newInstance() as GeminiHelper
            } catch (e: ClassNotFoundException) {
                try {
                    // Fallback to FOSS flavor implementation
                    val fossClass = Class.forName("me.tagavari.airmessage.helper.GeminiHelperFoss")
                    fossClass.getDeclaredConstructor().newInstance() as GeminiHelper
                } catch (e2: ClassNotFoundException) {
                    // Final fallback - create a basic implementation
                    object : GeminiHelper() {
                        override fun isUserAuthenticated(): Boolean = false
                    }
                }
            }
        }
    }
    
    private val ollamaClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }
    
    private fun isOllamaAvailable(context: Context): Boolean {
        return try {
            val baseUrl = Preferences.getOllamaBaseUrl(context)
            if (baseUrl.isEmpty()) return false
            
            // Check if Ollama server is accessible by making a quick ping
            val request = Request.Builder()
                .url("$baseUrl/api/tags")
                .get()
                .build()
            
            ollamaClient.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            // If Ollama is not available, fall back to checking Google authentication for Gemini
            isUserAuthenticated()
        }
    }
    
    /**
     * Generate smart reply suggestions for a conversation
     */
    fun generateSmartReplies(
        context: Context,
        conversationMessages: List<MessageInfo>,
        conversationInfo: ConversationInfo
    ): Single<List<String>> {
        return rxSingle {
            val aiProvider = Preferences.getPreferenceAIProvider(context)
            
            when (aiProvider) {
                "disabled" -> throw IllegalStateException("AI features are disabled in settings")
                "ollama" -> {
                    // Check Ollama availability
                    val baseUrl = Preferences.getOllamaBaseUrl(context)
                    if (baseUrl.isEmpty()) {
                        throw IllegalStateException("Ollama hostname not configured. Please set server details in settings.")
                    }
                    
                    val ollamaAvailable = try {
                        val request = Request.Builder()
                            .url("$baseUrl/api/tags")
                            .get()
                            .build()
                        ollamaClient.newCall(request).execute().use { it.isSuccessful }
                    } catch (e: Exception) { false }
                    
                    if (!ollamaAvailable) {
                        throw IllegalStateException("Ollama server not available at $baseUrl")
                    }
                }
                "gemini" -> {
                    val apiKey = getGeminiApiKey(context)
                    if (apiKey.isEmpty()) {
                        throw IllegalStateException("Gemini API key not configured. Please set your Google AI API key in Settings > AI Provider")
                    }
                }
                else -> throw IllegalStateException("Unknown AI provider: $aiProvider")
            }
            
            val conversationContext = buildConversationContext(conversationMessages, conversationInfo)
            
            // Get contextual memories for enhanced smart replies
            val contextualMemories = try {
                ConversationMemoryManager.getContextualMemories(context, conversationInfo)
                    .blockingGet()
            } catch (e: Exception) {
                Log.w("GeminiHelper", "Failed to retrieve contextual memories", e)
                emptyList<ConversationMemoryManager.MemoryItem>()
            }
            
            val prompt = buildSmartReplyPrompt(conversationContext, conversationInfo.isGroupChat, contextualMemories)
            
            val response = callAIAPI(context, prompt)
            parseSmartReplies(response)
        }.subscribeOn(Schedulers.io())
    }
    
    /**
     * Make API call to the selected AI provider
     */
    private fun callAIAPI(context: Context, prompt: String): String {
        val aiProvider = Preferences.getPreferenceAIProvider(context)
        
        return when (aiProvider) {
            "ollama" -> callOllamaAPI(context, prompt)
            "gemini" -> callGeminiAPI(context, prompt)
            else -> throw IllegalStateException("Unknown AI provider: $aiProvider")
        }
    }
    
    /**
     * Make API call to Ollama server
     */
    private fun callOllamaAPI(context: Context, prompt: String): String {
        val baseUrl = Preferences.getOllamaBaseUrl(context)
        val model = Preferences.getPreferenceOllamaModel(context)
        
        if (baseUrl.isEmpty()) {
            throw IllegalStateException("Ollama hostname not configured")
        }
        
        val json = JSONObject()
        json.put("model", if (model.isEmpty()) "llama3.2" else model)
        json.put("prompt", prompt)
        json.put("stream", false)
        
        // Add random seed for varied responses on retry
        val randomSeed = kotlin.random.Random.nextInt(1, 100000)
        json.put("seed", randomSeed)
        
        // Add temperature for response creativity (0.7-1.0 range for good variety)
        val temperature = 0.7 + (kotlin.random.Random.nextDouble() * 0.3)
        json.put("temperature", temperature)
        
        val requestBody = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$baseUrl/api/generate")
            .post(requestBody)
            .build()
            
        ollamaClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Ollama API call failed: ${response.code}")
            }
            
            val responseBody = response.body?.string() ?: ""
            val responseJson = JSONObject(responseBody)
            return responseJson.optString("response", "")
        }
    }
    
    /**
     * Make API call to Google Gemini API using REST API
     */
    private fun callGeminiAPI(context: Context, prompt: String): String {
        val apiKey = getGeminiApiKey(context)
        if (apiKey.isEmpty()) {
            throw IllegalStateException("Gemini API key not configured. Please set your API key in Settings > AI Provider")
        }
        
        val json = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", prompt)
                        })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.7)
                put("topK", 40)
                put("topP", 0.95)
                put("maxOutputTokens", 1024)
            })
        }
        
        val requestBody = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey")
            .post(requestBody)
            .build()
            
        ollamaClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Gemini API call failed: ${response.code} ${response.message}")
            }
            
            val responseBody = response.body?.string() ?: ""
            val responseJson = JSONObject(responseBody)
            
            return try {
                responseJson
                    .getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
            } catch (e: Exception) {
                throw IOException("Failed to parse Gemini API response: ${e.message}")
            }
        }
    }
    
    /**
     * Get Gemini API key from user preferences
     */
    private fun getGeminiApiKey(context: Context): String {
        return me.tagavari.airmessage.activity.Preferences.getPreferenceGeminiApiKey(context)
    }
    
    /**
     * Enhance a message with better grammar, tone, and clarity
     */
    fun enhanceMessage(
        context: Context,
        originalMessage: String,
        tone: MessageTone = MessageTone.NEUTRAL,
        messageContext: String? = null,
        conversationInfo: ConversationInfo? = null
    ): Single<String> {
        return rxSingle {
            val aiProvider = Preferences.getPreferenceAIProvider(context)
            
            when (aiProvider) {
                "disabled" -> throw IllegalStateException("AI features are disabled in settings")
                "ollama" -> {
                    val baseUrl = Preferences.getOllamaBaseUrl(context)
                    if (baseUrl.isEmpty()) {
                        throw IllegalStateException("Ollama hostname not configured. Please set server details in settings.")
                    }
                    
                    val ollamaAvailable = try {
                        val request = Request.Builder()
                            .url("$baseUrl/api/tags")
                            .get()
                            .build()
                        ollamaClient.newCall(request).execute().use { it.isSuccessful }
                    } catch (e: Exception) { false }
                    
                    if (!ollamaAvailable) {
                        throw IllegalStateException("Ollama server not available at $baseUrl")
                    }
                }
                "gemini" -> {
                    val apiKey = getGeminiApiKey(context)
                    if (apiKey.isEmpty()) {
                        throw IllegalStateException("Gemini API key not configured. Please set your Google AI API key in Settings > AI Provider")
                    }
                }
                else -> throw IllegalStateException("Unknown AI provider: $aiProvider")
            }
            
            // Retrieve contextual memories for enhanced message completion
            val contextualMemories = try {
                if (conversationInfo != null) {
                    val memories = ConversationMemoryManager.getContextualMemories(context, conversationInfo, originalMessage)
                        .blockingGet()
                    Log.d("GeminiHelper", "Retrieved ${memories.size} contextual memories for single message enhancement")
                    if (memories.isNotEmpty()) {
                        Log.d("GeminiHelper", "Available memories: ${memories.map { it.extractedInfo }}")
                    }
                    memories
                } else {
                    Log.d("GeminiHelper", "No conversation info provided for memory retrieval (single)")
                    emptyList<ConversationMemoryManager.MemoryItem>()
                }
            } catch (e: Exception) {
                Log.w("GeminiHelper", "Failed to retrieve contextual memories for single enhancement", e)
                emptyList<ConversationMemoryManager.MemoryItem>()
            }
            
            val prompt = buildEnhanceMessagePrompt(originalMessage, tone, messageContext, contextualMemories)
            val response = callAIAPI(context, prompt)
            
            val cleanedResponse = cleanEnhancedMessageResponse(response)
            cleanedResponse.takeIf { it.isNotEmpty() } ?: originalMessage
        }.subscribeOn(Schedulers.io())
    }
    
    /**
     * Generate multiple enhanced versions of a message with different tones
     */
    fun enhanceMessageMultiple(
        context: Context,
        originalMessage: String,
        messageContext: String? = null,
        conversationInfo: ConversationInfo? = null
    ): Single<List<String>> {
        return rxSingle {
            val aiProvider = Preferences.getPreferenceAIProvider(context)
            
            when (aiProvider) {
                "disabled" -> throw IllegalStateException("AI features are disabled in settings")
                "ollama" -> {
                    val baseUrl = Preferences.getOllamaBaseUrl(context)
                    if (baseUrl.isEmpty()) {
                        throw IllegalStateException("Ollama hostname not configured. Please set server details in settings.")
                    }
                    
                    val ollamaAvailable = try {
                        val request = Request.Builder()
                            .url("$baseUrl/api/tags")
                            .get()
                            .build()
                        ollamaClient.newCall(request).execute().use { it.isSuccessful }
                    } catch (e: Exception) { false }
                    
                    if (!ollamaAvailable) {
                        throw IllegalStateException("Ollama server not available at $baseUrl")
                    }
                }
                "gemini" -> {
                    val apiKey = getGeminiApiKey(context)
                    if (apiKey.isEmpty()) {
                        throw IllegalStateException("Gemini API key not configured. Please set your Google AI API key in Settings > AI Provider")
                    }
                }
                else -> throw IllegalStateException("Unknown AI provider: $aiProvider")
            }
            
            // Retrieve contextual memories for enhanced message completion
            val contextualMemories = try {
                if (conversationInfo != null) {
                    val memories = ConversationMemoryManager.getContextualMemories(context, conversationInfo, originalMessage)
                        .blockingGet()
                    Log.d("GeminiHelper", "Retrieved ${memories.size} contextual memories for multiple message enhancement")
                    if (memories.isNotEmpty()) {
                        Log.d("GeminiHelper", "Available memories: ${memories.map { it.extractedInfo }}")
                    }
                    memories
                } else {
                    Log.d("GeminiHelper", "No conversation info provided for memory retrieval (multiple)")
                    emptyList<ConversationMemoryManager.MemoryItem>()
                }
            } catch (e: Exception) {
                Log.w("GeminiHelper", "Failed to retrieve contextual memories for multiple enhancement", e)
                emptyList<ConversationMemoryManager.MemoryItem>()
            }
            
            val prompt = buildEnhanceMessageMultiplePrompt(originalMessage, messageContext, contextualMemories)
            val response = callAIAPI(context, prompt)
            
            parseMultipleEnhancements(response)
        }.subscribeOn(Schedulers.io())
    }
    
    /**
     * Check and correct grammar, spelling, and punctuation only (no tone changes)
     */
    fun checkGrammarAndSpelling(
        context: Context,
        originalMessage: String
    ): Single<String> {
        return rxSingle {
            val aiProvider = Preferences.getPreferenceAIProvider(context)
            
            when (aiProvider) {
                "disabled" -> throw IllegalStateException("AI features are disabled in settings")
                "ollama" -> {
                    val baseUrl = Preferences.getOllamaBaseUrl(context)
                    if (baseUrl.isEmpty()) {
                        throw IllegalStateException("Ollama hostname not configured. Please set server details in settings.")
                    }
                    
                    val ollamaAvailable = try {
                        val request = Request.Builder()
                            .url("$baseUrl/api/tags")
                            .get()
                            .build()
                        ollamaClient.newCall(request).execute().use { it.isSuccessful }
                    } catch (e: Exception) { false }
                    
                    if (!ollamaAvailable) {
                        throw IllegalStateException("Ollama server not available at $baseUrl")
                    }
                }
                "gemini" -> {
                    val apiKey = getGeminiApiKey(context)
                    if (apiKey.isEmpty()) {
                        throw IllegalStateException("Gemini API key not configured. Please set your Google AI API key in Settings > AI Provider")
                    }
                }
                else -> throw IllegalStateException("Unknown AI provider: $aiProvider")
            }
            
            val prompt = buildGrammarCheckPrompt(originalMessage)
            val response = callAIAPI(context, prompt)
            
            val cleanedResponse = cleanEnhancedMessageResponse(response)
            cleanedResponse.takeIf { it.isNotEmpty() } ?: originalMessage
        }.subscribeOn(Schedulers.io())
    }
    
    /**
     * Generate a summary of a long conversation
     */
    fun summarizeConversation(
        context: Context,
        messages: List<MessageInfo>,
        conversationInfo: ConversationInfo
    ): Single<String> {
        return rxSingle {
            val aiProvider = Preferences.getPreferenceAIProvider(context)
            
            when (aiProvider) {
                "disabled" -> throw IllegalStateException("AI features are disabled in settings")
                "ollama" -> {
                    val baseUrl = Preferences.getOllamaBaseUrl(context)
                    if (baseUrl.isEmpty()) {
                        throw IllegalStateException("Ollama hostname not configured. Please set server details in settings.")
                    }
                    
                    val ollamaAvailable = try {
                        val request = Request.Builder()
                            .url("$baseUrl/api/tags")
                            .get()
                            .build()
                        ollamaClient.newCall(request).execute().use { it.isSuccessful }
                    } catch (e: Exception) { false }
                    
                    if (!ollamaAvailable) {
                        throw IllegalStateException("Ollama server not available at $baseUrl")
                    }
                }
                "gemini" -> {
                    val apiKey = getGeminiApiKey(context)
                    if (apiKey.isEmpty()) {
                        throw IllegalStateException("Gemini API key not configured. Please set your Google AI API key in Settings > AI Provider")
                    }
                }
                else -> throw IllegalStateException("Unknown AI provider: $aiProvider")
            }
            
            val conversationContext = buildConversationContext(messages, conversationInfo)
            val prompt = buildSummarizationPrompt(conversationContext, conversationInfo.isGroupChat)
            
            val response = callAIAPI(context, prompt)
            response.trim().takeIf { it.isNotEmpty() } ?: "Unable to generate summary"
        }.subscribeOn(Schedulers.io())
    }
    
    /**
     * Analyze message content for potential issues (spam, inappropriate content, etc.)
     */
    fun analyzeMessageContent(context: Context, message: String): Single<ContentAnalysis> {
        return rxSingle {
            val aiProvider = Preferences.getPreferenceAIProvider(context)
            
            when (aiProvider) {
                "disabled" -> {
                    // Return safe default when AI is disabled
                    return@rxSingle ContentAnalysis(
                        isSpam = false,
                        hasInappropriateContent = false,
                        containsSensitiveInfo = false,
                        riskLevel = RiskLevel.LOW,
                        warnings = listOf("Content analysis disabled in settings")
                    )
                }
                "ollama" -> {
                    val baseUrl = Preferences.getOllamaBaseUrl(context)
                    if (baseUrl.isEmpty()) {
                        return@rxSingle ContentAnalysis(
                            isSpam = false,
                            hasInappropriateContent = false,
                            containsSensitiveInfo = false,
                            riskLevel = RiskLevel.LOW,
                            warnings = listOf("Ollama hostname not configured. Please set server details in settings.")
                        )
                    }
                    
                    val ollamaAvailable = try {
                        val request = Request.Builder()
                            .url("$baseUrl/api/tags")
                            .get()
                            .build()
                        ollamaClient.newCall(request).execute().use { it.isSuccessful }
                    } catch (e: Exception) { false }
                    
                    if (!ollamaAvailable) {
                        return@rxSingle ContentAnalysis(
                            isSpam = false,
                            hasInappropriateContent = false,
                            containsSensitiveInfo = false,
                            riskLevel = RiskLevel.LOW,
                            warnings = listOf("Content analysis unavailable - Ollama server not accessible at $baseUrl")
                        )
                    }
                }
                "gemini" -> {
                    val apiKey = getGeminiApiKey(context)
                    if (apiKey.isEmpty()) {
                        return@rxSingle ContentAnalysis(
                            isSpam = false,
                            hasInappropriateContent = false,
                            containsSensitiveInfo = false,
                            riskLevel = RiskLevel.LOW,
                            warnings = listOf("Gemini API key not configured. Please set your API key in Settings > AI Provider")
                        )
                    }
                }
                else -> throw IllegalStateException("Unknown AI provider: $aiProvider")
            }
            
            val prompt = buildContentAnalysisPrompt(message)
            val response = callAIAPI(context, prompt)
            
            parseContentAnalysis(response)
        }.subscribeOn(Schedulers.io())
    }
    
    /**
     * Extract action items from conversation (meetings, tasks, reminders)
     */
    fun extractActionItems(
        context: Context,
        messages: List<MessageInfo>,
        conversationInfo: ConversationInfo
    ): Single<List<ActionItem>> {
        return rxSingle {
            val aiProvider = Preferences.getPreferenceAIProvider(context)
            
            when (aiProvider) {
                "disabled" -> return@rxSingle emptyList<ActionItem>()
                "ollama" -> {
                    val baseUrl = Preferences.getOllamaBaseUrl(context)
                    if (baseUrl.isEmpty()) {
                        return@rxSingle emptyList<ActionItem>()
                    }
                    
                    val ollamaAvailable = try {
                        val request = Request.Builder()
                            .url("$baseUrl/api/tags")
                            .get()
                            .build()
                        ollamaClient.newCall(request).execute().use { it.isSuccessful }
                    } catch (e: Exception) { false }
                    
                    if (!ollamaAvailable) {
                        return@rxSingle emptyList<ActionItem>()
                    }
                }
                "gemini" -> {
                    val apiKey = getGeminiApiKey(context)
                    if (apiKey.isEmpty()) {
                        return@rxSingle emptyList<ActionItem>()
                    }
                }
                else -> throw IllegalStateException("Unknown AI provider: $aiProvider")
            }
            
            val conversationContext = buildConversationContext(messages, conversationInfo)
            val prompt = buildActionItemsPrompt(conversationContext)
            
            val response = callAIAPI(context, prompt)
            parseActionItems(response)
        }.subscribeOn(Schedulers.io())
    }
    
    // Private helper methods
    
    private fun buildConversationContext(
        messages: List<MessageInfo>,
        conversationInfo: ConversationInfo
    ): String {
        val recentMessages = messages.takeLast(MAX_CONVERSATION_CONTEXT)
        val context = StringBuilder()
        
        context.append("Conversation Context:\n")
        context.append("Type: ${if (conversationInfo.isGroupChat) "Group Chat" else "Direct Message"}\n")
        conversationInfo.title?.let { 
            context.append("Title: $it\n")
        }
        context.append("\nRecent Messages:\n")
        
        recentMessages.forEach { message ->
            val sender = message.sender ?: "Unknown"
            val text = message.messageText ?: ""
            context.append("$sender: $text\n")
        }
        
        return context.toString()
    }
    
    private fun buildSmartReplyPrompt(context: String, isGroupChat: Boolean, contextualMemories: List<ConversationMemoryManager.MemoryItem> = emptyList()): String {
        val memoryContext = if (contextualMemories.isNotEmpty()) {
            val memoryInfo = contextualMemories.take(5).joinToString("\n") { memory ->
                "- ${memory.extractedInfo} (from ${memory.conversationTitle ?: "another conversation"})"
            }
            "\nAdditional context from other conversations:\n$memoryInfo\n"
        } else ""
        
        return """
            Based on the following conversation, generate ${MAX_SMART_REPLIES} appropriate reply suggestions.
            The replies should be:
            - Contextually relevant and informed by all available information
            - Natural and conversational
            - Appropriate for a ${if (isGroupChat) "group chat" else "direct message"} setting
            - Brief (1-2 sentences max)
            - Diverse in tone and content
            - Reference information from other conversations when relevant and helpful
            
            Current conversation:
            $context
            $memoryContext
            Provide exactly ${MAX_SMART_REPLIES} reply options, each on a new line, without numbering or formatting:
        """.trimIndent()
    }
    
    private fun buildEnhanceMessagePrompt(
        originalMessage: String,
        tone: MessageTone,
        context: String?,
        contextualMemories: List<ConversationMemoryManager.MemoryItem> = emptyList()
    ): String {
        val toneDescription = when (tone) {
            MessageTone.FORMAL -> "formal and professional"
            MessageTone.CASUAL -> "casual and friendly"
            MessageTone.ENTHUSIASTIC -> "enthusiastic and positive"
            MessageTone.NEUTRAL -> "clear and natural"
        }
        
        val memoryContext = if (contextualMemories.isNotEmpty()) {
            val relevantMemories = contextualMemories.joinToString("\n") { "• ${it.extractedInfo} (from ${it.conversationTitle ?: "conversation"})" }
            Log.d("GeminiHelper", "Including ${contextualMemories.size} memories in single enhancement prompt")
            "Relevant information from your other conversations:\n$relevantMemories\n\n"
        } else {
            Log.d("GeminiHelper", "No memories available for single enhancement prompt")
            ""
        }
        
        return """
            Please improve the following message to be more $toneDescription while maintaining its original meaning.
            Fix any grammar or spelling errors, and make it clearer and more engaging.
            
            ${memoryContext}${context?.let { "Context: $it\n" } ?: ""}
            Original message: "$originalMessage"
            
            If the message appears incomplete (like "the google code is"), use the relevant information above to help complete it naturally.
            Return ONLY the improved message text. Do not use quotation marks, labels, formatting, or prefixes. Just return the plain improved message:
        """.trimIndent()
    }
    
    private fun buildEnhanceMessageMultiplePrompt(
        originalMessage: String,
        context: String?,
        contextualMemories: List<ConversationMemoryManager.MemoryItem> = emptyList()
    ): String {
        val memoryContext = if (contextualMemories.isNotEmpty()) {
            val relevantMemories = contextualMemories.joinToString("\n") { "• ${it.extractedInfo} (from ${it.conversationTitle ?: "conversation"})" }
            Log.d("GeminiHelper", "Including ${contextualMemories.size} memories in multiple enhancement prompt")
            "Relevant information from your other conversations:\n$relevantMemories\n\n"
        } else {
            Log.d("GeminiHelper", "No memories available for multiple enhancement prompt")
            ""
        }
        
        return """
            Please provide 3 different improved versions of this message. Do not use brackets or placeholders - provide the actual enhanced text.
            
            1. Make it casual and friendly
            2. Make it professional and polished  
            3. Make it enthusiastic and engaging
            
            Each version should maintain the original meaning while improving grammar, clarity, and tone.
            
            ${memoryContext}${context?.let { "Context: $it\n" } ?: ""}
            Original message: "$originalMessage"
            
            If the message appears incomplete (like "the google code is"), use the relevant information above to help complete it naturally.
            
            Response format (provide actual text, not placeholders):
            1: 
            2: 
            3: 
        """.trimIndent()
    }
    
    private fun parseMultipleEnhancements(response: String): List<String> {
        val enhancements = mutableListOf<String>()
        
        // Parse the numbered list response (format: "1: text", "2: text", "3: text")
        val lines = response.trim().split('\n')
        for (line in lines) {
            val trimmedLine = line.trim()
            if (trimmedLine.matches(Regex("^[123]:\\s*.+"))) {
                val enhancement = trimmedLine.substring(2).trim()
                if (enhancement.isNotEmpty() && !enhancement.startsWith("[") && !enhancement.endsWith("]")) {
                    enhancements.add(enhancement)
                }
            }
        }
        
        // Alternative parsing: look for numbered lines without colons
        if (enhancements.isEmpty()) {
            for (line in lines) {
                val trimmedLine = line.trim()
                if (trimmedLine.matches(Regex("^[123][.)]\\s*.+"))) {
                    val enhancement = trimmedLine.substring(2).trim()
                    if (enhancement.isNotEmpty() && !enhancement.startsWith("[") && !enhancement.endsWith("]")) {
                        enhancements.add(enhancement)
                    }
                }
            }
        }
        
        // Fallback: split by paragraphs and take meaningful content
        if (enhancements.isEmpty()) {
            val fallbackEnhancements = response.split(Regex("\n\n+"))
                .map { it.trim() }
                .filter { it.isNotEmpty() && it.length > 10 && !it.contains("[") && !it.contains("]") }
                .take(3)
            
            enhancements.addAll(fallbackEnhancements)
        }
        
        // Last resort fallback: generate basic variations if AI didn't work
        if (enhancements.isEmpty()) {
            val original = response.trim().takeIf { it.isNotEmpty() } ?: "Message enhanced"
            enhancements.add("$original 😊")
            enhancements.add("$original.")
            enhancements.add("$original!")
        }
        
        return enhancements.take(3)
    }
    
    private fun buildGrammarCheckPrompt(originalMessage: String): String {
        return """
            Please fix ONLY grammar, spelling, and punctuation errors in this message. 
            Do NOT change the tone, style, or meaning. Keep it exactly as the person intended to say it.
            If there are no errors, return the message unchanged.
            
            Original message: "$originalMessage"
            
            Return ONLY the corrected message text. Do not use quotation marks, labels, formatting, or prefixes. Just return the plain corrected message:
        """.trimIndent()
    }
    
    private fun buildSummarizationPrompt(context: String, isGroupChat: Boolean): String {
        return """
            Please provide a concise summary of the following ${if (isGroupChat) "group" else ""} conversation.
            Focus on key points, decisions, and important information discussed.
            
            $context
            
            Summary:
        """.trimIndent()
    }
    
    private fun buildContentAnalysisPrompt(message: String): String {
        return """
            Analyze the following message for potential issues. Provide a JSON response with the following structure:
            {
                "isSpam": boolean,
                "hasInappropriateContent": boolean,
                "containsSensitiveInfo": boolean,
                "riskLevel": "LOW"|"MEDIUM"|"HIGH",
                "warnings": ["list", "of", "warnings"]
            }
            
            Message to analyze: "$message"
            
            Analysis:
        """.trimIndent()
    }
    
    private fun buildActionItemsPrompt(context: String): String {
        return """
            Extract any action items, tasks, appointments, or reminders from the following conversation.
            Provide a JSON array where each item has this structure:
            {
                "type": "TASK"|"APPOINTMENT"|"REMINDER",
                "description": "description of the action item",
                "dueDate": "date if mentioned, null otherwise",
                "assignee": "person assigned if mentioned, null otherwise"
            }
            
            $context
            
            Action Items:
        """.trimIndent()
    }
    
    private fun parseSmartReplies(response: String): List<String> {
        return response
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("Here are") && !it.startsWith("Reply") }
            .take(MAX_SMART_REPLIES)
    }
    
    private fun parseContentAnalysis(response: String): ContentAnalysis {
        // Simple content analysis based on keywords for now
        // Could be enhanced to parse JSON if Ollama returns structured data
        val lowerResponse = response.lowercase()
        
        return ContentAnalysis(
            isSpam = lowerResponse.contains("spam") || lowerResponse.contains("promotional"),
            hasInappropriateContent = lowerResponse.contains("inappropriate") || lowerResponse.contains("offensive"),
            containsSensitiveInfo = lowerResponse.contains("sensitive") || lowerResponse.contains("personal"),
            riskLevel = when {
                lowerResponse.contains("high risk") -> RiskLevel.HIGH
                lowerResponse.contains("medium risk") -> RiskLevel.MEDIUM
                else -> RiskLevel.LOW
            },
            warnings = listOf()
        )
    }
    
    private fun parseActionItems(response: String): List<ActionItem> {
        // Simple parsing for action items
        // Look for common patterns like "TODO:", "Meeting:", "Reminder:", etc.
        val lines = response.split("\n").map { it.trim() }
        val actionItems = mutableListOf<ActionItem>()
        
        lines.forEach { line ->
            when {
                line.startsWith("TODO:", true) || line.startsWith("Task:", true) -> {
                    actionItems.add(ActionItem(ActionType.TASK, line.substring(5).trim(), null, null))
                }
                line.startsWith("Meeting:", true) || line.startsWith("Appointment:", true) -> {
                    actionItems.add(ActionItem(ActionType.APPOINTMENT, line.substring(8).trim(), null, null))
                }
                line.startsWith("Reminder:", true) -> {
                    actionItems.add(ActionItem(ActionType.REMINDER, line.substring(9).trim(), null, null))
                }
            }
        }
        
        return actionItems
    }
    
    /**
     * Clean up enhanced message response by removing formatting artifacts
     * NUCLEAR APPROACH - Remove all possible quote variants
     */
    private fun cleanEnhancedMessageResponse(response: String): String {
        var cleaned = response.trim()
        
        // Remove common formatting labels and prefixes - MORE COMPREHENSIVE LIST
        val labelsToRemove = listOf(
            "Professional and Polished:",
            "Casual and Friendly:", 
            "Enthusiastic and Engaging:",
            "Improved message:",
            "Enhanced message:",
            "Corrected message:",
            "Here is the improved message:",
            "Here's the enhanced version:",
            "**Professional and Polished:**",
            "**Casual and Friendly:**",
            "**Enthusiastic and Engaging:**",
            "**Improved message:**",
            "**Enhanced message:**",
            "Improved:",
            "Enhanced:",
            "Corrected:",
            "Result:",
            "Output:",
            "Response:"
        )
        
        // Remove labels (case insensitive) - MULTIPLE PASSES
        repeat(3) {
            labelsToRemove.forEach { label ->
                if (cleaned.startsWith(label, ignoreCase = true)) {
                    cleaned = cleaned.substring(label.length).trim()
                }
            }
        }
        
        // NUCLEAR QUOTE REMOVAL - Remove ALL types of quotes everywhere
        // Step 1: Remove ALL quotation characters completely
        cleaned = cleaned.replace("\"", "")     // Double quotes
        cleaned = cleaned.replace("'", "")      // Single quotes (all of them)
        cleaned = cleaned.replace(""", "")      // Left smart quote
        cleaned = cleaned.replace(""", "")      // Right smart quote  
        cleaned = cleaned.replace("'", "")      // Left smart apostrophe
        cleaned = cleaned.replace("'", "")      // Right smart apostrophe
        cleaned = cleaned.replace("‚", "")      // Single low quote
        cleaned = cleaned.replace("„", "")      // Double low quote
        cleaned = cleaned.replace("‹", "")      // Single left angle quote
        cleaned = cleaned.replace("›", "")      // Single right angle quote
        cleaned = cleaned.replace("«", "")      // Double left angle quote
        cleaned = cleaned.replace("»", "")      // Double right angle quote
        cleaned = cleaned.replace("`", "")      // Backticks
        cleaned = cleaned.replace("´", "")      // Acute accent
        
        // Step 2: Fix contractions that got broken (restore apostrophes only in contractions)
        val contractionFixes = mapOf(
            "dont" to "don't",
            "cant" to "can't", 
            "wont" to "won't",
            "isnt" to "isn't",
            "arent" to "aren't",
            "wasnt" to "wasn't",
            "werent" to "weren't",
            "havent" to "haven't",
            "hasnt" to "hasn't",
            "hadnt" to "hadn't",
            "wouldnt" to "wouldn't",
            "couldnt" to "couldn't",
            "shouldnt" to "shouldn't",
            "didnt" to "didn't",
            "doesnt" to "doesn't",
            "Im" to "I'm",
            "youre" to "you're",
            "hes" to "he's",
            "shes" to "she's",
            "its" to "it's",
            "were" to "we're",
            "theyre" to "they're",
            "Ill" to "I'll",
            "youll" to "you'll",
            "hell" to "he'll",
            "shell" to "she'll",
            "well" to "we'll",
            "theyll" to "they'll",
            "Ive" to "I've",
            "youve" to "you've",
            "weve" to "we've",
            "theyve" to "they've"
        )
        
        contractionFixes.forEach { (broken, fixed) ->
            cleaned = cleaned.replace("\\b$broken\\b".toRegex(RegexOption.IGNORE_CASE)) { 
                if (it.value[0].isUpperCase()) fixed.replaceFirstChar { char -> char.uppercase() } else fixed
            }
        }
        
        // Remove markdown formatting
        cleaned = cleaned.replace("**", "")
        cleaned = cleaned.replace("*", "")
        cleaned = cleaned.replace("__", "")
        cleaned = cleaned.replace("_", "")
        
        // Remove any remaining formatting artifacts
        cleaned = cleaned.removePrefix(":").removePrefix("-").removePrefix("•").trim()
        
        // Clean up multiple spaces
        cleaned = cleaned.replace(Regex("\\s+"), " ")
        
        return cleaned.trim()
    }
}

// Data classes for Gemini features

enum class MessageTone {
    FORMAL,
    CASUAL,
    ENTHUSIASTIC,
    NEUTRAL
}

data class ContentAnalysis(
    val isSpam: Boolean,
    val hasInappropriateContent: Boolean,
    val containsSensitiveInfo: Boolean,
    val riskLevel: RiskLevel,
    val warnings: List<String>
)

enum class RiskLevel {
    LOW,
    MEDIUM,
    HIGH
}

data class ActionItem(
    val type: ActionType,
    val description: String,
    val dueDate: String?,
    val assignee: String?
)

enum class ActionType {
    TASK,
    APPOINTMENT,
    REMINDER
}