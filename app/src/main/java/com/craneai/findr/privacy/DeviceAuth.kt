package com.craneai.findr.privacy

import android.app.KeyguardManager
import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Android's own unlock prompt: fingerprint or face, falling back to the phone's PIN, pattern or password. Findr has no PIN
 * of its own, so there is nothing extra to forget, and the phone's lockout rules apply. This is the same credential that
 * opens the phone, which is the limit of the protection: anyone who knows it can unlock Findr too.
 */
object DeviceAuth {
  /**
   * Every prompt in Findr is a plain UI gate: it unlocks the screen or reveals results, and no Keystore key is bound to it.
   * So BIOMETRIC_WEAK | DEVICE_CREDENTIAL is enough. A prompt that ever unlocks an auth-bound Keystore key must use
   * BIOMETRIC_STRONG | DEVICE_CREDENTIAL instead, because WEAK cannot release such a key.
   */
  const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

  enum class Outcome { SUCCESS, CANCELLED, FAILED }

  /** The phone has a PIN, pattern or password. When false, anyone holding it can open Findr and see everything. */
  fun deviceSecure(context: Context): Boolean =
    context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

  /** True when the phone has a screen lock (or an enrolled biometric) the prompt can check. */
  fun available(context: Context): Boolean =
    BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

  /**
   * Shows the prompt. [onResult] runs once, on the main thread. A wrong finger is not a result (the prompt lets the person
   * try again); cancelling or an error that ends the prompt is.
   */
  fun authenticate(activity: FragmentActivity, title: String, subtitle: String?, onResult: (Outcome, userDismissed: Boolean) -> Unit) {
    val callback = object : BiometricPrompt.AuthenticationCallback() {
      override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(Outcome.SUCCESS, false)

      override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
        val byUser = errorCode == BiometricPrompt.ERROR_USER_CANCELED || errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
        onResult(if (byUser || errorCode == BiometricPrompt.ERROR_CANCELED) Outcome.CANCELLED else Outcome.FAILED, byUser)
      }
    }
    val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
    val info = BiometricPrompt.PromptInfo.Builder()
      .setTitle(title)
      .apply { if (subtitle != null) setSubtitle(subtitle) }
      .setAllowedAuthenticators(AUTHENTICATORS)   // with DEVICE_CREDENTIAL no negative button may be set
      .setConfirmationRequired(false)
      .build()
    prompt.authenticate(info)
  }
}
