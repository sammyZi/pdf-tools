package com.sammyi57.pdftools

import android.net.Uri
import android.os.Build
import android.os.ext.SdkExtensions
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.pdf.ExperimentalPdfApi
import androidx.pdf.view.Highlight
import androidx.pdf.PdfDocument
import androidx.pdf.PdfPasswordException
import androidx.pdf.PdfPoint
import androidx.pdf.PdfRect
import androidx.pdf.SandboxedPdfLoader
import androidx.pdf.compose.PdfViewer
import androidx.pdf.compose.PdfViewerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

// The in-app PDF viewer, built on Jetpack PDF (the engine behind the Drive viewer): smooth scrolling and pinch zoom,
// text you can select and copy, search with highlights, links, a fast-scroll page indicator, page thumbnails and
// jump-to-page. The file is parsed in a sandboxed process, so a broken PDF can't take the app down with it.

@Serializable data class ViewerRoute(val path: String, val name: String)

object Viewer {
  /** Jetpack PDF needs Android 12 with the PDF system module at version 13+ (shipped through Play system updates). */
  val supported: Boolean by lazy {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13
  }

  /** Set by the navigator while the app is up: opens a PDF in the viewer. */
  var open: ((path: String, name: String) -> Unit)? = null

  /** A PDF another app asked us to open, copied into the cache; the navigator shows it once it's up, then clears it. */
  var incoming by mutableStateOf<Picked?>(null)
}

private const val MATCH = 0x55FFC94D // every search hit: a soft marker yellow
private const val CURRENT = 0x5CFF7A4F // the hit you're on: a light Tangerine (the view darkens it over text)

private class Match(val first: PdfRect, val all: List<PdfRect>)

