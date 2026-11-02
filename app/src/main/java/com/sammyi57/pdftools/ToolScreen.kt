package com.sammyi57.pdftools

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.math.hypot
import kotlin.math.min

/** What an editor gets: the chosen PDF, the tool's options, an updater for them, and a way to report a problem. */
class EditorProps(val file: Picked?, val value: Opts, val onChange: ((Opts) -> Opts) -> Unit, val onError: (String) -> Unit)

private val editorTitlesCache = PerLocale { mapOf(
  "organize" to appContext.getString(R.string.editor_title_arrange_pages), "workflow" to appContext.getString(R.string.editor_title_add_steps),
  "redact" to appContext.getString(R.string.editor_title_mark_blackout), "crop" to appContext.getString(R.string.editor_title_crop_or_resize),
  "fillSign" to appContext.getString(R.string.editor_title_fill_and_sign),
  "watermark" to appContext.getString(R.string.editor_title_options), "metadata" to appContext.getString(R.string.editor_title_details),
) }
private val EDITOR_TITLES: Map<String, String> get() = editorTitlesCache.get()

@Composable
private fun Editor(id: String, p: EditorProps) = when (id) {
  "organize" -> OrganizeEditor(p)
  "workflow" -> WorkflowEditor(p)
  "watermark" -> WatermarkEditor(p)
  "redact" -> RedactEditor(p)
  "crop" -> PdfCropEditor(p)
  "fillSign" -> FillSignEditor(p)
  "metadata" -> DetailsEditor(p)
  else -> Unit
}

private val detailFieldsCache = PerLocale { listOf(
  OptDef.Text("title", appContext.getString(R.string.opt_detail_title_label), "", appContext.getString(R.string.opt_detail_title_placeholder)),
  OptDef.Text("author", appContext.getString(R.string.opt_detail_author_label), "", appContext.getString(R.string.opt_detail_author_placeholder)),
  OptDef.Text("subject", appContext.getString(R.string.opt_detail_subject_label), "", appContext.getString(R.string.opt_detail_subject_placeholder)),
  OptDef.Text("keywords", appContext.getString(R.string.opt_detail_keywords_label), "", appContext.getString(R.string.opt_detail_keywords_placeholder)),
) }
private val DETAIL_FIELDS: List<OptDef> get() = detailFieldsCache.get()

// The fields start as what the PDF says now, read once per file ("for" remembers which), so a resumed draft keeps
// what was typed.
@Composable
private fun DetailsEditor(p: EditorProps) {
  val file = p.file ?: return T(stringResource(R.string.msg_choose_pdf_for_details), Type.small, c.sub)
  val readErr = stringResource(R.string.error_pdf_unreadable_short)
  LaunchedEffect(file.path) {
    if (p.value.str("for") == file.path) return@LaunchedEffect
    try {
      val now = withContext(Dispatchers.IO) { readDetails(File(file.path).readBytes()) }
      p.onChange { v -> now.entries.fold(v.with("for", file.path)) { acc, (k, s) -> acc.with(k, s) } }
    } catch (e: Exception) {
      p.onError(e.message ?: readErr)
    }
  }
  Options(DETAIL_FIELDS, p.value) { v -> p.onChange { v } }
  T(stringResource(R.string.msg_leave_field_empty_to_remove), Type.small, c.sub)
}

private fun stripPdf(name: String) = name.replace(Regex("\\.pdf$", RegexOption.IGNORE_CASE), "")

