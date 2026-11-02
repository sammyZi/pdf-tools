package com.sammyi57.pdftools

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as Pen
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Adobe Scan's review screen: swipe through pages, a thumbnail strip with long-press multi-select, and the edit toolbar
// (Add, Retake, Crop, Rotate, Filters, Adjust, Cleanup, Markup, Resize, Reorder, Reset, Delete) before Save PDF.

private data class ToolSpec(val glyph: Int, val id: String, val label: String, val disabled: Boolean)

@Composable
fun Review(
  doc: ScanDoc,
  setDoc: ((ScanDoc) -> ScanDoc) -> Unit,
  onClose: () -> Unit,
  onSave: suspend () -> Unit,
  onAddMore: () -> Unit,
  onRetake: (ScanPage) -> Unit,
) {
  val scope = rememberCoroutineScope()
  val pages = doc.pages
  val count = androidx.compose.runtime.rememberUpdatedState(pages.size)
  val pager = rememberPagerState { count.value }
  val index = pager.currentPage.coerceIn(0, max(0, pages.size - 1))
  val current = pages.getOrNull(index) ?: return
  var selected by remember { mutableStateOf<List<String>>(emptyList()) }
  var sheet by remember { mutableStateOf("") } // filters | adjust | resize
  var modal by remember { mutableStateOf("") } // crop | cleanup | markup | reorder | delete | rename | reset
  var saving by remember { mutableStateOf(false) }
  var filterAll by remember { mutableStateOf(false) } // Filters: every pick goes to all pages
  val selecting = selected.isNotEmpty()
  val targets = if (selecting) selected else listOf(current.id)

  BackHandler(selecting) { selected = emptyList() }
  BackHandler(sheet.isNotEmpty() && !selecting) { sheet = "" }

  // Unchanged shots keep their render; edited ones get a new rev and render again.
  val editShots = { ids: List<String>, fn: (Shot, ScanPage) -> Shot? ->
    setDoc { d ->
      d.copy(pages = d.pages.map { p ->
        if (p.id !in ids) p else p.copy(shots = p.shots.map { s -> fn(s, p)?.takeIf { it != s }?.copy(rev = s.rev + 1) ?: s })
      })
    }
  }
  val editPages = { ids: List<String>, fn: (ScanPage) -> ScanPage -> setDoc { d -> d.copy(pages = d.pages.map { if (it.id in ids) fn(it) else it }) } }
  val toggle = { id: String -> selected = if (id in selected) selected - id else selected + id }
  val removePages = {
    val gone = targets.size >= pages.size // photos stay on disk until saved or discarded, so Discard can bring them back
    setDoc { d -> d.copy(pages = d.pages.filter { it.id !in targets }) }
    selected = emptyList()
    if (gone) onAddMore() // nothing left: back to the camera, like Adobe
  }

  Column(Modifier.fillMaxSize().background(ScanUi.bg).statusBarsPadding().navigationBarsPadding()) {
    AnimatedContent(selecting, label = "bar", transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) }) { sel ->
      Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (sel) {
          Tap(I.close, stringResource(R.string.acc_cancel_selection)) { selected = emptyList() }
          T(stringResource(R.string.msg_count_selected, selected.size.toString()), Type.bold.copy(fontSize = 16.sp), ScanUi.text, Modifier.weight(1f))
          T(stringResource(R.string.action_select_all), Type.bold, ScanUi.accent, Modifier.tap { selected = pages.map { it.id } }.padding(horizontal = 12.dp, vertical = 12.dp))
        } else {
          Tap(I.arrowLeft, stringResource(R.string.action_back), onClick = onClose)
          Row(Modifier.weight(1f).tap(scaleTo = 0.97f, label = stringResource(R.string.action_rename)) { modal = "rename" }.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            T(doc.name, Type.bold.copy(fontSize = 16.sp), ScanUi.text, Modifier.weight(1f, fill = false), maxLines = 1)
            Spacer(Modifier.size(6.dp))
            Icon(I.pencilOutline, 15.dp, ScanUi.dim)
          }
          val ready = doc.isRendered && !saving
          Row(
            Modifier.padding(end = 4.dp).tap(enabled = ready, shape = CircleShape, scaleTo = 0.94f) { scope.launch { saving = true; try { onSave() } finally { saving = false } } }
              .graphicsLayer { alpha = if (ready) 1f else 0.6f }.background(ScanUi.accent, CircleShape).padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            if (!ready) Spinner(ScanUi.onAccent, 16.dp) else Icon(I.filePdfBox, 18.dp, ScanUi.onAccent)
            Spacer(Modifier.size(6.dp))
            T(if (doc.pdf != null) stringResource(R.string.action_update_pdf) else stringResource(R.string.action_save_pdf), Type.bold, ScanUi.onAccent)
          }
        }
      }
    }

    Box(Modifier.weight(1f).fillMaxWidth()) {
      HorizontalPager(pager, Modifier.fillMaxSize(), key = { pages.getOrNull(it)?.id ?: it }) { i ->
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          pages.getOrNull(i)?.let { PagePreview(it, maxWidth - 40.dp, maxHeight - 40.dp, flat = false) }
        }
      }
      T(stringResource(R.string.msg_x_slash_y, index + 1, pages.size), Type.num, ScanUi.text, Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp).background(ScanUi.raised, CircleShape).border(0.6.dp, ScanUi.line, CircleShape).padding(horizontal = 12.dp, vertical = 4.dp))
    }

    val strip = rememberLazyListState()
    LaunchedEffect(index) { runCatching { strip.animateScrollToItem(max(0, index - 2)) } }
    LazyRow(state = strip, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
      itemsIndexed(pages, key = { _, p -> p.id }) { i, p ->
        val on = if (selecting) p.id in selected else i == index
        val border by animateColorAsState(if (on) ScanUi.accent else Color.Transparent, label = "thumb")
        Box(
          Modifier.animateItem().size(62.dp, 76.dp).clip(RoundedCornerShape(8.dp)).background(ScanUi.panel).border(2.dp, border, RoundedCornerShape(8.dp))
            .pointerInput(p.id, selecting) {
              detectTapGestures(onLongPress = { toggle(p.id) }) { if (selecting) toggle(p.id) else scope.launch { pager.animateScrollToPage(i) } }
            },
          contentAlignment = Alignment.Center,
        ) {
          PagePreview(p, 48.dp, 62.dp, flat = true)
          if (selecting) {
            Box(
              Modifier.align(Alignment.TopEnd).padding(3.dp).size(20.dp).background(if (p.id in selected) ScanUi.accent else ScanUi.scrim, CircleShape).border(2.dp, Color.White, CircleShape),
              contentAlignment = Alignment.Center,
            ) { if (p.id in selected) Icon(I.check, 13.dp, ScanUi.onAccent) }
          }
        }
      }
      if (!selecting) item {
        Box(
          Modifier.size(62.dp, 76.dp).tap(scaleTo = 0.92f, label = stringResource(R.string.acc_add_pages), onClick = onAddMore).border(2.dp, ScanUi.accent.copy(alpha = 0.45f), RoundedCornerShape(8.dp)),
          contentAlignment = Alignment.Center,
        ) { Icon(I.plus, 24.dp, ScanUi.accent) }
      }
    }

    AnimatedVisibility(sheet.isNotEmpty(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
      Column(Modifier.fillMaxWidth().background(ScanUi.panel)) {
        Box(Modifier.fillMaxWidth().height(0.6.dp).background(ScanUi.line))
        when (sheet) {
          "filters" -> FiltersSheet(current.shots[0], filterAll,
            onPick = { f -> editShots(if (filterAll) pages.map { it.id } else targets) { s, _ -> s.copy(filter = f) } },
            onToggleAll = {
              if (!filterAll) editShots(pages.map { it.id }) { s, _ -> s.copy(filter = current.shots[0].filter) }
              filterAll = !filterAll
            },
          )
          "adjust" -> AdjustSheet(current.shots[0], { b, k -> editShots(targets) { s, _ -> s.copy(brightness = b, contrast = k) } }) {
            editShots(pages.map { it.id }) { s, _ -> s.copy(brightness = current.shots[0].brightness, contrast = current.shots[0].contrast) }
          }
          "resize" -> Column {
            SheetHead(stringResource(R.string.opt_page_size_label)) { ApplyAll(current.size) { editPages(pages.map { it.id }) { it.copy(size = current.size) } } }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
              PAGE_SIZES.forEach { (size, label) -> DarkChip(label, current.size == size) { editPages(targets) { it.copy(size = size) } } }
            }
          }
        }
      }
    }

    val tools = listOf(
      ToolSpec(I.plusBoxMultipleOutline, "add", stringResource(R.string.action_add), selecting) to onAddMore,
      ToolSpec(I.cameraRetakeOutline, "retake", stringResource(R.string.action_retake), selecting) to { onRetake(current) },
      ToolSpec(I.crop, "crop", stringResource(R.string.label_crop), selecting) to { modal = "crop" },
      // ponytail: rotating clears that page's cleanup/markup strokes; transform the strokes instead if people mind.
      ToolSpec(I.rotateRight, "rotate", stringResource(R.string.label_rotate), false) to { editShots(targets) { s, _ -> s.copy(rotation = (s.rotation + 90) % 360, strokes = emptyList()) } },
      ToolSpec(I.imageFilterBlackWhite, "filters", stringResource(R.string.label_filters), false) to { sheet = if (sheet == "filters") "" else "filters" },
      ToolSpec(I.tuneVariant, "adjust", stringResource(R.string.label_adjust), false) to { sheet = if (sheet == "adjust") "" else "adjust" },
      ToolSpec(I.eraser, "cleanup", stringResource(R.string.label_cleanup), selecting) to { modal = "cleanup" },
      ToolSpec(I.draw, "markup", stringResource(R.string.label_markup), selecting) to { modal = "markup" },
      ToolSpec(I.resize, "resize", stringResource(R.string.label_resize), false) to { sheet = if (sheet == "resize") "" else "resize" },
      ToolSpec(I.swapHorizontal, "reorder", stringResource(R.string.label_reorder), pages.size < 2) to { modal = "reorder" },
      ToolSpec(I.restore, "reset", stringResource(R.string.action_reset), false) to { modal = "reset" },
      ToolSpec(I.deleteOutline, "delete", stringResource(R.string.action_delete), false) to { modal = "delete" },
    )
    Box(Modifier.fillMaxWidth().height(0.6.dp).background(ScanUi.line))
    Row(Modifier.fillMaxWidth().background(ScanUi.panel).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 8.dp)) {
      tools.forEach { (spec, press) ->
        val (glyph, id, label, disabled) = spec
        val active = id == sheet
        val tint by animateColorAsState(if (active) ScanUi.accent else ScanUi.text, label = "tool")
        Column(
          Modifier.width(70.dp).graphicsLayer { alpha = if (disabled) 0.3f else 1f }.tap(enabled = !disabled, scaleTo = 0.88f, onClick = press).padding(vertical = 4.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          val pill by animateColorAsState(if (active) ScanUi.accentSoft else Color.Transparent, label = "toolPill")
          Box(Modifier.size(48.dp, 30.dp).background(pill, CircleShape), contentAlignment = Alignment.Center) { Icon(glyph, 24.dp, tint) }
          T(label, Type.med.copy(fontSize = 11.5.sp), tint, Modifier.padding(top = 3.dp))
        }
      }
    }
  }

  when (modal) {
    "crop" -> CropEditor(current, { modal = "" }) { corners ->
      editShots(listOf(current.id)) { sh, p -> val next = corners[p.shots.indexOf(sh)]; if (next == sh.corners) null else sh.copy(corners = next, strokes = emptyList()) }
      modal = ""
    }
    "cleanup", "markup" -> DrawEditor(current, modal, { modal = "" }) { strokes ->
      editShots(listOf(current.id)) { sh, p -> sh.copy(strokes = strokes[p.shots.indexOf(sh)]) }
      modal = ""
    }
    "reorder" -> FullSheet({ modal = "" }, ScanUi.bg) {
      Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
          Spacer(Modifier.size(48.dp))
          T(stringResource(R.string.title_reorder_pages), Type.bold.copy(fontSize = 16.sp), ScanUi.text, Modifier.weight(1f), align = TextAlign.Center)
          T(stringResource(R.string.action_done), Type.bold, ScanUi.accent, Modifier.tap { modal = "" }.padding(12.dp))
        }
        T(stringResource(R.string.msg_hold_page_drag_reorder), Type.small, ScanUi.dim, Modifier.fillMaxWidth(), align = TextAlign.Center)
        val scroll = rememberScrollState()
        val holder = remember { ScreenScroll(scroll) }
        androidx.compose.runtime.CompositionLocalProvider(LocalScreenScroll provides holder) {
          Column(Modifier.fillMaxSize().onGloballyPositioned { holder.window = it.boundsInWindow() }.verticalScroll(scroll).padding(16.dp)) {
            BoxWithConstraints {
              val cell = (maxWidth - 28.dp) / 3
              Reorderable(pages, { it.id }, { next -> setDoc { d -> d.copy(pages = next) } }, columns = 3, gap = 14.dp) { p, i, _ ->
                Column(Modifier.background(ScanUi.panel, RoundedCornerShape(8.dp)).padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                  PagePreview(p, cell - 12.dp, 150.dp, flat = true)
                  T("${i + 1}", Type.label.copy(fontSize = 12.sp), ScanUi.text, Modifier.padding(top = 6.dp))
                }
              }
            }
          }
        }
      }
    }
    "delete" -> ConfirmDialog(
      if (targets.size > 1) stringResource(R.string.title_delete_n_pages_q, targets.size) else stringResource(R.string.title_delete_this_page_q), stringResource(R.string.msg_removes_page_no_undo), stringResource(R.string.action_delete),
      danger = true, onDismiss = { modal = "" }, onConfirm = removePages,
    )
    "reset" -> ConfirmDialog(
      if (targets.size > 1) stringResource(R.string.title_reset_n_pages_q, targets.size) else stringResource(R.string.title_reset_this_page_q),
      stringResource(R.string.msg_reset_page_explain), stringResource(R.string.action_reset), onDismiss = { modal = "" },
    ) {
      editShots(targets) { sh, p -> sh.withDefaults(p.kind, sh.detected) }
      selected = emptyList()
    }
    "rename" -> PromptDialog(stringResource(R.string.title_rename_scan), doc.name, onClose = { modal = "" }) { name -> setDoc { it.copy(name = name) } }
  }
}

