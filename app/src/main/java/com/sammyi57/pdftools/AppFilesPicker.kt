package com.sammyi57.pdftools

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

// Choosing one or more PDFs from the app's saved files and folders, or (with Drive sync on) from the synced Drive
// folder: those are fetched into the cache only once picked, so choosing them doesn't fill the phone.

private const val DRIVE = "drive:" // Drive files go through the same lists as saved ones, under paths no phone file has

private data class Entry(val name: String, val path: String, val size: Long, val modified: Long, val folder: String)

@Composable
fun AppFilesPicker(multi: Boolean, already: List<String>, onClose: () -> Unit, onSelect: (List<Picked>) -> Unit) {
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  var onDrive by remember { mutableStateOf(false) }
  var folder by remember { mutableStateOf<String?>(null) } // folder name
  var query by remember { mutableStateOf("") }
  var drive by remember { mutableStateOf<DriveListing?>(null) }
  var busy by remember { mutableStateOf("") } // fetching picked Drive files
  var problem by remember { mutableStateOf("") }
  val phone = remember { listAllFiles().filter { it.name.lowercase().endsWith(".pdf") } }
  var selected by remember { mutableStateOf(phone.filter { it.path in already }.associate { it.path to Picked(it.name, it.path, it.size) }) }
  val root = remember { rootFolder().path }

  val all: List<Entry> = if (onDrive) (drive?.files ?: emptyList()).map { Entry(it.name, DRIVE + it.id, it.size, it.modified, it.folder) }
  else phone.map { s -> Entry(s.name, s.path, s.size, s.modified, java.io.File(s.path).parent?.takeIf { it != root }?.let { java.io.File(it).name } ?: "") }
  val folders: List<Pair<String, Int>> = if (onDrive) (drive?.folders ?: emptyList()).map { n -> n to all.count { it.folder == n } }
  else listFolders().map { f -> f.name to all.count { it.folder == f.name } }

  val needle = query.trim().lowercase()
  val shown = if (needle.isNotEmpty()) all.filter { it.name.lowercase().contains(needle) } else all.filter { it.folder == (folder ?: "") }

  BackHandler {
    when {
      query.isNotEmpty() -> query = ""
      folder != null -> folder = null
      else -> onClose()
    }
  }

  val showSource = { toDrive: Boolean ->
    onDrive = toDrive
    folder = null
    query = ""
    problem = ""
    if (toDrive && drive == null) {
      drive = Backup.cached() // the listing the Drive tab or the last sync left behind
      if (drive == null) scope.launch { try { drive = Backup.list() } catch (e: Exception) { problem = Backup.describe(e) } }
    }
  }

  // Picked Drive files become cache copies the tool can read; phone files pass straight through.
  val finish = { list: List<Picked> ->
    if (busy.isEmpty()) scope.launch {
      val byId = (drive?.files ?: emptyList()).associateBy { DRIVE + it.id }
      val out = ArrayList<Picked>()
      problem = ""
      try {
        for (p in list) {
          val f = byId[p.path]
          if (f == null) { out.add(p); continue }
          busy = context.getString(R.string.msg_fetching_from_drive, stem(f.name))
          out.add(Backup.fetch(f))
        }
        busy = ""
        onSelect(out)
        onClose()
      } catch (e: Exception) {
        problem = Backup.describe(e)
        busy = ""
      }
    }
    Unit
  }

  FullSheet(onClose, c.bg) {
    Column(Modifier.fillMaxSize()) {
      Column(Modifier.background(c.surface).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
          if (folder != null && needle.isEmpty()) IconBtn(I.arrowLeft, 24.dp, label = stringResource(R.string.action_back)) { folder = null }
          else Box(Modifier.size(40.dp).background(c.accentSoft, RoundedCornerShape(Radius.control)), contentAlignment = Alignment.Center) { Icon(I.folderOutline, 24.dp, c.accent) }
          Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            T(if (multi) stringResource(R.string.title_select_pdfs) else stringResource(R.string.title_select_pdf), Type.title.copy(fontSize = Type.title.fontSize * 0.72f))
            T(
              when {
                needle.isNotEmpty() -> stringResource(R.string.label_search_in_app_files)
                folder != null -> folder!!
                onDrive -> stringResource(R.string.label_google_drive_folder)
                else -> stringResource(R.string.label_app_files_saved)
              },
              Type.small, c.sub, maxLines = 1,
            )
          }
          IconBtn(I.close, 22.dp, label = stringResource(R.string.action_close), onClick = onClose)
        }
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
          if (Backup.state.enabled) {
            Segmented(listOf(false to stringResource(R.string.label_this_phone), true to stringResource(R.string.label_google_drive)), onDrive, icons = listOf(I.cellphone, I.googleDrive)) { if (it != onDrive) showSource(it) }
            Spacer(Modifier.height(10.dp))
          }
          SearchField(query, { query = it }, if (onDrive) stringResource(R.string.hint_search_drive_pdfs) else stringResource(R.string.hint_search_saved_pdfs))
        }
      }
      AnimatedVisibility(busy.isNotEmpty() || problem.isNotEmpty()) {
        Row(Modifier.fillMaxWidth().background(c.surface).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
          if (busy.isNotEmpty()) { Spinner(c.accent, 18.dp); Spacer(Modifier.size(10.dp)) }
          T(busy.ifEmpty { problem }, Type.small, if (busy.isEmpty()) c.accent else c.sub)
        }
      }

      val pdfOne = stringResource(R.string.unit_pdf_one)
      val pdfMany = stringResource(R.string.unit_pdf_many)
      Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
        if (onDrive && drive == null && problem.isEmpty()) {
          Spinner(c.accent, modifier = Modifier.padding(top = 40.dp).align(Alignment.CenterHorizontally))
          return@Column
        }
        if (needle.isEmpty() && folder == null && folders.isNotEmpty()) {
          GroupLabel(stringResource(R.string.label_folders), first = true)
          Group {
            folders.forEachIndexed { i, (name, n) ->
              ListRow(name, first = i == 0, lead = { FolderGlyph(32.dp) }, subtitle = plural(n, pdfOne, pdfMany), chevron = true, onClick = { folder = name })
            }
          }
        }
        if (shown.isNotEmpty()) {
          val allOn = shown.all { it.path in selected }
          GroupLabel(
            when {
              needle.isNotEmpty() -> stringResource(R.string.msg_count_found, plural(shown.size, pdfOne, pdfMany))
              folder != null -> folder!!
              folders.isNotEmpty() -> stringResource(R.string.label_files_in_root)
              else -> stringResource(R.string.label_all_saved_pdfs)
            },
            if (multi) (if (allOn) stringResource(R.string.action_deselect_all) else stringResource(R.string.action_select_all)) else null,
            {
              selected = if (allOn) selected - shown.map { it.path }.toSet() else selected + shown.associate { it.path to Picked(it.name, it.path, it.size) }
            },
          )
          Group {
            shown.forEachIndexed { i, f ->
              val on = f.path in selected
              ListRow(
                stem(f.name), first = i == 0, marquee = true, titleColor = if (on) c.accent else null,
                lead = { Row(verticalAlignment = Alignment.CenterVertically) { if (multi) { CheckMark(on); Spacer(Modifier.size(10.dp)) }; DocGlyph() } },
                subtitle = "${fmtSize(f.size)} · ${fmtDate(f.modified)}", chevron = !multi,
                onClick = {
                  val p = Picked(f.name, f.path, f.size)
                  if (!multi) finish(listOf(p)) else selected = if (on) selected - f.path else selected + (f.path to p)
                },
              )
            }
          }
        } else {
          Empty(
            { ToolArt("files", 80.dp) },
            when { needle.isNotEmpty() -> stringResource(R.string.title_no_matching_pdfs); folder != null -> stringResource(R.string.title_folder_empty); else -> stringResource(R.string.title_no_saved_pdfs) },
            when {
              needle.isNotEmpty() -> stringResource(R.string.msg_no_pdfs_matching, query)
              folder != null -> stringResource(R.string.msg_pdfs_appear_in_folder)
              onDrive -> stringResource(R.string.msg_pdfs_appear_drive)
              else -> stringResource(R.string.msg_pdfs_appear_tools)
            },
          )
        }
      }

      if (multi) {
        Row(Modifier.fillMaxWidth().background(c.surface).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            T(if (selected.isNotEmpty()) stringResource(R.string.msg_count_selected, plural(selected.size, pdfOne, pdfMany)) else stringResource(R.string.label_none_selected), Type.bold)
            if (selected.isNotEmpty()) T(stringResource(R.string.action_clear_all), Type.small, c.accent, Modifier.tap { selected = emptyMap() })
          }
          Btn(if (selected.isNotEmpty()) stringResource(R.string.btn_add_count, selected.size) else stringResource(R.string.title_select_pdfs), enabled = selected.isNotEmpty(), busy = busy.isNotEmpty()) { finish(selected.values.toList()) }
        }
      } else Spacer(Modifier.navigationBarsPadding())
    }
  }
}