@Composable
fun ToolScreen(id: String, resume: Boolean) {
  val nav = LocalNav.current
  val tool = toolById(id)
  if (tool == null) {
    LaunchedEffect(Unit) { nav.back() }
    return
  }
  val scope = rememberCoroutineScope()
  val draft = remember { Drafts.current?.takeIf { resume && it.tool == tool.id } }
  var files by remember { mutableStateOf<List<Picked>>(draft?.files?.map { it.toPicked() } ?: emptyList()) }
  var opts by remember { mutableStateOf<Opts>(draft?.opts ?: defaults(tool.opts)) }
  var busy by remember { mutableStateOf("") } // "run" | "preview"
  var error by remember { mutableStateOf("") }
  var results by remember { mutableStateOf<List<Saved>>(emptyList()) }
  var appFiles by remember { mutableStateOf(false) }
  var locked by remember { mutableStateOf<List<Picked>>(emptyList()) }
  var naming by remember { mutableStateOf<Pair<List<Output>, String>?>(null) }
  var parts by remember { mutableStateOf<List<Named>?>(null) } // a multi-file preview
  val multi = tool.input == Input.Pdfs
  val ready = files.size >= (if (multi) tool.min ?: 2 else 1)
  val hasEditor = tool.id in EDITOR_TITLES

  // Autosave while there's unfinished work, kept after going back or closing the app; finishing or clearing drops it.
  // Only touches the saved draft once this screen has work of its own, so peeking at a tool keeps an older draft.
  val owns = remember { mutableStateOf(draft != null) }
  LaunchedEffect(files, opts, results) {
    if (files.isNotEmpty() && results.isEmpty()) {
      owns.value = true
      val kept = if (opts.containsKey("password")) opts.with("password", "") else opts // never on disk
      Drafts.set(ToolDraft(tool.id, files.map { it.toDraft() }, kept))
    } else if (owns.value) Drafts.set(null)
  }

  val changeFiles = { next: List<Picked> -> files = next; results = emptyList(); error = "" }
  val append = { more: List<Picked> -> files = if (multi) files + more else more; results = emptyList(); error = "" }

  // Encrypted PDFs are swapped for decrypted copies before any tool reads them. Those that need a password wait in
  // `locked` and are asked for one at a time.
  val addFiles = { picked: List<Picked> ->
    scope.launch {
      val ok = ArrayList<Picked>()
      val need = ArrayList<Picked>()
      for (p in if (multi) picked else picked.take(1)) {
        val path = withContext(Dispatchers.IO) { runCatching { unlockPdf(p.path, "") }.getOrElse { p.path } } // unreadable: let the tool explain
        if (path != null) ok.add(p.copy(path = path)) else need.add(p)
      }
      if (ok.isNotEmpty()) append(ok)
      locked = need
    }
    Unit
  }
  val pickDevice = { scope.launch { addFiles(pickPdfs(multi)) }; Unit }

  val errTooBig = stringResource(R.string.error_pdf_too_big)
  val errSomethingWrongPdf = stringResource(R.string.error_something_wrong_with_pdf)
  val exec = { mode: String ->
    scope.launch {
      busy = mode
      error = ""
      results = emptyList()
      try {
        val outs = withContext(Dispatchers.Default) {
          val inputs = files.map { Named(it.name, File(it.path).readBytes()) }
          tool.run(inputs, opts)
        }
        val base = stripPdf(files[0].name)
        val nameOf = { o: Output -> if (o.full) o.name else if (o.name.isNotEmpty()) "$base-${o.name}" else base }
        when {
          mode == "preview" && outs.size == 1 -> withContext(Dispatchers.IO) { saveTemp("${nameOf(outs[0])} (preview).pdf", outs[0].bytes) }.let { openFile(it.path, it.name) }
          mode == "preview" -> parts = outs.map { Named(nameOf(it), it.bytes) } // several files: pick which one to look at
          outs.all { it.full } -> results = withContext(Dispatchers.IO) { outs.map { saveBytes(it.name, it.ext, it.bytes) } } // already named per input
          else -> naming = outs to if (outs.size == 1) nameOf(outs[0]) else base
        }
      } catch (e: UserError) {
        error = e.message ?: ""
      } catch (_: OutOfMemoryError) {
        error = errTooBig
      } catch (e: Exception) {
        error = e.message ?: errSomethingWrongPdf
      }
      busy = ""
    }
    Unit
  }

  // Saving asks for a name first, filled in with the usual one ("Report-compressed"). For several outputs (splits) the
  // name is the start of each part's name.
  // Takes the outputs, not `naming`: the dialog clears `naming` before it calls this.
  val saveAs = { outs: List<Output>, typed: String ->
    val name = typed.replace(Regex("\\.(pdf|zip)$", RegexOption.IGNORE_CASE), "")
    naming = null
    scope.launch {
      results = withContext(Dispatchers.IO) {
        outs.map { o -> saveBytes(if (outs.size == 1) name else if (o.full) o.name else if (o.name.isNotEmpty()) "$name-${o.name}" else name, o.ext, o.bytes) }
      }
    }
    Unit
  }

  Screen(bottom = 40.dp) {
    BackBar(stringResource(R.string.label_tools)) { nav.back() }
    // The tool's own glow card, like Home's: its picture on a Cool Blue tile (drawn in light colours whatever the theme,
    // so it reads on the dark card), the name in white and the description in Cool Blue.
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.card)).glow(c.hero, 0.95f, 1.15f).padding(18.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        CompositionLocalProvider(LocalPalette provides LightPalette) { ArtTile(tool.id, 64.dp, Modifier.sharedArt(tool.id), bg = Brand.cool) }
        Spacer(Modifier.size(14.dp))
        T(tool.title, Type.title.copy(fontSize = 23.sp, lineHeight = 30.sp), Color.White, Modifier.weight(1f))
        val pinned = tool.id in Pins.ids
        IconBtn(if (pinned) I.pin else I.pinOutline, 22.dp, if (pinned) Brand.tangerine else Color.White, if (pinned) stringResource(R.string.acc_unpin_from_home) else stringResource(R.string.acc_pin_to_top_of_home)) { Pins.toggle(tool.id) }
      }
      T(tool.desc, Type.body, Brand.cool.copy(alpha = 0.9f), Modifier.padding(top = 14.dp))
    }

    StepHead(1, if (multi) stringResource(R.string.title_choose_pdfs) else stringResource(R.string.title_choose_pdf))
    if (files.isEmpty()) {
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PickerCard(stringResource(R.string.label_device_storage), stringResource(R.string.msg_drive_downloads_phone), I.fileDocumentOutline, Brand.cool, Brand.cherry, Modifier.weight(1f), pickDevice)
        PickerCard(stringResource(R.string.label_app_files), if (Backup.state.enabled) stringResource(R.string.msg_saved_here_or_in_drive) else stringResource(R.string.msg_saved_in_pdf_tools), I.folderOutline, c.accentSoft, c.accent, Modifier.weight(1f)) { appFiles = true }
      }
    } else {
      Group(Modifier.animateContentSize()) {
        if (multi && files.size > 1) {
          Reorderable(files, { it.path }, changeFiles) { f, i, _ -> Box(Modifier.background(c.surface)) { FileRow(f, i, true, files.size, changeFiles, files) } }
        } else files.forEachIndexed { i, f -> FileRow(f, i, false, files.size, changeFiles, files) }
      }
      if (multi && files.size > 1) T(stringResource(R.string.msg_use_arrows_or_drag), Type.small, c.sub, Modifier.padding(top = 8.dp))
      if (multi) {
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          Btn(stringResource(R.string.label_device), Modifier.weight(1f), I.plus, BtnKind.Secondary, height = 46.dp, onClick = pickDevice)
          Btn(stringResource(R.string.label_app_files), Modifier.weight(1f), I.folderOutline, BtnKind.Secondary, height = 46.dp) { appFiles = true }
        }
      }
    }

    if (hasEditor || tool.opts.isNotEmpty()) {
      StepHead(2, EDITOR_TITLES[tool.id] ?: stringResource(R.string.editor_title_options))
      if (hasEditor) Editor(tool.id, EditorProps(files.firstOrNull(), opts, { fn -> opts = fn(opts) }) { error = it })
      else Options(tool.opts, opts) { opts = it }
    }

    Btn(
      if (busy == "run") stringResource(R.string.msg_doing_ellipsis, tool.doing) else tool.cta, Modifier.fillMaxWidth().padding(top = 28.dp), kind = BtnKind.Accent, busy = busy == "run",
      enabled = ready && busy != "preview",
    ) { exec("run") }
    if (!tool.noPreview) {
      Btn(
        if (busy == "preview") stringResource(R.string.msg_preparing_preview) else stringResource(R.string.action_preview_result), Modifier.fillMaxWidth().padding(top = 10.dp), I.eyeOutline, BtnKind.Secondary,
        busy = busy == "preview", enabled = ready && busy != "run",
      ) { exec("preview") }
    }

    AnimatedVisibility(error.isNotEmpty(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
      Row(Modifier.padding(top = 14.dp).fillMaxWidth().background(c.accentSoft, RoundedCornerShape(Radius.control)).padding(12.dp)) {
        Icon(I.alertCircleOutline, 18.dp, c.accent)
        Spacer(Modifier.size(8.dp))
        T(error, Type.small.copy(fontSize = 14.sp), c.accentInk)
      }
    }

    AnimatedVisibility(results.isNotEmpty(), enter = expandVertically() + fadeIn(), exit = fadeOut()) {
      Column {
        GroupLabel(if (results.size > 1) stringResource(R.string.msg_files_saved_count, results.size) else stringResource(R.string.label_saved), stringResource(R.string.action_open_files), { nav.show(Tab.Files) })
        val shareLabel = stringResource(R.string.action_share)
        Group {
          results.forEachIndexed { i, r ->
            ListRow(
              stem(r.name), first = i == 0, lead = { DocGlyph(kindOf(r.name)) }, marquee = true, onClick = { openFile(r.path, r.name) },
              subtitle = if (results.size == 1 && files.size == 1 && files[0].size != null) "${fmtSize(files[0].size)} → ${fmtSize(r.size)}" else fmtSize(r.size),
            ) {
              T(shareLabel, Type.label, modifier = Modifier.tap(shape = CircleShape, scaleTo = 0.92f) { share(r.path, r.name) }.border(1.dp, c.lineStrong, CircleShape).padding(horizontal = 14.dp, vertical = 7.dp))
            }
          }
        }
        if (results.size > 1) {
          Btn(stringResource(R.string.action_share_all_as_zip), Modifier.fillMaxWidth().padding(top = 10.dp), I.folderZipOutline, BtnKind.Secondary) {
            scope.launch {
              val z = withContext(Dispatchers.IO) { saveBytes("${files.firstOrNull()?.name?.let(::stripPdf) ?: "export"}-all", "zip", zipFiles(results.map { Named(it.name, File(it.path).readBytes()) })) }
              share(z.path, z.name)
            }
          }
        }
        T(stringResource(R.string.action_use_another_file), Type.item, c.accent, Modifier.fillMaxWidth().padding(top = 18.dp).tap { changeFiles(emptyList()) }.padding(vertical = 4.dp), align = TextAlign.Center)
      }
    }
  }

  if (appFiles) AppFilesPicker(multi, files.map { it.path }, { appFiles = false }) { picked -> addFiles(if (multi) picked.filter { p -> files.none { it.path == p.path } } else picked) }
  locked.firstOrNull()?.let { f ->
    PasswordDialog(f, { path -> append(listOf(f.copy(path = path))); locked = locked.drop(1) }) { locked = locked.drop(1) }
  }
  parts?.let { list ->
    // Preview of a tool that makes several files: one temp file at a time, so parts open one at a time.
    AppDialog({ parts = null }, stringResource(R.string.title_preview), stringResource(R.string.msg_this_makes_n_files, plural(list.size, stringResource(R.string.unit_file_one), stringResource(R.string.unit_file_many)))) {
      Group(Modifier.padding(top = 14.dp)) {
        list.forEachIndexed { i, p ->
          ListRow(stem(p.name), first = i == 0, lead = { DocGlyph() }, subtitle = fmtSize(p.bytes.size.toLong()), marquee = true, chevron = true, onClick = {
            scope.launch { withContext(Dispatchers.IO) { saveTemp("${p.name} (preview).pdf", p.bytes) }.let { openFile(it.path, it.name) } }
          })
        }
      }
    }
  }
  naming?.let { (outs, name) ->
    // Only Discard throws the result away; back or a stray tap outside keeps it under the suggested name.
    PromptDialog(
      stringResource(R.string.title_save_as), name, if (outs.size > 1) stringResource(R.string.msg_each_file_starts_with_name, outs.size) else stringResource(R.string.msg_saved_in_files), stringResource(R.string.hint_file_name), stringResource(R.string.action_save), stringResource(R.string.action_discard),
      onClose = { naming = null }, onDismiss = { saveAs(outs, name) }, onSubmit = { saveAs(outs, it) },
    )
  }
}

