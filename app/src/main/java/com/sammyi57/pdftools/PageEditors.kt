package com.sammyi57.pdftools

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Redact, Crop & Resize and Fill & Sign share one way of working on a PDF: a page at a time on a stage that keeps its
// height (so changing pages never shifts the screen), a page bar, and a strip of every page to jump around in.

private val MAX_H = 460.dp

private class Pages(val path: String) {
  var count by mutableIntStateOf(0)
  var index by mutableIntStateOf(0)
  var img by mutableStateOf<Pair<Int, Thumb>?>(null)
  var thumbs by mutableStateOf<List<Thumb>?>(null)
  val loading get() = img?.first != index
  fun go(i: Int) { index = i.coerceIn(0, max(0, count - 1)) }
}

@Composable
private fun rememberPages(file: Picked, onError: (String) -> Unit): Pages {
  val s = remember(file.path) { Pages(file.path) }
  val readErr = stringResource(R.string.error_pdf_unreadable_short)
  LaunchedEffect(file.path) {
    try { s.count = withContext(Dispatchers.IO) { pageSizes(file.path).size } } catch (_: Exception) { onError(readErr) }
    // Same size as Organize's, so each tool reuses the other's cached renders.
    s.thumbs = withContext(Dispatchers.IO) { runCatching { renderThumbs(file.path, 200) }.getOrNull() }
  }
  LaunchedEffect(file.path, s.index) {
    val i = s.index
    runCatching { withContext(Dispatchers.IO) { renderPage(file.path, i, 1000) } }.getOrNull()?.let { s.img = i to it }
  }
  return s
}

/** The page drawn as big as fits, and its size on screen in dp and px. */
private class Frame(val w: Dp, val h: Dp, val wPx: Float, val hPx: Float, val img: ImageBitmap)

@Composable
private fun PageStage(pages: Pages, clip: Boolean = true, overlay: @Composable BoxScope.(Frame) -> Unit) {
  BoxWithConstraints(
    Modifier.fillMaxWidth().height(MAX_H + 24.dp).background(c.sunken, RoundedCornerShape(Radius.group)).padding(12.dp),
    contentAlignment = Alignment.Center,
  ) {
    val t = pages.img?.second
    val bmp = rememberBitmap(t?.path, 1000)
    if (t == null || bmp == null) {
      Spinner()
      return@BoxWithConstraints
    }
    val k = min(maxWidth.value / t.width, MAX_H.value / t.height)
    val w = (t.width * k).dp
    val h = (t.height * k).dp
    val d = LocalDensity.current
    val frame = Frame(w, h, with(d) { w.toPx() }, with(d) { h.toPx() }, bmp)
    Box(Modifier.size(w, h).shadow(4.dp).then(if (clip) Modifier.clip(RoundedCornerShape(2.dp)) else Modifier).background(c.page)) {
      Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
      overlay(frame)
      val veil by animateFloatAsState(if (pages.loading) 0.6f else 0f, label = "veil")
      if (veil > 0f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = veil }.background(c.page), contentAlignment = Alignment.Center) { Spinner() }
    }
  }
}

@Composable
private fun PageBar(pages: Pages, trailing: @Composable RowScope.() -> Unit = {}) {
  var goTo by remember { mutableStateOf(false) }
  Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
    IconBtn(I.chevronLeft, color = if (pages.index > 0) c.ink else c.faint, label = stringResource(R.string.acc_previous_page)) { pages.go(pages.index - 1) }
    Row(
      Modifier.tap(pressed = c.sunken, shape = RoundedCornerShape(8.dp), enabled = pages.count > 1) { goTo = true }.padding(start = 6.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      T(if (pages.count > 0) stringResource(R.string.msg_page_x_of_n, pages.index + 1, pages.count) else stringResource(R.string.acc_page_n, pages.index + 1), Type.med.copy(fontSize = 14.sp))
      if (pages.count > 1) Icon(I.menuDown, 18.dp, c.sub)
    }
    IconBtn(I.chevronRight, color = if (pages.index < pages.count - 1) c.ink else c.faint, label = stringResource(R.string.acc_next_page)) { pages.go(pages.index + 1) }
    Spacer(Modifier.weight(1f))
    trailing()
  }
  if (goTo) {
    PromptDialog(stringResource(R.string.title_go_to_page), "${pages.index + 1}", stringResource(R.string.msg_1_to_n, pages.count), stringResource(R.string.hint_page_number), stringResource(R.string.action_go), keyboard = KeyboardType.Number, onClose = { goTo = false }) {
      it.toIntOrNull()?.let { n -> pages.go(n - 1) }
    }
  }
}

