package com.sammyi57.pdftools

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private const val FEEDBACK_FORM = "https://forms.gle/Fc3RHMTXAiA8fT5VA"

@Composable
fun SettingsTab() {
  val files = remember(Store.version) { listAllFiles() }
  var clearConfirm by remember { mutableStateOf(false) }
  var clearChoice by remember { mutableStateOf(false) } // files are also in Google Drive
  var driveConfirm by remember { mutableStateOf(false) }
  var wiping by remember { mutableStateOf(false) }
  var terms by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  // Drive goes first: if it fails, the phone keeps its files and the error shows under Google Drive.
  val wipeBoth = {
    wiping = true
    scope.launch {
      try { Backup.deleteEverything() } catch (e: Exception) { Backup.update { it.copy(error = Backup.describe(e)) } }
      wiping = false
    }
    Unit
  }
  val clearAll = { keepDrive: Boolean ->
    if (keepDrive) Backup.keepInDriveOnly(files.map { it.path })
    clearFiles()
    clearChoice = false
  }
  val version = remember { runCatching { appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName }.getOrNull() ?: "" }
  val fileOne = stringResource(R.string.unit_file_one)
  val fileMany = stringResource(R.string.unit_file_many)

  Screen {
    LargeTitle(stringResource(R.string.title_settings))

    GroupLabel(stringResource(R.string.label_appearance), first = true)
    ThemePicker()

    GroupLabel(stringResource(R.string.label_storage))
    Group {
      ListRow(stringResource(R.string.label_saved_files), first = true, lead = { Lead(I.folderOutline) }, value = files.size.toString())
      ListRow(stringResource(R.string.label_space_used), lead = { Lead(I.harddisk) }, value = fmtSize(files.sumOf { it.size }))
      ListRow(
        stringResource(R.string.action_delete_all_saved_files), lead = { Lead(I.trashCanOutline, true) }, danger = true, value = if (wiping) stringResource(R.string.label_deleting) else null,
        onClick = if (files.isEmpty() || wiping) null else ({ if (Backup.hasDriveCopies(files.map { it.path })) clearChoice = true else clearConfirm = true }),
      )
    }

    GroupLabel(stringResource(R.string.label_scanning))
    Group {
      ListRow(
        stringResource(R.string.label_make_scans_searchable), first = true, lead = { Lead(I.textRecognition) },
        subtitle = stringResource(R.string.msg_make_scans_searchable_desc),
        onClick = { ScanPrefs.save(!ScanPrefs.searchable) },
      ) { Toggle(ScanPrefs.searchable) { ScanPrefs.save(it) } }
    }

    GroupLabel(stringResource(R.string.label_google_drive))
    DriveBackup()

    GroupLabel(stringResource(R.string.label_privacy))
    Group {
      if (Backup.state.enabled) {
        ListRow(stringResource(R.string.label_processed_on_phone), first = true, lead = { Lead(I.cellphoneLock) }, subtitle = stringResource(R.string.msg_processed_on_phone_desc))
      } else {
        ListRow(stringResource(R.string.label_files_stay_on_phone), first = true, lead = { Lead(I.cellphoneLock) }, subtitle = stringResource(R.string.msg_files_stay_on_phone_desc))
      }
      ListRow(stringResource(R.string.label_no_account_tracking), lead = { Lead(I.accountOffOutline) }, subtitle = stringResource(R.string.msg_no_account_tracking_desc))
      ListRow(stringResource(R.string.label_works_offline), lead = { Lead(I.airplane) }, subtitle = stringResource(R.string.msg_works_offline_desc))
    }

    GroupLabel(stringResource(R.string.label_about))
    Group {
      ListRow(stringResource(R.string.label_version), first = true, lead = { Lead(I.informationOutline) }, value = version)
      ListRow(stringResource(R.string.label_pdf_engine), lead = { Lead(I.codeTags) }, value = "PdfBox")
      ListRow(stringResource(R.string.label_privacy_policy_and_terms), lead = { Lead(I.shieldCheckOutline) }, chevron = true, onClick = { terms = true })
      ListRow(
        stringResource(R.string.label_send_feedback), lead = { Lead(I.messageTextOutline) }, subtitle = stringResource(R.string.msg_send_feedback_desc),
        onClick = { openUrl(FEEDBACK_FORM) },
      ) { Icon(I.openInNew, 18.dp, c.faint) }
    }
  }

  if (terms) FullSheet({ terms = false }) { Consent(onClose = { terms = false }) }
  if (clearConfirm) {
    ConfirmDialog(
      stringResource(R.string.title_delete_all_saved_files_q), stringResource(R.string.msg_delete_all_saved_files_body, plural(files.size, fileOne, fileMany)),
      stringResource(R.string.action_delete_all), danger = true, requireTyping = stringResource(R.string.confirm_word), onDismiss = { clearConfirm = false },
    ) { clearFiles() }
  }
  if (clearChoice) {
    AppDialog({ clearChoice = false }, stringResource(R.string.title_delete_all_saved_files_q), stringResource(R.string.msg_delete_all_saved_files_also_drive, plural(files.size, fileOne, fileMany))) {
      Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Btn(stringResource(R.string.action_delete_phone_only), Modifier.fillMaxWidth(), I.cellphoneRemove, BtnKind.Secondary) { clearAll(true) }
        Btn(stringResource(R.string.action_delete_phone_and_drive), Modifier.fillMaxWidth(), I.deleteOutline, BtnKind.Danger) { clearChoice = false; driveConfirm = true }
        T(
          stringResource(R.string.msg_delete_choice_explain),
          Type.small, c.sub, align = TextAlign.Center,
        )
      }
    }
  }
  if (driveConfirm) {
    ConfirmDialog(
      stringResource(R.string.title_delete_from_phone_and_drive_q), stringResource(R.string.msg_delete_from_phone_and_drive_body, plural(files.size, fileOne, fileMany)),
      stringResource(R.string.action_delete_all), danger = true, requireTyping = stringResource(R.string.confirm_word), onDismiss = { driveConfirm = false },
    ) { wipeBoth() }
  }
}