@OptIn(ExperimentalPdfApi::class)
@Composable
fun PdfViewerScreen(path: String, name: String) {
  val nav = LocalNav.current
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val state = remember { PdfViewerState() }
  val loader = remember { SandboxedPdfLoader(context) }
  var doc by remember { mutableStateOf<PdfDocument?>(null) }
  var problem by remember { mutableStateOf("") }
  var password by remember { mutableStateOf<String?>(null) }
  var asking by remember { mutableStateOf(false) }
  var wrong by remember { mutableStateOf(false) }

  LaunchedEffect(password) {
    try {
      doc = loader.openDocument(Uri.fromFile(File(path)), password)
      asking = false
    } catch (_: PdfPasswordException) {
      wrong = password != null
      asking = true
    } catch (_: Exception) {
      problem = "This PDF couldn’t be opened. It may be damaged."
    }
  }
  val latest by rememberUpdatedState(doc)
  DisposableEffect(Unit) { onDispose { runCatching { latest?.close() } } }

  // Search: every hit softly highlighted, the current one in Tangerine, stepped through with the arrows.
  var searching by remember { mutableStateOf(false) }
  var query by remember { mutableStateOf("") }
  var matches by remember { mutableStateOf<List<Match>>(emptyList()) }
  var at by remember { mutableIntStateOf(0) }
  var searched by remember { mutableStateOf(false) }
  LaunchedEffect(query, doc) {
    val d = doc ?: return@LaunchedEffect
    searched = false
    if (query.isBlank()) { matches = emptyList(); return@LaunchedEffect }
    delay(250) // typing settles first
    val found = runCatching { d.searchDocument(query.trim(), 0 until d.pageCount) }.getOrNull()
    val list = ArrayList<Match>()
    if (found != null) for (k in 0 until found.size()) {
      val page = found.keyAt(k)
      for (m in found.valueAt(k)) if (m.bounds.isNotEmpty()) list += Match(PdfRect(page, m.bounds[0]), m.bounds.map { PdfRect(page, it) })
    }
    matches = list.sortedWith(compareBy({ it.first.pageNum }, { it.first.top }))
    at = 0
    searched = true
  }
  LaunchedEffect(matches, at, searching, doc) {
    // The view throws if highlights are touched before it has the document, so only once it's loaded (and never
    // fatally: a highlight that can't be drawn just isn't).
    if (doc == null) return@LaunchedEffect
    runCatching { state.setHighlights(if (!searching) emptyList() else matches.flatMapIndexed { i, m -> m.all.map { Highlight(it, if (i == at) CURRENT else MATCH) } }) }
    matches.getOrNull(at)?.let { runCatching { state.scrollToPosition(PdfPoint(it.first.pageNum, it.first.left, it.first.top)) } }
  }
  BackHandler(searching) { searching = false; query = "" }
  val searchFocus = remember { FocusRequester() }
  LaunchedEffect(searching) { if (searching) { delay(150); runCatching { searchFocus.requestFocus() } } } // keyboard up as search opens

  var thumbs by remember { mutableStateOf(false) }
  var jumping by remember { mutableStateOf(false) }
  val pages = doc?.pageCount ?: 0
  val page = (state.firstVisiblePage + 1).coerceIn(1, maxOf(1, pages))

  Column(Modifier.fillMaxSize().background(c.bg)) {
    AnimatedContent(searching, Modifier.statusBarsPadding(), label = "viewerBar", transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) }) { s ->
      Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (s) {
          IconBtn(I.arrowLeft, label = "Close search") { searching = false; query = "" }
          Field(query, { query = it }, Modifier.weight(1f), "Search in this PDF", focus = searchFocus)
          T(
            when { query.isBlank() -> ""; !searched -> "…"; matches.isEmpty() -> "0"; else -> "${at + 1}/${matches.size}" },
            Type.num, c.sub, Modifier.padding(horizontal = 6.dp),
          )
          IconBtn(I.chevronUp, label = "Previous match", enabled = matches.isNotEmpty()) { at = (at - 1 + matches.size) % matches.size }
          IconBtn(I.chevronDown, label = "Next match", enabled = matches.isNotEmpty()) { at = (at + 1) % matches.size }
        } else {
          IconBtn(I.arrowLeft, label = "Back") { nav.back() }
          Marquee(stem(name), Type.bold.copy(fontSize = Type.bold.fontSize * 1.1f), c.ink, Modifier.weight(1f).padding(horizontal = 4.dp))
          IconBtn(I.magnify, label = "Search", enabled = doc != null) { searching = true }
          IconBtn(I.shareVariantOutline, label = "Share") { share(path, name) }
          IconBtn(I.openInNew, label = "Open in another app") { openExternally(path, name) }
        }
      }
    }

    Box(Modifier.weight(1f).fillMaxWidth().background(c.sunken)) {
      when {
        problem.isNotEmpty() -> T(problem, Type.body, c.sub, Modifier.align(Alignment.Center).padding(32.dp))
        doc == null -> Spinner(c.accent, modifier = Modifier.align(Alignment.Center))
      }
      PdfViewer(
        doc, state, Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 80.dp),
        onUrlLinkClicked = { uri -> openUrl(uri.toString()); true },
      )
      SelectionHandles(state, c.accent)
      if (doc != null) {
        // The page you're on, and the way to every other: tap the number to jump, the grid for thumbnails.
        Row(
          Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp)
            .background(c.panel.copy(alpha = 0.92f), CircleShape).padding(4.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          T(
            "Page $page of $pages", Type.label, c.onPanel,
            Modifier.tap(shape = CircleShape, scaleTo = 0.94f, label = "Go to page") { jumping = true }.padding(horizontal = 14.dp, vertical = 8.dp),
          )
          Box(
            Modifier.size(36.dp).tap(shape = CircleShape, scaleTo = 0.9f, label = "Page thumbnails") { thumbs = true }.background(c.accent, CircleShape),
            contentAlignment = Alignment.Center,
          ) { Icon(I.viewGridOutline, 18.dp, Brand.cherry) }
        }
      }
    }
  }

  if (jumping) PromptDialog(
    "Go to page", "$page", "1 to $pages", "Page number", "Go", keyboard = KeyboardType.Number, onClose = { jumping = false },
  ) { typed -> typed.toIntOrNull()?.let { n -> scope.launch { state.scrollToPage((n - 1).coerceIn(0, pages - 1)) } } }

  if (thumbs) ModalBottomSheet({ thumbs = false }, containerColor = c.surface) {
    Thumbnails(path, pages, page - 1) { i -> thumbs = false; scope.launch { state.scrollToPage(i) } }
  }

  if (asking) PasswordPrompt(name, wrong, onCancel = { nav.back() }) { password = it }
}

