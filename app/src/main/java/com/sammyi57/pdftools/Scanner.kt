package com.sammyi57.pdftools

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// The scanner flow: capture <-> review for one scan document. Opened fresh from Scan, or with an id to keep editing.
// Every change is written to disk as it happens, so a scan survives the app closing; leaving asks Save / Keep / Discard.
@Composable
fun Scanner(id: String?) {
  val nav = LocalNav.current
  val scope = rememberCoroutineScope()
  DarkSurfaceBars()
  var doc by remember { mutableStateOf(id?.let(::loadDoc) ?: newDoc()) }
  var capture by remember { mutableStateOf(id == null) }
  var retake by remember { mutableStateOf<ScanPage?>(null) }
  var draft by remember { mutableStateOf(if (id == null) latestDraft() else null) }
  var asking by remember { mutableStateOf(false) } // the leave dialog
  var saving by remember { mutableStateOf(false) }
  var recentOpen by remember { mutableStateOf(false) }
  var failure by remember { mutableStateOf("") }
  val rendering = remember { HashSet<String>() }
  val persisted = remember { mutableStateOf(id != null) }
  val left = remember { mutableStateOf(false) } // repeated taps must pop the scanner only once

  LaunchedEffect(Unit) { if (id != null) withContext(Dispatchers.IO) { cleanFiles(doc) } } // leftovers from a session that ended unsaved
  LaunchedEffect(doc) {
    // Autosave every change. Once on disk, keep saving even at 0 pages, or deleted pages would come back after a restart.
    if (doc.pages.isEmpty() && !persisted.value) return@LaunchedEffect
    val d = doc
    withContext(Dispatchers.IO) { saveDoc(d) }
    persisted.value = true
  }
  LaunchedEffect(doc) {
    // Render every shot whose settings changed since its last render.
    doc.pages.forEach { p ->
      p.shots.forEachIndexed { i, sh ->
        if (sh.rendered) return@forEachIndexed
        val key = "${p.id}:$i:${sh.rev}"
        if (!rendering.add(key)) return@forEachIndexed
        scope.launch {
          val r = runCatching { renderShot(doc.id, sh) }.getOrNull()
          doc = doc.copy(pages = doc.pages.map { q ->
            if (q.id != p.id) q else q.copy(shots = q.shots.mapIndexed { j, x ->
              when {
                j != i -> x
                x.src != sh.src || x.rev != sh.rev -> { if (r != null) deleteQuietly(r.out); x } // edited again meanwhile: drop this render
                // An unreadable photo shows and exports as the original rather than blocking Save forever.
                r == null -> x.copy(out = x.src, outW = x.width, outH = x.height, outRev = x.rev)
                else -> x.copy(out = r.out, outW = r.outW, outH = r.outH, bg = r.bg, outRev = r.outRev) // the previous render stays until cleanFiles
              }
            })
          })
          rendering.remove(key)
        }
      }
    }
  }

  val back = { if (!left.value) { left.value = true; nav.back() } }
  val leave = { if (doc.isDirty() && (doc.pages.isNotEmpty() || doc.saved != null)) asking = true else back() }
  BackHandler(!asking && !recentOpen && draft == null) {
    if (capture && doc.pages.isNotEmpty()) { retake = null; capture = false } else leave()
  }

  val somethingWentWrong = stringResource(R.string.error_something_went_wrong)
  val save: suspend () -> Unit = {
    try {
      val saved = exportPdf(doc)
      doc = saved
      back()
      saved.pdf?.let { openFile(it.path, it.name) }
    } catch (e: Exception) {
      failure = e.message ?: somethingWentWrong
    }
  }

  Box(Modifier.fillMaxSize().background(ScanUi.bg)) {
    // Review needs pages; with none, the camera is the only useful screen.
    AnimatedContent(capture || doc.pages.isEmpty(), label = "scan", transitionSpec = {
      (fadeIn(tween(260)) + scaleIn(tween(320), initialScale = 0.96f)) togetherWith fadeOut(tween(160))
    }) { camera ->
      if (camera) {
        Capture(
          doc, retake, paused = draft != null || asking || recentOpen,
          onAdd = { page -> doc = doc.copy(pages = doc.pages.indexOfFirst { it.id == page.id }.let { at -> if (at < 0) doc.pages + page else doc.pages.mapIndexed { j, q -> if (j == at) page else q } }) },
          onRecent = { recentOpen = true },
          onReview = { retake = null; capture = false },
          onClose = leave,
        )
      } else {
        Review(
          doc, { fn -> doc = fn(doc) }, onClose = leave, onSave = save,
          onAddMore = { retake = null; capture = true },
          onRetake = { retake = it; capture = true },
        )
      }
    }
  }

  if (recentOpen) RecentScans({ recentOpen = false }) { picked -> doc = picked; capture = false; recentOpen = false; draft = null }

  if (asking) {
    val pageOneAsk = stringResource(R.string.unit_page_one)
    val pageManyAsk = stringResource(R.string.unit_page_many)
    AppDialog(
      { asking = false }, if (doc.saved != null) stringResource(R.string.title_save_your_changes_q) else stringResource(R.string.title_save_this_scan_q),
      if (doc.saved != null) stringResource(R.string.msg_edited_after_saving) else stringResource(R.string.msg_pages_not_saved_as_pdf, plural(doc.pages.size, pageOneAsk, pageManyAsk)),
    ) {
      Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (doc.pages.isNotEmpty()) Btn(if (doc.saved != null) stringResource(R.string.action_update_pdf) else stringResource(R.string.action_save_pdf), Modifier.fillMaxWidth(), I.filePdfBox, busy = saving) {
          scope.launch { saving = true; save(); saving = false; asking = false }
        }
        Btn(stringResource(R.string.action_keep_as_draft), Modifier.fillMaxWidth(), I.contentSaveOutline, BtnKind.Secondary) { saveDoc(doc); asking = false; back() }
        Btn(if (doc.saved != null) stringResource(R.string.action_discard_changes) else stringResource(R.string.action_discard_scan), Modifier.fillMaxWidth(), I.deleteOutline, BtnKind.Danger) { discardChanges(doc); asking = false; back() }
        T(stringResource(R.string.msg_drafts_stay_in_recent_scans), Type.small, c.sub, Modifier.fillMaxWidth(), align = TextAlign.Center)
      }
    }
  }
  draft?.let { d ->
    val pageOneDraft = stringResource(R.string.unit_page_one)
    val pageManyDraft = stringResource(R.string.unit_page_many)
    AppDialog({ draft = null }, stringResource(R.string.title_continue_unsaved_scan_q), "${d.name} · ${plural(d.pages.size, pageOneDraft, pageManyDraft)}") {
      Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Btn(stringResource(R.string.action_continue_editing), Modifier.fillMaxWidth(), I.fileDocumentEditOutline) { doc = d; capture = false; draft = null }
        Btn(stringResource(R.string.action_start_new_scan), Modifier.fillMaxWidth(), I.cameraOutline, BtnKind.Secondary) { draft = null }
        Btn(if (d.saved != null) stringResource(R.string.action_discard_its_changes) else stringResource(R.string.action_discard_it), Modifier.fillMaxWidth(), I.deleteOutline, BtnKind.Danger) { discardChanges(d); draft = null }
      }
    }
  }
  if (failure.isNotEmpty()) {
    AppDialog({ failure = "" }, stringResource(R.string.title_could_not_save_pdf), failure) { Btn(stringResource(R.string.action_ok), Modifier.fillMaxWidth().padding(top = 18.dp), kind = BtnKind.Secondary) { failure = "" } }
  }
}

