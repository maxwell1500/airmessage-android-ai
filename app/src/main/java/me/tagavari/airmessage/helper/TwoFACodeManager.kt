package me.tagavari.airmessage.helper

import android.content.Context
import android.util.Log
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import me.tagavari.airmessage.data.DatabaseManager
import me.tagavari.airmessage.messaging.MessageInfo
import java.util.regex.Pattern

/**
 * Manager for 2FA code detection and storage
 * Automatically detects 2FA codes from messages and stores the last 3
 */
object TwoFACodeManager {
    private const val TAG = "TwoFACodeManager"
    
    // Common 2FA patterns
    private val twoFAPatterns = listOf(
        // Google: G-123456
        Pattern.compile("G-\\d{6}"),
        // Standard 6-digit codes: 123456
        Pattern.compile("\\b\\d{6}\\b"),
        // Standard 4-digit codes: 1234
        Pattern.compile("\\b\\d{4}\\b"),
        // Apple: Your Apple ID verification code is 123456
        Pattern.compile("Apple ID.*?verification.*?(\\d{6})"),
        // Microsoft: Your verification code is 123456
        Pattern.compile("verification.*?code.*?(\\d{6})"),
        // Generic: Code: 123456, Code 123456
        Pattern.compile("code[:\\s]+(\\d{4,8})"),
        // Generic: Use 123456 to verify
        Pattern.compile("use\\s+(\\d{4,8})\\s+to\\s+verify", Pattern.CASE_INSENSITIVE),
        // Generic: Your code is 123456
        Pattern.compile("your.*?code.*?(\\d{4,8})", Pattern.CASE_INSENSITIVE)
    )
    
    // Keywords that indicate a 2FA message
    private val twoFAKeywords = listOf(
        "verification", "verify", "code", "authenticate", "login", "sign in", 
        "security", "2fa", "two-factor", "otp", "one-time", "passcode",
        "don't share", "expires", "valid for"
    )
    
    /**
     * Determines if a message should be processed as a potential 2FA message
     * Based on content patterns and keywords
     */
    fun shouldProcessAs2FA(messageText: String): Boolean {
        Log.d(TAG, "shouldProcessAs2FA checking: '$messageText'")
        
        if (messageText.isBlank() || messageText.length < 4) {
            Log.d(TAG, "  -> Too short or blank: ${messageText.length} chars")
            return false
        }
        
        val text = messageText.lowercase()
        
        // Check for 2FA keywords
        val matchedKeywords = twoFAKeywords.filter { keyword ->
            text.contains(keyword.lowercase())
        }
        val hasKeywords = matchedKeywords.isNotEmpty()
        
        // Check for code patterns
        val matchedPatterns = twoFAPatterns.filter { pattern ->
            pattern.matcher(messageText).find()
        }
        val hasCodePattern = matchedPatterns.isNotEmpty()
        
        // Also check for numeric codes (4-8 digits)
        val numericMatches = Regex("\\b\\d{4,8}\\b").findAll(messageText).toList()
        val hasNumericCode = numericMatches.isNotEmpty()
        
        Log.d(TAG, "  -> Keywords found: $matchedKeywords")
        Log.d(TAG, "  -> Pattern matches: ${matchedPatterns.size}")
        Log.d(TAG, "  -> Numeric codes: ${numericMatches.map { it.value }}")
        Log.d(TAG, "  -> Final result: hasKeywords=$hasKeywords, hasPatterns=$hasCodePattern, hasNumeric=$hasNumericCode")
        
        // Message is 2FA if it has keywords OR code patterns OR numeric codes
        val result = hasKeywords || hasCodePattern || hasNumericCode
        Log.d(TAG, "  -> shouldProcessAs2FA result: $result")
        return result
    }
    
    /**
     * Test method to manually check if a message would be detected as 2FA
     * Useful for debugging
     */
    fun testMessage(messageText: String, senderNumber: String): String {
        Log.d(TAG, "=== TESTING 2FA DETECTION ===")
        Log.d(TAG, "Sender: '$senderNumber' (length=${senderNumber.length})")
        Log.d(TAG, "Message: '$messageText'")
        
        val senderCheck = senderNumber.length <= 7
        val contentCheck = shouldProcessAs2FA(messageText)
        val wouldProcess = senderCheck && contentCheck
        
        val result = """
            |Test Results:
            |  Sender: $senderNumber (${senderNumber.length} chars)
            |  Sender ≤7 chars: $senderCheck
            |  Content check: $contentCheck
            |  Would process as 2FA: $wouldProcess
            |  
            |  Message: $messageText
        """.trimMargin()
        
        Log.d(TAG, result)
        return result
    }
    
    /**
     * Data class for 2FA codes
     */
    data class TwoFACode(
        val id: Long = 0,
        val code: String,
        val service: String, // e.g., "Google", "Apple", etc.
        val phoneNumber: String,
        val messageText: String,
        val timestamp: Long,
        val isUsed: Boolean = false
    )
    
