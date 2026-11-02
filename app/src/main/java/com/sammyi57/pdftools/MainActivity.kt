package com.sammyi57.pdftools

import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
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

/** A value built from string resources, rebuilt only when the app's language changes. */
class PerLocale<T>(private val build: () -> T) {
  @Volatile private var cached: Pair<java.util.Locale, T>? = null
  fun get(): T {
    val locale = appContext.resources.configuration.locales[0]
    cached?.let { if (it.first == locale) return it.second }
    return build().also { cached = locale to it }
  }
}

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    appContext = applicationContext
    hostActivity = this
    PDFBoxResourceLoader.init(applicationContext) // PdfBox's fonts and glyph lists
    preferFastestRefreshRate()
    enableEdgeToEdge()
    setContent { App() }
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
