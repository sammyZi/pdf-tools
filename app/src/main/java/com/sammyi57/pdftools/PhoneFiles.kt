package com.sammyi57.pdftools

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// Every PDF on the phone (Downloads, WhatsApp, Documents…), found by walking shared storage. That takes "All files
// access", which the user grants once in system settings; before Android 11 there's no such grant, so those phones get the
// system picker instead.

private const val MAX_SCANNED = 10_000
private const val MAX_SHOWN = 200
private const val STALE_MS = 30 * 60_000L

object DevicePdfs {
  val legacy get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.R

  fun allowed() = !legacy && Environment.isExternalStorageManager()

  /** Opens the system screen where "All files access" is switched on for this app. */
  fun openSettings() {
    val ctx = hostActivity ?: return
    val own = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
    try { ctx.startActivity(own) } catch (_: Exception) { ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
  }

  /** Newest first. Skips hidden folders and Android/data and Android/obb, which apps can't read; Android/media is kept for WhatsApp and the like. */
  fun scan(): List<Saved> {
    val out = ArrayList<Saved>()
    fun walk(dir: File, depth: Int) {
      for (f in dir.listFiles() ?: return) {
        if (out.size >= MAX_SCANNED) return
        if (f.isDirectory) {
          if (f.name.startsWith(".") || depth > 12) continue
          if (depth == 0 && f.name == "Android") File(f, "media").takeIf { it.isDirectory }?.let { walk(it, depth + 2) } else walk(f, depth + 1)
        } else if (f.name.endsWith(".pdf", ignoreCase = true)) {
          out.add(Saved(f.name, f.path, f.length(), f.lastModified()))
        }
      }
    }
    walk(Environment.getExternalStorageDirectory(), 0)
    return out.sortedByDescending { it.modified }.also(::keep)
  }

  // The last scan is kept in memory and in a file, so opening the tab (or the app) shows the list at once instead of
  // walking the whole phone again. It's refreshed in the background once it's old, or by Rescan.
  private val cacheFile get() = File(appContext.filesDir, "phone-pdfs.tsv")
  var cached: List<Saved>? = null
    private set

  private fun keep(list: List<Saved>) {
    cached = list
    runCatching { cacheFile.writeText(list.joinToString("\n") { "${it.path}\t${it.size}\t${it.modified}" }) }
  }

  /** The saved list, minus files deleted since. Null before the first scan. */
  fun load(): List<Saved>? = cached ?: runCatching {
    cacheFile.takeIf { it.exists() }?.readLines()?.mapNotNull { line ->
      val (path, size, modified) = line.split('\t').takeIf { it.size == 3 } ?: return@mapNotNull null
      File(path).takeIf { it.exists() }?.let { Saved(it.name, path, size.toLong(), modified.toLong()) }
    }
  }.getOrNull()?.also { cached = it }

  fun stale() = System.currentTimeMillis() - cacheFile.lastModified() > STALE_MS
}

private enum class PhoneFilter(val label: String) { All("All"), Recent("Recent"), Large("5 MB+") }

/** A row of filter chips that scrolls sideways. */
@Composable
fun <K> ChipRow(options: List<Pair<K, String>>, value: K, modifier: Modifier = Modifier, onChange: (K) -> Unit) {
  Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    options.forEach { (k, label) -> Chip(label, value == k) { onChange(k) } }
  }
}

@Composable
fun PhoneFiles(query: String) {
  val scope = rememberCoroutineScope()
  var filter by rememberSaveable { mutableStateOf(PhoneFilter.All) }
  var allowed by remember { mutableStateOf(DevicePdfs.allowed()) }
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { allowed = DevicePdfs.allowed() } // back from the settings screen
  var files by remember { mutableStateOf(DevicePdfs.cached) }
  var busy by remember { mutableStateOf(false) }
  val rescan: suspend () -> Unit = {
    busy = true
    files = withContext(Dispatchers.IO) { DevicePdfs.scan() }
    busy = false
  }
  LaunchedEffect(allowed) {
    if (!allowed) return@LaunchedEffect
    if (files == null) files = withContext(Dispatchers.IO) { DevicePdfs.load() }
    if (files == null || DevicePdfs.stale()) rescan()
  }

  if (!allowed) {
    Empty(
      { ToolArt("files", 112.dp) },
      "See every PDF on this phone",
      if (DevicePdfs.legacy) "Pick a PDF from your phone's files and it opens right here."
      else "Allow access to your files once, and every PDF on this phone (Downloads, WhatsApp, Documents) shows up here, searchable. Nothing leaves your phone.",
    ) {
      if (DevicePdfs.legacy) Btn("Browse for a PDF", Modifier.fillMaxWidth(), I.folderOutline) {
        scope.launch { pickPdfs(false).firstOrNull()?.let { openFile(it.path, it.name) } }
      } else Btn("Allow access", Modifier.fillMaxWidth(), I.folderOutline) { DevicePdfs.openSettings() }
    }
    return
  }

  val all = files
  if (all == null) {
    T("Looking for PDFs…", Type.body, c.sub, Modifier.fillMaxWidth().padding(top = 36.dp), align = TextAlign.Center)
    return
  }
  val needle = query.trim().lowercase()
  val hits = all.filter {
    (needle.isEmpty() || it.name.lowercase().contains(needle)) && when (filter) {
      PhoneFilter.All -> true
      PhoneFilter.Recent -> System.currentTimeMillis() - it.modified < 7 * 86_400_000L
      PhoneFilter.Large -> it.size >= 5_000_000L
    }
  }
  Column(Modifier.padding(top = 14.dp)) {
    ChipRow(PhoneFilter.entries.map { it to it.label }, filter, Modifier.padding(bottom = 6.dp)) { filter = it }
    if (hits.isEmpty()) {
      T(
        if (needle.isEmpty()) "No PDFs found on this phone." else "No PDFs match \"${query.trim()}\".",
        Type.body, c.sub, Modifier.fillMaxWidth().padding(top = 36.dp), align = TextAlign.Center,
      )
      return@Column
    }
    byDay(hits.take(MAX_SHOWN)) { it.modified }.forEachIndexed { gi, (label, items) ->
      if (gi == 0) GroupLabel(label, if (busy) "Scanning…" else "Rescan", { if (!busy) scope.launch { rescan() } }, first = true)
      else GroupLabel(label)
      Group {
        items.forEachIndexed { i, f ->
          ListRow(
            stem(f.name), first = i == 0, lead = { DocGlyph(kindOf(f.name)) }, marquee = true,
            subtitle = File(f.path).parentFile?.name ?: "",
            onClick = { scope.launch { importIncoming(Uri.fromFile(File(f.path)))?.let { openFile(it.path, it.name) } } },
          ) {
            // Size over time in a fixed-width column, so every row lines up at the right edge.
            Column(Modifier.width(62.dp), horizontalAlignment = Alignment.End) {
              T(fmtSize(f.size), Type.med.copy(fontSize = 12.5.sp, lineHeight = 17.sp), c.ink, maxLines = 1)
              T(fmtTime(f.modified), Type.small.copy(fontSize = 11.5.sp, lineHeight = 16.sp), c.sub, maxLines = 1)
            }
          }
        }
      }
    }
    if (hits.size > MAX_SHOWN) T(
      "Showing the newest $MAX_SHOWN of ${hits.size}. Search to narrow it down.",
      Type.small, c.sub, Modifier.fillMaxWidth().padding(top = 18.dp), align = TextAlign.Center,
    )
  }
}
