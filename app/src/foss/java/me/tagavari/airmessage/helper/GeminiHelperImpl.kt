package me.tagavari.airmessage.helper

/**
 * FOSS flavor implementation of GeminiHelper without Firebase dependencies
 */
internal class GeminiHelperFoss : GeminiHelper() {
    
    override fun isUserAuthenticated(): Boolean {
        // FOSS builds don't have Firebase Auth, so we can't check authentication
        // Return true to allow setup message, but API key will still be needed
        return true
    }
}