// Every page, to scroll through and spot the one you need; a dot marks pages that already have something on them.
@Composable
private fun PageStrip(pages: Pages, marked: (Int) -> Boolean) {
  if (pages.count <= 1) return
  val list = rememberLazyListState()
  LaunchedEffect(pages.index) { runCatching { list.animateScrollToItem(max(0, pages.index - 2)) } }
  LazyRow(state = list, contentPadding = PaddingValues(vertical = 8.dp, horizontal = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    items(pages.count) { i ->
      val on = i == pages.index
      val thumb = rememberBitmap(pages.thumbs?.getOrNull(i)?.path, 200)
      Column(Modifier.width(52.dp).tap(scaleTo = 0.92f, label = stringResource(R.string.acc_page_n, i + 1)) { pages.go(i) }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
          Modifier.size(52.dp, 70.dp).clip(RoundedCornerShape(6.dp)).background(c.page)
            .border(if (on) 2.dp else 1.dp, if (on) c.accent else c.lineStrong, RoundedCornerShape(6.dp)),
          contentAlignment = Alignment.Center,
        ) {
          if (thumb != null) Image(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
          if (marked(i)) Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(8.dp).background(c.accent, CircleShape))
        }
        T("${i + 1}", Type.num.copy(fontSize = 11.sp), if (on) c.accent else c.sub, Modifier.padding(top = 3.dp))
      }
    }
  }
}

/** A different PDF starts clean; the first one keeps what it had, which may come from a resumed draft. */
@Composable
private fun ResetOnNewFile(file: Picked, key: String, p: EditorProps) {
  val shown = remember { mutableStateOf(file.path) }
  LaunchedEffect(file.path) {
    if (file.path != shown.value) p.onChange { kotlinx.serialization.json.JsonObject(it - key) }
    shown.value = file.path
  }
}

// ── Redact ──────────────────────────────────────────────────────────────────

private const val TAP = 0.012f // a drag smaller than this (fraction of the page) is a tap: it removes the box under it

@Composable
fun RedactEditor(p: EditorProps) {
  val file = p.file ?: return T(stringResource(R.string.msg_choose_pdf_redact), Type.small, c.sub)
  ResetOnNewFile(file, "boxes", p)
  val pages = rememberPages(file, p.onError)
  val boxes = p.value.get<Map<String, List<List<Float>>>>("boxes") ?: emptyMap()
  val onPage = boxes[pages.index.toString()] ?: emptyList()
  val total = boxes.values.sumOf { it.size }
  var live by remember { mutableStateOf<List<Float>?>(null) }
  val edit = { page: Int, fn: (List<List<Float>>) -> List<List<Float>> ->
    p.onChange { v ->
      val all = (v.get<Map<String, List<List<Float>>>>("boxes") ?: emptyMap()).toMutableMap()
      val next = fn(all[page.toString()] ?: emptyList())
      if (next.isEmpty()) all.remove(page.toString()) else all[page.toString()] = next
      v.put("boxes", all)
    }
  }
  val accent = c.accent

  PageStage(pages) { f ->
    val index = pages.index
    val latest by rememberUpdatedState(edit)
    val boxOf = { a: Offset, b: Offset ->
      val x1 = (a.x / f.wPx).coerceIn(0f, 1f); val y1 = (a.y / f.hPx).coerceIn(0f, 1f)
      val x2 = (b.x / f.wPx).coerceIn(0f, 1f); val y2 = (b.y / f.hPx).coerceIn(0f, 1f)
      listOf(min(x1, x2), min(y1, y2), abs(x2 - x1), abs(y2 - y1))
    }
    Canvas(
      Modifier.fillMaxSize()
        .pointerInputIf(!pages.loading, index, f.wPx) {
          var start = Offset.Zero
          var cur = Offset.Zero
          detectDragGestures(
            onDragStart = { start = it; cur = it; live = boxOf(it, it) },
            onDragEnd = { val b = boxOf(start, cur); live = null; if (b[2] > TAP || b[3] > TAP) latest(index) { l -> l + listOf(b) } },
            onDragCancel = { live = null },
          ) { change, _ -> change.consume(); cur = change.position; live = boxOf(start, cur) }
        }
        .pointerInputIf(!pages.loading, index, f.wPx, "tap") {
          detectTapGestures { pos -> // a tap removes the topmost box under it
            val x = pos.x / f.wPx
            val y = pos.y / f.hPx
            latest(index) { l -> l.indexOfLast { x >= it[0] && x <= it[0] + it[2] && y >= it[1] && y <= it[1] + it[3] }.let { hit -> if (hit < 0) l else l.filterIndexed { i, _ -> i != hit } } }
          }
        },
    ) {
      for (b in (if (pages.loading) emptyList() else onPage)) drawRect(Color.Black, Offset(b[0] * size.width, b[1] * size.height), Size(b[2] * size.width, b[3] * size.height))
      live?.let { b ->
        val o = Offset(b[0] * size.width, b[1] * size.height)
        val s = Size(b[2] * size.width, b[3] * size.height)
        drawRect(Color.Black.copy(alpha = 0.45f), o, s)
        drawRect(accent, o, s, style = Stroke(1.5.dp.toPx()))
      }
    }
  }
  PageBar(pages) {
    if (onPage.isNotEmpty()) {
      T(stringResource(R.string.action_undo), Type.med, c.accent, Modifier.tap(scaleTo = 0.9f) { edit(pages.index) { it.dropLast(1) } })
      T(stringResource(R.string.action_clear_page), Type.med, c.accent, Modifier.padding(start = 16.dp).tap(scaleTo = 0.9f) { edit(pages.index) { emptyList() } })
    }
  }
  PageStrip(pages) { !boxes[it.toString()].isNullOrEmpty() }
  T(
    (if (total > 0) stringResource(R.string.msg_areas_on_pages_blacked_out, plural(total, stringResource(R.string.unit_area_one), stringResource(R.string.unit_area_many)), plural(boxes.size, stringResource(R.string.unit_page_one), stringResource(R.string.unit_page_many))) else stringResource(R.string.msg_drag_to_black_out)) +
      " " + stringResource(R.string.msg_pages_become_images),
    Type.small, c.sub,
  )
}

