package com.sammyi57.pdftools

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import java.io.File

// ── Navigation ──────────────────────────────────────────────────────────────

@Serializable object MainRoute
@Serializable data class ToolRoute(val id: String, val resume: Boolean = false)
@Serializable data class ScanRoute(val id: String = "")

enum class Tab { Tools, Files, Settings }

class Nav(private val controller: NavHostController) {
  var tab by mutableStateOf(Tab.Tools)
  private var scanAt = 0L

  fun viewer(path: String, name: String) {
    controller.navigate(ViewerRoute(path, name)) { launchSingleTop = true }
  }

  fun tool(id: String, resume: Boolean = false) {
    if (id == "scan") return scan()
    controller.navigate(ToolRoute(id, resume)) { launchSingleTop = true }
  }

  // A quick double tap must not stack two cameras.
  fun scan(id: String? = null) {
    if (System.currentTimeMillis() - scanAt < 1000) return
    scanAt = System.currentTimeMillis()
    controller.navigate(ScanRoute(id ?: "")) { launchSingleTop = true }
  }

  /** Leaves the current screen; taps queued while a heavy job ran can't pop past the tabs. */
  fun back() {
    if (controller.previousBackStackEntry != null) controller.popBackStack()
  }

  fun show(t: Tab) {
    controller.popBackStack(MainRoute, inclusive = false)
    tab = t
  }
}

val LocalNav = staticCompositionLocalOf<Nav> { error("No navigation") }
private val LocalShared = compositionLocalOf<SharedTransitionScope?> { null }
private val LocalAnim = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** Room the floating tab bar (and the resume bar over it) takes at the bottom of a tab. */
val LocalBottomRoom = compositionLocalOf { 40.dp }

/** The tool's artwork flies between the list row and the tool screen's header. */
@Composable
fun Modifier.sharedArt(id: String): Modifier {
  val shared = LocalShared.current ?: return this
  val anim = LocalAnim.current ?: return this
  return with(shared) {
    this@sharedArt.sharedElement(
      rememberSharedContentState("art-$id"), anim,
      boundsTransform = { _, _ -> spring(0.82f, Spring.StiffnessMediumLow) },
    )
  }
}

private val Emph = CubicBezierEasing(0.2f, 0f, 0f, 1f)

// ── Root ────────────────────────────────────────────────────────────────────

@Composable
fun App() {
  val dark = when (ThemeState.pref) {
    ThemePref.System -> isSystemInDarkTheme()
    ThemePref.Light -> false
    ThemePref.Dark -> true
  }
  val palette = if (dark) DarkPalette else LightPalette
  val view = LocalView.current
  LaunchedEffect(dark) {
    val window = (view.context as Activity).window
    window.decorView.setBackgroundColor(palette.bg.toArgb()) // what shows between screens follows the theme
    WindowCompat.getInsetsController(window, view).apply {
      isAppearanceLightStatusBars = !dark
      isAppearanceLightNavigationBars = !dark
    }
  }
  CompositionLocalProvider(LocalPalette provides palette) {
    val scheme = if (dark) darkColorScheme(primary = palette.accent, background = palette.bg, surface = palette.surface, onSurface = palette.ink, surfaceContainerLow = palette.surface)
    else lightColorScheme(primary = palette.accent, background = palette.bg, surface = palette.surface, onSurface = palette.ink, surfaceContainerLow = palette.surface)
    MaterialTheme(colorScheme = scheme) { Root() }
  }
}