/** Draws a page from the PDF layout, so size choices, book halves and ID front/back look exactly as saved. */
@Composable
fun PagePreview(page: ScanPage, maxW: Dp, maxH: Dp, flat: Boolean) {
  val sheets = layoutScanPage(page.kind, page.size, page.shots.map { it.shownW to it.shownH })
  val gap = if (sheets.size > 1) 6f else 0f
  val k = min((maxW.value - gap) / sheets.sumOf { it.W.toDouble() }.toFloat(), maxH.value / sheets.maxOf { it.H })
  val pending = page.shots.any { !it.rendered }
  Row(horizontalArrangement = Arrangement.spacedBy(gap.dp), verticalAlignment = Alignment.CenterVertically) {
    sheets.forEach { sheet ->
      Box(Modifier.size((sheet.W * k).dp, (sheet.H * k).dp).then(if (!flat) Modifier.shadow(10.dp) else Modifier).background(Color.White).clip(RoundedCornerShape(0.dp))) {
        sheet.items.forEach { it ->
          val sh = page.shots[it.img]
          val bw = it.w * k
          val bh = it.h * k
          val iw = bw / it.crop.w
          val ih = bh / it.crop.h
          val bmp = rememberBitmap(sh.out ?: sh.src, if (flat) 300 else 1600)
          Box(Modifier.offset((it.x * k).dp, ((sheet.H - it.y - it.h) * k).dp).size(bw.dp, bh.dp).clip(RoundedCornerShape(0.dp))) {
            if (bmp != null) Image(bmp, null, Modifier.offset((-it.crop.x * iw).dp, (-it.crop.y * ih).dp).size(iw.dp, ih.dp), contentScale = ContentScale.FillBounds)
          }
        }
        if (pending && !flat) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) { Spinner(ScanUi.accent) }
      }
    }
  }
}