private fun Modifier.pointerInputIf(on: Boolean, vararg keys: Any?, block: suspend androidx.compose.ui.input.pointer.PointerInputScope.() -> Unit): Modifier =
  if (on) this.then(Modifier.pointerInput(*keys, block = block)) else this

// ── Crop & Resize ───────────────────────────────────────────────────────────

private val FULL = listOf(0f, 0f, 1f, 1f)
private const val MIN_CROP = 0.06f // smallest crop, so the corners can't be dragged through each other
// Which edges each corner moves: [left|right, top|bottom], clockwise from the top left.
private val CORNERS = listOf(0 to 0, 1 to 0, 1 to 1, 0 to 1)

@Composable
fun PdfCropEditor(p: EditorProps) {
  val scope = rememberCoroutineScope()
  Options(CROP_OPTS, p.value) { v -> p.onChange { v } }
  val file = p.file ?: return T(stringResource(R.string.msg_choose_pdf_crop), Type.small, c.sub)
  if (p.value.str("mode") == "resize") {
    T(stringResource(R.string.msg_page_scaled_to_fit), Type.small, c.sub)
    return
  }
  ResetOnNewFile(file, "boxes", p)
  val pages = rememberPages(file, p.onError)
  val boxes = p.value.get<Map<String, List<Float>>>("boxes") ?: emptyMap()
  val stored = boxes[pages.index.toString()]
  var live by remember { mutableStateOf<List<Float>?>(null) } // the rect mid-drag, kept out of opts until the finger lifts
  var auto by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
  var note by remember { mutableStateOf("") }
  val setBox = { page: Int, box: List<Float>? ->
    p.onChange { v ->
      val all = (v.get<Map<String, List<Float>>>("boxes") ?: emptyMap()).toMutableMap()
      if (box != null) all[page.toString()] = box else all.remove(page.toString())
      v.put("boxes", all)
    }
  }
  val rect = live ?: stored ?: FULL // untouched pages show the frame around the whole page

  // Auto crop: each page is rendered (the same cached render the editor shows) and trimmed to its ink or, for a
  // photographed page, to the sheet the page detector finds. The crops land in one change at the end.
  val croppingLabel = stringResource(R.string.msg_cropping_ellipsis)
  val nOfMLabel = stringResource(R.string.msg_n_of_m)
  val trimmedPageOf = stringResource(R.string.msg_auto_crop_trimmed_pages)
  val trimmedSingle = stringResource(R.string.msg_trimmed_adjust)
  val nothingToTrimAll = stringResource(R.string.msg_nothing_to_trim_all)
  val nothingToTrimOne = stringResource(R.string.msg_nothing_to_trim_one)
  val autoCropReadErr = stringResource(R.string.error_auto_crop_unreadable)
  val pageOne = stringResource(R.string.unit_page_one)
  val pageMany = stringResource(R.string.unit_page_many)
  val autoCrop = { all: Boolean ->
    if (auto == null && !pages.loading) scope.launch {
      val list = if (all) (0 until pages.count).toList() else listOf(pages.index)
      note = ""
      val found = HashMap<String, List<Float>>()
      try {
        list.forEachIndexed { n, i ->
          auto = all to (if (all) nOfMLabel.format(n + 1, list.size) else croppingLabel)
          autoCropPage(withContext(Dispatchers.IO) { renderPage(file.path, i, 1000) }.path)?.let { found[i.toString()] = it }
        }
        if (found.isNotEmpty()) p.onChange { v -> v.put("boxes", (v.get<Map<String, List<Float>>>("boxes") ?: emptyMap()) + found) }
        note = when {
          found.isNotEmpty() && all -> trimmedPageOf.format(plural(found.size, pageOne, pageMany), list.size)
          found.isNotEmpty() -> trimmedSingle
          all -> nothingToTrimAll
          else -> nothingToTrimOne
        }
      } catch (_: Exception) {
        p.onError(autoCropReadErr)
      }
      auto = null
    }
    Unit
  }

  PageStage(pages, clip = false) { f ->
    val (rx, ry, rw, rh) = rect
    Canvas(Modifier.fillMaxSize()) {
      // Everything outside the frame dimmed, so what survives the crop is what stays bright.
      val path = Path().apply {
        fillType = PathFillType.EvenOdd
        addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
        addRect(androidx.compose.ui.geometry.Rect(rx * size.width, ry * size.height, (rx + rw) * size.width, (ry + rh) * size.height))
      }
      drawPath(path, Color.Black.copy(alpha = 0.5f))
      drawRect(Color.White, Offset(rx * size.width, ry * size.height), Size(rw * size.width, rh * size.height), style = Stroke(2.dp.toPx()))
    }
    if (!pages.loading) CORNERS.forEachIndexed { k, (ax, ay) ->
      val index = pages.index
      val cx = (if (ax == 1) rx + rw else rx) * f.wPx
      val cy = (if (ay == 1) ry + rh else ry) * f.hPx
      val latestStored by rememberUpdatedState(stored)
      val latestSet by rememberUpdatedState(setBox)
      Box(
        Modifier.offset { IntOffset((cx - 22.dp.toPx()).roundToInt(), (cy - 22.dp.toPx()).roundToInt()) }.size(44.dp)
          .pointerInput(k, index, f.wPx) {
            var start = FULL
            var total = Offset.Zero
            detectDragGestures(
              onDragStart = { start = live ?: latestStored ?: FULL; total = Offset.Zero },
              onDragEnd = { live?.let { latestSet(index, it) }; live = null },
              onDragCancel = { live = null },
            ) { change, amount ->
              change.consume()
              total += amount
              val (sx, sy, sw, sh) = start
              var x0 = sx; var y0 = sy; var x1 = sx + sw; var y1 = sy + sh
              val dx = total.x / f.wPx
              val dy = total.y / f.hPx
              // Only the dragged edges move; each stops short of the opposite one so the rect can never invert.
              if (ax == 0) x0 = (sx + dx).coerceIn(0f, x1 - MIN_CROP) else x1 = (sx + sw + dx).coerceIn(x0 + MIN_CROP, 1f)
              if (ay == 0) y0 = (sy + dy).coerceIn(0f, y1 - MIN_CROP) else y1 = (sy + sh + dy).coerceIn(y0 + MIN_CROP, 1f)
              live = listOf(x0, y0, x1 - x0, y1 - y0)
            }
          },
        contentAlignment = Alignment.Center,
      ) {
        Box(Modifier.size(17.dp).shadow(3.dp, CircleShape).background(Color.White, CircleShape).border(2.5.dp, c.accent, CircleShape))
      }
    }
  }
  PageBar(pages) {
    if (stored != null) {
      if (pages.count > 1) T(stringResource(R.string.action_apply_to_all), Type.med, c.accent, Modifier.tap(scaleTo = 0.9f) {
        p.onChange { v -> v.put("boxes", (0 until pages.count).associate { it.toString() to stored }) }
      })
      T(stringResource(R.string.action_reset_page), Type.med, c.accent, Modifier.padding(start = 16.dp).tap(scaleTo = 0.9f) { setBox(pages.index, null) })
    }
  }
  // Both buttons stay put while one runs (the other just stops taking taps), so nothing jumps.
  val autoCropLabel = stringResource(R.string.action_auto_crop)
  val autoCropAllLabel = stringResource(R.string.action_auto_crop_all)
  Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    Btn(auto?.takeIf { !it.first }?.second ?: autoCropLabel, Modifier.weight(1f), I.autoFix, BtnKind.Secondary, busy = auto?.first == false, height = 46.dp) { autoCrop(false) }
    if (pages.count > 1) Btn(auto?.takeIf { it.first }?.second ?: autoCropAllLabel, Modifier.weight(1f), I.autoFix, BtnKind.Secondary, busy = auto?.first == true, height = 46.dp) { autoCrop(true) }
  }
  PageStrip(pages) { boxes.containsKey(it.toString()) }
  T(
    note.ifEmpty {
      if (boxes.isNotEmpty()) stringResource(R.string.msg_pages_cropped_frame, plural(boxes.size, pageOne, pageMany))
      else stringResource(R.string.msg_pull_corners_or_autocrop)
    },
    Type.small, c.sub,
  )
}

