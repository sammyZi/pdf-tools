package com.sammyi57.pdftools

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Filter(val labelRes: Int) { All(R.string.filter_all), Pdf(R.string.filter_pdf), Zip(R.string.filter_zip), Recent(R.string.filter_recent) }
private val Filter.label: String get() = appContext.getString(labelRes)

private sealed interface Menu {
  data class OfFile(val f: Saved) : Menu
  data class OfFolder(val f: Folder, val count: Int) : Menu
}

@Composable
fun FilesTab() {
  val nav = LocalNav.current
  val scope = rememberCoroutineScope()
  var folderPath by rememberSaveable { mutableStateOf<String?>(null) }
  var driveFolder by rememberSaveable { mutableStateOf<String?>(null) }
  var onDrive by rememberSaveable { mutableStateOf(false) }
  var filter by rememberSaveable { mutableStateOf(Filter.All) }
  var query by rememberSaveable { mutableStateOf("") }

  val version = Store.version
  val folders = remember(version) { listFolders().map { it to listFiles(it.path).size } }
  val folder = folders.firstOrNull { it.first.path == folderPath }?.first
  val files = remember(version, folderPath) { listFiles(folderPath) }
  val all = remember(version) { listAllFiles() }
  val scans = remember(version) { listDocs().mapNotNull { d -> d.pdf?.let { it.name to d.id } }.toMap() }

  // Opening Files also pulls in changes made in Drive.
  LaunchedEffect(Unit) { if (Backup.state.enabled) Backup.syncNow() }

  val folderName = if (onDrive) driveFolder else folder?.name
  val goBack = { folderPath = null; driveFolder = null; query = ""; filter = Filter.All }
  BackHandler(folderName != null) { goBack() }

  var prompt by remember { mutableStateOf<PromptSpec?>(null) }
  var confirm by remember { mutableStateOf<ConfirmSpec?>(null) }
  var menu by remember { mutableStateOf<Menu?>(null) }
  var moving by remember { mutableStateOf<Saved?>(null) }
  var deleteChoice by remember { mutableStateOf<DeleteChoice?>(null) }
  var exporting by remember { mutableStateOf<Pair<Saved, Boolean>?>(null) }

  val needle = query.trim().lowercase()
  val filtered = files.filter {
    when (filter) {
      Filter.Pdf -> it.name.lowercase().endsWith(".pdf")
      Filter.Zip -> it.name.lowercase().endsWith(".zip")
      Filter.Recent -> System.currentTimeMillis() - it.modified < 7 * 86_400_000L
      Filter.All -> true
    } && (needle.isEmpty() || it.name.lowercase().contains(needle))
  }
  val filteredBytes = filtered.sumOf { it.size }
  val fileOne = stringResource(R.string.unit_file_one)
  val fileMany = stringResource(R.string.unit_file_many)
  val folderOne = stringResource(R.string.unit_folder_one)
  val folderMany = stringResource(R.string.unit_folder_many)
  val subtitle = when {
    needle.isNotEmpty() || filter != Filter.All -> if (filtered.isNotEmpty()) stringResource(R.string.msg_files_count_size, plural(filtered.size, fileOne, fileMany), fmtSize(filteredBytes)) else stringResource(R.string.msg_no_matching_files)
    folder != null -> if (files.isNotEmpty()) stringResource(R.string.msg_files_count_size, plural(files.size, fileOne, fileMany), fmtSize(filteredBytes)) else stringResource(R.string.msg_empty_folder)
    all.isNotEmpty() && folders.isNotEmpty() -> stringResource(R.string.msg_files_across_folders, plural(all.size, fileOne, fileMany), plural(folders.size, folderOne, folderMany), fmtSize(all.sumOf { it.size }))
    all.isNotEmpty() -> stringResource(R.string.msg_files_on_phone, plural(all.size, fileOne, fileMany), fmtSize(all.sumOf { it.size }))
    folders.isNotEmpty() -> plural(folders.size, folderOne, folderMany)
    else -> null
  }

  val newFolderTitle = stringResource(R.string.title_new_folder)
  val newFolderSubtitle = stringResource(R.string.msg_enter_folder_name)
  val folderNameHint = stringResource(R.string.hint_folder_name)
  val actionCreate = stringResource(R.string.action_create)
  val newFolder = { prompt = PromptSpec(newFolderTitle, newFolderSubtitle, folderNameHint, "", actionCreate) { createFolder(it) } }
  val titleDeleteFileQ = stringResource(R.string.title_delete_file_q)
  val msgFileWillBeRemoved = stringResource(R.string.msg_file_will_be_removed)
  val deleteFile = { f: Saved ->
    if (Backup.hasDriveCopies(listOf(f.path))) deleteChoice = DeleteChoice(titleDeleteFileQ, "\"${f.name}\"", listOf(f.path)) { removeFile(f.path) }
    else confirm = ConfirmSpec(titleDeleteFileQ, "\"${f.name}\" $msgFileWillBeRemoved") { removeFile(f.path) }
  }
  val titleDeleteFolderQ = stringResource(R.string.title_delete_folder_q)
  val msgFilesInside = stringResource(R.string.msg_files_inside)
  val msgAnyFilesInside = stringResource(R.string.msg_any_files_inside)
  val msgFolderWillBeDeleted = stringResource(R.string.msg_folder_will_be_deleted)
  val deleteFolder = { f: Folder, count: Int ->
    val paths = listFiles(f.path).map { it.path }
    // Both sides: the whole Drive folder goes to the Trash, Drive-only files too, not just the copies sync linked.
    val both = {
      scope.launch { try { Backup.deleteFolder(f.name) } catch (e: Exception) { Backup.update { it.copy(error = Backup.describe(e)) } } }
      Unit
    }
    if (Backup.hasDriveCopies(paths)) deleteChoice = DeleteChoice(titleDeleteFolderQ, "\"${f.name}\" ${msgFilesInside.format(plural(count, fileOne, fileMany))}", paths, both) { removeFolder(f.path) }
    else confirm = ConfirmSpec(titleDeleteFolderQ, "\"${f.name}\" ${if (count == 0) msgAnyFilesInside else msgFilesInside.format(plural(count, fileOne, fileMany))} $msgFolderWillBeDeleted") { removeFolder(f.path) }
  }

  Screen {
    if (folderName != null) {
      Row(Modifier.padding(top = 4.dp).tap(scaleTo = 0.95f, onClick = goBack).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(I.arrowLeft, 20.dp)
        Spacer(Modifier.size(6.dp))
        T(stringResource(R.string.action_all_files_title), Type.med, c.sub)
      }
      LargeTitle(folderName, if (onDrive) stringResource(R.string.label_in_google_drive) else subtitle)
    } else {
      LargeTitle(stringResource(R.string.label_files), if (onDrive) stringResource(R.string.msg_synced_with_drive) else subtitle)
    }

    // Phone / Google Drive, only at the top level: inside a folder the subtitle already says which side it is.
    if (folderName == null) {
      Segmented(listOf(false to stringResource(R.string.label_on_this_phone), true to stringResource(R.string.label_google_drive)), onDrive, icons = listOf(I.cellphone, I.googleDrive)) { onDrive = it }
      Spacer(Modifier.height(12.dp))
    }
    SearchField(query, { query = it }, stringResource(R.string.hint_search_files))

    AnimatedContent(onDrive, label = "place", transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) }) { drive ->
      Column {
        if (drive) {
          DriveFiles(query, driveFolder) { driveFolder = it; query = "" }
          return@Column
        }
        // Folders sit on top as a strip of cards; the filters below only narrow the files.
        val showFolders = folder == null && needle.isEmpty() && (folders.isNotEmpty() || files.isNotEmpty())
        if (showFolders) {
          GroupLabel(if (folders.isEmpty()) stringResource(R.string.label_folders) else stringResource(R.string.label_folders_count, folders.size), first = true)
          Row(Modifier.padding(top = 4.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NewFolderCard(newFolder)
            folders.forEach { (f, n) ->
              FolderCard(f.name, n, onOpen = { folderPath = f.path; query = ""; filter = Filter.All }) { menu = Menu.OfFolder(f, n) }
            }
          }
        }
        when {
          files.isEmpty() && needle.isEmpty() && showFolders -> T(
            stringResource(R.string.msg_files_outside_folder_appear), Type.small, c.sub, Modifier.fillMaxWidth().padding(top = 28.dp), align = TextAlign.Center,
          )
          files.isEmpty() && needle.isEmpty() -> Empty(
            { ToolArt("files", 112.dp) },
            if (folder != null) stringResource(R.string.title_folder_empty) else stringResource(R.string.title_no_files_yet),
            if (folder != null) stringResource(R.string.msg_move_files_into_folder) else stringResource(R.string.msg_pdfs_saved_here),
          ) {
            if (folder != null) Btn(stringResource(R.string.action_back_to_all_files), Modifier.fillMaxWidth(), kind = BtnKind.Secondary, onClick = goBack)
            else {
              Btn(stringResource(R.string.action_create_a_folder), Modifier.fillMaxWidth(), I.folderPlusOutline, onClick = newFolder)
              Btn(stringResource(R.string.label_scan_document), Modifier.fillMaxWidth(), I.cameraOutline, BtnKind.Secondary) { nav.scan() }
              Btn(stringResource(R.string.action_browse_tools), Modifier.fillMaxWidth(), kind = BtnKind.Secondary) { nav.show(Tab.Tools) }
            }
          }
          else -> {
            Row(
              Modifier.padding(top = if (showFolders) 22.dp else 12.dp, bottom = 14.dp).horizontalScroll(rememberScrollState()),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Filter.entries.forEach { f -> Chip(f.label, filter == f) { filter = f } }
            }
            if (filtered.isEmpty()) {
              T(stringResource(R.string.msg_no_files_match, needle.ifEmpty { filter.label }), Type.body, c.sub, Modifier.fillMaxWidth().padding(top = 28.dp), align = TextAlign.Center)
            }
            val moreLabel = stringResource(R.string.action_more)
            byDay(filtered) { it.modified }.forEachIndexed { gi, (label, items) ->
              GroupLabel(label, first = gi == 0)
              Group {
                items.forEachIndexed { i, f ->
                  ListRow(
                    stem(f.name), first = i == 0, lead = { DocGlyph(kindOf(f.name)) }, marquee = true,
                    subtitle = "${fmtSize(f.size)} · ${fmtTime(f.modified)}", onClick = { openFile(f.path, f.name) }, onLongClick = { menu = Menu.OfFile(f) },
                  ) {
                    // Only files still waiting to upload get a cloud: "Synced" is the normal state.
                    if (Backup.waiting(f.path, f.name)) Icon(I.cloudUploadOutline, 17.dp, c.accent)
                    IconBtn(I.dotsVertical, color = c.sub, label = moreLabel) { menu = Menu.OfFile(f) }
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  menu?.let { m ->
    ModalBottomSheet({ menu = null }, containerColor = c.surface) {
      Column(Modifier.padding(horizontal = 14.dp).padding(bottom = 18.dp).navigationBarsPadding()) {
        val title = when (m) { is Menu.OfFile -> m.f.name; is Menu.OfFolder -> m.f.name }
        T(title, Type.bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), maxLines = 1)
        val pick = { run: () -> Unit -> { menu = null; run() } }
        when (m) {
          is Menu.OfFile -> {
            val f = m.f
            scans[f.name]?.let { id -> MenuRow(I.fileDocumentEditOutline, stringResource(R.string.action_edit_scan), onClick = pick { nav.scan(id) }) }
            MenuRow(I.shareVariantOutline, stringResource(R.string.action_share), onClick = pick { share(f.path, f.name) })
            if (kindOf(f.name) == DocKind.Pdf) {
              MenuRow(I.fileWordOutline, stringResource(R.string.action_save_as_word), onClick = pick { exporting = f to true })
              MenuRow(I.fileImageOutline, stringResource(R.string.action_save_as_jpeg), onClick = pick { exporting = f to false })
            }
            val renameFileTitle = stringResource(R.string.title_rename_file)
            val renameFileHint = stringResource(R.string.hint_file_name)
            val renamePrefix = stringResource(R.string.msg_rename_quoted)
            val saveLabel = stringResource(R.string.action_save)
            MenuRow(I.pencilOutline, stringResource(R.string.action_rename), onClick = pick {
              prompt = PromptSpec(renameFileTitle, renamePrefix.format(f.name), renameFileHint, stem(f.name), saveLabel) { relinkPdf(f.path, renameFile(f, it)) }
            })
            MenuRow(I.folderMoveOutline, stringResource(R.string.action_move_to_folder), onClick = pick { moving = f })
            MenuRow(I.deleteOutline, stringResource(R.string.action_delete), color = c.accent, onClick = pick { deleteFile(f) })
          }
          is Menu.OfFolder -> {
            val renameFolderTitle = stringResource(R.string.title_rename_folder)
            val renamePrefix = stringResource(R.string.msg_rename_quoted)
            val folderNameHint2 = stringResource(R.string.hint_folder_name)
            val saveLabel = stringResource(R.string.action_save)
            MenuRow(I.pencilOutline, stringResource(R.string.action_rename), onClick = pick {
              prompt = PromptSpec(renameFolderTitle, renamePrefix.format(m.f.name), folderNameHint2, m.f.name, saveLabel) { renameFolder(m.f.path, it) }
            })
            MenuRow(I.deleteOutline, stringResource(R.string.action_delete), color = c.accent, onClick = pick { deleteFolder(m.f, m.count) })
          }
        }
      }
    }
  }

  val deleteLabel = stringResource(R.string.action_delete)
  prompt?.let { p -> PromptDialog(p.title, p.initial, p.subtitle, p.placeholder, p.submit, onClose = { prompt = null }, onSubmit = p.run) }
  confirm?.let { cf -> ConfirmDialog(cf.title, cf.message, deleteLabel, danger = true, onDismiss = { confirm = null }, onConfirm = cf.run) }

  deleteChoice?.let { d ->
    AppDialog({ deleteChoice = null }, d.title, stringResource(if (d.paths.size > 1) R.string.msg_also_in_drive_plural else R.string.msg_also_in_drive_singular, d.what)) {
      Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Btn(stringResource(R.string.action_delete_phone_only), Modifier.fillMaxWidth(), I.cellphoneRemove, BtnKind.Secondary) { Backup.keepInDriveOnly(d.paths); d.remove(); deleteChoice = null }
        Btn(stringResource(R.string.action_delete_phone_and_drive), Modifier.fillMaxWidth(), I.deleteOutline, BtnKind.Danger) { (d.removeBoth ?: d.remove)(); deleteChoice = null }
        T(
          stringResource(R.string.msg_delete_file_choice_explain),
          Type.small, c.sub, align = TextAlign.Center,
        )
      }
    }
  }

  moving?.let { f ->
    AppDialog({ moving = null }, stringResource(R.string.title_move_file), f.name) {
      Column(Modifier.padding(top = 12.dp, bottom = 16.dp)) {
        if (folder != null) {
          MenuRow(I.homeOutline, stringResource(R.string.label_all_files_root), stringResource(R.string.msg_move_out_of_folder), c.accent, trailing = { Icon(I.chevronRight, 20.dp, c.faint) }) { moveFileToRoot(f.path); moving = null }
        }
        val targets = folders.filter { it.first.path != folder?.path }
        targets.forEach { (t, n) ->
          Row(
            Modifier.fillMaxWidth().tap(pressed = c.sunken, shape = RoundedCornerShape(Radius.tile)) { moveFile(f.path, t.path); moving = null }.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            FolderGlyph(28.dp)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
              T(t.name, Type.item)
              T(plural(n, fileOne, fileMany), Type.small, c.sub)
            }
            Icon(I.chevronRight, 20.dp, c.faint)
          }
        }
        if (targets.isEmpty() && folder == null) {
          T(stringResource(R.string.msg_no_folders_yet), Type.small, c.sub, Modifier.fillMaxWidth().padding(vertical = 16.dp), align = TextAlign.Center)
        }
      }
      Btn(stringResource(R.string.action_cancel), Modifier.fillMaxWidth(), kind = BtnKind.Secondary) { moving = null }
    }
  }

  exporting?.let { (pdf, word) -> ExportDialog(pdf, word) { exporting = null } }
}

private val FOLDER_CARD = 128.dp

@Composable
private fun NewFolderCard(onClick: () -> Unit) {
  val shape = RoundedCornerShape(Radius.group)
  Column(
    Modifier.width(FOLDER_CARD).height(112.dp).tap(shape = shape, scaleTo = 0.95f, label = stringResource(R.string.label_new_folder), onClick = onClick)
      .background(c.accentSoft, shape).padding(14.dp),
    verticalArrangement = Arrangement.SpaceBetween,
  ) {
    Box(Modifier.size(38.dp).background(c.surface, CircleShape), contentAlignment = Alignment.Center) { Icon(I.plus, 20.dp, c.accent) }
    T(stringResource(R.string.label_new_folder), Type.bold, c.accent, maxLines = 1)
  }
}

@Composable
private fun FolderCard(name: String, count: Int, onOpen: () -> Unit, onMore: () -> Unit) {
  val shape = RoundedCornerShape(Radius.group)
  Box(
    Modifier.width(FOLDER_CARD).height(112.dp).tap(shape = shape, scaleTo = 0.95f, label = stringResource(R.string.acc_open_file, name), onLongClick = onMore, onClick = onOpen)
      .background(c.surface, shape).border(0.6.dp, c.lineStrong, shape),
  ) {
    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
      FolderGlyph(40.dp)
      Column {
        T(name, Type.bold, maxLines = 1)
        T(plural(count, stringResource(R.string.unit_file_one), stringResource(R.string.unit_file_many)), Type.small, c.sub, maxLines = 1)
      }
    }
    Box(Modifier.align(Alignment.TopEnd).padding(4.dp)) { IconBtn(I.dotsVertical, 18.dp, c.sub, stringResource(R.string.action_more), onClick = onMore) }
  }
}

private class PromptSpec(val title: String, val subtitle: String, val placeholder: String, val initial: String, val submit: String, val run: (String) -> Unit)
private class ConfirmSpec(val title: String, val message: String, val run: () -> Unit)
private class DeleteChoice(val title: String, val what: String, val paths: List<String>, val removeBoth: (() -> Unit)? = null, val remove: () -> Unit)

// Files → Save as Word doc / Save as JPEG: converts, then offers where else the result should go.
@Composable
private fun ExportDialog(pdf: Saved, word: Boolean, onClose: () -> Unit) {
  val scope = rememberCoroutineScope()
  val nav = LocalNav.current
  var file by remember { mutableStateOf<Saved?>(null) }
  var failed by remember { mutableStateOf(false) }
  var busy by remember { mutableStateOf("") }
  var notes by remember { mutableStateOf(mapOf<String, String>()) }
  LaunchedEffect(pdf) {
    // Closing while it converts only hides the dialog: the file still lands in Files.
    try { file = withContext(Dispatchers.IO) { exportAs(pdf, word) } } catch (_: Exception) { failed = true }
  }
  val f = file
  when {
    failed -> AppDialog(onClose, stringResource(R.string.title_could_not_convert_pdf), stringResource(R.string.msg_pdf_protected_or_damaged)) {
      Btn(stringResource(R.string.action_ok), Modifier.fillMaxWidth().padding(top = 18.dp), kind = BtnKind.Secondary, onClick = onClose)
    }
    f == null -> AppDialog(onClose, if (word) stringResource(R.string.msg_saving_as_word) else stringResource(R.string.msg_saving_as_jpeg), stringResource(R.string.msg_every_page_converted_on_phone)) {
      Spinner(modifier = Modifier.padding(vertical = 20.dp).align(Alignment.CenterHorizontally))
    }
    else -> {
      val many = f.name.lowercase().endsWith(".zip")
      val drive = Backup.state
      val somethingWentWrong = stringResource(R.string.error_something_went_wrong)
      val run = { key: String, fn: suspend () -> String? ->
        if (busy.isEmpty()) scope.launch {
          busy = key
          try { fn()?.let { notes = notes + (key to it) } } catch (e: Exception) { notes = notes + (key to (e.message ?: somethingWentWrong)) }
          busy = ""
        }
        Unit
      }
      val savedTo = stringResource(R.string.msg_saved_to)
      val uploadedToDrive = stringResource(R.string.msg_uploaded_to_drive)
      AppDialog(onClose, if (word) stringResource(R.string.msg_word_doc_saved) else if (many) stringResource(R.string.msg_jpegs_saved) else stringResource(R.string.msg_jpeg_saved), stringResource(R.string.msg_file_size_in_files, f.name, fmtSize(f.size))) {
        Column(Modifier.padding(top = 10.dp)) {
          val option = @Composable { key: String, glyph: Int, title: String, sub: String, fn: suspend () -> String? ->
            MenuRow(glyph, title, notes[key] ?: sub, trailing = { if (busy == key) Spinner() }) { run(key, fn) }
          }
          option("open", I.openInNew, stringResource(R.string.action_open), if (word) stringResource(R.string.msg_in_word_or_docs_app) else if (many) stringResource(R.string.msg_zip_one_jpeg_per_page) else stringResource(R.string.msg_in_gallery_or_photo_app)) { openFile(f.path, f.name); null }
          option("device", I.cellphoneArrowDown, stringResource(R.string.action_save_to_device), stringResource(R.string.msg_pick_a_folder_like_downloads)) { saveToDevice(f).takeIf { it.isNotEmpty() }?.let { savedTo.format(it) } }
          option("drive", I.googleDrive, stringResource(R.string.action_save_to_google_drive), if (drive.enabled) stringResource(R.string.msg_pdf_tools_folder_email, drive.email ?: "") else stringResource(R.string.msg_turn_on_drive_sync_first)) {
            if (!drive.enabled) { onClose(); nav.show(Tab.Settings); null }
            else try { Backup.saveCopy(f, mimeOf(f.name)); uploadedToDrive } catch (e: Exception) { throw UserError(Backup.describe(e)) }
          }
          option("share", I.shareVariantOutline, stringResource(R.string.action_share), stringResource(R.string.msg_send_to_app_or_contact)) { share(f.path, f.name); null }
        }
      }
    }
  }
}

// Files → Google Drive: what's in the synced "PDF Tools" folder. Files only in Drive stay there until chosen: tap the
// download icon, or hold a file to select several (a whole day at once) and download them.
@Composable
private fun ColumnScope.DriveFiles(query: String, folder: String?, onOpenFolder: (String) -> Unit) {
  val nav = LocalNav.current
  val scope = rememberCoroutineScope()
  val state = Backup.state
  var error by remember { mutableStateOf("") }
  var busyId by remember { mutableStateOf("") }
  var picked by remember { mutableStateOf<Set<String>?>(null) } // selected file ids; null when not selecting
  var downloading by remember { mutableStateOf("") }
  var folderMenu by remember { mutableStateOf<String?>(null) }
  var renaming by remember { mutableStateOf<String?>(null) }
  var deleting by remember { mutableStateOf<String?>(null) }
  val data = Backup.cached()

  // Only the first visit fetches: after that every sync, which lists Drive anyway, leaves a fresh listing behind.
  LaunchedEffect(state.email, state.enabled) {
    if (!state.enabled || Backup.cached() != null) return@LaunchedEffect
    error = ""
    try { Backup.list() } catch (e: Exception) { error = Backup.describe(e) }
  }
  BackHandler(picked != null) { picked = null } // back leaves selection before it leaves a folder

  if (!state.enabled) {
    Empty({ Icon(I.googleDrive, 56.dp, c.faint) }, stringResource(R.string.title_drive_sync_off), stringResource(R.string.msg_turn_on_drive_sync_desc)) {
      Btn(stringResource(R.string.action_turn_on_in_settings), Modifier.fillMaxWidth(), I.cogOutline) { nav.show(Tab.Settings) }
    }
    return
  }

  val fileOneD = stringResource(R.string.unit_file_one)
  val fileManyD = stringResource(R.string.unit_file_many)
  val downloadingOneLabel = stringResource(R.string.msg_downloading_one)
  val downloadingNOfMLabel = stringResource(R.string.msg_downloading_n_of_m)
  val all = data?.files ?: emptyList()
  val chosen = all.filter { picked?.contains(it.id) == true }
  val toFetch = chosen.filter { !Backup.isOnPhone(it) }
  val download = { list: List<DriveFile> ->
    val todo = list.filter { !Backup.isOnPhone(it) }
    if (todo.isEmpty()) picked = null
    else scope.launch {
      error = ""
      val label = { done: Int -> if (todo.size > 1) downloadingNOfMLabel.format(minOf(done + 1, todo.size), todo.size) else downloadingOneLabel }
      downloading = label(0)
      try {
        Backup.downloadToPhone(todo) { downloading = label(it) }
        picked = null
      } catch (e: Exception) {
        error = Backup.describe(e)
      }
      downloading = ""
    }
    Unit
  }
  val act = { f: DriveFile, fn: suspend () -> Unit ->
    scope.launch {
      busyId = f.id
      error = ""
      try { fn() } catch (e: Exception) { error = Backup.describe(e) }
      busyId = ""
    }
    Unit
  }
  val toggle = { ids: List<String>, on: Boolean ->
    val next = (picked ?: emptySet()).toMutableSet().apply { if (on) addAll(ids) else removeAll(ids.toSet()) }
    picked = next.ifEmpty { null } // unticking the last one leaves selection
  }

  // The selection bar floats over the bottom of the screen, so it stays in reach however far the list is scrolled.
  Floating(picked != null) {
    val shape = RoundedCornerShape(Radius.card)
    Row(
      Modifier.fillMaxWidth().shadow(14.dp, shape).background(c.surface, shape).border(0.6.dp, c.lineStrong, shape).padding(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconBtn(I.close, label = stringResource(R.string.acc_cancel_selection)) { picked = null }
      Column(Modifier.weight(1f).padding(start = 2.dp)) {
        T(stringResource(R.string.msg_count_selected, plural(chosen.size, fileOneD, fileManyD)), Type.bold)
        T(
          downloading.ifEmpty {
            if (toFetch.isNotEmpty()) stringResource(R.string.msg_size_to_download, fmtSize(toFetch.sumOf { it.size })) + (if (toFetch.size < chosen.size) " " + stringResource(R.string.msg_already_here_count, chosen.size - toFetch.size) else "")
            else stringResource(R.string.label_already_on_phone)
          },
          Type.small, c.sub, maxLines = 1,
        )
      }
      Btn(stringResource(R.string.action_download), icon = I.downloadOutline, busy = downloading.isNotEmpty(), enabled = toFetch.isNotEmpty(), height = 46.dp) { download(chosen) }
    }
  }

  val needle = query.trim().lowercase()
  // Searching looks everywhere (inside this folder when one is open); otherwise show just this level.
  val shown = if (needle.isNotEmpty()) all.filter { (folder == null || it.folder == folder) && "${it.folder}/${it.name}".lowercase().contains(needle) }
  else all.filter { it.folder == (folder ?: "") }
  val folders = if (folder == null && needle.isEmpty()) data?.folders ?: emptyList() else emptyList()
  val problem = error.ifEmpty { if (!Backup.syncing) state.error ?: "" else "" }

  Spacer(Modifier.height(12.dp))
  GroupLabel(state.email ?: stringResource(R.string.label_google_drive), if (Backup.syncing) stringResource(R.string.label_syncing) else stringResource(R.string.label_sync_now), if (Backup.syncing) null else ({ Backup.syncNow(); Unit }), first = true, caps = false)
  Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(0.6.dp).background(c.line)) // sets the account apart from the files under it
  if (problem.isNotEmpty()) T(problem, Type.small, c.accent, Modifier.padding(top = 4.dp, bottom = 4.dp))
  if (downloading.isNotEmpty() && picked == null) T(downloading, Type.small, c.sub, Modifier.padding(vertical = 4.dp))

  when {
    data == null && problem.isEmpty() -> Spinner(c.accent, modifier = Modifier.padding(top = 40.dp).align(Alignment.CenterHorizontally))
    folders.isNotEmpty() || shown.isNotEmpty() -> {
      if (folders.isNotEmpty()) {
        GroupLabel(stringResource(R.string.label_folders))
        Group {
          val moreLabel = stringResource(R.string.action_more)
          folders.forEachIndexed { i, name ->
            ListRow(
              name, first = i == 0, lead = { FolderGlyph() }, subtitle = plural(all.count { it.folder == name }, fileOneD, fileManyD),
              onClick = { onOpenFolder(name) }, onLongClick = { folderMenu = name },
            ) {
              if (busyId == name) Spinner(c.accent, modifier = Modifier.padding(horizontal = 10.dp))
              else IconBtn(I.dotsVertical, color = c.sub, label = moreLabel) { folderMenu = name }
            }
          }
        }
      }
      val deselectLabel = stringResource(R.string.action_deselect)
      val selectAllLabel = stringResource(R.string.action_select_all)
      val onThisPhoneSuffix = stringResource(R.string.msg_on_this_phone_suffix)
      val downloadLabel = stringResource(R.string.action_download)
      val shareLabel = stringResource(R.string.action_share)
      byDay(shown) { it.modified }.forEachIndexed { gi, (label, items) ->
        val ids = items.map { it.id }
        val allOn = picked != null && ids.all { picked!!.contains(it) }
        GroupLabel(label, if (picked != null) (if (allOn) deselectLabel else selectAllLabel) else null, { toggle(ids, !allOn) })
        Group {
          items.forEachIndexed { i, f ->
            val here = Backup.isOnPhone(f)
            val on = picked?.contains(f.id) == true
            ListRow(
              stem(f.name), first = i == 0, marquee = true,
              lead = { if (picked != null) CheckMark(on) else DocGlyph() },
              subtitle = "${if (needle.isNotEmpty() && f.folder.isNotEmpty()) "${f.folder} · " else ""}${fmtSize(f.size)} · ${fmtTime(f.modified)}${if (here) onThisPhoneSuffix else ""}",
              onClick = if (picked != null) ({ toggle(listOf(f.id), !on) }) else if (busyId.isNotEmpty()) null else ({ act(f) { Backup.fetch(f).let { openFile(it.path, it.name) } } }),
              onLongClick = { if (downloading.isEmpty()) toggle(listOf(f.id), !on) },
            ) {
              if (picked == null) {
                if (busyId == f.id) Spinner(c.accent, modifier = Modifier.padding(horizontal = 10.dp))
                else {
                  if (!here) IconBtn(I.downloadOutline, label = downloadLabel) { if (downloading.isEmpty()) download(listOf(f)) }
                  IconBtn(I.shareVariantOutline, label = shareLabel) { act(f) { Backup.fetch(f).let { share(it.path, it.name) } } }
                }
              }
            }
          }
        }
      }
    }
    data != null -> T(
      if (needle.isNotEmpty()) stringResource(R.string.msg_no_drive_files_match, query.trim()) else if (folder != null) stringResource(R.string.msg_folder_no_pdfs_in_drive) else stringResource(R.string.msg_no_pdfs_in_drive_yet),
      Type.body, c.sub, Modifier.fillMaxWidth().padding(top = 40.dp), align = TextAlign.Center,
    )
  }
  val folderAct = { name: String, fn: suspend () -> Unit ->
    scope.launch {
      busyId = name
      error = ""
      try { fn() } catch (e: Exception) { error = Backup.describe(e) }
      busyId = ""
    }
    Unit
  }
  folderMenu?.let { name ->
    val inside = all.filter { it.folder == name }
    val toGet = inside.filter { !Backup.isOnPhone(it) }
    ModalBottomSheet({ folderMenu = null }, containerColor = c.surface) {
      Column(Modifier.padding(horizontal = 14.dp).padding(bottom = 18.dp).navigationBarsPadding()) {
        T(name, Type.bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), maxLines = 1)
        val pick = { run: () -> Unit -> { folderMenu = null; run() } }
        MenuRow(
          I.downloadOutline, stringResource(R.string.action_download_all),
          if (toGet.isEmpty()) stringResource(R.string.label_already_on_phone) else stringResource(R.string.msg_count_and_size, plural(toGet.size, fileOneD, fileManyD), fmtSize(toGet.sumOf { it.size })),
          onClick = pick { if (downloading.isEmpty()) download(toGet) },
        )
        MenuRow(I.pencilOutline, stringResource(R.string.action_rename), onClick = pick { renaming = name })
        MenuRow(I.deleteOutline, stringResource(R.string.action_delete), color = c.accent, onClick = pick { deleting = name })
      }
    }
  }
  renaming?.let { name ->
    PromptDialog(stringResource(R.string.title_rename_folder), name, stringResource(R.string.msg_renamed_in_drive_and_phone), stringResource(R.string.hint_folder_name), stringResource(R.string.action_save), onClose = { renaming = null }) { typed ->
      folderAct(name) { Backup.renameFolder(name, typed) }
    }
  }
  deleting?.let { name ->
    ConfirmDialog(
      stringResource(R.string.title_delete_folder_q), stringResource(R.string.msg_delete_drive_folder_body, name, plural(all.count { it.folder == name }, fileOneD, fileManyD)),
      stringResource(R.string.action_delete), danger = true, requireTyping = stringResource(R.string.confirm_word), onDismiss = { deleting = null },
    ) { folderAct(name) { Backup.deleteFolder(name) } }
  }

  T(
    if (picked != null) stringResource(R.string.msg_tap_files_to_toggle) else stringResource(R.string.msg_hold_file_to_pick_several),
    Type.small.copy(fontSize = Type.small.fontSize * 0.9f), c.faint, Modifier.fillMaxWidth().padding(top = 12.dp), align = TextAlign.Center,
  )
}