@Composable
private fun PickerCard(title: String, sub: String, glyph: Int, tileBg: Color, tileFg: Color, modifier: Modifier, onClick: () -> Unit) {
  val line = c.lineStrong
  val shape = RoundedCornerShape(Radius.group)
  Column(
    modifier.tap(pressed = c.sunken, shape = shape, scaleTo = 0.96f, onClick = onClick)
      .drawBehind {
        drawRoundRect(line, cornerRadius = CornerRadius(16.dp.toPx()), style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
      }
      .padding(16.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Box(Modifier.size(46.dp).background(tileBg, RoundedCornerShape(Radius.control)), contentAlignment = Alignment.Center) { Icon(glyph, 26.dp, tileFg) }
    T(title, Type.bold.copy(fontSize = 14.5.sp), modifier = Modifier.padding(top = 8.dp))
    T(sub, Type.small, c.sub, Modifier.padding(top = 2.dp), align = TextAlign.Center)
  }
}

@Composable
private fun FileRow(f: Picked, i: Int, draggable: Boolean, count: Int, set: (List<Picked>) -> Unit, files: List<Picked>) {
  ListRow(
    f.name, first = i == 0, subtitle = fmtSize(f.size), marquee = true,
    lead = {
      Row(verticalAlignment = Alignment.CenterVertically) {
        if (draggable) T("${i + 1}", Type.label, c.sub, Modifier.width(18.dp), align = TextAlign.Center)
        if (draggable) Spacer(Modifier.size(8.dp))
        DocGlyph()
      }
    },
  ) {
    IconBtn(I.close, label = stringResource(R.string.action_remove)) { set(files.filter { it !== f }) }
    if (draggable) {
      IconBtn(I.chevronUp, color = if (i == 0) c.faint else c.ink, label = stringResource(R.string.acc_move_earlier)) { set(files.swap(i, i - 1)) }
      IconBtn(I.chevronDown, color = if (i == count - 1) c.faint else c.ink, label = stringResource(R.string.acc_move_later)) { set(files.swap(i, i + 1)) }
    }
  }
}

// Asks for the password of a picked PDF that needs one; a wrong password keeps the dialog open.
@Composable
private fun PasswordDialog(file: Picked, onUnlock: (String) -> Unit, onSkip: () -> Unit) {
  val scope = rememberCoroutineScope()
  var password by remember(file.path) { mutableStateOf("") }
  var shown by remember { mutableStateOf(false) }
  var wrong by remember(file.path) { mutableStateOf(false) }
  var busy by remember { mutableStateOf(false) }
  val submit = {
    if (password.isNotEmpty() && !busy) scope.launch {
      busy = true
      val path = withContext(Dispatchers.IO) { runCatching { unlockPdf(file.path, password) }.getOrNull() }
      busy = false
      if (path != null) onUnlock(path) else wrong = true
    }
    Unit
  }
  AppDialog(onSkip, stringResource(R.string.title_password_protected_pdf), stringResource(R.string.msg_enter_password_to_use_here, stem(file.name))) {
    Field(
      password, { password = it; wrong = false }, Modifier.padding(top = 14.dp), stringResource(R.string.opt_password_label), secret = !shown, error = wrong, onDone = submit,
    ) { IconBtn(if (shown) I.eyeOffOutline else I.eyeOutline, color = c.sub, label = if (shown) stringResource(R.string.acc_hide_password) else stringResource(R.string.acc_show_password)) { shown = !shown } }
    if (wrong) T(stringResource(R.string.error_wrong_password), Type.small, c.accent, Modifier.padding(top = 6.dp))
    Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Btn(stringResource(R.string.action_skip), Modifier.weight(1f), kind = BtnKind.Secondary, onClick = onSkip)
      Btn(stringResource(R.string.action_unlock), Modifier.weight(1f), busy = busy, enabled = password.isNotEmpty(), onClick = submit)
    }
  }
}

// ── Organize ────────────────────────────────────────────────────────────────

@Composable
private fun OrganizeEditor(p: EditorProps) {
  val file = p.file
  if (file == null) {
    T(stringResource(R.string.msg_pages_appear_once_chosen), Type.small, c.sub)
    return
  }
  var pages by remember(file.path) { mutableStateOf<List<Pair<Int, Int>>?>(null) }
  var thumbs by remember(file.path) { mutableStateOf<List<Thumb>?>(null) }
  val order = p.value.get<List<PageOrder>>("order") ?: emptyList()
  val set = { o: List<PageOrder> -> p.onChange { it.put("order", o) } }
  val restored = remember { mutableStateOf(order.isNotEmpty()) } // a resumed draft's order survives the first load
  val readErr = stringResource(R.string.error_pdf_unreadable_or_protected)

  LaunchedEffect(file.path) {
    // Page sizes come from Android's PDF renderer, which reads a big PDF without parsing it all.
    try {
      val sizes = withContext(Dispatchers.IO) { pageSizes(file.path) }
      pages = sizes
      if (restored.value) restored.value = false else set(sizes.indices.map { PageOrder(it) })
    } catch (_: Exception) {
      p.onError(readErr)
    }
    thumbs = withContext(Dispatchers.IO) { runCatching { renderThumbs(file.path, 200) }.getOrNull() }
  }
  val sizes = pages
  if (sizes == null) {
    Spinner(modifier = Modifier.padding(vertical = 24.dp))
    return
  }
  val edit = { fn: (List<PageOrder>) -> List<PageOrder> -> p.onChange { v -> v.put("order", fn(v.get<List<PageOrder>>("order") ?: emptyList())) } }
  Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.Top) {
    T(stringResource(R.string.msg_keeping_x_of_y_pages, order.count { !it.del }, plural(sizes.size, stringResource(R.string.unit_page_one), stringResource(R.string.unit_page_many))), Type.small, c.sub, Modifier.weight(1f))
    T(stringResource(R.string.action_reset), Type.med, c.accent, Modifier.padding(start = 12.dp).tap(scaleTo = 0.92f) { set(sizes.indices.map { PageOrder(it) }) })
  }
  Reorderable(order, { it.i }, set, columns = 2, gap = 10.dp) { o, k, _ ->
    OrganizeTile(o, k, sizes.getOrNull(o.i), thumbs?.getOrNull(o.i),
      onRotate = { edit { l -> l.map { if (it.i == o.i) it.copy(rot = (it.rot + 90) % 360) else it } } },
      onDelete = { edit { l -> l.map { if (it.i == o.i) it.copy(del = !it.del) else it } } }, // toggles, so a page can come back
    )
  }
}