// ── Capture ─────────────────────────────────────────────────────────────────

private val MODES = listOf("whiteboard", "book", "doc", "id", "card")
private const val STEADY_MS = 1100L // corners must hold still this long before auto-capture fires
private const val NOT_FOUND_MS = 6000L
private fun moved(a: List<Float>, b: List<Float>) = a.indices.maxOf { abs(a[it] - b[it]) }

private class Tracker {
  var last: List<Float>? = null
  var shown: List<Float>? = null
  var misses = 0
  var since = 0L
  var armed = true
  var busy = false
  var seen = System.currentTimeMillis()
  var captured: List<Float>? = null
}

// Adobe Scan's capture screen: capture modes, live edge dots, auto-capture when steady, flash, gallery import, QR
// links, ID card front/back, and a thumbnail stack that opens the review.
@Composable
private fun Capture(
  doc: ScanDoc, retake: ScanPage?, paused: Boolean, onAdd: (ScanPage) -> Unit, onRecent: () -> Unit, onReview: () -> Unit, onClose: () -> Unit,
) {
  val context = LocalContext.current
  val owner = LocalLifecycleOwner.current
  val scope = rememberCoroutineScope()
  var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
  var denied by remember { mutableStateOf(false) }
  var kind by remember { mutableStateOf(retake?.kind ?: "doc") }
  var flash by remember { mutableStateOf("off") }
  var auto by remember { mutableStateOf(false) } // manual by default: the shutter fires only when tapped
  var corners by remember { mutableStateOf<List<Float>?>(null) }
  var steady by remember { mutableStateOf(false) }
  var lost by remember { mutableStateOf(false) }
  var busy by remember { mutableStateOf(false) }
  var front by remember { mutableStateOf<Shot?>(null) } // ID card: front captured, waiting for the back
  var selfie by remember { mutableStateOf(false) } // the front-facing lens instead of the back one
  val hasFrontLens = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT) }
  var qr by remember { mutableStateOf("") }
  val flashFx = remember { Animatable(0f) }
  val track = remember { Tracker() }
  val detecting = kind != "whiteboard"
  val camera = remember { DocCamera(context) }
  val latestPaused = remember { mutableStateOf(paused) }.apply { value = paused }
  val latestAuto = remember { mutableStateOf(auto) }.apply { value = auto }

  // Ask once each time the camera opens; after a permanent denial the button opens Settings instead.
  LaunchedEffect(Unit) {
    if (!granted) {
      granted = runCatching { awaitResult(ActivityResultContracts.RequestPermission(), Manifest.permission.CAMERA) }.getOrDefault(false)
      denied = !granted
    }
  }
  LaunchedEffect(kind) { // reset the auto-capture tracker when the mode changes
    front = null
    corners = null
    track.last = null; track.shown = null; track.misses = 0; track.since = 0; track.armed = true; track.seen = System.currentTimeMillis(); track.captured = null
  }
  camera.detecting = detecting
  camera.qr = kind == "doc"
  camera.flash = if (selfie) "off" else flash // the back camera's choice comes back when you flip back

  val addPhoto: suspend (Photo, String) -> Unit = { photo, as_ ->
    val shot = withContext(Dispatchers.IO) { makeShot(doc.id, photo, as_) }
    if (as_ == "id" && front == null) front = shot
    else {
      onAdd(ScanPage(retake?.id ?: uid(), as_, retake?.size ?: "fit", if (as_ == "id" && front != null) listOf(front!!, shot) else listOf(shot)))
      front = null
      if (retake != null) onReview()
    }
  }

  val shoot = {
    if (!track.busy) {
      track.busy = true // detection frames arriving mid-capture can't fire a second shot
      track.armed = false
      busy = true
      scope.launch {
        launch { flashFx.snapTo(1f); flashFx.animateTo(0f, tween(260)) }
        try {
          val photo = camera.takePicture()
          track.captured = photo.corners
          addPhoto(photo, kind)
        } catch (_: Exception) {}
        track.busy = false
        busy = false
      }
    }
  }

  val onDetect = { found0: List<Float>? ->
    val t = track
    val now = System.currentTimeMillis()
    var found = found0?.takeIf { it.size == 8 }
    // Steady outline: ease small movements, and ride out a couple of missed frames before letting go.
    val shownNow = t.shown
    if (found != null && shownNow != null && moved(found, shownNow) < 0.05f) found = found.mapIndexed { i, v -> shownNow[i] * 0.55f + v * 0.45f }
    if (found == null && t.shown != null && ++t.misses <= 3) Unit
    else {
      t.misses = 0
      t.shown = found
      corners = found
      if (found == null) {
        t.last = null; t.since = 0; t.armed = true // page left the frame: ready for the next one
        steady = false
        lost = now - t.seen > NOT_FOUND_MS
      } else {
        t.seen = now
        lost = false
        if (t.captured?.let { moved(found, it) > 0.12f } == true) t.armed = true // a different page is in view
        if (t.last == null || moved(found, t.last!!) > 0.02f) t.since = now
        t.last = found
        steady = now - t.since > 250 && latestAuto.value && t.armed
        if (latestAuto.value && !latestPaused.value && t.armed && now - t.since > STEADY_MS) shoot() // never behind a dialog
      }
    }
  }

  DisposableEffect(granted, selfie) {
    val main = ContextCompat.getMainExecutor(context)
    camera.onDetect = { c -> main.execute { onDetect(c) } }
    camera.onQr = { s -> main.execute { qr = s } }
    corners = null // the old lens's outline doesn't belong on the new one
    if (granted) scope.launch { runCatching { camera.bind(owner, selfie) } }
    onDispose { }
  }
  DisposableEffect(Unit) { onDispose { camera.release() } }

  val importPhotos = {
    scope.launch {
      val picked = pickImages(retake == null)
      if (picked.isEmpty()) return@launch
      busy = true
      val as_ = if (kind == "id") "card" else kind // one gallery photo can't be both sides
      var added = 0
      for (a in picked) {
        try {
          addPhoto(detectPage(a.path), as_) // auto-crop imported photos, like Adobe's import
          added++
        } catch (_: Exception) {}
      }
      busy = false
      if (added > 0 && retake == null) onReview() // a retake already returned to review
    }
    Unit
  }

  val status = when {
    !detecting -> stringResource(R.string.status_fit_whiteboard)
    busy -> stringResource(R.string.status_capturing)
    kind == "id" -> if (front != null) stringResource(R.string.status_turn_card_over) else stringResource(R.string.status_capture_front_of_card)
    !auto -> stringResource(R.string.status_line_up_and_tap)
    steady -> stringResource(R.string.status_capturing_hold_steady)
    lost -> stringResource(R.string.status_no_document_found)
    corners != null -> stringResource(R.string.status_hold_steady)
    else -> stringResource(R.string.status_looking_for_document)
  }

  Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
      Tap(I.close, stringResource(R.string.action_close), onClick = if (doc.pages.isNotEmpty()) onReview else onClose)
      if (retake != null) T(stringResource(R.string.label_retake_page), Type.bold.copy(fontSize = 16.sp), ScanUi.text, Modifier.weight(1f), align = TextAlign.Center) else Spacer(Modifier.weight(1f))
      if (retake == null && doc.pages.isEmpty()) Tap(I.history, stringResource(R.string.label_recent_scans), onClick = onRecent)
      // Flash stays in its place whatever the lens, so nothing shifts when flipping: front lenses have no flash on most
      // phones, so there it's dimmed and off.
      val (next, glyph) = when (flash) { "off" -> "auto" to I.flashOff; "auto" -> "on" to I.flashAuto; else -> "off" to I.flash }
      val flashLabel = stringResource(when (flash) { "off" -> R.string.acc_flash_off; "auto" -> R.string.acc_flash_auto; else -> R.string.acc_flash_on })
      Box(Modifier.graphicsLayer { alpha = if (selfie) 0.35f else 1f }) {
        Tap(if (selfie) I.flashOff else glyph, if (selfie) stringResource(R.string.acc_no_flash_front_camera) else flashLabel) { if (!selfie) flash = next }
      }
      if (hasFrontLens) Tap(I.cameraFlipOutline, if (selfie) stringResource(R.string.acc_switch_to_back_camera) else stringResource(R.string.acc_switch_to_front_camera)) { selfie = !selfie }
      if (detecting) {
        val tint by animateColorAsState(if (auto) ScanUi.accent else ScanUi.text, label = "auto")
        Row(Modifier.tap(scaleTo = 0.92f) { auto = !auto }.padding(horizontal = 10.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
          Icon(if (auto) I.cameraIris else I.gestureTap, 20.dp, tint)
          Spacer(Modifier.size(4.dp))
          T(if (auto) stringResource(R.string.label_auto) else stringResource(R.string.label_manual), Type.med, tint)
        }
      }
    }

    // The preview fills all the space, cropping the 3:4 camera frame at its edges like a camera app (the photo keeps
    // the whole frame). The page outline is in fractions of that full frame, so it's mapped through the same crop.
    Box(Modifier.fillMaxWidth().weight(1f)) {
      Box(Modifier.fillMaxSize().background(ScanUi.panel)) {
        if (granted) {
          AndroidView({ camera.preview }, Modifier.fillMaxSize())
          corners?.let { cs ->
            Canvas(Modifier.fillMaxSize()) {
              val fw = max(size.width, size.height * 3 / 4) // the frame as shown, before the crop
              val fh = fw * 4 / 3
              val ox = (size.width - fw) / 2
              val oy = (size.height - fh) / 2
              val pts = (0 until 4).map { Offset(ox + cs[it * 2] * fw, oy + cs[it * 2 + 1] * fh) }
              val path = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) }; close() }
              drawPath(path, ScanUi.accentSoft)
              drawPath(path, ScanUi.accent, style = Stroke(2.5.dp.toPx()))
              pts.forEach { drawCircle(Color.White, 9.dp.toPx(), it); drawCircle(ScanUi.accent, 9.dp.toPx(), it, style = Stroke(4.dp.toPx())) }
            }
          }
        } else {
          Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(I.cameraOffOutline, 36.dp, ScanUi.dim)
            T(stringResource(R.string.msg_allow_camera_access), Type.item, ScanUi.text, Modifier.padding(vertical = 12.dp), align = TextAlign.Center)
            T(
              if (denied) stringResource(R.string.action_open_settings) else stringResource(R.string.action_allow_camera), Type.bold, ScanUi.onAccent,
              Modifier.tap(shape = CircleShape) {
                if (denied) context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                else scope.launch { granted = runCatching { awaitResult(ActivityResultContracts.RequestPermission(), Manifest.permission.CAMERA) }.getOrDefault(false); denied = !granted }
              }.background(ScanUi.accent, CircleShape).padding(horizontal = 22.dp, vertical = 10.dp),
            )
          }
        }
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = flashFx.value }.background(Color.White))

        val statusBg by animateColorAsState(if (steady) ScanUi.accent else ScanUi.scrim, label = "status")
        val statusFg by animateColorAsState(if (steady) ScanUi.onAccent else Color.White, label = "statusText")
        T(status, Type.med, statusFg, Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp).background(statusBg, CircleShape).padding(horizontal = 16.dp, vertical = 8.dp))

        androidx.compose.animation.AnimatedVisibility(front != null, Modifier.align(Alignment.TopCenter), enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut()) {
          Row(Modifier.padding(12.dp).fillMaxWidth().background(ScanUi.scrim, RoundedCornerShape(Radius.control)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            val img = rememberBitmap(front?.src, 200)
            Box(Modifier.size(56.dp, 40.dp).clip(RoundedCornerShape(4.dp)).background(ScanUi.raised)) { if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
              T(stringResource(R.string.msg_front_captured), Type.bold, ScanUi.text)
              T(stringResource(R.string.msg_now_capture_back), Type.small, ScanUi.dim)
            }
            Row(Modifier.tap(shape = CircleShape) { front = null }.background(ScanUi.raised, CircleShape).padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
              Icon(I.cameraRetakeOutline, 16.dp, ScanUi.text)
              Spacer(Modifier.size(5.dp))
              T(stringResource(R.string.action_retake), Type.med, ScanUi.text)
            }
          }
        }

        androidx.compose.animation.AnimatedVisibility(qr.isNotEmpty() && front == null, Modifier.align(Alignment.TopCenter), enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut()) {
          val link = Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(qr)
          Row(Modifier.padding(12.dp).fillMaxWidth().background(Color.White, RoundedCornerShape(Radius.control)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(I.qrcode, 22.dp, ScanUi.accent)
            T(qr, Type.med, Color(0xFF111111), Modifier.weight(1f).padding(horizontal = 10.dp), maxLines = 2)
            T(if (link) stringResource(R.string.action_open) else stringResource(R.string.action_share), Type.bold, ScanUi.accent, Modifier.tap { if (link) openUrl(qr) else shareText(qr) })
            Spacer(Modifier.size(10.dp))
            Box(Modifier.tap(scaleTo = 0.85f) { qr = "" }) { Icon(I.close, 18.dp, Color(0xFF555555)) }
          }
        }
      }
    }

    if (retake == null) {
      Row(
        Modifier.fillMaxWidth().height(58.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(26.dp), verticalAlignment = Alignment.CenterVertically,
      ) {
        MODES.forEach { m ->
          val on = m == kind
          val color by animateColorAsState(if (on) ScanUi.accent else ScanUi.text, label = "mode")
          Column(Modifier.tap(scaleTo = 0.92f) { kind = m }, horizontalAlignment = Alignment.CenterHorizontally) {
            T(KIND_LABEL.getValue(m), (if (on) Type.bold else Type.med).copy(fontSize = 15.5.sp), color)
            val dot by animateFloatAsState(if (on) 1f else 0f, spring(0.5f), label = "dot")
            Box(Modifier.padding(top = 3.dp).size(5.dp).graphicsLayer { scaleX = dot; scaleY = dot }.background(ScanUi.accent, CircleShape))
          }
        }
      }
    }

    Row(Modifier.fillMaxWidth().height(110.dp).padding(bottom = 6.dp), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
      Tap(I.imageMultipleOutline, stringResource(R.string.acc_import_from_gallery), 28, onClick = importPhotos)
      val inner by animateColorAsState(if (steady) ScanUi.accent else Color.White, label = "shutter")
      Box(
        Modifier.size(78.dp).tap(enabled = !busy && granted, shape = CircleShape, scaleTo = 0.9f, label = stringResource(R.string.acc_capture), onClick = shoot).border(4.dp, Color.White, CircleShape),
        contentAlignment = Alignment.Center,
      ) { if (busy) Spinner(ScanUi.accent) else Box(Modifier.size(62.dp).background(inner, CircleShape)) }
      val last = doc.pages.takeLast(3)
      Box(Modifier.size(56.dp, 70.dp).tap(enabled = doc.pages.isNotEmpty(), label = stringResource(R.string.acc_review_n_pages, doc.pages.size), onClick = onReview), contentAlignment = Alignment.Center) {
        last.forEachIndexed { i, p ->
          val img = rememberBitmap(p.shots[0].out ?: p.shots[0].src, 200)
          Box(
            Modifier.size(46.dp, 60.dp).graphicsLayer { rotationZ = (i - last.size + 1) * 7f }.clip(RoundedCornerShape(4.dp)).background(ScanUi.raised)
              .border(1.5.dp, Color.White, RoundedCornerShape(4.dp)),
          ) { if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        if (doc.pages.isNotEmpty()) {
          T("${doc.pages.size}", Type.label.copy(fontSize = 12.sp), ScanUi.onAccent, Modifier.align(Alignment.TopEnd).offset(6.dp, (-4).dp).background(ScanUi.accent, CircleShape).padding(horizontal = 7.dp, vertical = 1.dp))
        }
      }
    }
  }
}

@Composable
fun Tap(glyph: Int, label: String, size: Int = 24, tint: Color = ScanUi.text, onClick: () -> Unit) {
  Box(Modifier.size(48.dp).tap(shape = CircleShape, scaleTo = 0.85f, label = label, onClick = onClick), contentAlignment = Alignment.Center) { Icon(glyph, size.dp, tint, label = label) }
}

// ── Recent scans ────────────────────────────────────────────────────────────

// Reachable from the camera's top bar: reopen a scan to keep editing, or delete it.
@Composable
private fun RecentScans(onClose: () -> Unit, onPick: (ScanDoc) -> Unit) {
  var docs by remember { mutableStateOf(listDocs()) }
  var deleting by remember { mutableStateOf<ScanDoc?>(null) }
  FullSheet(onClose, ScanUi.bg) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
      Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Tap(I.close, stringResource(R.string.action_close), onClick = onClose)
        T(stringResource(R.string.label_recent_scans), Type.bold.copy(fontSize = 16.sp), ScanUi.text, Modifier.weight(1f), align = TextAlign.Center)
        Spacer(Modifier.size(48.dp))
      }
      if (docs.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
          Icon(I.lineScan, 48.dp, ScanUi.dim)
          T(stringResource(R.string.msg_scans_appear_here), Type.body, ScanUi.dim, Modifier.padding(top = 12.dp), align = TextAlign.Center)
        }
      } else Column(Modifier.verticalScroll(rememberScrollState())) {
        val pageOneRS = stringResource(R.string.unit_page_one)
        val pageManyRS = stringResource(R.string.unit_page_many)
        val draftLabel = stringResource(R.string.label_draft)
        val unsavedChangesLabel = stringResource(R.string.label_unsaved_changes)
        val savedLabel = stringResource(R.string.label_saved)
        docs.forEachIndexed { i, d ->
          Stagger(i) {
            Row(Modifier.fillMaxWidth().tap(pressed = ScanUi.panel) { onPick(d) }.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
              val first = d.pages.firstOrNull()?.shots?.firstOrNull()
              val img = rememberBitmap(first?.let { it.out ?: it.src }, 200)
              Box(Modifier.size(40.dp, 52.dp).clip(RoundedCornerShape(4.dp)).background(ScanUi.raised)) { if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
              Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                T(d.name, Type.item, ScanUi.text, maxLines = 1)
                T("${plural(d.pages.size, pageOneRS, pageManyRS)} · ${if (d.saved == null) draftLabel else if (d.isDirty()) unsavedChangesLabel else savedLabel} · ${fmtDate(d.modified)}", Type.small, ScanUi.dim)
              }
              d.pdf?.let { pdf -> Tap(I.filePdfBox, stringResource(R.string.acc_open_pdf), 22) { openFile(pathOf(pdf.path), pdf.name) } }
              Tap(I.deleteOutline, stringResource(R.string.acc_delete_scan), 22, ScanUi.dim) { deleting = d }
            }
          }
        }
      }
    }
    deleting?.let { d ->
      ConfirmDialog(stringResource(R.string.title_delete_this_scan_q), stringResource(R.string.msg_delete_scan_body), stringResource(R.string.action_delete), danger = true, onDismiss = { deleting = null }) {
        deleteDoc(d.id)
        docs = listDocs()
      }
    }
  }
}