// ── Fill & Sign ─────────────────────────────────────────────────────────────

private const val TEXT_SIZE = 0.028f // a new line's height, as a fraction of the page
private const val IMG_W = 0.34f // a new picture's width, as a fraction of the page
// Pen colours, the usual ones for filling forms in; ink first.
private val PEN: List<Pair<String, String>> @Composable get() = listOf(
  INK to stringResource(R.string.color_black), "#1D4ED8" to stringResource(R.string.color_blue), "#DC2626" to stringResource(R.string.color_red),
  "#15803D" to stringResource(R.string.color_green), "#6B7280" to stringResource(R.string.color_grey),
)
private val OPACITY = listOf(1f, 0.75f, 0.5f, 0.25f)

private fun hexColor(hex: String) = Color(android.graphics.Color.parseColor(if (hex.startsWith("#")) hex else "#$hex"))

@Composable
fun FillSignEditor(p: EditorProps) {
  val scope = rememberCoroutineScope()
  val file = p.file ?: return T(stringResource(R.string.msg_choose_pdf_fill_sign), Type.small, c.sub)
  ResetOnNewFile(file, "items", p)
  val pages = rememberPages(file, p.onError)
  val items = p.value.get<List<SignItem>>("items") ?: emptyList()
  var sel by remember(file.path) { mutableIntStateOf(-1) }
  var typing by remember { mutableStateOf<Int?>(null) } // the item being typed, -1 for a new one
  // The item being dragged, kept out of opts until the finger lifts: writing every frame re-saved the draft each time.
  var drag by remember { mutableStateOf<Triple<Int, Float, Float>?>(null) }
  LaunchedEffect(pages.index) { sel = -1 }

  val set = { fn: (List<SignItem>) -> List<SignItem> -> p.onChange { v -> v.put("items", fn(v.get<List<SignItem>>("items") ?: emptyList())) } }
  val edit = { i: Int, fn: (SignItem) -> SignItem -> set { l -> l.mapIndexed { j, x -> if (j == i) fn(x) else x } } }
  val add = { it: SignItem -> set { l -> (l + it).also { n -> sel = n.size - 1 } } }
  val scale = { i: Int, by: Float ->
    edit(i) { it -> if (it.kind == "text") it.copy(size = (it.size * by).coerceIn(0.008f, 0.3f)) else it.copy(w = (it.w * by).coerceIn(0.04f, 1f), h = (it.h * by).coerceIn(0.02f, 1f)) }
  }
  // Turns the selected item clockwise, kept within -180..180 so the label reads naturally.
  val turn = { by: Float -> if (sel in items.indices) edit(sel) { it.copy(angle = ((((it.angle + by) % 360) + 540) % 360 - 180).let { a -> if (a == -180f) 180f else a }) } }
  var frame by remember { mutableStateOf<Pair<Float, Float>?>(null) } // the page's size on screen, for a picture's shape

  PageStage(pages) { f ->
    frame = f.w.value to f.h.value
    val d = LocalDensity.current
    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { sel = -1 } })
    items.forEachIndexed { i, it ->
      if (it.page != pages.index) return@forEachIndexed
      val x = if (drag?.first == i) drag!!.second else it.x
      val y = if (drag?.first == i) drag!!.third else it.y
      val latestItems by rememberUpdatedState(items)
      val latestEdit by rememberUpdatedState(edit)
      Box(
        Modifier.offset { IntOffset((x * f.wPx).roundToInt(), (y * f.hPx).roundToInt()) }
          .graphicsLayer { rotationZ = it.angle } // about its centre, like the PDF
          .border(1.dp, if (sel == i) c.accent else Color.Transparent, RoundedCornerShape(2.dp))
          .background(if (sel == i) c.accentSoft.copy(alpha = 0.6f) else Color.Transparent)
          .pointerInput(i, f.wPx) { detectTapGestures { sel = i } }
          .pointerInput(i, f.wPx) {
            var sx = 0f
            var sy = 0f
            var total = Offset.Zero
            detectDragGestures(
              onDragStart = { sel = i; latestItems.getOrNull(i)?.let { s -> sx = s.x; sy = s.y }; total = Offset.Zero },
              onDragEnd = { drag?.let { (j, nx, ny) -> latestEdit(j) { s -> s.copy(x = nx, y = ny) } }; drag = null },
              onDragCancel = { drag = null },
            ) { change, amount ->
              change.consume()
              total += amount
              // Kept on the page: a picture wholly, a line of text (whose width isn't known here) by its start and height.
              val s = latestItems.getOrNull(i) ?: return@detectDragGestures
              val (w, h) = if (s.kind == "image") s.w to s.h else 0.05f to s.size
              drag = Triple(i, min((sx + total.x / f.wPx).coerceIn(0f, 1f), max(0f, 1 - w)), min((sy + total.y / f.hPx).coerceIn(0f, 1f), max(0f, 1 - h)))
            }
          },
      ) {
        // Opacity on the content only, so a faded item's selection frame stays easy to see.
        Box(Modifier.graphicsLayer { alpha = it.opacity }) {
          if (it.kind == "text") {
            // Android's sans-serif is the closest system font to the Helvetica the PDF gets.
            val px = it.size * f.hPx
            T(it.text, TextStyle(fontFamily = FontFamily.SansSerif, fontSize = with(d) { px.toSp() }, lineHeight = with(d) { (px * 1.16f).toSp() }), hexColor(it.color ?: INK), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Visible)
          } else {
            val bmp = rememberBitmap(it.uri, 800)
            Box(Modifier.size(f.w * it.w, f.h * it.h)) { if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
          }
        }
      }
    }
  }

  val selected = items.getOrNull(sel)
  PageBar(pages) {
    if (selected != null) {
      if (selected.kind == "text") IconBtn(I.pencilOutline, label = stringResource(R.string.acc_edit_text)) { typing = sel }
      IconBtn(I.formatFontSizeDecrease, label = stringResource(R.string.acc_smaller)) { scale(sel, 1 / 1.18f) }
      IconBtn(I.formatFontSizeIncrease, label = stringResource(R.string.acc_bigger)) { scale(sel, 1.18f) }
      IconBtn(I.deleteOutline, color = c.accent, label = stringResource(R.string.action_remove)) { val i = sel; sel = -1; set { l -> l.filterIndexed { j, _ -> j != i } } }
    }
  }

  if (selected != null) {
    val shape = RoundedCornerShape(Radius.group)
    Column(Modifier.padding(top = 8.dp).fillMaxWidth().background(c.surface, shape).border(0.6.dp, c.lineStrong, shape).padding(horizontal = 12.dp, vertical = 4.dp)) {
      if (selected.kind == "text") PanelRow(stringResource(R.string.label_colour)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          PEN.forEach { (hex, name) ->
            val on = (selected.color ?: INK) == hex
            Box(
              Modifier.size(34.dp).tap(shape = CircleShape, scaleTo = 0.85f, label = name) { edit(sel) { it.copy(color = hex) } }
                .border(2.dp, if (on) c.accent else Color.Transparent, CircleShape),
              contentAlignment = Alignment.Center,
            ) { Box(Modifier.size(24.dp).background(hexColor(hex), CircleShape).border(0.6.dp, c.lineStrong, CircleShape)) }
          }
        }
      }
      PanelRow(stringResource(R.string.opt_opacity_label)) {
        Segmented(OPACITY.map { it to "${(it * 100).roundToInt()}%" }, OPACITY.minBy { abs(it - selected.opacity) }) { v -> edit(sel) { it.copy(opacity = v) } }
      }
      PanelRow(stringResource(R.string.label_rotate)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          IconBtn(I.rotateLeft, label = stringResource(R.string.acc_turn_left)) { turn(-15f) }
          T("${selected.angle.roundToInt()}°", Type.num.copy(fontSize = 14.sp), modifier = Modifier.width(48.dp).tap(scaleTo = 0.9f, label = stringResource(R.string.acc_straighten)) { edit(sel) { it.copy(angle = 0f) } }, align = androidx.compose.ui.text.style.TextAlign.Center)
          IconBtn(I.rotateRight, label = stringResource(R.string.acc_turn_right)) { turn(15f) }
          Spacer(Modifier.weight(1f))
          T("+90°", Type.med.copy(fontSize = 13.sp), modifier = Modifier.tap(pressed = c.sunken, shape = CircleShape, scaleTo = 0.92f) { turn(90f) }.border(1.dp, c.lineStrong, CircleShape).padding(horizontal = 12.dp, vertical = 7.dp))
        }
      }
    }
  }

  // A picture onto the page: copied under the app's own files (so a resumed draft still finds it after the cache is
  // cleared), its width a share of the page and its height following from its shape on screen.
  val addPicture = { path: String ->
    val png = mimeOfPicked(path) == "image/png"
    val b = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(path, b)
    val aspect = b.outHeight.toFloat().coerceAtLeast(1f) / b.outWidth.toFloat().coerceAtLeast(1f)
    val (fw, fh) = frame ?: (1f to 1f)
    val keep = File(appContext.filesDir, "sign-images").apply { mkdirs() }.let { File(it, "${uid()}.${if (png) "png" else "jpg"}") }
    File(path).copyTo(keep, true)
    add(SignItem(pages.index, "image", 0.1f, 0.1f, w = IMG_W, h = IMG_W * fw * aspect / fh, uri = keep.path))
  }
  var signing by remember { mutableStateOf(false) }
  val pickPngOrJpegErr = stringResource(R.string.error_pick_jpeg_or_png)
  Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Btn(stringResource(R.string.label_text), Modifier.weight(1f), I.formatText, BtnKind.Secondary, height = 46.dp) { typing = -1 }
    Btn(stringResource(R.string.label_signature), Modifier.weight(1.3f), I.drawPen, BtnKind.Secondary, height = 46.dp) { signing = true }
    Btn(stringResource(R.string.label_picture), Modifier.weight(1f), I.imageOutline, BtnKind.Secondary, height = 46.dp) {
      scope.launch {
        val pic = pickImages(false).firstOrNull() ?: return@launch
        if (mimeOfPicked(pic.path) == null) return@launch p.onError(pickPngOrJpegErr)
        addPicture(pic.path)
      }
    }
  }
  if (signing) SignaturePad({ signing = false }) { f -> signing = false; addPicture(f.path) }
  PageStrip(pages) { i -> items.any { it.page == i } }
  T(
    if (items.isNotEmpty()) stringResource(R.string.msg_items_added_help, plural(items.size, stringResource(R.string.unit_item_one), stringResource(R.string.unit_item_many)))
    else stringResource(R.string.msg_add_text_or_signature_help),
    Type.small, c.sub, Modifier.padding(top = 10.dp),
  )

  val textLatinOnlyErr = stringResource(R.string.error_text_latin_only)
  val charsLeftOutErr = stringResource(R.string.error_chars_left_out)
  val addTextTitle = stringResource(R.string.title_add_text)
  val editTextTitle = stringResource(R.string.title_edit_text)
  val typeWhatGoesOnPage = stringResource(R.string.hint_type_what_goes_on_page)
  val addLabel = stringResource(R.string.action_add)
  val saveLabel = stringResource(R.string.action_save)
  typing?.let { t ->
    PromptDialog(
      if (t == -1) addTextTitle else editTextTitle, if (t >= 0) items.getOrNull(t)?.text ?: "" else "", placeholder = typeWhatGoesOnPage,
      submitLabel = if (t == -1) addLabel else saveLabel, onClose = { typing = null },
    ) { typed ->
      // The page gets Helvetica, which only writes Latin text: store what it can write, and say what it can't.
      val text = winAnsi(typed).trim()
      if (text.isEmpty()) return@PromptDialog p.onError(textLatinOnlyErr)
      if (Regex("[^\\x20-\\x7E\\xA0-\\xFF‘’“”–—₹]").containsMatchIn(typed)) p.onError(charsLeftOutErr)
      if (t == -1) add(SignItem(pages.index, "text", 0.1f, 0.1f, size = TEXT_SIZE, text = text)) else edit(t) { it.copy(text = text) }
    }
  }
}