private val AREA_H = 180.dp

@Composable
private fun OrganizeTile(o: PageOrder, k: Int, size: Pair<Int, Int>?, thumb: Thumb?, onRotate: () -> Unit, onDelete: () -> Unit) {
  val shape = RoundedCornerShape(Radius.control)
  // Turns smoothly to its new angle, the long way round never: 270 -> 360 instead of back to 0.
  val target = remember { mutableStateOf(o.rot.toFloat()) }
  if (((target.value % 360) + 360) % 360 != o.rot.toFloat()) target.value += ((o.rot - target.value % 360 + 360) % 360)
  val turn by animateFloatAsState(target.value, spring(0.7f), label = "turn")
  val faded by animateFloatAsState(if (o.del) 0.3f else 1f, label = "del")
  Column(Modifier.background(c.surface, shape).border(0.6.dp, c.lineStrong, shape).padding(8.dp)) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(AREA_H).clip(RoundedCornerShape(8.dp)).background(c.sunken).graphicsLayer { alpha = faded }, contentAlignment = Alignment.Center) {
      val (w, h) = thumb?.let { it.width to it.height } ?: size ?: (400 to 600)
      val turned = o.rot % 180 != 0
      val (sw, sh) = if (turned) h to w else w to h
      val fit = min((maxWidth.value - 16) / sw, AREA_H.value / sh)
      val box = Modifier.size((w * fit).dp, (h * fit).dp)
      val img = rememberBitmap(thumb?.path, 400)
      Box(box.graphicsLayer { rotationZ = turn }.background(c.page).border(0.6.dp, c.lineStrong), contentAlignment = Alignment.Center) {
        if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        else T("${o.i + 1}", Type.title.copy(fontSize = 20.sp), c.faint)
      }
      T("${o.i + 1}", Type.label.copy(fontSize = 11.sp), c.onInk, Modifier.align(Alignment.BottomStart).padding(6.dp).background(c.ink, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
      if (o.rot > 0) T("${o.rot}°", Type.label.copy(fontSize = 11.sp), Color.White, Modifier.align(Alignment.TopEnd).padding(6.dp).background(c.accent, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
    }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceAround) {
      if (o.del) IconBtn(I.restore, label = stringResource(R.string.acc_keep_page_n, o.i + 1), onClick = onDelete)
      else {
        IconBtn(I.rotateRight, label = stringResource(R.string.acc_rotate_page_n, o.i + 1), onClick = onRotate)
        IconBtn(I.deleteOutline, label = stringResource(R.string.acc_remove_page_at_position, o.i + 1, k + 1), onClick = onDelete)
      }
    }
  }
}

// ── Watermark ───────────────────────────────────────────────────────────────

@Composable
private fun WatermarkEditor(p: EditorProps) {
  val file = p.file
  val text = winAnsi(p.value.str("text")).trim() // exactly what the stamp will say
  var page by remember(file?.path) { mutableStateOf<Triple<Pair<Float, Float>, Int, Thumb>?>(null) } // stored size, rotation, render
  val readErr = stringResource(R.string.error_pdf_unreadable_short)
  LaunchedEffect(file?.path) {
    if (file == null) return@LaunchedEffect
    try {
      page = withContext(Dispatchers.IO) {
        val (w, h) = pageSizes(file.path)[0]
        val rot = pageRotations(file.path).firstOrNull() ?: 0
        // pageSizes is as displayed; the layout wants the page as the PDF stores it.
        Triple(if (rot % 180 != 0) h.toFloat() to w.toFloat() else w.toFloat() to h.toFloat(), rot, renderThumbs(file.path, 480, 1)[0])
      }
    } catch (e: Exception) {
      p.onError(e.message ?: readErr)
    }
  }
  BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = 120.dp).background(c.sunken, RoundedCornerShape(Radius.group)).padding(12.dp), contentAlignment = Alignment.Center) {
    val pg = page
    when {
      file == null -> T(stringResource(R.string.msg_choose_pdf_for_watermark_preview), Type.small, c.sub, align = TextAlign.Center)
      pg == null -> Spinner()
      else -> {
        val (stored, rot, t) = pg
        val fit = min(maxWidth.value / t.width, 320f / t.height)
        val w = t.width * fit
        val h = t.height * fit
        val k = hypot(w, h) / hypot(stored.first, stored.second) // page points -> preview dp
        val img = rememberBitmap(t.path, 600)
        Box(Modifier.size(w.dp, h.dp).background(c.page).clip(RoundedCornerShape(2.dp)), contentAlignment = Alignment.Center) {
          if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
          if (text.isNotEmpty()) {
            val l = watermarkLayout(text, stored.first, stored.second, p.value.str("scale").toFloatOrNull() ?: 1f)
            val alpha by animateFloatAsState(p.value.str("opacity").toFloatOrNull() ?: 0.2f, label = "wm")
            val size by animateFloatAsState(l.size * k, label = "wmSize")
            T(
              text,
              TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = with(LocalDensity.current) { size.dp.toSp() }),
              Color(0xFFD1452B), // matches the stamped rgb
              Modifier.wrapContentSize(unbounded = true).graphicsLayer { this.alpha = alpha; rotationZ = rot - (l.angle * 180 / Math.PI).toFloat() },
              maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Visible,
            )
          }
        }
      }
    }
  }
  T(stringResource(R.string.msg_preview_page_1), Type.small, c.sub, Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 14.dp), align = TextAlign.Center)
  Options(toolById("watermark")!!.opts, p.value) { v -> p.onChange { v } }
}