@Composable
private fun SheetHead(title: String, trailing: @Composable () -> Unit) {
  Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
    T(title, Type.bold, ScanUi.text, Modifier.weight(1f))
    trailing()
  }
}

@Composable
private fun DarkChip(label: String, on: Boolean, onClick: () -> Unit) {
  val bg by animateColorAsState(if (on) ScanUi.accent else ScanUi.raised, label = "chip")
  val fg by animateColorAsState(if (on) ScanUi.onAccent else Color.White, label = "chipText")
  T(label, Type.med.copy(fontSize = 13.sp), fg, Modifier.tap(shape = CircleShape, scaleTo = 0.93f, onClick = onClick).background(bg, CircleShape).padding(horizontal = 14.dp, vertical = 9.dp))
}

// Copies this page's setting to every page in the scan; says so once it has.
@Composable
private fun ApplyAll(key: Any, onClick: () -> Unit) {
  var done by remember(key) { mutableStateOf(false) }
  val bg by animateColorAsState(if (done) ScanUi.accent else ScanUi.raised, label = "applied")
  T(
    if (done) stringResource(R.string.msg_applied_to_all_pages) else stringResource(R.string.action_apply_to_all_pages), Type.med.copy(fontSize = 13.sp), if (done) ScanUi.onAccent else ScanUi.accent,
    Modifier.tap(shape = CircleShape, scaleTo = 0.94f) { onClick(); done = true }.background(bg, CircleShape).padding(horizontal = 14.dp, vertical = 8.dp),
  )
}

