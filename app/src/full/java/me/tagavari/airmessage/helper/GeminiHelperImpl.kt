package me.tagavari.airmessage.helper

import com.google.firebase.auth.FirebaseAuth

/**
 * Full flavor implementation of GeminiHelper with Firebase Auth support
 */
internal class GeminiHelperFull : GeminiHelper() {
    
    override fun isUserAuthenticated(): Boolean {
        return try {
            FirebaseAuth.getInstance().currentUser != null
        } catch (e: Exception) {
            false
        }
    }
}

