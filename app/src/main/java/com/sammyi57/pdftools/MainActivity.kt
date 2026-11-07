package com.sammyi57.pdftools

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.content.IntentCompat
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/** The application context, for storage and engines that run outside any screen. Set before anything draws. */
lateinit var appContext: Context
  internal set // the on-device tests set it too, with no activity running

/** The one activity, while it exists: system pickers and Google sign-in are launched from it. */
var hostActivity: MainActivity? = null
  private set

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    appContext = applicationContext
    hostActivity = this
    PDFBoxResourceLoader.init(applicationContext) // PdfBox's fonts and glyph lists
    preferFastestRefreshRate()
    enableEdgeToEdge()
    registerAsPdfHandler()
    if (savedInstanceState == null) takeIncoming(intent)
    setContent { App() }
  }

  // singleTask: a PDF opened while the app is already running arrives here instead of in a new activity.
  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    takeIncoming(intent)
  }

  /** Shows PDF Tools in "Open with" for PDFs, where the in-app viewer exists to open them. */
  private fun registerAsPdfHandler() {
    if (!Viewer.supported) return
    packageManager.setComponentEnabledSetting(ComponentName(this, "com.sammyi57.pdftools.PdfHandler"), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
  }

  private fun takeIncoming(intent: Intent) {
    Incoming.uri = when (intent.action) {
      Intent.ACTION_VIEW -> intent.data
      Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
      else -> null
    }
  }

  override fun onDestroy() {
    if (hostActivity === this) hostActivity = null
    super.onDestroy()
  }

  // Some phones keep apps at 60 Hz unless asked; ask for the display's fastest mode at the current resolution.
  private fun preferFastestRefreshRate() {
    @Suppress("DEPRECATION")
    val screen = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else windowManager.defaultDisplay
    screen?.let { d ->
      val current = d.mode
      d.supportedModes
        .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
        .maxByOrNull { it.refreshRate }
        ?.let { best -> window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId } }
    }
  }
}

private val resultKeys = AtomicInteger()

/**
 * Launches a system screen (a picker, the account chooser, a consent sheet) and suspends until it returns. Registered
 * on the fly, so any code can ask without wiring a launcher through the UI first.
 * ponytail: a result arriving after the process was killed is dropped; the user just picks again.
 */
suspend fun <I, O> awaitResult(contract: ActivityResultContract<I, O>, input: I): O {
  val activity = hostActivity ?: error("The app isn't ready yet.")
  return suspendCancellableCoroutine { cont ->
    var launcher: ActivityResultLauncher<I>? = null
    launcher = activity.activityResultRegistry.register("await-${resultKeys.incrementAndGet()}", contract) { out ->
      launcher?.unregister()
      if (cont.isActive) cont.resume(out)
    }
    cont.invokeOnCancellation { launcher.unregister() }
    launcher.launch(input)
  }
}