@Composable
private fun Root() {
  val controller = rememberNavController()
  val nav = remember { Nav(controller) }
  var agreed by remember { mutableStateOf(Terms.accepted()) } // read before the first frame: no flash of the terms
  DisposableEffect(nav) {
    Viewer.open = { path, name -> nav.viewer(path, name) }
    onDispose { Viewer.open = null }
  }
  // A PDF opened from another app (a file manager, downloads, mail) goes straight to the viewer.
  LaunchedEffect(Viewer.incoming) {
    Viewer.incoming?.let { nav.viewer(it.path, it.name); Viewer.incoming = null }
  }
  SharedTransitionLayout(Modifier.fillMaxSize().background(c.bg)) {
    val shared = this
    Box(Modifier.fillMaxSize()) {
      CompositionLocalProvider(LocalNav provides nav, LocalShared provides shared) {
        NavHost(
          controller, MainRoute, Modifier.fillMaxSize(),
          enterTransition = { slideInHorizontally(tween(420, easing = Emph)) { it / 4 } + fadeIn(tween(260)) },
          exitTransition = {
            if (targetState.destination.hasRoute<ScanRoute>()) ExitTransition.KeepUntilTransitionsFinished
            else slideOutHorizontally(tween(420, easing = Emph)) { -it / 8 } + fadeOut(tween(420), 0.4f)
          },
          popEnterTransition = {
            if (initialState.destination.hasRoute<ScanRoute>()) EnterTransition.None
            else slideInHorizontally(tween(380, easing = Emph)) { -it / 8 } + fadeIn(tween(380), 0.4f)
          },
          popExitTransition = { slideOutHorizontally(tween(320, easing = Emph)) { it / 3 } + fadeOut(tween(220)) },
        ) {
          composable<MainRoute> { CompositionLocalProvider(LocalAnim provides this) { MainShell() } }
          composable<ToolRoute> { entry ->
            val r = entry.toRoute<ToolRoute>()
            CompositionLocalProvider(LocalAnim provides this) { ToolScreen(r.id, r.resume) }
          }
          // The scanner comes up over everything, sliding in from the bottom like a native camera sheet.
          composable<ScanRoute>(
            enterTransition = { slideInVertically(tween(440, easing = Emph)) { it } },
            popExitTransition = { slideOutVertically(tween(340, easing = Emph)) { it } },
          ) { entry -> Scanner(entry.toRoute<ScanRoute>().id.ifEmpty { null }) }
          composable<ViewerRoute> { entry -> entry.toRoute<ViewerRoute>().let { PdfViewerScreen(it.path, it.name) } }
        }
      }
      // Over the navigator, never instead of it: the app is already up behind it, so accepting just lifts this away.
      AnimatedVisibility(!agreed, enter = fadeIn(), exit = fadeOut(tween(260)) + slideOutVertically(tween(320)) { it / 6 }) {
        BackHandler { } // back must not skip the gate
        Consent(onAccept = { Terms.accept(); agreed = true })
      }
    }
  }
}

// ── Tabs ────────────────────────────────────────────────────────────────────

@Composable
private fun MainShell() {
  val nav = LocalNav.current
  BackHandler(nav.tab != Tab.Tools) { nav.tab = Tab.Tools }
  val draft = Drafts.current
  val draftTool = draft?.let { toolById(it.tool) }
  val holder = rememberSaveableStateHolder()
  val haze = rememberHazeState() // the tabs are what the bar blurs as they scroll under it
  Box(Modifier.fillMaxSize().background(c.bg)) {
    CompositionLocalProvider(LocalBottomRoom provides if (draftTool != null) 180.dp else 112.dp) {
      AnimatedContent(
        nav.tab, Modifier.hazeSource(haze), label = "tab",
        // Slides the way the tab bar reads: a tab to the right comes in from the right and pushes this one left.
        transitionSpec = {
          val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
          (slideInHorizontally(tween(340, easing = Emph)) { it * dir / 3 } + fadeIn(tween(240, 40))) togetherWith
            (slideOutHorizontally(tween(340, easing = Emph)) { -it * dir / 3 } + fadeOut(tween(160)))
        },
      ) { t ->
        holder.SaveableStateProvider(t.name) {
          when (t) {
            Tab.Tools -> Home()
            Tab.Files -> FilesTab()
            Tab.Settings -> SettingsTab()
          }
        }
      }
    }
    Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 14.dp)) {
      AnimatedVisibility(
        draftTool != null,
        enter = slideInVertically(spring(0.75f, Spring.StiffnessMediumLow)) { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
      ) {
        if (draft != null && draftTool != null) ResumeBar(draftTool, draft.files.size)
      }
      NavPill(haze)
    }
  }
}

// A tool left open when the app closed: pick up where you were, or throw it away.
@Composable
private fun ResumeBar(tool: Tool, files: Int) {
  val nav = LocalNav.current
  val shape = RoundedCornerShape(Radius.card)
  var confirming by remember { mutableStateOf(false) }
  if (confirming) ConfirmDialog(
    "Discard ${tool.title}?", "The ${plural(files, "file")} you picked and the options you set will be cleared. Your files themselves aren't touched.",
    "Discard", danger = true, onDismiss = { confirming = false },
  ) { Drafts.set(null) }
  Row(
    Modifier.padding(horizontal = 14.dp).padding(bottom = 10.dp).fillMaxWidth().shadow(10.dp, shape)
      .tap(shape = shape, scaleTo = 0.98f, label = "Continue ${tool.title}") { nav.tool(tool.id, resume = true) }
      .background(c.surface, shape).border(0.6.dp, c.lineStrong, shape).padding(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    ArtTile(tool.id, 42.dp)
    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
      T(tool.title, Type.bold, maxLines = 1)
      T("Still open · ${plural(files, "file")}", Type.small.copy(fontSize = Type.small.fontSize * 0.93f), c.sub, maxLines = 1)
    }
    T("Discard", Type.label, c.sub, Modifier.tap(scaleTo = 0.9f) { confirming = true }.padding(horizontal = 10.dp, vertical = 8.dp))
    Box(Modifier.background(c.ink, CircleShape).padding(horizontal = 14.dp, vertical = 8.dp)) { T("Open", Type.label, c.onInk) }
  }
}