    /**
     * Process a message for 2FA code detection
     * Returns true if a 2FA code was detected and stored
     */
    fun processMessage(context: Context, messageInfo: MessageInfo): Single<Boolean> {
        return Single.fromCallable {
            val messageText = messageInfo.messageText ?: return@fromCallable false
            val phoneNumber = messageInfo.sender ?: ""
            
            Log.d(TAG, "Processing message for 2FA: '${messageText.take(100)}' from $phoneNumber")
            
            // Check if this looks like a 2FA message
            if (!isPotential2FAMessage(messageText, phoneNumber)) {
                return@fromCallable false
            }
            
            // Extract codes from the message
            val codes = extract2FACodes(messageText)
            if (codes.isEmpty()) {
                Log.d(TAG, "No 2FA codes found in message")
                return@fromCallable false
            }
            
            // Determine the service name
            val service = detectService(messageText, phoneNumber)
            
            // Store the codes
            codes.forEach { code ->
                val twoFACode = TwoFACode(
                    code = code,
                    service = service,
                    phoneNumber = phoneNumber,
                    messageText = messageText,
                    timestamp = messageInfo.date
                )
                
                store2FACode(context, twoFACode)
                Log.i(TAG, "Stored 2FA code: $code from $service")
            }
            
            return@fromCallable true
        }.subscribeOn(Schedulers.io())
    }
    
    /**
     * Check if a message could contain a 2FA code
     */
    private fun isPotential2FAMessage(messageText: String, phoneNumber: String): Boolean {
        val lowerText = messageText.lowercase()
        
        // Check for short phone numbers (common for 2FA services)
        val isShortNumber = phoneNumber.length <= 6 || phoneNumber.all { it.isDigit() }
        
        // Check for 2FA keywords
        val hasKeywords = twoFAKeywords.any { keyword -> lowerText.contains(keyword) }
        
        // Check for patterns that look like codes
        val hasCodePattern = twoFAPatterns.any { pattern -> pattern.matcher(messageText).find() }
        
        val isPotential = isShortNumber && (hasKeywords || hasCodePattern)
        Log.d(TAG, "2FA potential check: shortNum=$isShortNumber, keywords=$hasKeywords, pattern=$hasCodePattern -> $isPotential")
        
        return isPotential
    }
    
    /**
     * Extract 2FA codes from message text
     */
    private fun extract2FACodes(messageText: String): List<String> {
        val codes = mutableSetOf<String>()
        
        twoFAPatterns.forEach { pattern ->
            val matcher = pattern.matcher(messageText)
            while (matcher.find()) {
                val code = if (matcher.groupCount() > 0) {
                    matcher.group(1) // Use captured group if available
                } else {
                    matcher.group() // Use full match
                }
                
                code?.let {
                    // Clean the code (remove prefixes like "G-")
                    val cleanCode = it.replace(Regex("[^\\d]"), "")
                    if (cleanCode.length in 4..8) { // Valid code length
                        codes.add(cleanCode)
                    }
                }
            }
        }
        
        Log.d(TAG, "Extracted codes: $codes")
        return codes.toList()
    }
    
    /**
     * Detect the service name from message content
     */
    private fun detectService(messageText: String, phoneNumber: String): String {
        val lowerText = messageText.lowercase()
        
        return when {
            lowerText.contains("google") || phoneNumber.contains("google") -> "Google"
            lowerText.contains("apple") || phoneNumber.contains("apple") -> "Apple"
            lowerText.contains("microsoft") || phoneNumber.contains("microsoft") -> "Microsoft"
            lowerText.contains("facebook") || lowerText.contains("meta") -> "Facebook/Meta"
            lowerText.contains("twitter") || lowerText.contains("x.com") -> "Twitter/X"
            lowerText.contains("instagram") -> "Instagram"
            lowerText.contains("whatsapp") -> "WhatsApp"
            lowerText.contains("telegram") -> "Telegram"
            lowerText.contains("discord") -> "Discord"
            lowerText.contains("github") -> "GitHub"
            lowerText.contains("amazon") -> "Amazon"
            lowerText.contains("paypal") -> "PayPal"
            lowerText.contains("bank") -> "Bank"
            else -> phoneNumber.takeIf { it.isNotEmpty() } ?: "Unknown Service"
        }
    }
    