@Composable
private fun FiltersSheet(shot: Shot, all: Boolean, onPick: (String) -> Unit, onToggleAll: () -> Unit) {
  var previews by remember(shot.src, shot.corners, shot.rotation) { mutableStateOf(mapOf<String, String>()) }
  LaunchedEffect(shot.src, shot.corners, shot.rotation) { // small renders of the current page in every filter
    val made = ArrayList<String>()
    try {
      for ((f, _) in FILTERS) {
        runCatching { processImage(shot.src, ProcessOptions(shot.corners, shot.rotation, f, maxSize = 300)) }.getOrNull()?.let {
          made.add(it.path)
          previews = previews + (f to it.path)
        }
      }
      kotlinx.coroutines.awaitCancellation()
    } finally {
      made.forEach(::deleteQuietly)
    }
  }
  Column {
    SheetHead(stringResource(R.string.label_filters)) {
      Row(Modifier.tap(scaleTo = 0.95f, onClick = onToggleAll), verticalAlignment = Alignment.CenterVertically) {
        Box(
          Modifier.size(20.dp).background(if (all) ScanUi.accent else Color.Transparent, RoundedCornerShape(5.dp)).border(2.dp, if (all) ScanUi.accent else ScanUi.dim, RoundedCornerShape(5.dp)),
          contentAlignment = Alignment.Center,
        ) { if (all) Icon(I.check, 14.dp, ScanUi.onAccent) }
        Spacer(Modifier.size(8.dp))
        T(stringResource(R.string.action_apply_to_all_pages), Type.med.copy(fontSize = 13.sp), if (all) ScanUi.accent else ScanUi.text)
      }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      FILTERS.forEach { (f, label) ->
        val on = shot.filter == f
        val border by animateColorAsState(if (on) ScanUi.accent else Color.Transparent, label = "filter")
        Column(Modifier.width(76.dp).tap(scaleTo = 0.92f) { onPick(f) }, horizontalAlignment = Alignment.CenterHorizontally) {
          Box(Modifier.size(70.dp, 88.dp).clip(RoundedCornerShape(8.dp)).background(ScanUi.raised).border(2.dp, border, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            val bmp = rememberBitmap(previews[f], 300)
            if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else Spinner(ScanUi.dim, 18.dp)
          }
          T(label, Type.med.copy(fontSize = 11.5.sp), if (on) ScanUi.accent else ScanUi.text, Modifier.padding(top = 6.dp), align = TextAlign.Center)
        }
      }
    }
  }
}

@Composable
private fun AdjustSheet(shot: Shot, onCommit: (Float, Float) -> Unit, onApplyAll: () -> Unit) {
  Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    ScanSlider(stringResource(R.string.label_brightness), shot.brightness) { onCommit(it, shot.contrast) }
    ScanSlider(stringResource(R.string.label_contrast), shot.contrast) { onCommit(shot.brightness, it) }
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.weight(1f)) { ApplyAll("${shot.brightness},${shot.contrast}", onApplyAll) }
      T(stringResource(R.string.action_reset), Type.bold, ScanUi.accent, Modifier.tap { onCommit(0f, 0f) }.padding(8.dp))
    }
  }
}

// -1..1 slider; shows the value live and re-renders the page only when the finger lifts.
@Composable
private fun ScanSlider(label: String, value: Float, onCommit: (Float) -> Unit) {
  var v by remember(value) { mutableFloatStateOf(value) }
  var w by remember { mutableIntStateOf(1) }
  Column {
    Row {
      T(label, Type.med.copy(fontSize = 13.sp), ScanUi.text, Modifier.weight(1f))
      T("${(v * 100).roundToInt()}", Type.med.copy(fontSize = 13.sp), ScanUi.dim)
    }
    Box(
      Modifier.fillMaxWidth().height(36.dp).onSizeChanged { w = it.width }
        .pointerInput(Unit) {
          detectTapGestures { v = (it.x / w * 2 - 1).coerceIn(-1f, 1f); onCommit((v * 100).roundToInt() / 100f) }
        }
        .pointerInput(Unit) {
          detectDragGestures(
            onDragStart = { v = (it.x / w * 2 - 1).coerceIn(-1f, 1f) },
            onDragEnd = { onCommit((v * 100).roundToInt() / 100f) },
          ) { change, amount -> change.consume(); v = (v + amount.x / w * 2).coerceIn(-1f, 1f) }
        },
      contentAlignment = Alignment.CenterStart,
    ) {
      Canvas(Modifier.fillMaxSize()) {
        val y = size.height / 2
        val x = (v + 1) / 2 * size.width
        drawLine(ScanUi.raised, Offset(0f, y), Offset(size.width, y), 4.dp.toPx(), StrokeCap.Round)
        drawLine(ScanUi.accent, Offset(size.width / 2, y), Offset(x, y), 4.dp.toPx(), StrokeCap.Round)
        drawCircle(Color.White, 11.dp.toPx(), Offset(x, y))
      }
    }
  }
}

