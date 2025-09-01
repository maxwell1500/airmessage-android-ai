package me.tagavari.airmessage.helper

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import me.tagavari.airmessage.messaging.MessageInfo
import me.tagavari.airmessage.messaging.ConversationInfo
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Manages contextual conversation memory for enhanced smart replies.
 * Extracts key information from messages and maintains a compact, searchable memory store.
 */
object ConversationMemoryManager {
    private const val TAG = "ConversationMemory"
    private const val MEMORY_FILE_NAME = "conversation_memory.json"
    private const val PREF_MEMORY_MESSAGE_LIMIT = "conversation_memory_message_limit"
    private const val DEFAULT_MESSAGE_LIMIT = 50
    
    // Rate limiting for Gemini API to avoid 429 errors
    private var lastGeminiRequestTime: Long = 0
    private var consecutiveFailures: Int = 0
    private const val BASE_DELAY_MS = 3000L  // Start with 3 seconds between requests
    private const val MAX_DELAY_MS = 60000L  // Cap at 60 seconds
    private const val OLLAMA_TIMEOUT_SECONDS = 15L
    
    // Using JSON objects instead of Gson for Android compatibility
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(OLLAMA_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(OLLAMA_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(OLLAMA_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    
    /**
     * Data class representing a memory item extracted from messages
     */
    data class MemoryItem(
        val id: String,
        val conversationGuid: String,
        val conversationTitle: String?,
        val extractedInfo: String,
        val originalMessage: String,
        val senderName: String?,
        val timestamp: Long,
        val category: String, // "code", "event", "location", "time", "person", "general"
        val confidence: Float = 1.0f
    )
    
    /**
     * Data class representing the complete memory store
     */
    data class ConversationMemoryStore(
        val items: MutableList<MemoryItem> = mutableListOf(),
        var lastUpdated: Long = System.currentTimeMillis()
    )
    
    /**
     * Gets the configured message limit from preferences
     */
    @JvmStatic
    fun getMessageLimit(context: Context): Int {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        return preferences.getInt(PREF_MEMORY_MESSAGE_LIMIT, DEFAULT_MESSAGE_LIMIT)
    }
    
    /**
     * Sets the message limit in preferences
     */
    @JvmStatic
    fun setMessageLimit(context: Context, limit: Int) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        preferences.edit()
            .putInt(PREF_MEMORY_MESSAGE_LIMIT, limit)
            .apply()
    }
    
    /**
     * Extracts contextual information from a message and adds it to memory
     */
    @JvmStatic
    fun processMessage(context: Context, message: MessageInfo, conversation: ConversationInfo): Completable {
        // Skip messages without text or that are too short
        val messageText = message.messageText
        if (messageText.isNullOrBlank() || messageText.length < 10) {
            return Completable.complete()
        }
        
        return Completable.fromAction {
            // Extract information using selected AI provider
            val extractedInfo = extractInformationFromMessage(context, messageText, conversation)
            
            if (extractedInfo != null) {
                // Add to memory synchronously since we're already on IO thread
                val memoryFile = getMemoryFile(context)
                val memoryStore = loadMemoryStore(context)
                
                // Add the new item
                memoryStore.items.add(extractedInfo)
                
                // Enforce message limit by removing oldest items
                val messageLimit = getMessageLimit(context)
                while (memoryStore.items.size > messageLimit) {
                    memoryStore.items.removeAt(0) // Remove oldest
                }
                
                // Update timestamp
                memoryStore.lastUpdated = System.currentTimeMillis()
                
                // Save to file
                saveMemoryStore(memoryFile, memoryStore)
                
                Log.d(TAG, "Added memory item: ${extractedInfo.extractedInfo}")
            }
        }
        .subscribeOn(Schedulers.io())
        .onErrorComplete { error ->
            Log.w(TAG, "Failed to process message for memory", error)
            true // Continue silently on error
        }
    }
    
    /**
     * Extracts information from message text using the selected AI provider
     */
    private fun extractInformationFromMessage(context: Context, messageText: String, conversation: ConversationInfo): MemoryItem? {
        try {
            Log.d(TAG, "Extracting info from message: ${messageText.take(50)}...")
            val prompt = buildExtractionPrompt(messageText)
            
            Log.d(TAG, "AI extraction prompt: ${prompt.take(200)}...")
            val response = callSelectedAI(context, prompt)
            Log.d(TAG, "AI response received: ${response?.take(300) ?: "NULL"}")
            
            val result = parseExtractionResponse(response, messageText, conversation)
            
            if (result != null) {
                Log.i(TAG, "Successfully extracted memory item: ${result.extractedInfo}")
            } else {
                Log.w(TAG, "No memory item extracted from: '${messageText.take(100)}'")
            }
            
            return result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract information from message: '${messageText.take(50)}'", e)
            return null
        }
    }
    
    /**
     * Builds the prompt for information extraction
     */
    private fun buildExtractionPrompt(messageText: String): String {
        return """
Extract useful information from this message that could help generate better smart replies in future conversations. Look for:

**High Priority (extract these):**
- Codes/credentials (2FA codes, access codes, passwords, PINs, confirmation numbers)
- Events with times/dates (meetings, parties, appointments, deadlines)
- Locations and addresses
- Important decisions or plans
- Commitments and availability

**Medium Priority (extract if relevant):**
- Names of people, places, or companies
- Preferences and interests mentioned
- Problems or issues discussed
- Future activities or plans
- Important facts or information shared

**Low Priority (skip routine messages):**
- Basic greetings ("hi", "hello", "thanks")
- Simple confirmations ("ok", "yes", "sounds good")
- Very short responses

Message: "$messageText"

Respond in JSON format:
{
  "hasInfo": true/false,
  "info": "concise but complete extracted information",
  "category": "code|event|location|person|plan|interest|fact|general",
  "confidence": 0.0-1.0
}

Examples:
- "Your verification code is 123456" → {"hasInfo": true, "info": "Verification code: 123456", "category": "code", "confidence": 1.0}
- "Meeting at 3pm tomorrow at Starbucks" → {"hasInfo": true, "info": "Meeting at 3pm tomorrow at Starbucks", "category": "event", "confidence": 0.95}
- "I love hiking in the mountains" → {"hasInfo": true, "info": "Enjoys hiking in mountains", "category": "interest", "confidence": 0.8}
- "My favorite restaurant is Giuseppe's on Main Street" → {"hasInfo": true, "info": "Favorite restaurant: Giuseppe's on Main Street", "category": "fact", "confidence": 0.85}
- "Just arrived at the airport" → {"hasInfo": true, "info": "Currently at airport", "category": "location", "confidence": 0.9}
- "ok" → {"hasInfo": false}

Be generous in extracting information - when in doubt, include it if it might be useful later.
        """.trimIndent()
    }
    
    /**
     * Calls the selected AI provider for information extraction
     */
    private fun callSelectedAI(context: Context, prompt: String): String? {
        return try {
            // Check if AI is enabled first
            val aiEnabled = me.tagavari.airmessage.activity.Preferences.getPreferenceAIEnabled(context)
            Log.d(TAG, "AI enabled: $aiEnabled")
            
            if (!aiEnabled) {
                Log.d(TAG, "AI features are disabled - skipping extraction")
                return null
            }
            
            val aiProvider = me.tagavari.airmessage.activity.Preferences.getPreferenceAIProvider(context)
            Log.d(TAG, "Using AI provider: $aiProvider")
            
            // Also debug the raw preference value
            val rawPrefs = android.preference.PreferenceManager.getDefaultSharedPreferences(context)
            val rawValue = rawPrefs.getString(context.getResources().getString(me.tagavari.airmessage.R.string.preference_features_aiprovider_key), "DEFAULT_NOT_SET")
            Log.d(TAG, "Raw AI provider preference: '$rawValue'")
            
            when (aiProvider) {
                "disabled" -> {
                    Log.d(TAG, "AI features are disabled")
                    null
                }
                "ollama" -> {
                    callOllamaAI(context, prompt)
                }
                "ollama_turbo" -> {
                    callOllamaTurboAI(context, prompt)
                }
                "gemini" -> {
                    callGeminiAI(context, prompt)
                }
                else -> {
                    Log.w(TAG, "Unknown AI provider: $aiProvider")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to call AI provider", e)
            null
        }
    }
    
    /**
     * Calls Ollama AI using user-configured settings
     */
    private fun callOllamaAI(context: Context, prompt: String): String? {
        val hostname = me.tagavari.airmessage.activity.Preferences.getPreferenceOllamaHostname(context)
        val port = me.tagavari.airmessage.activity.Preferences.getPreferenceOllamaPort(context)
        val model = me.tagavari.airmessage.activity.Preferences.getPreferenceOllamaModel(context)
        
        Log.d(TAG, "Ollama config - hostname: '$hostname', port: '$port', model: '$model'")
        
        if (hostname.isEmpty()) {
            Log.w(TAG, "Ollama hostname not configured - cannot make AI call")
            return null
        }
        
        val requestJson = JSONObject()
        requestJson.put("model", if (model.isEmpty()) "llama3.2" else model)
        requestJson.put("prompt", prompt)
        requestJson.put("stream", false)
        
        val options = JSONObject()
        options.put("temperature", 0.1)
        requestJson.put("options", options)
        
        val requestBody = requestJson.toString().toRequestBody("application/json".toMediaType())
        
        val request = Request.Builder()
            .url("http://$hostname:$port/api/generate")
            .post(requestBody)
            .build()
        
        return try {
            Log.d(TAG, "Calling Ollama API at $hostname:$port...")
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    if (responseBody != null) {
                        Log.d(TAG, "Ollama API response received")
                        val responseJson = JSONObject(responseBody)
                        val aiResponse = responseJson.optString("response", "")
                        Log.d(TAG, "Extracted AI response: ${aiResponse.take(100)}")
                        aiResponse
                    } else {
                        Log.w(TAG, "Ollama API response body is null")
                        null
                    }
                } else {
                    Log.w(TAG, "Ollama API call failed: ${response.code} - ${response.message}")
                    null
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Network error calling Ollama API", e)
            null
        }
    }
    
    /**
     * Calls Google Gemini API using user-configured API key with aggressive rate limiting
     */
    private fun callGeminiAI(context: Context, prompt: String): String? {
        val apiKey = me.tagavari.airmessage.activity.Preferences.getPreferenceGeminiApiKey(context)
        
        Log.d(TAG, "Gemini API key configured: ${if (apiKey.isEmpty()) "NO" else "YES (${apiKey.length} chars)"}")
        
        if (apiKey.isEmpty()) {
            Log.w(TAG, "Gemini API key not configured - cannot make AI call")
            return null
        }
        
        // Implement aggressive rate limiting with exponential backoff
        val currentTime = System.currentTimeMillis()
        val timeSinceLastRequest = currentTime - lastGeminiRequestTime
        
        // Calculate delay based on consecutive failures (exponential backoff)
        val baseDelay = if (consecutiveFailures > 0) {
            minOf(BASE_DELAY_MS * (1L shl minOf(consecutiveFailures, 4)), MAX_DELAY_MS)
        } else {
            BASE_DELAY_MS
        }
        
        if (timeSinceLastRequest < baseDelay) {
            val waitTime = baseDelay - timeSinceLastRequest
            Log.d(TAG, "Rate limiting: waiting ${waitTime}ms (consecutive failures: $consecutiveFailures)")
            try {
                Thread.sleep(waitTime)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        
        lastGeminiRequestTime = System.currentTimeMillis()
        
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
                put("temperature", 0.1)
                put("maxOutputTokens", 512)
            })
        }
        
        val requestBody = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey")
            .post(requestBody)
            .build()
        
        return try {
            Log.d(TAG, "Calling Gemini API (attempt after $consecutiveFailures consecutive failures)...")
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    if (responseBody != null) {
                        Log.d(TAG, "Gemini API response received - resetting failure count")
                        consecutiveFailures = 0  // Reset on success
                        val responseJson = JSONObject(responseBody)
                        val aiResponse = responseJson
                            .getJSONArray("candidates")
                            .getJSONObject(0)
                            .getJSONObject("content")
                            .getJSONArray("parts")
                            .getJSONObject(0)
                            .getString("text")
                        Log.d(TAG, "Extracted AI response: ${aiResponse.take(100)}")
                        aiResponse
                    } else {
                        Log.w(TAG, "Gemini API response body is null")
                        consecutiveFailures++
                        Log.w(TAG, "Incremented consecutive failures to: $consecutiveFailures")
                        null
                    }
                } else {
                    Log.w(TAG, "Gemini API call failed: ${response.code} - ${response.message}")
                    if (response.code == 429) {
                        consecutiveFailures++
                        Log.w(TAG, "Rate limited (429) - consecutive failures now: $consecutiveFailures, next delay will be ~${minOf(BASE_DELAY_MS * (1L shl minOf(consecutiveFailures, 4)), MAX_DELAY_MS)}ms")
                    } else if (response.code in 400..499) {
                        consecutiveFailures++
                        Log.w(TAG, "Client error (${response.code}) - consecutive failures now: $consecutiveFailures")
                    }
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to call Gemini API", e)
            consecutiveFailures++
            Log.w(TAG, "Exception occurred - consecutive failures now: $consecutiveFailures")
            null
        }
    }
    
    /**
     * Calls Ollama Turbo API using API key
     */
    private fun callOllamaTurboAI(context: Context, prompt: String): String? {
        val apiKey = me.tagavari.airmessage.activity.Preferences.getPreferenceOllamaTurboApiKey(context)
        
        Log.d(TAG, "Ollama Turbo API key configured: ${if (apiKey.isEmpty()) "NO" else "YES (${apiKey.length} chars)"}")
        
        if (apiKey.isEmpty()) {
            Log.w(TAG, "Ollama Turbo API key not configured - cannot make AI call")
            return null
        }
        
        // Ollama Turbo API call structure - this will need to be updated based on their actual API
        // For now, I'll implement a placeholder that follows typical API patterns
        val json = JSONObject().apply {
            put("model", "turbo") // or whatever the turbo model name is
            put("prompt", prompt)
            put("stream", false)
            put("options", JSONObject().apply {
                put("temperature", 0.1)
                put("max_tokens", 512)
            })
        }
        
        val requestBody = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://api.ollama.ai/v1/generate") // placeholder URL - update with actual Ollama Turbo API endpoint
            .post(requestBody)
            .addHeader("Authorization", "Bearer $apiKey")
            .build()
        
        return try {
            Log.d(TAG, "Calling Ollama Turbo API...")
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    if (responseBody != null) {
                        Log.d(TAG, "Ollama Turbo API response received")
                        val responseJson = JSONObject(responseBody)
                        val aiResponse = responseJson.optString("response", "")
                        Log.d(TAG, "Extracted AI response: ${aiResponse.take(100)}")
                        aiResponse
                    } else {
                        Log.w(TAG, "Ollama Turbo API response body is null")
                        null
                    }
                } else {
                    Log.w(TAG, "Ollama Turbo API call failed: ${response.code} - ${response.message}")
                    null
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Network error calling Ollama Turbo API", e)
            null
        }
    }
    
    /**
     * Parses the extraction response from AI provider
     */
    private fun parseExtractionResponse(response: String?, messageText: String, conversation: ConversationInfo): MemoryItem? {
        if (response.isNullOrBlank()) {
            Log.w(TAG, "Empty or null response from AI provider")
            return null
        }
        
        Log.d(TAG, "Parsing AI response (length: ${response.length}): ${response.take(200)}...")
        
        // Clean up the response - remove markdown code block formatting if present
        val cleanedResponse = response
            .replace("```json", "")
            .replace("```", "")
            .trim()
        
        Log.d(TAG, "Cleaned response: ${cleanedResponse.take(100)}...")
        
        return try {
            // Try to parse the cleaned response as JSON first
            val extracted = JSONObject(cleanedResponse)
            val hasInfo = extracted.optBoolean("hasInfo", false)
            
            Log.d(TAG, "Parsed JSON hasInfo: $hasInfo")
            
            if (hasInfo) {
                val extractedInfo = extracted.optString("info", "")
                Log.d(TAG, "Extracted info: $extractedInfo")
                
                if (extractedInfo.isNotBlank()) {
                    MemoryItem(
                        id = "${conversation.guid ?: "unknown"}_${System.currentTimeMillis()}",
                        conversationGuid = conversation.guid ?: "",
                        conversationTitle = conversation.title,
                        extractedInfo = extractedInfo,
                        originalMessage = messageText,
                        senderName = null, // Will be set by caller if available
                        timestamp = System.currentTimeMillis(),
                        category = extracted.optString("category", "general"),
                        confidence = extracted.optDouble("confidence", 1.0).toFloat()
                    )
                } else {
                    Log.d(TAG, "Extracted info is blank")
                    null
                }
            } else {
                Log.d(TAG, "No useful info found in message")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse extraction response as JSON: ${e.message}")
            Log.d(TAG, "Raw response for fallback parsing: ${cleanedResponse.take(200)}")
            
            // Enhanced fallback: try to extract JSON from within text response
            val jsonMatch = Regex("\\{[^}]*\"hasInfo\"[^}]*\\}").find(cleanedResponse)
            if (jsonMatch != null) {
                try {
                    val jsonPart = jsonMatch.value
                    Log.d(TAG, "Found JSON in response: $jsonPart")
                    val extracted = JSONObject(jsonPart)
                    val hasInfo = extracted.optBoolean("hasInfo", false)
                    
                    if (hasInfo) {
                        val extractedInfo = extracted.optString("info", "")
                        if (extractedInfo.isNotBlank()) {
                            Log.d(TAG, "Fallback JSON extracted info: $extractedInfo")
                            return MemoryItem(
                                id = "${conversation.guid ?: "unknown"}_${System.currentTimeMillis()}",
                                conversationGuid = conversation.guid ?: "",
                                conversationTitle = conversation.title,
                                extractedInfo = extractedInfo,
                                originalMessage = messageText,
                                senderName = null,
                                timestamp = System.currentTimeMillis(),
                                category = extracted.optString("category", "general"),
                                confidence = extracted.optDouble("confidence", 0.7).toFloat()
                            )
                        }
                    }
                } catch (e2: Exception) {
                    Log.w(TAG, "Fallback JSON parsing also failed: ${e2.message}")
                }
            }
            
            // Final fallback: simple regex extraction
            if (response.contains("hasInfo") && (response.contains("true") || response.contains(": true"))) {
                Log.d(TAG, "Attempting regex fallback extraction")
                val infoMatch = Regex("\"info\"\\s*:?\\s*\"([^\"]+)\"").find(response)
                val categoryMatch = Regex("\"category\"\\s*:?\\s*\"([^\"]+)\"").find(response)
                
                if (infoMatch != null) {
                    val extractedInfo = infoMatch.groupValues[1]
                    Log.d(TAG, "Regex extracted info: $extractedInfo")
                    
                    return MemoryItem(
                        id = "${conversation.guid ?: "unknown"}_${System.currentTimeMillis()}",
                        conversationGuid = conversation.guid ?: "",
                        conversationTitle = conversation.title,
                        extractedInfo = extractedInfo,
                        originalMessage = messageText,
                        senderName = null,
                        timestamp = System.currentTimeMillis(),
                        category = categoryMatch?.groupValues?.get(1) ?: "general",
                        confidence = 0.5f
                    )
                }
            }
            
            // Ultra-aggressive fallback: if response contains useful patterns, extract them
            Log.d(TAG, "Attempting ultra-aggressive fallback parsing")
            val usefulPatterns = listOf(
                Regex("\\b\\d{4,6}\\b"), // Codes (4-6 digits)
                Regex("\\b(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b", RegexOption.IGNORE_CASE), // Days
                Regex("\\b(?:january|february|march|april|may|june|july|august|september|october|november|december)\\b", RegexOption.IGNORE_CASE), // Months
                Regex("\\b\\d{1,2}:\\d{2}\\s*(?:am|pm)?\\b", RegexOption.IGNORE_CASE), // Times
                Regex("\\b(?:meeting|appointment|event|party|dinner|lunch)\\b", RegexOption.IGNORE_CASE) // Event words
            )
            
            for (pattern in usefulPatterns) {
                val matches = pattern.findAll(response).toList()
                if (matches.isNotEmpty()) {
                    val extractedInfo = "Response contains: ${matches.joinToString(", ") { it.value }}"
                    Log.d(TAG, "Ultra-aggressive extraction: $extractedInfo")
                    
                    return MemoryItem(
                        id = "${conversation.guid ?: "unknown"}_${System.currentTimeMillis()}",
                        conversationGuid = conversation.guid ?: "",
                        conversationTitle = conversation.title,
                        extractedInfo = extractedInfo,
                        originalMessage = messageText,
                        senderName = null,
                        timestamp = System.currentTimeMillis(),
                        category = "extracted",
                        confidence = 0.3f
                    )
                }
            }
            
            Log.w(TAG, "All extraction methods failed for response: ${response.take(100)}")
            null
        }
    }
    
    /**
     * Retrieves contextual memories that could be relevant for generating smart replies
     */
    @JvmStatic
    fun getContextualMemories(context: Context, currentConversation: ConversationInfo, query: String? = null): Single<List<MemoryItem>> {
        return Single.fromCallable {
            val memoryStore = loadMemoryStore(context)
            var relevantItems = memoryStore.items
                .filter { it.conversationGuid != currentConversation.guid } // Exclude current conversation
                .sortedByDescending { it.timestamp } // Most recent first
            
            // If query provided, filter by relevance
            if (!query.isNullOrBlank()) {
                relevantItems = relevantItems.filter { item ->
                    item.extractedInfo.contains(query, ignoreCase = true) ||
                    item.originalMessage.contains(query, ignoreCase = true) ||
                    item.category.contains(query, ignoreCase = true)
                }
            }
            
            // Return top 10 most relevant items
            relevantItems.take(10)
        }
        .subscribeOn(Schedulers.io())
        .observeOn(AndroidSchedulers.mainThread())
    }
    
    /**
     * Gets all memory items for debugging/management
     */
    @JvmStatic
    fun getAllMemories(context: Context): Single<List<MemoryItem>> {
        return Single.fromCallable {
            loadMemoryStore(context).items.sortedByDescending { it.timestamp }
        }
        .subscribeOn(Schedulers.io())
        .observeOn(AndroidSchedulers.mainThread())
    }
    
    /**
     * Clears all memory items
     */
    @JvmStatic
    fun clearAllMemories(context: Context): Completable {
        return Completable.fromAction {
            val memoryFile = getMemoryFile(context)
            val emptyStore = ConversationMemoryStore()
            saveMemoryStore(memoryFile, emptyStore)
            Log.d(TAG, "Cleared all conversation memories")
        }
        .subscribeOn(Schedulers.io())
    }
    
    /**
     * Clears old memories beyond the current message limit, keeping only the newest ones
     */
    @JvmStatic
    fun clearOldMemories(context: Context): Completable {
        return Completable.fromAction {
            val memoryFile = getMemoryFile(context)
            val memoryStore = loadMemoryStore(context)
            val messageLimit = getMessageLimit(context)
            
            if (memoryStore.items.size > messageLimit) {
                // Sort by timestamp (newest first) and keep only the newest ones
                val sortedItems = memoryStore.items.sortedByDescending { it.timestamp }
                val itemsToKeep = sortedItems.take(messageLimit)
                val removedCount = memoryStore.items.size - itemsToKeep.size
                
                // Clear the list and add back only the newest items
                memoryStore.items.clear()
                memoryStore.items.addAll(itemsToKeep)
                memoryStore.lastUpdated = System.currentTimeMillis()
                
                // Save the updated store
                saveMemoryStore(memoryFile, memoryStore)
                
                Log.d(TAG, "Cleared $removedCount old conversation memories, kept ${itemsToKeep.size}")
            } else {
                Log.d(TAG, "No old memories to clear. Current count: ${memoryStore.items.size}, limit: $messageLimit")
            }
        }
        .subscribeOn(Schedulers.io())
    }
    
    /**
     * Result data for bulk processing operation
     */
    data class ProcessingResult(
        val conversationsProcessed: Int,
        val messagesProcessed: Int,
        val memoriesExtracted: Int,
        val processingTimeMs: Long
    )
    
    /**
     * Processes existing messages from all conversations to build memory store
     */
    @JvmStatic
    fun processExistingMessages(context: Context): io.reactivex.rxjava3.core.Single<ProcessingResult> {
        return io.reactivex.rxjava3.core.Single.fromCallable {
            try {
                val startTime = System.currentTimeMillis()
                val messageLimit = getMessageLimit(context)
                var totalProcessed = 0
                var totalExtracted = 0
                var consecutiveFailures: Int // Track consecutive failures per batch
                
                Log.d(TAG, "Starting to process existing messages with global limit: $messageLimit")
                
                // Get all conversations from the database
                val conversations = me.tagavari.airmessage.data.DatabaseManager.getInstance()
                    .fetchSummaryConversations(context, false)
                
                Log.d(TAG, "Found ${conversations.size} conversations to process")
                
                // Collect ALL messages from ALL conversations first, then apply global limit
                val allMessages = mutableListOf<Pair<MessageInfo, ConversationInfo>>()
                
                for (conversation in conversations) {
                    try {
                        // Get conversation items (which includes messages)
                        val conversationItems = me.tagavari.airmessage.data.DatabaseManager.getInstance()
                            .loadConversationItems(context, conversation.localID)
                        
                        // Filter to get only MessageInfo items
                        val messages = conversationItems
                            .filterIsInstance<MessageInfo>()
                            .filter { !it.messageText.isNullOrBlank() && it.messageText!!.length >= 10 }
                        
                        // Add to global list with conversation info
                        messages.forEach { message ->
                            allMessages.add(Pair(message, conversation))
                        }
                        
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to load messages from conversation ${conversation.title}", e)
                    }
                }
                
                // Sort all messages by date and take the most recent ones up to the limit
                val messagesToProcess = allMessages
                    .sortedBy { it.first.date }
                    .takeLast(messageLimit)
                
                Log.d(TAG, "Processing ${messagesToProcess.size} messages total (from ${allMessages.size} available messages)")
                
                val processedConversations = mutableSetOf<String>()
                
                // Process messages in batches to improve success rate and performance
                val batchSize = if (messageLimit > 500) 50 else 25 // Larger batches for big jobs
                val batches = messagesToProcess.chunked(batchSize)
                
                Log.d(TAG, "Processing in ${batches.size} batches of up to $batchSize messages each")
                
                for ((batchIndex, batch) in batches.withIndex()) {
                    Log.d(TAG, "Processing batch ${batchIndex + 1}/${batches.size} (${batch.size} messages)")
                    
                    // Reset consecutive failures for each batch to avoid early termination
                    consecutiveFailures = 0
                    var batchSuccesses = 0
                    
                    for ((messageIndex, messageWithConversation) in batch.withIndex()) {
                        val (message, conversation) = messageWithConversation
                        processedConversations.add(conversation.guid ?: conversation.localID.toString())
                        
                        // Stop current batch if too many consecutive failures, but continue to next batch
                        if (consecutiveFailures >= 8) { // Slightly more permissive within batch
                            Log.w(TAG, "Too many consecutive failures in batch ${batchIndex + 1}, moving to next batch")
                            break
                        }
                        
                        try {
                            // Conservative rate limiting within batch to avoid 429 errors
                            if (messageIndex > 0) {
                                Thread.sleep(500) // Slower but more reliable
                            }
                            
                            val globalIndex = batchIndex * batchSize + messageIndex + 1
                            Log.d(TAG, "Processing message $globalIndex/${messagesToProcess.size} from ${conversation.title}")
                            
                            val messageText = message.messageText!!
                            val extractedInfo = extractInformationFromMessage(context, messageText, conversation)
                            if (extractedInfo != null) {
                                consecutiveFailures = 0 // Reset failure counter on success
                                batchSuccesses++
                                
                                // Add to memory synchronously since we're already on IO thread
                                val memoryFile = getMemoryFile(context)
                                val memoryStore = loadMemoryStore(context)
                                
                                // Add the new item
                                memoryStore.items.add(extractedInfo)
                                totalExtracted++
                                
                                // Enforce global message limit by removing oldest items
                                val globalLimit = getMessageLimit(context)
                                while (memoryStore.items.size > globalLimit) {
                                    memoryStore.items.removeAt(0) // Remove oldest
                                }
                                
                                // Update timestamp
                                memoryStore.lastUpdated = System.currentTimeMillis()
                                
                                // Save to file
                                saveMemoryStore(memoryFile, memoryStore)
                                
                                Log.v(TAG, "Extracted memory: ${extractedInfo.extractedInfo}")
                            } else {
                                consecutiveFailures++
                                Log.d(TAG, "No memory extracted from message $globalIndex: '${messageText.take(50)}...' (consecutive failures: $consecutiveFailures)")
                            }
                            totalProcessed++
                            
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to process message ${batchIndex * batchSize + messageIndex + 1}", e)
                            consecutiveFailures++
                            totalProcessed++ // Still count as processed even if failed
                        }
                    }
                    
                    Log.d(TAG, "Batch ${batchIndex + 1} completed: ${batchSuccesses}/${batch.size} successful extractions")
                    
                    // Longer pause between batches to respect Gemini rate limits
                    if (batchIndex < batches.size - 1) {
                        Log.d(TAG, "Pausing 5 seconds between batches to respect API rate limits")
                        Thread.sleep(5000) // 5 second pause between batches
                    }
                }
                
                val endTime = System.currentTimeMillis()
                val processingTimeMs = endTime - startTime
                
                val extractionRate = if (totalProcessed > 0) (totalExtracted * 100f / totalProcessed) else 0f
                Log.d(TAG, "Finished processing existing messages. Processed: $totalProcessed, Extracted: $totalExtracted (${String.format("%.1f", extractionRate)}%), Time: ${processingTimeMs}ms")
                
                ProcessingResult(
                    conversationsProcessed = processedConversations.size,
                    messagesProcessed = totalProcessed,
                    memoriesExtracted = totalExtracted,
                    processingTimeMs = processingTimeMs
                )
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process existing messages", e)
                throw e
            }
        }
        .subscribeOn(Schedulers.io())
    }
    
    /**
     * Gets the memory file
     */
    private fun getMemoryFile(context: Context): File {
        return File(context.filesDir, MEMORY_FILE_NAME)
    }
    
    /**
     * Loads the memory store from file
     */
    private fun loadMemoryStore(context: Context): ConversationMemoryStore {
        val memoryFile = getMemoryFile(context)
        return try {
            if (memoryFile.exists()) {
                val json = memoryFile.readText()
                parseMemoryStoreFromJson(json)
            } else {
                ConversationMemoryStore()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load memory store, creating new one", e)
            ConversationMemoryStore()
        }
    }
    
    /**
     * Parses ConversationMemoryStore from JSON string
     */
    private fun parseMemoryStoreFromJson(json: String): ConversationMemoryStore {
        val jsonObject = JSONObject(json)
        val itemsArray = jsonObject.optJSONArray("items") ?: JSONArray()
        val lastUpdated = jsonObject.optLong("lastUpdated", System.currentTimeMillis())
        
        val items = mutableListOf<MemoryItem>()
        for (i in 0 until itemsArray.length()) {
            val itemJson = itemsArray.getJSONObject(i)
            val memoryItem = MemoryItem(
                id = itemJson.optString("id", ""),
                conversationGuid = itemJson.optString("conversationGuid", ""),
                conversationTitle = itemJson.optString("conversationTitle"),
                extractedInfo = itemJson.optString("extractedInfo", ""),
                originalMessage = itemJson.optString("originalMessage", ""),
                senderName = itemJson.optString("senderName"),
                timestamp = itemJson.optLong("timestamp", 0L),
                category = itemJson.optString("category", "general"),
                confidence = itemJson.optDouble("confidence", 1.0).toFloat()
            )
            items.add(memoryItem)
        }
        
        return ConversationMemoryStore(items, lastUpdated)
    }
    
    /**
     * Saves the memory store to file
     */
    private fun saveMemoryStore(memoryFile: File, memoryStore: ConversationMemoryStore) {
        try {
            val json = convertMemoryStoreToJson(memoryStore)
            memoryFile.writeText(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save memory store", e)
        }
    }
    
    /**
     * Converts ConversationMemoryStore to JSON string
     */
    private fun convertMemoryStoreToJson(memoryStore: ConversationMemoryStore): String {
        val jsonObject = JSONObject()
        jsonObject.put("lastUpdated", memoryStore.lastUpdated)
        
        val itemsArray = JSONArray()
        for (item in memoryStore.items) {
            val itemJson = JSONObject()
            itemJson.put("id", item.id)
            itemJson.put("conversationGuid", item.conversationGuid)
            itemJson.put("conversationTitle", item.conversationTitle)
            itemJson.put("extractedInfo", item.extractedInfo)
            itemJson.put("originalMessage", item.originalMessage)
            itemJson.put("senderName", item.senderName)
            itemJson.put("timestamp", item.timestamp)
            itemJson.put("category", item.category)
            itemJson.put("confidence", item.confidence)
            itemsArray.put(itemJson)
        }
        jsonObject.put("items", itemsArray)
        
        return jsonObject.toString()
    }
}