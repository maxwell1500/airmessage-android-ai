package me.tagavari.airmessage.helper

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import me.tagavari.airmessage.messaging.ConversationInfo
import me.tagavari.airmessage.messaging.MessageInfo
import java.util.concurrent.TimeUnit

/**
 * Provides AI-powered message composition assistance integrated with Gemini
 * Features include real-time suggestions, tone adjustment, and message enhancement
 */
class MessageCompositionAssistant(
    private val context: Context,
    private val messageField: EditText
) {
    
    private val compositeDisposable = CompositeDisposable()
    private val geminiHelper = GeminiHelper.getInstance()
    
    var onSuggestionAvailable: ((String) -> Unit)? = null
    var onEnhancementAvailable: ((String) -> Unit)? = null
    var onContentAnalysis: ((ContentAnalysis) -> Unit)? = null
    
    // Current conversation context
    var conversationInfo: ConversationInfo? = null
    var conversationHistory: List<MessageInfo> = emptyList()
    
    // Settings
    var isAutoEnhanceEnabled = true
    var isContentAnalysisEnabled = true
    var enhancementTone = MessageTone.NEUTRAL
    
    private val textWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        
        override fun afterTextChanged(s: Editable?) {
            val text = s?.toString() ?: ""
            if (text.isNotEmpty() && text.length > 10) { // Only analyze meaningful text
                scheduleAnalysis(text)
            }
        }
    }
    
    fun attachToMessageField() {
        messageField.addTextChangedListener(textWatcher)
    }
    
    fun detachFromMessageField() {
        messageField.removeTextChangedListener(textWatcher)
        compositeDisposable.clear()
    }
    
    /**
     * Schedule content analysis with debouncing to avoid too many API calls
     */
    private fun scheduleAnalysis(text: String) {
        compositeDisposable.clear()
        
        val analysisDisposable = Single.timer(1, TimeUnit.SECONDS)
            .flatMap { 
                if (isContentAnalysisEnabled) {
                    geminiHelper.analyzeMessageContent(context, text)
                } else {
                    Single.just(ContentAnalysis(false, false, false, RiskLevel.LOW, emptyList()))
                }
            }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { analysis -> onContentAnalysis?.invoke(analysis) },
                { error -> 
                    // Handle error silently in production
                    android.util.Log.w("MessageCompositionAssistant", "Content analysis failed", error)
                }
            )
        
        compositeDisposable.add(analysisDisposable)
    }
    
    /**
     * Enhance the current message with AI improvements
     */
    fun enhanceCurrentMessage(): Single<String> {
        val currentText = messageField.text.toString()
        return if (currentText.isNotEmpty()) {
            val contextString = buildConversationContext()
            geminiHelper.enhanceMessage(context, currentText, enhancementTone, contextString, conversationInfo)
                .observeOn(AndroidSchedulers.mainThread())
                .doOnSuccess { enhanced ->
                    onEnhancementAvailable?.invoke(enhanced)
                }
        } else {
            Single.just(currentText)
        }
    }
    
    /**
     * Apply the enhanced message to the input field
     */
    fun applyEnhancement(enhancedMessage: String) {
        messageField.setText(enhancedMessage)
        messageField.setSelection(enhancedMessage.length) // Move cursor to end
    }
    
    /**
     * Generate auto-complete suggestions based on current input
     */
    fun generateAutoCompleteSuggestions(): Single<List<String>> {
        val currentText = messageField.text.toString()
        return if (currentText.length > 3) { // Only suggest for meaningful input
            val context = buildConversationContext()
            
            // Use Gemini to generate context-aware completions
            """
                Complete the following message in a natural way. Provide 3 different completion options.
                Context: $context
                Partial message: "$currentText"
                
                Completions (each on a new line):
            """.trimIndent()
            
            Single.fromCallable {
                // This would use Gemini's completion API
                // For now, return empty list as placeholder
                emptyList<String>()
            }.subscribeOn(Schedulers.io())
        } else {
            Single.just(emptyList())
        }
    }
    
    /**
     * Check for common writing issues and provide suggestions
     */
    fun checkWritingIssues(): Single<List<WritingIssue>> {
        val currentText = messageField.text.toString()
        return if (currentText.isNotEmpty()) {
            Single.fromCallable {
                val issues: MutableList<WritingIssue> = mutableListOf()
                
                // Basic checks (can be enhanced with more sophisticated analysis)
                if (currentText.length > 500) {
                    issues.add(WritingIssue.TOO_LONG)
                }
                
                if (currentText.all { it.isUpperCase() || !it.isLetter() }) {
                    issues.add(WritingIssue.ALL_CAPS)
                }
                
                // Check for excessive punctuation
                val exclamationCount = currentText.count { it == '!' }
                if (exclamationCount > 3) {
                    issues.add(WritingIssue.EXCESSIVE_PUNCTUATION)
                }
                
                issues.toList()
            }.subscribeOn(Schedulers.computation())
        } else {
            Single.just(emptyList())
        }
    }
    
    /**
     * Adjust the tone of the current message
     */
    fun adjustMessageTone(targetTone: MessageTone): Single<String> {
        val currentText = messageField.text.toString()
        return if (currentText.isNotEmpty()) {
            val contextString = buildConversationContext()
            geminiHelper.enhanceMessage(context, currentText, targetTone, contextString)
                .observeOn(AndroidSchedulers.mainThread())
        } else {
            Single.just(currentText)
        }
    }
    
    private fun buildConversationContext(): String? {
        return conversationInfo?.let { conv ->
            val recentMessages = conversationHistory.takeLast(5)
            if (recentMessages.isNotEmpty()) {
                "Recent conversation in ${if (conv.isGroupChat) "group chat" else "direct message"}:\n" +
                recentMessages.joinToString("\n") { "${it.sender}: ${it.messageText}" }
            } else null
        }
    }
    
    /**
     * Update the conversation context for better AI suggestions
     */
    fun updateConversationContext(info: ConversationInfo, history: List<MessageInfo>) {
        this.conversationInfo = info
        this.conversationHistory = history.sortedBy { it.date }
    }
    
    
    fun dispose() {
        compositeDisposable.dispose()
    }
}

/**
 * Common writing issues that can be detected and corrected
 */
enum class WritingIssue {
    TOO_LONG,
    ALL_CAPS,
    EXCESSIVE_PUNCTUATION,
    POTENTIAL_TYPO,
    UNCLEAR_MEANING,
    TOO_FORMAL,
    TOO_CASUAL
}

/**
 * Interface for message composition assistance callbacks
 */
interface MessageCompositionListener {
    fun onSuggestionGenerated(suggestion: String)
    fun onEnhancementReady(originalMessage: String, enhancedMessage: String)
    fun onContentIssueDetected(issue: ContentAnalysis)
    fun onWritingIssueFound(issues: List<WritingIssue>)
}