// ── Crop ────────────────────────────────────────────────────────────────────

private val FULL_CORNERS = listOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
private const val ZOOM = 2.5f

// Original-image fractions <-> fractions of the photo as displayed after rotating clockwise.
private fun toView(x: Float, y: Float, rot: Int) = when (rot) { 90 -> (1 - y) to x; 180 -> (1 - x) to (1 - y); 270 -> y to (1 - x); else -> x to y }
private fun fromView(x: Float, y: Float, rot: Int) = when (rot) { 90 -> y to (1 - x); 180 -> (1 - x) to (1 - y); 270 -> (1 - y) to x; else -> x to y }
private fun same(a: List<Float>?, b: List<Float>?) = (a ?: FULL_CORNERS).zip(b ?: FULL_CORNERS).all { (p, q) -> abs(p - q) < 1e-4f }

// Draws the photo turned by `rot` into the box (0,0)-(dw,dh) at `scale`.
private fun DrawScope.photo(img: ImageBitmap, rot: Int, dw: Float, dh: Float) {
  val turned = rot % 180 != 0
  val (iw, ih) = if (turned) dh to dw else dw to dh
  translate(dw / 2, dh / 2) {
    rotate(rot.toFloat(), Offset.Zero) {
      drawImage(img, dstOffset = IntOffset((-iw / 2).roundToInt(), (-ih / 2).roundToInt()), dstSize = IntSize(iw.roundToInt(), ih.roundToInt()))
    }
  }
}

// Adobe Scan's crop screen: drag the four corner handles (with a magnifier under your finger), Auto-detect, or No crop.
@Composable
private fun CropEditor(page: ScanPage, onCancel: () -> Unit, onDone: (List<List<Float>?>) -> Unit) {
  var side by remember { mutableIntStateOf(0) }
  var all by remember { mutableStateOf(page.shots.map { it.corners ?: FULL_CORNERS }) }
  var drag by remember { mutableStateOf<Triple<Int, Float, Float>?>(null) } // corner, x, y on screen
  val shot = page.shots[side]
  val rot = ((shot.rotation % 360) + 360) % 360
  val img = rememberBitmap(shot.src, 2000)
  val setSide = { cs: List<Float>? -> all = all.mapIndexed { i, x -> if (i == side) cs ?: FULL_CORNERS else x } }
  FullSheet(onCancel, ScanUi.bg) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
      EditorBar(stringResource(R.string.label_crop), onCancel) {
        onDone(all.mapIndexed { i, cs -> if (same(cs, FULL_CORNERS)) null else if (same(cs, page.shots[i].corners)) page.shots[i].corners else cs })
      }
      if (page.shots.size > 1) SideTabs(side) { side = it }
      BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        val pad = 24.dp
        val d = LocalDensity.current
        val areaW = with(d) { maxWidth.toPx() }
        val areaH = with(d) { maxHeight.toPx() }
        val (pw, ph) = if (rot % 180 != 0) shot.height to shot.width else shot.width to shot.height
        val k = min((areaW - with(d) { pad.toPx() } * 2) / pw, (areaH - with(d) { pad.toPx() } * 2) / ph)
        val dw = pw * k
        val dh = ph * k
        val ox = (areaW - dw) / 2
        val oy = (areaH - dh) / 2
        val corners = all[side]
        val pts = (0 until 4).map { i -> toView(corners[i * 2], corners[i * 2 + 1], rot).let { (vx, vy) -> Offset(ox + vx * dw, oy + vy * dh) } }
        Canvas(
          Modifier.fillMaxSize().pointerInput(side, rot, dw) {
            var idx = -1
            var start = Offset.Zero
            var total = Offset.Zero
            detectDragGestures(
              onDragStart = { at ->
                val cur = all[side].let { cs -> (0 until 4).map { i -> toView(cs[i * 2], cs[i * 2 + 1], rot).let { (vx, vy) -> Offset(ox + vx * dw, oy + vy * dh) } } }
                val near = cur.indices.minBy { (cur[it] - at).getDistance() }
                idx = if ((cur[near] - at).getDistance() < 56.dp.toPx()) near else -1
                start = if (idx >= 0) cur[idx] else Offset.Zero
                total = Offset.Zero
              },
              onDragEnd = { idx = -1; drag = null },
              onDragCancel = { idx = -1; drag = null },
            ) { change, amount ->
              if (idx < 0) return@detectDragGestures
              change.consume()
              total += amount
              val vx = ((start.x + total.x - ox) / dw).coerceIn(0f, 1f)
              val vy = ((start.y + total.y - oy) / dh).coerceIn(0f, 1f)
              val (x, y) = fromView(vx, vy, rot)
              val i = idx
              all = all.mapIndexed { s, cs -> if (s != side) cs else cs.mapIndexed { j, v -> if (j == i * 2) x else if (j == i * 2 + 1) y else v } }
              drag = Triple(i, ox + vx * dw, oy + vy * dh)
            }
          },
        ) {
          if (img != null) translate(ox, oy) { photo(img, rot, dw, dh) }
          val path = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) }; close() }
          drawPath(path, ScanUi.accentSoft)
          drawPath(path, ScanUi.accent, style = Pen(2.dp.toPx()))
          pts.forEachIndexed { i, p ->
            val r = (if (drag?.first == i) 14 else 11).dp.toPx()
            drawCircle(Color.White.copy(alpha = 0.9f), r, p)
            drawCircle(ScanUi.accent, r, p, style = Pen(3.dp.toPx()))
          }
          // A magnifier on the side away from the finger.
          val dg = drag
          if (dg != null && img != null) {
            val size = 116.dp.toPx()
            val cx = if (dg.second < areaW / 2) areaW - 16.dp.toPx() - size / 2 else 16.dp.toPx() + size / 2
            val cy = 12.dp.toPx() + size / 2
            val circle = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(cx, cy), size / 2)) }
            clipPath(circle) {
              drawRect(ScanUi.panel, Offset(cx - size / 2, cy - size / 2), Size(size, size))
              translate(cx - (dg.second - ox) * ZOOM, cy - (dg.third - oy) * ZOOM) { photo(img, rot, dw * ZOOM, dh * ZOOM) }
            }
            drawCircle(Color.White, size / 2, Offset(cx, cy), style = Pen(3.dp.toPx()))
            drawLine(ScanUi.accent, Offset(cx - 12.dp.toPx(), cy), Offset(cx + 12.dp.toPx(), cy), 2.dp.toPx())
            drawLine(ScanUi.accent, Offset(cx, cy - 12.dp.toPx()), Offset(cx, cy + 12.dp.toPx()), 2.dp.toPx())
          }
        }
      }
      Row(Modifier.fillMaxWidth().background(ScanUi.panel).padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceAround) {
        EditorAction(I.autoFix, stringResource(R.string.action_auto_detect), shot.detected != null) { setSide(shot.detected) }
        EditorAction(I.fullscreen, stringResource(R.string.action_no_crop)) { setSide(null) }
        EditorAction(I.restore, stringResource(R.string.action_reset)) { setSide(shot.corners) }
      }
    }
  }
}