    /**
     * Store a 2FA code in the database, maintaining only the last 3
     */
    private fun store2FACode(context: Context, twoFACode: TwoFACode) {
        try {
            val db = DatabaseManager.getInstance().writableDatabase
            
            // Insert the new code
            db.execSQL(
                """
                INSERT INTO twofa_codes (code, service, phone_number, message_text, timestamp, is_used)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                arrayOf(
                    twoFACode.code,
                    twoFACode.service,
                    twoFACode.phoneNumber,
                    twoFACode.messageText,
                    twoFACode.timestamp,
                    if (twoFACode.isUsed) 1 else 0
                )
            )
            
            // Keep only the last 3 codes
            db.execSQL(
                """
                DELETE FROM twofa_codes 
                WHERE _id NOT IN (
                    SELECT _id FROM twofa_codes 
                    ORDER BY timestamp DESC 
                    LIMIT 3
                )
                """
            )
            
            Log.d(TAG, "Stored 2FA code and cleaned old entries")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to store 2FA code", e)
        }
    }
    
    /**
     * Get all stored 2FA codes (up to 3 most recent)
     */
    fun getAllCodes(context: Context): Single<List<TwoFACode>> {
        return Single.fromCallable {
            try {
                val db = DatabaseManager.getInstance().readableDatabase
                val cursor = db.rawQuery(
                    """
                    SELECT _id, code, service, phone_number, message_text, timestamp, is_used
                    FROM twofa_codes 
                    ORDER BY timestamp DESC 
                    LIMIT 3
                    """,
                    null
                )
                
                val codes = mutableListOf<TwoFACode>()
                while (cursor.moveToNext()) {
                    codes.add(
                        TwoFACode(
                            id = cursor.getLong(0),
                            code = cursor.getString(1),
                            service = cursor.getString(2),
                            phoneNumber = cursor.getString(3),
                            messageText = cursor.getString(4),
                            timestamp = cursor.getLong(5),
                            isUsed = cursor.getInt(6) == 1
                        )
                    )
                }
                cursor.close()
                
                Log.d(TAG, "Retrieved ${codes.size} 2FA codes")
                codes
            } catch (e: Exception) {
                Log.e(TAG, "Failed to retrieve 2FA codes", e)
                emptyList<TwoFACode>()
            }
        }.subscribeOn(Schedulers.io())
    }
    
    /**
     * Mark a 2FA code as used
     */
    fun markCodeAsUsed(context: Context, codeId: Long): Completable {
        return Completable.fromAction {
            try {
                val db = DatabaseManager.getInstance().writableDatabase
                db.execSQL(
                    "UPDATE twofa_codes SET is_used = 1 WHERE id = ?",
                    arrayOf(codeId)
                )
                Log.d(TAG, "Marked 2FA code as used: $codeId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to mark 2FA code as used", e)
            }
        }.subscribeOn(Schedulers.io())
    }
    
    /**
     * Clear all 2FA codes
     */
    fun clearAllCodes(context: Context): Completable {
        return Completable.fromAction {
            try {
                val db = DatabaseManager.getInstance().writableDatabase
                db.execSQL("DELETE FROM twofa_codes")
                Log.d(TAG, "Cleared all 2FA codes")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear 2FA codes", e)
            }
        }.subscribeOn(Schedulers.io())
    }
    
    /**
     * Process existing messages for 2FA codes (called from AI settings)
     */
    fun processExistingMessages(context: Context): Single<ProcessingResult> {
        return Single.fromCallable {
            Log.i(TAG, "Starting 2FA processing of existing messages")
            
            try {
                val db = DatabaseManager.getInstance().readableDatabase
                
                // Get recent messages (last 40) that might contain 2FA codes
                val cursor = db.rawQuery(
                    """
                    SELECT sender, message_text, date 
                    FROM messages 
                    WHERE message_text IS NOT NULL 
                    AND message_text != ''
                    AND sender IS NOT NULL
                    AND LENGTH(sender) <= 10  -- Focus on short numbers likely to be 2FA services
                    ORDER BY date DESC 
                    LIMIT 40
                    """,
                    null
                )
                
                var messagesProcessed = 0
                var codesFound = 0
                
                while (cursor.moveToNext() && codesFound < 3) {
                    val senderAddress = cursor.getString(0) ?: continue
                    val messageText = cursor.getString(1) ?: continue
                    val dateSent = cursor.getLong(2)
                    
                    messagesProcessed++
                    
                    // Create MessageInfo using the proper constructor
                    val messageInfo = MessageInfo(
                        -1, // localID
                        -1, // serverID  
                        null, // guid
                        dateSent, // date
                        senderAddress, // sender
                        messageText, // messageText
                        null, // messageSubject
                        mutableListOf(), // attachments
                        null, // sendStyle
                        false, // sendStyleViewed
                        -1, // dateRead
                        0, // messageState
                        0, // errorCode
                        false, // errorDetailsAvailable
                        null // errorDetails
                    )
                    
                    val foundCode = processMessage(context, messageInfo).blockingGet()
                    if (foundCode) {
                        codesFound++
                        Log.i(TAG, "Found 2FA code in existing message from $senderAddress")
                    }
                }
                
                cursor.close()
                
                Log.i(TAG, "2FA processing complete: $messagesProcessed messages processed, $codesFound codes found")
                ProcessingResult(messagesProcessed, codesFound, 0)
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process existing messages for 2FA", e)
                ProcessingResult(0, 0, 0)
            }
        }.subscribeOn(Schedulers.io())
    }
    
    /**
     * Result of processing messages
     */
    data class ProcessingResult(
        val messagesProcessed: Int,
        val codesFound: Int,
        val conversationsProcessed: Int // Not used for 2FA but kept for consistency
    )
}