// ── Workflow ────────────────────────────────────────────────────────────────

@Composable
private fun WorkflowEditor(p: EditorProps) {
  val steps = p.value.get<List<WorkflowStep>>("steps") ?: emptyList()
  val set = { next: List<WorkflowStep> -> p.onChange { it.put("steps", next) } }
  Reorderable(steps, { it.id }, set, gap = 10.dp, handle = true) { step, k, handle ->
    val t = toolById(step.op) // null only for a step saved by an older version that dropped the tool
    val shape = RoundedCornerShape(Radius.group)
    Column(Modifier.fillMaxWidth().background(c.surface, shape).border(0.6.dp, c.lineStrong, shape).padding(start = 14.dp, end = 8.dp, top = 10.dp)) {
      Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp).background(c.accent, CircleShape), contentAlignment = Alignment.Center) { T("${k + 1}", Type.label.copy(fontSize = 11.sp), Color.White) }
        T(t?.stepTitle ?: stringResource(R.string.label_unavailable_step), Type.bold.copy(fontSize = 15.sp), modifier = Modifier.weight(1f).padding(start = 10.dp))
        IconBtn(I.close, label = stringResource(R.string.acc_remove_step)) { set(steps.filter { it.id != step.id }) }
        Box(handle.padding(7.dp)) { Icon(I.drag, 22.dp, c.faint, label = stringResource(R.string.acc_reorder)) }
      }
      Column(Modifier.padding(end = 6.dp)) {
        Options(t?.stepOpts ?: emptyList(), step.opts) { o -> set(steps.map { if (it.id == step.id) it.copy(opts = JsonObject(o)) else it }) }
      }
    }
  }
  T(
    if (steps.isNotEmpty()) stringResource(R.string.msg_workflow_steps_help)
    else stringResource(R.string.msg_workflow_pick_steps_help),
    Type.small, c.sub, Modifier.padding(top = if (steps.isNotEmpty()) 12.dp else 0.dp, bottom = 10.dp),
  )
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    stepTools().forEach { t ->
      Row(
        Modifier.tap(pressed = c.sunken, shape = CircleShape, scaleTo = 0.92f) { set(steps + WorkflowStep("${t.id}-${uid()}", t.id, defaults(t.stepOpts))) }
          .border(1.dp, c.lineStrong, CircleShape).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(I.plus, 14.dp)
        Spacer(Modifier.size(4.dp))
        T(t.stepTitle, Type.med.copy(fontSize = 13.sp))
      }
    }
  }
}