@Composable
private fun EditorBar(title: String, onCancel: () -> Unit, onDone: () -> Unit) {
  Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
    T(stringResource(R.string.action_cancel), Type.item, ScanUi.text, Modifier.tap(onClick = onCancel).padding(14.dp))
    T(title, Type.bold.copy(fontSize = 16.sp), ScanUi.text, Modifier.weight(1f), align = TextAlign.Center)
    T(stringResource(R.string.action_done), Type.bold.copy(fontSize = 15.sp), ScanUi.accent, Modifier.tap(onClick = onDone).padding(14.dp))
  }
}

@Composable
private fun SideTabs(side: Int, onPick: (Int) -> Unit) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
    Row(Modifier.background(ScanUi.raised, CircleShape).padding(3.dp)) {
      listOf(stringResource(R.string.label_front), stringResource(R.string.label_back)).forEachIndexed { i, label ->
        val bg by animateColorAsState(if (side == i) ScanUi.accent else Color.Transparent, label = "side")
        T(label, Type.med, if (side == i) ScanUi.onAccent else ScanUi.dim, Modifier.tap(scaleTo = 0.94f) { onPick(i) }.background(bg, CircleShape).padding(horizontal = 20.dp, vertical = 6.dp))
      }
    }
  }
}

@Composable
private fun EditorAction(glyph: Int, label: String, enabled: Boolean = true, onClick: () -> Unit) {
  Column(
    Modifier.width(84.dp).graphicsLayer { alpha = if (enabled) 1f else 0.35f }.tap(enabled = enabled, scaleTo = 0.9f, onClick = onClick).padding(vertical = 4.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Icon(glyph, 22.dp, ScanUi.text)
    T(label, Type.med.copy(fontSize = 12.sp), ScanUi.text, Modifier.padding(top = 4.dp))
  }
}

// ── Cleanup & Markup ────────────────────────────────────────────────────────
// Cleanup paints over stains, fingers and marks with the paper color; Markup is a pen and shapes. Strokes are
// fractions of the finished page and burned in when the page renders.

private data class DrawTool(val shape: String, val glyph: Int, val label: String)

private val DRAW_TOOLS: Map<String, List<DrawTool>> @Composable get() = mapOf(
  "cleanup" to listOf(DrawTool("path", I.brush, stringResource(R.string.label_brush)), DrawTool("fill", I.rectangle, stringResource(R.string.label_area))),
  "markup" to listOf(DrawTool("path", I.draw, stringResource(R.string.label_pen)), DrawTool("rect", I.rectangleOutline, stringResource(R.string.label_rectangle)), DrawTool("ellipse", I.ellipseOutline, stringResource(R.string.label_ellipse))),
)
private val DRAW_SIZES = mapOf("cleanup" to listOf(0.02f, 0.04f, 0.08f), "markup" to listOf(0.004f, 0.008f, 0.016f))
private val MARKUP_COLORS = listOf("#111111", "#1473E6", "#D7373F", "#12805C", "#E68619", "#FFFFFF")

@Composable
private fun DrawEditor(page: ScanPage, mode: String, onCancel: () -> Unit, onDone: (List<List<Stroke>>) -> Unit) {
  var side by remember { mutableIntStateOf(0) }
  var all by remember { mutableStateOf(page.shots.map { it.strokes }) }
  var base by remember { mutableStateOf<Processed?>(null) }
  var tool by remember { mutableIntStateOf(0) }
  var size by remember { mutableIntStateOf(1) }
  var color by remember { mutableStateOf(if (mode == "cleanup") "auto" else MARKUP_COLORS[2]) }
  var live by remember { mutableStateOf<Stroke?>(null) }
  val shot = page.shots[side]

  LaunchedEffect(side) { // the page as rendered, minus strokes, to draw on
    base = null
    var made: String? = null
    try {
      base = processImage(shot.src, ProcessOptions(shot.corners, shot.rotation, shot.filter, shot.brightness, shot.contrast, maxSize = 1600)).also { made = it.path }
      kotlinx.coroutines.awaitCancellation()
    } finally {
      deleteQuietly(made)
    }
  }
  val bmp = rememberBitmap(base?.path, 1600)
  val fill = if (color == "auto") base?.bg ?: "#FFFFFF" else color
  val swatches = if (mode == "cleanup") listOf("auto", "#FFFFFF", "#000000") else MARKUP_COLORS
  // Any colour: a hue slider and a shade slider (black → the hue → white), or sampled straight off the page.
  var picking by remember { mutableStateOf(false) }
  var sampling by remember { mutableStateOf(false) }
  var hue by remember { mutableFloatStateOf(14f) }
  var shade by remember { mutableFloatStateOf(0.5f) }
  val setColor = { c: Color -> color = "#%06X".format(c.toArgb() and 0xFFFFFF) }

  FullSheet(onCancel, ScanUi.bg) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
      EditorBar(if (mode == "cleanup") stringResource(R.string.label_cleanup) else stringResource(R.string.label_markup), onCancel) { onDone(all) }
      Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { if (page.shots.size > 1) SideTabs(side) { side = it } }
        SmallAction(I.undo, stringResource(R.string.action_undo), all[side].isNotEmpty()) { all = all.mapIndexed { i, l -> if (i == side) l.dropLast(1) else l } }
        Spacer(Modifier.size(4.dp))
        SmallAction(I.deleteSweepOutline, stringResource(R.string.action_clear), all[side].isNotEmpty()) { all = all.mapIndexed { i, l -> if (i == side) emptyList() else l } }
      }
      BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        val b = base
        if (b == null || bmp == null) {
          Spinner(ScanUi.accent)
          return@BoxWithConstraints
        }
        val k = min((maxWidth.value - 24) / b.width, (maxHeight.value - 24) / b.height)
        val w = (b.width * k).dp
        val h = (b.height * k).dp
        val strokes = all[side] + listOfNotNull(live)
        val drawTools = DRAW_TOOLS
        Box(Modifier.size(w, h)) {
          Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
          Canvas(
            Modifier.fillMaxSize().pointerInput(side, tool, size, fill, sampling) {
              if (sampling) { // the next tap takes the page's colour there instead of drawing
                detectTapGestures { p ->
                  val px = bmp.asAndroidBitmap()
                  val x = (p.x / this.size.width * px.width).toInt().coerceIn(0, px.width - 1)
                  val y = (p.y / this.size.height * px.height).toInt().coerceIn(0, px.height - 1)
                  setColor(Color(px.getPixel(x, y)))
                  sampling = false
                }
                return@pointerInput
              }
              val make = { p: Offset -> Stroke(drawTools.getValue(mode)[tool].shape, listOf(p.x / this.size.width, p.y / this.size.height), DRAW_SIZES.getValue(mode)[size], fill) }
              detectDragGestures(
                onDragStart = { live = make(it) },
                onDragEnd = { live?.takeIf { it.points.size >= 4 }?.let { s -> all = all.mapIndexed { i, l -> if (i == side) l + s else l } }; live = null },
                onDragCancel = { live = null },
              ) { change, _ ->
                change.consume()
                val s = live ?: return@detectDragGestures
                val x = (change.position.x / this.size.width).coerceIn(0f, 1f)
                val y = (change.position.y / this.size.height).coerceIn(0f, 1f)
                val pts = s.points
                // Shapes only need their two corners; freehand skips points closer than ~0.3% to stay light.
                live = s.copy(points = if (s.shape == "path") (if (hypot(x - pts[pts.size - 2], y - pts[pts.size - 1]) > 0.003f) pts + listOf(x, y) else pts) else listOf(pts[0], pts[1], x, y))
              }
            },
          ) { strokes.forEach { drawStroke(it) } }
        }
      }
      Column(Modifier.fillMaxWidth().background(ScanUi.panel).padding(vertical = 6.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
          DRAW_TOOLS.getValue(mode).forEachIndexed { i, t ->
            val on = tool == i
            val bg by animateColorAsState(if (on) ScanUi.accent else ScanUi.raised, label = "drawTool")
            val fg by animateColorAsState(if (on) ScanUi.onAccent else Color.White, label = "drawToolText")
            Row(Modifier.tap(shape = CircleShape, scaleTo = 0.93f) { tool = i }.background(bg, CircleShape).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
              Icon(t.glyph, 20.dp, fg)
              Spacer(Modifier.size(6.dp))
              T(t.label, Type.med.copy(fontSize = 13.sp), fg)
            }
          }
          Box(Modifier.size(0.6.dp, 26.dp).background(ScanUi.line))
          DRAW_SIZES.getValue(mode).indices.forEach { i ->
            Box(
              Modifier.size(38.dp).tap(shape = CircleShape, scaleTo = 0.9f, label = stringResource(R.string.acc_size_n, i + 1)) { size = i }.background(ScanUi.raised, CircleShape)
                .border(2.dp, if (size == i) ScanUi.accent else Color.Transparent, CircleShape),
              contentAlignment = Alignment.Center,
            ) { Box(Modifier.size((6 + i * 6).dp).background(ScanUi.text, CircleShape)) }
          }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
          val paperColorLabel = stringResource(R.string.label_paper_color)
          val autoLabel = stringResource(R.string.label_auto)
          swatches.forEach { sw ->
            Box(
              Modifier.tap(shape = CircleShape, scaleTo = 0.88f, label = if (sw == "auto") paperColorLabel else sw) { color = sw }
                .border(2.dp, if (color == sw) ScanUi.accent else Color.Transparent, CircleShape).padding(3.dp),
            ) {
              Box(
                Modifier.size(30.dp).background(hexToColor(if (sw == "auto") base?.bg ?: "#FFFFFF" else sw), CircleShape).border(1.dp, ScanUi.line, CircleShape),
                contentAlignment = Alignment.Center,
              ) { if (sw == "auto") T(autoLabel, Type.label.copy(fontSize = 8.5.sp), Color(0xFF333333)) }
            }
          }
          val custom = color !in swatches
          Box(
            Modifier.tap(shape = CircleShape, scaleTo = 0.88f, label = stringResource(R.string.acc_choose_a_color)) {
              picking = !picking
              if (picking && !custom) setColor(shadeOf(hue, shade))
            }.border(2.dp, if (custom) ScanUi.accent else Color.Transparent, CircleShape).padding(3.dp),
          ) {
            Box(Modifier.size(30.dp).background(Brush.sweepGradient(RAINBOW), CircleShape), contentAlignment = Alignment.Center) {
              if (custom) Box(Modifier.size(18.dp).background(hexToColor(color), CircleShape).border(1.5.dp, Color.White, CircleShape))
              else Icon(I.plus, 16.dp, Color.White)
            }
          }
          Box(
            Modifier.size(36.dp).tap(shape = CircleShape, scaleTo = 0.88f, label = stringResource(R.string.acc_pick_color_from_page)) { sampling = !sampling }
              .background(if (sampling) ScanUi.accent else ScanUi.raised, CircleShape),
            contentAlignment = Alignment.Center,
          ) { Icon(I.eyedropper, 18.dp, if (sampling) ScanUi.onAccent else Color.White) }
          val hint = when {
            sampling -> stringResource(R.string.msg_tap_page_to_use_color)
            mode == "cleanup" -> stringResource(R.string.msg_paint_over_stains_help)
            else -> null
          }
          if (hint != null) T(hint, Type.small.copy(fontSize = 12.sp), if (sampling) ScanUi.accent else ScanUi.dim, Modifier.padding(start = 6.dp))
        }
        AnimatedVisibility(picking, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
          Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GradientSlider(Brush.horizontalGradient(RAINBOW), hue / 360f) { hue = it * 360f; setColor(shadeOf(hue, shade)) }
            val pure = shadeOf(hue, 0.5f)
            GradientSlider(Brush.horizontalGradient(listOf(Color.Black, pure, Color.White)), shade) { shade = it; setColor(shadeOf(hue, shade)) }
          }
        }
      }
    }
  }
}