// The floating pill from the reference, in frosted glass: the page blurs behind it and a wide orange capsule slides to
// the open tab. The camera is one more icon in the row; it opens the scanner instead of a tab, so it never lights up.
@Composable
private fun NavPill(haze: HazeState) {
  val nav = LocalNav.current
  val slots = listOf<Pair<Tab?, Int>>(Tab.Tools to I.viewGridOutline, null to I.cameraPlusOutline, Tab.Files to I.folderOutline, Tab.Settings to I.cogOutline)
  val labels = listOf("Tools", "Scan a document", "Files", "Settings")
  val pill = CircleShape
  val glass = c.panel
  // Black Cherry glass: enough colour to read over warm paper, see-through enough to show the blur.
  val glassAlpha = if (c.dark) 0.55f else 0.68f
  val onActive = Brand.cherry
  // A light rim catching the edge of the glass, brighter at the top-left.
  val rim = androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color.White.copy(alpha = 0.38f), Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.16f)))
  BoxWithConstraints(
    Modifier.padding(horizontal = 28.dp).fillMaxWidth().height(62.dp).shadow(10.dp, pill, ambientColor = Color.Black, spotColor = Color.Black)
      .clip(pill)
      .hazeEffect(haze) {
        blurRadius = 24.dp
        backgroundColor = glass
        tints = listOf(HazeTint(glass.copy(alpha = glassAlpha)))
        noiseFactor = 0.12f
      }
      .border(1.dp, rim, pill).padding(6.dp),
  ) {
    val slot = maxWidth / slots.size
    val capsule = minOf(slot - 4.dp, 76.dp)
    val active = slots.indexOfFirst { it.first == nav.tab }
    val x by animateDpAsState(slot * active + (slot - capsule) / 2, spring(0.72f, Spring.StiffnessMediumLow), label = "pill")
    Box(Modifier.offset(x = x).size(capsule, maxHeight).background(c.accent, CircleShape))
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
      slots.forEachIndexed { i, (tab, glyph) ->
        val on = tab != null && nav.tab == tab
        val tint by androidx.compose.animation.animateColorAsState(if (on) onActive else c.onPanel.copy(alpha = 0.78f), tween(220), label = "tint")
        Box(
          Modifier.width(slot).fillMaxSize().tap(shape = CircleShape, scaleTo = 0.86f, label = labels[i]) { if (tab == null) nav.scan() else nav.show(tab) },
          contentAlignment = Alignment.Center,
        ) { Icon(glyph, if (on) 19.dp else 18.dp, tint, label = labels[i]) }
      }
    }
  }
}

// ── Terms ───────────────────────────────────────────────────────────────────

// Whether the terms and privacy policy were accepted on this device. Raising VERSION shows them again after an update.
object Terms {
  const val VERSION = 2
  const val UPDATED = "20 October 2026"
  private val file get() = File(appContext.filesDir, "consent.json") // same file the Expo app used

  fun accepted(): Boolean = try {
    org.json.JSONObject(file.readText()).optInt("version") == VERSION
  } catch (_: Exception) {
    false // never accepted, or unreadable: ask again rather than assume
  }

  fun accept() {
    try { file.writeText(org.json.JSONObject().put("version", VERSION).put("at", System.currentTimeMillis()).toString()) } catch (_: Exception) {}
  }
}

/** Forces light status bar icons while a dark camera surface is up. */
@Composable
fun DarkSurfaceBars() {
  val view = LocalView.current
  val themeDark = c.dark
  DisposableEffect(Unit) {
    val window = (view.context as Activity).window
    val ctl = WindowCompat.getInsetsController(window, view)
    ctl.isAppearanceLightStatusBars = false
    ctl.isAppearanceLightNavigationBars = false
    onDispose {
      ctl.isAppearanceLightStatusBars = !themeDark
      ctl.isAppearanceLightNavigationBars = !themeDark
    }
  }
}

/** A plain top bar for pushed screens: a back chevron and a label. */
@Composable
fun BackBar(label: String, onBack: () -> Unit) {
  Row(
    Modifier.padding(top = 4.dp, bottom = 10.dp).offset(x = (-8).dp).tap(shape = CircleShape, scaleTo = 0.94f, label = "Back", onClick = onBack)
      .padding(vertical = 6.dp, horizontal = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(I.chevronLeft, 26.dp)
    T(label, Type.item.copy(fontSize = Type.item.fontSize * 1.05f))
  }
}