@Composable
fun Lead(glyph: Int, danger: Boolean = false) {
  Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) { Icon(glyph, 20.dp, if (danger) c.accent else c.sub) }
}

@Composable
private fun DriveBackup() {
  val scope = rememberCoroutineScope()
  val state = Backup.state
  val syncing = Backup.syncing
  var busy by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf("") }
  var confirmOff by remember { mutableStateOf(false) }
  val signInFailedMsg = stringResource(R.string.error_google_signin_failed)
  // Turning sync on and changing accounts both go through the account picker.
  val connect = {
    scope.launch {
      busy = true
      error = ""
      try { Backup.connect() } catch (e: Exception) { error = e.message ?: signInFailedMsg }
      busy = false
    }
    Unit
  }
  val status = when {
    !state.enabled -> stringResource(R.string.label_off)
    syncing -> stringResource(R.string.label_syncing)
    state.error != null -> stringResource(R.string.label_needs_attention)
    state.lastSync != null -> stringResource(R.string.msg_synced_at, fmtDate(state.lastSync))
    else -> stringResource(R.string.label_not_synced_yet)
  }
  Group {
    ListRow(
      stringResource(R.string.label_sync_pdfs_with_drive), first = true, lead = { Lead(I.googleDrive) },
      subtitle = if (state.enabled) state.email ?: stringResource(R.string.label_signed_in) else stringResource(R.string.msg_sync_keeps_same_desc),
    ) {
      if (busy) Spinner(c.accent) else Toggle(state.enabled) { on -> if (on) connect() else confirmOff = true }
    }
    if (state.enabled) {
      ListRow(stringResource(R.string.label_status), lead = { Lead(I.cloudSyncOutline) }, value = status)
      ListRow(stringResource(R.string.label_sync_now), lead = { Lead(I.sync) }, onClick = if (syncing) null else ({ Backup.syncNow(); Unit }))
      ListRow(stringResource(R.string.label_change_account), lead = { Lead(I.accountSwitchOutline) }, subtitle = stringResource(R.string.msg_change_account_desc), onClick = if (busy) null else connect)
    }
  }
  // The last run's problem is hidden while a new run is trying again.
  val problem = error.ifEmpty { if (state.enabled && !syncing) state.error ?: "" else "" }
  if (problem.isNotEmpty()) T(problem, Type.small, c.accent, Modifier.padding(top = 8.dp))
  if (state.enabled) {
    T(
      stringResource(R.string.msg_sync_both_ways_desc),
      Type.small, c.sub, Modifier.padding(top = 8.dp),
    )
  }
  if (confirmOff) {
    ConfirmDialog(
      stringResource(R.string.title_turn_off_drive_sync_q), stringResource(R.string.msg_turn_off_drive_sync_body),
      stringResource(R.string.action_turn_off), onDismiss = { confirmOff = false },
    ) { scope.launch { busy = true; Backup.disable(); busy = false } }
  }
}

@Composable
private fun ThemePicker() {
  Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    listOf(ThemePref.System to stringResource(R.string.label_theme_system), ThemePref.Light to stringResource(R.string.label_theme_light), ThemePref.Dark to stringResource(R.string.label_theme_dark)).forEach { (p, label) ->
      val on = ThemeState.pref == p
      val border by animateColorAsState(if (on) c.accent else c.lineStrong, tween(220), label = "theme")
      val width by animateDpAsState(if (on) 2.5.dp else 1.dp, tween(220), label = "themeW")
      val shape = RoundedCornerShape(Radius.control)
      Column(Modifier.weight(1f).tap(scaleTo = 0.95f, label = label) { ThemeState.set(p) }, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().height(88.dp).clip(shape).border(width, border, shape)) {
          if (p == ThemePref.System) {
            Mini(LightPalette, Modifier.weight(1f))
            Mini(DarkPalette, Modifier.weight(1f))
          } else Mini(if (p == ThemePref.Light) LightPalette else DarkPalette, Modifier.weight(1f))
        }
        T(label, if (on) Type.bold.copy(fontSize = Type.small.fontSize) else Type.small, if (on) c.ink else c.sub, Modifier.padding(top = 8.dp))
      }
    }
  }
}

// A tiny drawing of the app in that palette: a title, the hero card, the floating bar.
@Composable
private fun Mini(p: Palette, modifier: Modifier) {
  Column(modifier.fillMaxHeight().background(p.bg).padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
    Box(Modifier.height(6.dp).fillMaxWidth(0.65f).background(p.ink, RoundedCornerShape(3.dp)))
    Box(Modifier.weight(1f).fillMaxWidth().background(androidx.compose.ui.graphics.Brush.verticalGradient(p.hero), RoundedCornerShape(5.dp)))
    Box(Modifier.height(7.dp).fillMaxWidth(0.8f).align(Alignment.CenterHorizontally).background(p.panel, RoundedCornerShape(4.dp)))
  }
}
