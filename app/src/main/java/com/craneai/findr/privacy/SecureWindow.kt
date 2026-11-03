package com.craneai.findr.privacy

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The window's FLAG_SECURE follows [secure] (PrivacyController.secureWindow) from the moment the value changes. It used to be set by
 * an effect in the composition, which ran after the frame that first drew a sensitive result: one frame or two in which a screenshot
 * or the Recents card could still hold it (UI audit P10). Collected on the main thread without a dispatch hop, so a screen that reports
 * a sensitive result while it composes (SideEffect) has the flag on before that frame is drawn. Same flag, same rule; only earlier.
 */
fun ComponentActivity.followSecureWindow(secure: StateFlow<Boolean>) {
  lifecycleScope.launch {
    secure.collect { on ->
      if (on) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
  }
}
