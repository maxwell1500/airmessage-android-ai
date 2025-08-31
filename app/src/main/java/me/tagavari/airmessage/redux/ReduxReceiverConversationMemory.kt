package me.tagavari.airmessage.redux

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import me.tagavari.airmessage.helper.ConversationMemoryManager
import me.tagavari.airmessage.messaging.ConversationInfo
import me.tagavari.airmessage.messaging.MessageInfo

/**
 * Redux receiver that listens for messaging events and extracts contextual information
 * for the conversation memory system.
 */
object ReduxReceiverConversationMemory {
    private const val TAG = "ConversationMemoryReceiver"
    private const val PREF_MEMORY_ENABLED = "ai_conversation_memory"
    private const val PREF_AI_ENABLED = "ai_enabled"
    
    private var disposables = CompositeDisposable()
    private var isInitialized = false
    
    /**
     * Initializes the conversation memory receiver
     */
    @JvmStatic
    fun initialize(context: Context) {
        if (isInitialized) {
            Log.w(TAG, "Already initialized")
            return
        }
        
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        
        // Listen for new messages and process them for memory extraction
        val disposable = ReduxEmitterNetwork.messageUpdateSubject
            .subscribeOn(Schedulers.io())
            .filter { event -> isMemoryEnabled(preferences) }
            .subscribe(
                { event -> processMessagingEvent(context, event) },
                { error -> Log.e(TAG, "Error processing messaging event", error) }
            )
        
        disposables.add(disposable)
        isInitialized = true
        
        Log.d(TAG, "Conversation memory receiver initialized")
    }
    
    /**
     * Shuts down the conversation memory receiver
     */
    @JvmStatic
    fun shutdown() {
        disposables.dispose()
        disposables = CompositeDisposable()
        isInitialized = false
        Log.d(TAG, "Conversation memory receiver shut down")
    }
    
    /**
     * Checks if conversation memory is enabled in preferences
     */
    private fun isMemoryEnabled(preferences: SharedPreferences): Boolean {
        val aiEnabled = preferences.getBoolean(PREF_AI_ENABLED, false)
        val memoryEnabled = preferences.getBoolean(PREF_MEMORY_ENABLED, true)
        return aiEnabled && memoryEnabled
    }
    
    /**
     * Processes messaging events to extract contextual information
     */
    private fun processMessagingEvent(context: Context, event: ReduxEventMessaging) {
        when (event) {
            is ReduxEventMessaging.Message -> {
                // Process new messages for memory extraction
                for ((conversationInfo, insertResults) in event.conversationItems) {
                    for (insertResult in insertResults) {
                        if (insertResult.targetItem is MessageInfo) {
                            val messageInfo = insertResult.targetItem as MessageInfo
                            processMessageForMemory(context, messageInfo, conversationInfo)
                        }
                    }
                }
            }
            // We could add other event types here if needed
            else -> {
                // Ignore other types of messaging events
            }
        }
    }
    
    /**
     * Processes a single message for memory extraction
     */
    private fun processMessageForMemory(context: Context, message: MessageInfo, conversation: ConversationInfo) {
        // Skip if message has no text or is very short
        val messageText = message.messageText
        if (messageText.isNullOrBlank() || messageText.length < 10) {
            return
        }
        
        // Skip processing our own outgoing messages for now (could be enabled later)
        if (message.isOutgoing) {
            return
        }
        
        Log.d(TAG, "Processing message for memory extraction: conversation=${conversation.guid}")
        
        // Process the message asynchronously
        val disposable = ConversationMemoryManager.processMessage(context, message, conversation)
            .subscribeOn(Schedulers.io())
            .subscribe(
                { 
                    Log.v(TAG, "Successfully processed message for memory") 
                },
                { error -> 
                    Log.w(TAG, "Failed to process message for memory", error)
                }
            )
        
        disposables.add(disposable)
    }
}