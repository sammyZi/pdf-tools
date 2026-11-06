package com.sammyi57.pdftools

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.lifecycle.lifecycleScope
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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
    setContent { App() }
    offerAsPdfViewer()
    if (savedInstanceState == null) openIncoming(intent) // after a rotation or restore it's already open
  }

  // singleTask: a PDF opened from another app while we're running arrives here, not in onCreate.
  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    openIncoming(intent)
  }

  /** A PDF handed over by another app: copied into the cache (its content:// grant ends with this task), then shown. */
  private fun openIncoming(intent: Intent?) {
    val uri = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data ?: return
    lifecycleScope.launch {
      val picked = withContext(Dispatchers.IO) { copyIn(uri, "document.pdf") } ?: return@launch
      if (Viewer.supported) Viewer.incoming = picked else openExternally(picked.path, picked.name)
    }
  }

  // Lists the app as a PDF opener only where the in-app viewer works (the .ViewPdf alias in the manifest).
  private fun offerAsPdfViewer() {
    val want = if (Viewer.supported) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    val alias = ComponentName(this, PDF_ALIAS)
    if (packageManager.getComponentEnabledSetting(alias) != want) {
      packageManager.setComponentEnabledSetting(alias, want, PackageManager.DONT_KILL_APP)
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

/** The manifest's .ViewPdf alias: a class name, so it keeps the code's package even when the app id gets a suffix. */
val PDF_ALIAS = MainActivity::class.java.name.substringBeforeLast('.') + ".ViewPdf"

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
