package me.tagavari.airmessage.helper

import android.content.Context
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
                    if (!isUserAuthenticated()) {
                        throw IllegalStateException("Please sign in with your Google account to use Gemini AI")
                    }
                }
                else -> throw IllegalStateException("Unknown AI provider: $aiProvider")
            }
            
            val conversationContext = buildConversationContext(conversationMessages, conversationInfo)
            val prompt = buildSmartReplyPrompt(conversationContext, conversationInfo.isGroupChat)
            
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
            "gemini" -> callGeminiAPI(prompt)
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
     * Make API call to Gemini (placeholder for flavor-specific implementation)
     */
    private fun callGeminiAPI(prompt: String): String {
        // This will be implemented in flavor-specific classes
        // For now, throw an error to indicate it needs implementation
        throw IllegalStateException("Gemini API not implemented - please use flavor-specific implementation")
    }
    
    /**
     * Enhance a message with better grammar, tone, and clarity
     */
    fun enhanceMessage(
        context: Context,
        originalMessage: String,
        tone: MessageTone = MessageTone.NEUTRAL,
        messageContext: String? = null
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
                    if (!isUserAuthenticated()) {
                        throw IllegalStateException("Please sign in with your Google account to use Gemini AI")
                    }
                }
                else -> throw IllegalStateException("Unknown AI provider: $aiProvider")
            }
            
            val prompt = buildEnhanceMessagePrompt(originalMessage, tone, messageContext)
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
        messageContext: String? = null
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
                    if (!isUserAuthenticated()) {
                        throw IllegalStateException("Please sign in with your Google account to use Gemini AI")
                    }
                }
                else -> throw IllegalStateException("Unknown AI provider: $aiProvider")
            }
            
            val prompt = buildEnhanceMessageMultiplePrompt(originalMessage, messageContext)
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
                    if (!isUserAuthenticated()) {
                        throw IllegalStateException("Please sign in with your Google account to use Gemini AI")
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
                    if (!isUserAuthenticated()) {
                        throw IllegalStateException("Please sign in with your Google account to use Gemini AI")
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
                    if (!isUserAuthenticated()) {
                        return@rxSingle ContentAnalysis(
                            isSpam = false,
                            hasInappropriateContent = false,
                            containsSensitiveInfo = false,
                            riskLevel = RiskLevel.LOW,
                            warnings = listOf("Please sign in with Google account for content analysis")
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
                    if (!isUserAuthenticated()) {
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
            
            Return ONLY the improved message text. Do not use quotation marks, labels, formatting, or prefixes. Just return the plain improved message:
        """.trimIndent()
    }
    
    private fun buildEnhanceMessageMultiplePrompt(
        originalMessage: String,
        context: String?
    ): String {
        return """
            Please provide 3 different improved versions of this message. Do not use brackets or placeholders - provide the actual enhanced text.
            
            1. Make it casual and friendly
            2. Make it professional and polished  
            3. Make it enthusiastic and engaging
            
            Each version should maintain the original meaning while improving grammar, clarity, and tone.
            ${context?.let { "Context: $it\n" } ?: ""}
            
            Original message: "$originalMessage"
            
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
     * Uses a simple but aggressive approach to remove all quotes
     */
    private fun cleanEnhancedMessageResponse(response: String): String {
        var cleaned = response.trim()
        
        // Remove common formatting labels and prefixes
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
            "**Enhanced message:**"
        )
        
        // Remove labels (case insensitive)
        labelsToRemove.forEach { label ->
            if (cleaned.startsWith(label, ignoreCase = true)) {
                cleaned = cleaned.substring(label.length).trim()
            }
        }
        
        // AGGRESSIVE QUOTE REMOVAL - Remove quotes but preserve contractions
        // Remove all double quotes completely
        cleaned = cleaned.replace("\"", "")  // Remove all double quotes
        cleaned = cleaned.replace(""", "")   // Remove smart quotes
        cleaned = cleaned.replace(""", "")   // Remove smart quotes
        
        // For single quotes, be more careful to preserve contractions
        // Remove single quotes that are likely quotation marks, not contractions
        cleaned = cleaned.replace(Regex("^'"), "")    // Remove quote at start
        cleaned = cleaned.replace(Regex("'$"), "")    // Remove quote at end  
        cleaned = cleaned.replace(Regex("\\s'"), " ") // Remove quote after space
        cleaned = cleaned.replace(Regex("'\\s"), " ") // Remove quote before space
        
        // Handle smart apostrophes in quotes but preserve in contractions
        cleaned = cleaned.replace(Regex("^'"), "")    // Remove smart quote at start
        cleaned = cleaned.replace(Regex("'$"), "")    // Remove smart quote at end
        cleaned = cleaned.replace(Regex("\\s'"), " ") // Remove smart quote after space
        cleaned = cleaned.replace(Regex("'\\s"), " ") // Remove smart quote before space
        
        // Remove markdown formatting
        cleaned = cleaned.replace("**", "")
        cleaned = cleaned.replace("*", "")
        
        // Remove any remaining leading colons or dashes
        cleaned = cleaned.removePrefix(":").removePrefix("-").trim()
        
        // Clean up any double spaces that might have been created
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