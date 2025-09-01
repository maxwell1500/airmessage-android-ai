package me.tagavari.airmessage.helper

/**
 * Full flavor implementation of GeminiHelper
 * Uses API key authentication only, no Firebase Auth needed for Gemini API
 */
internal class GeminiHelperFull : GeminiHelper() {
    
    override fun isUserAuthenticated(): Boolean {
        // For Gemini API, we only need the API key, not user authentication
        // Return true to allow API key-based authentication
        return true
    }
}