// ── Signature ───────────────────────────────────────────────────────────────

/**
 * The signature drawn once and reused: a transparent PNG cropped to the ink, in the app's private files. A new name
 * each time it's redrawn, so a cached picture of the old one is never shown.
 */
private val signatureDir get() = File(appContext.filesDir, "signature").apply { mkdirs() }
private fun savedSignature(): File? = signatureDir.listFiles()?.filter { it.isFile }?.maxByOrNull { it.lastModified() }

private const val SIGN_INK = 0xFF0F172A.toInt() // INK, the dark blue-black of a pen

/** Shows the saved signature to use again, or a pad to draw one (and redraw it any time). */
@Composable
private fun SignaturePad(onClose: () -> Unit, onUse: (File) -> Unit) {
  val saved = remember { savedSignature() }
  var drawing by remember { mutableStateOf(saved == null) }
  val strokes = remember { androidx.compose.runtime.mutableStateListOf<List<Offset>>() }
  var live by remember { mutableStateOf<List<Offset>>(emptyList()) }
  val pen = with(LocalDensity.current) { 3.dp.toPx() }
  val shape = RoundedCornerShape(Radius.group)
  AppDialog(onClose, stringResource(R.string.title_your_signature), if (drawing) stringResource(R.string.msg_sign_with_finger) else stringResource(R.string.msg_saved_on_this_phone)) {
    Box(Modifier.padding(top = 14.dp).fillMaxWidth().height(170.dp).clip(shape).background(Color.White).border(1.dp, c.lineStrong, shape)) {
      if (drawing) {
        if (strokes.isEmpty() && live.isEmpty()) {
          T(stringResource(R.string.msg_sign_here), Type.title, Color(0xFFB8B4B0), Modifier.align(Alignment.Center))
        }
        androidx.compose.foundation.Canvas(
          Modifier.fillMaxSize().pointerInput(Unit) {
            detectDragGestures(
              onDragStart = { live = listOf(it) },
              onDragEnd = { if (live.isNotEmpty()) strokes.add(live); live = emptyList() },
              onDragCancel = { live = emptyList() },
            ) { change, _ -> change.consume(); live = live + change.position }
          },
        ) {
          for (s in strokes + listOf(live)) {
            if (s.size < 2) continue
            val path = androidx.compose.ui.graphics.Path().apply { moveTo(s[0].x, s[0].y); s.drop(1).forEach { lineTo(it.x, it.y) } }
            drawPath(path, Color(SIGN_INK), style = androidx.compose.ui.graphics.drawscope.Stroke(pen, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
          }
        }
      } else {
        val bmp = rememberBitmap(saved?.path, 900)
        if (bmp != null) Image(bmp, stringResource(R.string.acc_your_saved_signature), Modifier.fillMaxSize().padding(16.dp), contentScale = ContentScale.Fit)
      }
    }
    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      if (drawing) {
        Btn(stringResource(R.string.action_clear), Modifier.weight(1f), kind = BtnKind.Secondary, enabled = strokes.isNotEmpty()) { strokes.clear() }
        Btn(stringResource(R.string.action_save_and_use), Modifier.weight(1f), kind = BtnKind.Accent, enabled = strokes.isNotEmpty()) { onUse(saveSignature(strokes.toList(), pen)) }
      } else {
        Btn(stringResource(R.string.action_draw_new), Modifier.weight(1f), kind = BtnKind.Secondary) { drawing = true }
        Btn(stringResource(R.string.action_use_it), Modifier.weight(1f), kind = BtnKind.Accent) { saved?.let(onUse) }
      }
    }
  }
}

/** Draws the strokes twice as sharp as on screen, cropped to the ink with a little margin, onto a clear PNG. */
private fun saveSignature(strokes: List<List<Offset>>, pen: Float): File {
  val pts = strokes.flatten()
  val pad = pen * 2
  val minX = pts.minOf { it.x } - pad
  val minY = pts.minOf { it.y } - pad
  val k = 2f
  val w = ((pts.maxOf { it.x } + pad - minX) * k).toInt().coerceAtLeast(1)
  val h = ((pts.maxOf { it.y } + pad - minY) * k).toInt().coerceAtLeast(1)
  val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
  val paint = android.graphics.Paint().apply {
    color = SIGN_INK; style = android.graphics.Paint.Style.STROKE; strokeWidth = pen * k; isAntiAlias = true
    strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND
  }
  val canvas = android.graphics.Canvas(bmp)
  for (s in strokes) {
    val path = android.graphics.Path()
    s.forEachIndexed { i, p -> val x = (p.x - minX) * k; val y = (p.y - minY) * k; if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
    if (s.size == 1) canvas.drawPoint((s[0].x - minX) * k, (s[0].y - minY) * k, paint) else canvas.drawPath(path, paint)
  }
  val old = signatureDir.listFiles()?.toList().orEmpty()
  val out = File(signatureDir, "${uid()}.png")
  out.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
  bmp.recycle()
  old.forEach { it.delete() } // pages already signed keep their own copy (under sign-images)
  return out
}

@Composable
private fun PanelRow(label: String, content: @Composable () -> Unit) {
  Row(Modifier.heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
    T(label, Type.med, c.sub, Modifier.width(72.dp))
    Box(Modifier.weight(1f)) { content() }
  }
}
