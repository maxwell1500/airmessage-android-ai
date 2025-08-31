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
            // Extract information using Ollama AI
            val extractedInfo = extractInformationFromMessage(messageText, conversation)
            
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
     * Extracts information from message text using Ollama AI
     */
    private fun extractInformationFromMessage(messageText: String, conversation: ConversationInfo): MemoryItem? {
        try {
            Log.d(TAG, "Extracting info from message: ${messageText.take(50)}...")
            val prompt = buildExtractionPrompt(messageText)
            val response = callOllamaAPI(prompt)
            val result = parseExtractionResponse(response, messageText, conversation)
            
            if (result != null) {
                Log.d(TAG, "Successfully extracted memory item")
            } else {
                Log.d(TAG, "No memory item extracted")
            }
            
            return result
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract information from message", e)
            return null
        }
    }
    
    /**
     * Builds the prompt for information extraction
     */
    private fun buildExtractionPrompt(messageText: String): String {
        return """
Extract key contextual information from this message that would be useful for generating smart replies in other conversations. Focus on:

- Codes (verification codes, 2FA codes, access codes, temporary passwords, PINs)
- Events and dates/times (parties, meetings, appointments)
- Locations and addresses
- Important decisions or plans
- People mentioned
- Commitments or availability

Message: "$messageText"

Respond in JSON format:
{
  "hasInfo": true/false,
  "info": "concise extracted information",
  "category": "code|event|location|time|person|general",
  "confidence": 0.0-1.0
}

Examples:
- For "Your verification code is 123456": {"hasInfo": true, "info": "Verification code: 123456", "category": "code", "confidence": 1.0}
- For "Use code ABC123 to access": {"hasInfo": true, "info": "Access code: ABC123", "category": "code", "confidence": 1.0}
- For "Meeting at 3pm": {"hasInfo": true, "info": "Meeting at 3pm", "category": "event", "confidence": 0.9}

If no useful contextual information, respond: {"hasInfo": false}
        """.trimIndent()
    }
    
    /**
     * Calls the Ollama API for information extraction
     */
    private fun callOllamaAPI(prompt: String): String? {
        val requestJson = JSONObject()
        requestJson.put("model", "orieg/gemma3-tools:4b")
        requestJson.put("prompt", prompt)
        requestJson.put("stream", false)
        
        val options = JSONObject()
        options.put("temperature", 0.1)
        requestJson.put("options", options)
        
        val requestBody = requestJson.toString().toRequestBody("application/json".toMediaType())
        
        val request = Request.Builder()
            .url("http://maxwell15000.ddns.net:11434/api/generate")
            .post(requestBody)
            .build()
        
        return try {
            Log.d(TAG, "Calling Ollama API...")
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    if (responseBody != null) {
                        Log.d(TAG, "Ollama API response body: $responseBody")
                        val responseJson = JSONObject(responseBody)
                        val aiResponse = responseJson.optString("response", "")
                        Log.d(TAG, "Extracted AI response: $aiResponse")
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
     * Parses the extraction response from Ollama
     */
    private fun parseExtractionResponse(response: String?, messageText: String, conversation: ConversationInfo): MemoryItem? {
        if (response.isNullOrBlank()) {
            Log.d(TAG, "Empty response from Ollama")
            return null
        }
        
        Log.d(TAG, "Ollama response: $response")
        
        return try {
            // Try to parse as JSON first
            val extracted = JSONObject(response)
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
            Log.w(TAG, "Failed to parse extraction response as JSON", e)
            
            // Fallback: try to extract info directly from non-JSON response
            if (response.contains("hasInfo") && response.contains("true")) {
                Log.d(TAG, "Attempting to extract from non-JSON response")
                val infoMatch = Regex("\"info\"\\s*:\\s*\"([^\"]+)\"").find(response)
                val categoryMatch = Regex("\"category\"\\s*:\\s*\"([^\"]+)\"").find(response)
                
                if (infoMatch != null) {
                    val extractedInfo = infoMatch.groupValues[1]
                    Log.d(TAG, "Fallback extracted info: $extractedInfo")
                    
                    return MemoryItem(
                        id = "${conversation.guid ?: "unknown"}_${System.currentTimeMillis()}",
                        conversationGuid = conversation.guid ?: "",
                        conversationTitle = conversation.title,
                        extractedInfo = extractedInfo,
                        originalMessage = messageText,
                        senderName = null,
                        timestamp = System.currentTimeMillis(),
                        category = categoryMatch?.groupValues?.get(1) ?: "general",
                        confidence = 0.8f
                    )
                }
            }
            
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
                var conversationsProcessed = 0
                var consecutiveFailures = 0
                val maxConsecutiveFailures = 5 // Stop if API fails 5 times in a row
                
                Log.d(TAG, "Starting to process existing messages with limit: $messageLimit")
                
                // Get all conversations from the database
                val conversations = me.tagavari.airmessage.data.DatabaseManager.getInstance()
                    .fetchSummaryConversations(context, false)
                
                Log.d(TAG, "Found ${conversations.size} conversations to process")
                
                for (conversation in conversations) {
                    conversationsProcessed++
                    
                    // Stop processing if we've had too many consecutive failures
                    if (consecutiveFailures >= maxConsecutiveFailures) {
                        Log.w(TAG, "Stopping bulk processing due to too many API failures")
                        break
                    }
                    
                    try {
                        // Get conversation items (which includes messages)
                        val conversationItems = me.tagavari.airmessage.data.DatabaseManager.getInstance()
                            .loadConversationItems(context, conversation.localID)
                        
                        // Filter to get only MessageInfo items and take the last N messages
                        val messages = conversationItems
                            .filterIsInstance<MessageInfo>()
                            .sortedBy { it.date }
                            .takeLast(messageLimit)
                        
                        Log.d(TAG, "Processing ${messages.size} messages from conversation: ${conversation.title ?: conversation.guid}")
                        
                        // Process each message for memory extraction with rate limiting
                        for ((index, message) in messages.withIndex()) {
                            try {
                                val messageText = message.messageText
                                if (!messageText.isNullOrBlank() && messageText.length >= 10) {
                                    // Add rate limiting: 500ms delay between API calls to prevent overwhelming
                                    if (index > 0) {
                                        Thread.sleep(500)
                                    }
                                    
                                    Log.d(TAG, "Processing message ${index + 1}/${messages.size} from ${conversation.title}")
                                    
                                    val extractedInfo = extractInformationFromMessage(messageText, conversation)
                                    if (extractedInfo != null) {
                                        consecutiveFailures = 0 // Reset failure counter on success
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
                                        if (consecutiveFailures >= maxConsecutiveFailures) {
                                            Log.w(TAG, "Too many consecutive API failures ($consecutiveFailures), stopping bulk processing")
                                            break
                                        }
                                    }
                                    totalProcessed++
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to process message: ${message.localID}", e)
                                // Continue processing other messages
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to process conversation: ${conversation.localID}", e)
                        // Continue processing other conversations
                    }
                }
                
                val endTime = System.currentTimeMillis()
                val processingTimeMs = endTime - startTime
                
                Log.d(TAG, "Finished processing existing messages. Processed: $totalProcessed, Extracted: $totalExtracted, Time: ${processingTimeMs}ms")
                
                ProcessingResult(
                    conversationsProcessed = conversationsProcessed,
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