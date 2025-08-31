package me.tagavari.airmessage.helper

import android.content.Context
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlinx.coroutines.rx3.rxSingle
import me.tagavari.airmessage.messaging.MessageInfo
import me.tagavari.airmessage.messaging.ConversationInfo
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
        private const val OLLAMA_MODEL_NAME = "orieg/gemma3-tools:4b"
        private const val OLLAMA_BASE_URL = "http://maxwell15000.ddns.net:11434"
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
    
    private fun isOllamaAvailable(): Boolean {
        return try {
            // For now, assume Ollama is available if user is authenticated
            // We could add a ping check to the Ollama server here
            isUserAuthenticated()
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Generate smart reply suggestions for a conversation
     */
    fun generateSmartReplies(
        conversationMessages: List<MessageInfo>,
        conversationInfo: ConversationInfo
    ): Single<List<String>> {
        return rxSingle {
            if (!isOllamaAvailable()) {
                if (!isUserAuthenticated()) {
                    throw IllegalStateException("Please sign in with your Google account to use AI features")
                } else {
                    throw IllegalStateException("Ollama server not available. Please check your connection.")
                }
            }
            
            val context = buildConversationContext(conversationMessages, conversationInfo)
            val prompt = buildSmartReplyPrompt(context, conversationInfo.isGroupChat)
            
            val response = callOllamaAPI(prompt)
            parseSmartReplies(response)
        }.subscribeOn(Schedulers.io())
    }
    
    /**
     * Make API call to Ollama server
     */
    private fun callOllamaAPI(prompt: String): String {
        val json = JSONObject()
        json.put("model", OLLAMA_MODEL_NAME)
        json.put("prompt", prompt)
        json.put("stream", false)
        
        val requestBody = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$OLLAMA_BASE_URL/api/generate")
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
     * Enhance a message with better grammar, tone, and clarity
     */
    fun enhanceMessage(
        originalMessage: String,
        tone: MessageTone = MessageTone.NEUTRAL,
        context: String? = null
    ): Single<String> {
        return rxSingle {
            if (!isOllamaAvailable()) {
                if (!isUserAuthenticated()) {
                    throw IllegalStateException("Please sign in with your Google account to use AI features")
                } else {
                    throw IllegalStateException("Ollama server not available. Please check your connection.")
                }
            }
            
            val prompt = buildEnhanceMessagePrompt(originalMessage, tone, context)
            val response = callOllamaAPI(prompt)
            
            response.trim().takeIf { it.isNotEmpty() } ?: originalMessage
        }.subscribeOn(Schedulers.io())
    }
    
    /**
     * Generate a summary of a long conversation
     */
    fun summarizeConversation(
        messages: List<MessageInfo>,
        conversationInfo: ConversationInfo
    ): Single<String> {
        return rxSingle {
            if (!isOllamaAvailable()) {
                if (!isUserAuthenticated()) {
                    throw IllegalStateException("Please sign in with your Google account to use AI features")
                } else {
                    throw IllegalStateException("Ollama server not available. Please check your connection.")
                }
            }
            
            val context = buildConversationContext(messages, conversationInfo)
            val prompt = buildSummarizationPrompt(context, conversationInfo.isGroupChat)
            
            val response = callOllamaAPI(prompt)
            response.trim().takeIf { it.isNotEmpty() } ?: "Unable to generate summary"
        }.subscribeOn(Schedulers.io())
    }
    
    /**
     * Analyze message content for potential issues (spam, inappropriate content, etc.)
     */
    fun analyzeMessageContent(message: String): Single<ContentAnalysis> {
        return rxSingle {
            if (!isOllamaAvailable()) {
                // Return safe default when Ollama is not available
                return@rxSingle ContentAnalysis(
                    isSpam = false,
                    hasInappropriateContent = false,
                    containsSensitiveInfo = false,
                    riskLevel = RiskLevel.LOW,
                    warnings = listOf("Content analysis unavailable - Ollama server not accessible")
                )
            }
            
            val prompt = buildContentAnalysisPrompt(message)
            val response = callOllamaAPI(prompt)
            
            parseContentAnalysis(response)
        }.subscribeOn(Schedulers.io())
    }
    
    /**
     * Extract action items from conversation (meetings, tasks, reminders)
     */
    fun extractActionItems(
        messages: List<MessageInfo>,
        conversationInfo: ConversationInfo
    ): Single<List<ActionItem>> {
        return rxSingle {
            if (!isOllamaAvailable()) {
                // Return empty list when Ollama is not available
                return@rxSingle emptyList<ActionItem>()
            }
            
            val context = buildConversationContext(messages, conversationInfo)
            val prompt = buildActionItemsPrompt(context)
            
            val response = callOllamaAPI(prompt)
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
    
    private fun buildSmartReplyPrompt(context: String, isGroupChat: Boolean): String {
        return """
            Based on the following conversation, generate ${MAX_SMART_REPLIES} appropriate reply suggestions.
            The replies should be:
            - Contextually relevant
            - Natural and conversational
            - Appropriate for a ${if (isGroupChat) "group chat" else "direct message"} setting
            - Brief (1-2 sentences max)
            - Diverse in tone and content
            
            $context
            
            Provide exactly ${MAX_SMART_REPLIES} reply options, each on a new line, without numbering or formatting:
        """.trimIndent()
    }
    
    private fun buildEnhanceMessagePrompt(
        originalMessage: String,
        tone: MessageTone,
        context: String?
    ): String {
        val toneDescription = when (tone) {
            MessageTone.FORMAL -> "formal and professional"
            MessageTone.CASUAL -> "casual and friendly"
            MessageTone.ENTHUSIASTIC -> "enthusiastic and positive"
            MessageTone.NEUTRAL -> "clear and natural"
        }
        
        return """
            Please improve the following message to be more $toneDescription while maintaining its original meaning.
            Fix any grammar or spelling errors, and make it clearer and more engaging.
            ${context?.let { "Context: $it\n" } ?: ""}
            
            Original message: "$originalMessage"
            
            Improved message:
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