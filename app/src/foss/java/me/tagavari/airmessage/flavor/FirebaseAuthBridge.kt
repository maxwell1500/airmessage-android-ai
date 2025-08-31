package me.tagavari.airmessage.flavor

object FirebaseAuthBridge {
	@JvmStatic
	fun isSupported() = false
	
	@JvmStatic
	fun getUserSummary(): String? = null
	
	@JvmStatic
	fun signOut() {
		// No-op for FOSS builds since Firebase Auth is not available
	}
}