private fun hexToColor(hex: String) = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.White)

private val RAINBOW = listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)

/** `hue` in degrees; `shade` 0 is black, 0.5 the pure hue, 1 white. */
private fun shadeOf(hue: Float, shade: Float): Color {
  val pure = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))
  return if (shade < 0.5f) lerp(Color.Black, pure, shade * 2) else lerp(pure, Color.White, (shade - 0.5f) * 2)
}

/** A gradient bar with a round thumb; tap or drag anywhere on it. `value` and `onChange` are 0..1. */
@Composable
private fun GradientSlider(brush: Brush, value: Float, onChange: (Float) -> Unit) {
  BoxWithConstraints(
    Modifier.fillMaxWidth().height(28.dp)
      .pointerInput(Unit) { detectTapGestures { onChange((it.x / size.width).coerceIn(0f, 1f)) } }
      .pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> change.consume(); onChange((change.position.x / size.width).coerceIn(0f, 1f)) } },
    contentAlignment = Alignment.CenterStart,
  ) {
    Box(Modifier.fillMaxWidth().height(14.dp).background(brush, CircleShape).border(1.dp, ScanUi.line, CircleShape))
    Box(Modifier.offset(x = (maxWidth - 26.dp) * value).size(26.dp).background(Color.White, CircleShape).border(2.dp, ScanUi.raised, CircleShape))
  }
}