/**
 * Drag handles at both ends of a text selection, like Drive's. The viewer tracks and drags them itself, but in this
 * version doesn't draw them, so they're drawn here over it, from its own page-to-screen mapping. Drawing only: touches
 * fall through to the viewer, which moves the selection.
 */
@OptIn(ExperimentalPdfApi::class)
@Composable
private fun SelectionHandles(state: PdfViewerState, color: androidx.compose.ui.graphics.Color) {
  val sel = state.currentSelection ?: return
  val bounds = sel.bounds.takeIf { it.isNotEmpty() } ?: return
  androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
    state.zoom; state.firstVisiblePageOffset // read so the handles follow scrolling and zooming
    val r = 9.dp.toPx()
    val first = bounds.first()
    val last = bounds.last()
    // A teardrop hanging below each end: a circle with its inner corner squared off to the point.
    fun handle(at: androidx.compose.ui.geometry.Offset, start: Boolean) {
      val dx = if (start) -r else r
      drawCircle(color, r, androidx.compose.ui.geometry.Offset(at.x + dx, at.y + r))
      drawRect(color, androidx.compose.ui.geometry.Offset(if (start) at.x - r else at.x, at.y), androidx.compose.ui.geometry.Size(r, r))
    }
    // Hung from the line's lower edge (whichever of top/bottom is lower on the page: the rect's naming varies).
    state.pdfPointToVisibleOffset(PdfPoint(first.pageNum, first.left, maxOf(first.top, first.bottom)))?.let { handle(it, true) }
    state.pdfPointToVisibleOffset(PdfPoint(last.pageNum, last.right, maxOf(last.top, last.bottom)))?.let { handle(it, false) }
  }
}

/** Every page small, three across, the current one ringed; tap one to go there. */
@Composable
private fun Thumbnails(path: String, pages: Int, current: Int, onPick: (Int) -> Unit) {
  var shots by remember { mutableStateOf<List<Thumb>?>(null) }
  LaunchedEffect(path) { shots = withContext(Dispatchers.IO) { runCatching { renderThumbs(path, 240) }.getOrNull() } } // locked files: numbers only
  LazyVerticalGrid(
    GridCells.Fixed(3), Modifier.fillMaxWidth().height(520.dp).navigationBarsPadding(),
    contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    itemsIndexed((0 until pages).toList()) { _, i ->
      val t = shots?.getOrNull(i)
      val img = rememberBitmap(t?.path, 300)
      Column(Modifier.tap(scaleTo = 0.94f, label = "Page ${i + 1}") { onPick(i) }, horizontalAlignment = Alignment.CenterHorizontally) {
        val shape = RoundedCornerShape(6.dp)
        Box(
          Modifier.fillMaxWidth().aspectRatio(if (t != null) t.width.toFloat() / t.height else 0.71f).clip(shape).background(c.page)
            .border(if (i == current) 2.5.dp else 0.6.dp, if (i == current) c.accent else c.lineStrong, shape),
          contentAlignment = Alignment.Center,
        ) {
          if (img != null) Image(img, "Page ${i + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
          else T("${i + 1}", Type.title, c.faint)
        }
        T("${i + 1}", Type.num, if (i == current) c.accent else c.sub, Modifier.padding(top = 4.dp))
      }
    }
  }
}

@Composable
private fun PasswordPrompt(name: String, wrong: Boolean, onCancel: () -> Unit, onSubmit: (String) -> Unit) {
  var typed by remember { mutableStateOf("") }
  AppDialog(onCancel, "Password-protected PDF", "Enter the password for “${stem(name)}” to open it.") {
    Field(typed, { typed = it }, Modifier.padding(top = 14.dp), "Password", secret = true, error = wrong, onDone = { if (typed.isNotEmpty()) onSubmit(typed) })
    if (wrong) T("That password didn’t open it. Try again.", Type.small, c.accent, Modifier.padding(top = 6.dp))
    Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Btn("Cancel", Modifier.weight(1f), kind = BtnKind.Secondary, onClick = onCancel)
      Btn("Open", Modifier.weight(1f), kind = BtnKind.Accent, enabled = typed.isNotEmpty()) { onSubmit(typed) }
    }
  }
}