private fun DrawScope.drawStroke(s: Stroke) {
  if (s.points.size < 2) return
  val pts = s.points.chunked(2).filter { it.size == 2 }.map { Offset(it[0] * size.width, it[1] * size.height) }
  val color = hexToColor(s.color)
  val width = max(1f, s.width * size.width)
  when (s.shape) {
    "path" -> {
      val path = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) } }
      drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    else -> {
      val a = pts.first()
      val b = pts.last()
      val tl = Offset(min(a.x, b.x), min(a.y, b.y))
      val sz = Size(abs(b.x - a.x), abs(b.y - a.y))
      when (s.shape) {
        "fill" -> drawRect(color, tl, sz)
        "ellipse" -> drawOval(color, tl, sz, style = androidx.compose.ui.graphics.drawscope.Stroke(width))
        else -> drawRect(color, tl, sz, style = androidx.compose.ui.graphics.drawscope.Stroke(width))
      }
    }
  }
}

@Composable
private fun SmallAction(glyph: Int, label: String, enabled: Boolean, onClick: () -> Unit) {
  Row(
    Modifier.graphicsLayer { alpha = if (enabled) 1f else 0.35f }.tap(enabled = enabled, shape = CircleShape, scaleTo = 0.92f, label = label, onClick = onClick)
      .background(ScanUi.raised, CircleShape).padding(horizontal = 10.dp, vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(glyph, 20.dp, ScanUi.text)
    Spacer(Modifier.size(4.dp))
    T(label, Type.med.copy(fontSize = 12.5.sp), ScanUi.text)
  }
}
