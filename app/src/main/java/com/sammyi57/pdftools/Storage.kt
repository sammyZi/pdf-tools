package com.sammyi57.pdftools

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

// Outputs live in the app's files folder ("PDF Tools", one level of folders) so they survive restarts; the cache can
// be purged by the OS. The same folder the Expo app used, so its files carry over.

data class Saved(val name: String, val path: String, val size: Long, val modified: Long)
data class Folder(val name: String, val path: String)

/** A file chosen as a tool's input: from the device (copied into the cache), from Files, or from Drive. */
@Serializable
data class Picked(val name: String, val path: String, val size: Long? = null)

object Store {
  /** Bumped on every change to saved files, so screens showing them read the folder again. */
  var version by mutableIntStateOf(0)
    private set

  fun changed() {
    version++
    syncSoon() // mirrored to Google Drive when sync is on
  }

  /** For changes sync itself made (downloads, deletions from Drive): show them, without syncing again. */
  fun refreshOnly() {
    version++
  }
}

private val BAD = Regex("[\\\\/:*?\"<>|]+")
fun cleanName(s: String) = s.replace(BAD, "_").trim()

fun rootFolder(): File = File(appContext.filesDir, "PDF Tools").apply { mkdirs() }

fun createFolder(name: String): Folder {
  val clean = cleanName(name).ifEmpty { "New Folder" }
  var d = File(rootFolder(), clean)
  var i = 2
  while (d.exists()) d = File(rootFolder(), "$clean (${i++})")
  d.mkdirs()
  Store.changed()
  return Folder(d.name, d.path)
}

fun listFolders(): List<Folder> = rootFolder().listFiles()?.filter { it.isDirectory }?.map { Folder(it.name, it.path) }
  ?.sortedBy { it.name.lowercase() } ?: emptyList()

fun renameFolder(path: String, newName: String) {
  val clean = cleanName(newName)
  val d = File(path)
  if (clean.isEmpty() || !d.exists() || d.name == clean) return
  val target = File(rootFolder(), clean)
  if (target.exists()) return // avoid collision with an existing folder
  if (d.renameTo(target)) relinkFolder(d.path, target.path)
  Store.changed()
}

private fun insideRoot(path: String) = File(path).canonicalPath.startsWith(rootFolder().canonicalPath + File.separator)

// Only ever deletes inside the app's "PDF Tools" folder: never a file the user picked from their own storage.
fun removeFolder(path: String) {
  if (!insideRoot(path)) return
  File(path).deleteRecursively()
  Store.changed()
}

fun removeFile(path: String) {
  if (!insideRoot(path)) return
  File(path).delete()
  Store.changed()
}

fun clearFiles() {
  rootFolder().deleteRecursively()
  rootFolder()
  Store.changed()
}

// Never overwrites: "Report-merged.pdf" becomes "Report-merged (2).pdf" if taken.
private fun freeFile(base: String, ext: String, dir: File = rootFolder()): File {
  dir.mkdirs()
  val clean = cleanName(base).ifEmpty { "document" }
  var f = File(dir, "$clean.$ext")
  var i = 2
  while (f.exists()) f = File(dir, "$clean (${i++}).$ext")
  return f
}

private fun moveInto(f: File, dir: File): File {
  val dot = f.name.lastIndexOf('.')
  val target = freeFile(if (dot > 0) f.name.substring(0, dot) else f.name, if (dot > 0) f.name.substring(dot + 1) else "pdf", dir)
  if (!f.renameTo(target)) { f.copyTo(target); f.delete() }
  return target
}

fun moveFile(path: String, folderPath: String) {
  val f = File(path).takeIf { it.exists() } ?: return
  relinkPdf(path, moveInto(f, File(folderPath)).toSaved()) // a scan's PDF stays linked to its scan wherever it goes
  Store.changed()
}

fun moveFileToRoot(path: String) = moveFile(path, rootFolder().path)

private fun File.toSaved() = Saved(name, path, length(), lastModified())

fun saveBytes(base: String, ext: String, bytes: ByteArray, folder: String? = null): Saved {
  val f = freeFile(base, ext, folder?.let(::File) ?: rootFolder())
  f.writeBytes(bytes)
  Store.changed()
  return f.toSaved()
}

/** saveBytes() for output already written to the cache: moved in under a free name. */
fun adoptTemp(tmp: File, base: String, ext: String, folder: String? = null): Saved {
  val f = freeFile(base, ext, folder?.let(::File) ?: rootFolder())
  if (!tmp.renameTo(f)) { tmp.copyTo(f); tmp.delete() }
  Store.changed()
  return f.toSaved()
}

/** Keeps the extension; a name that's taken gets " (2)" like any save. Returns the file under its new name. */
fun renameFile(f: Saved, newName: String): Saved {
  val ext = f.name.substringAfterLast('.', "pdf")
  val base = newName.trim().replace(Regex("\\.$ext$", RegexOption.IGNORE_CASE), "")
  if (base.isEmpty() || "$base.$ext" == f.name) return f
  val src = File(f.path)
  val dest = freeFile(base, ext, src.parentFile ?: rootFolder())
  src.renameTo(dest)
  Store.changed() // Drive sync spots the same content under a new name and renames it there too
  return f.copy(name = dest.name, path = dest.path)
}

fun listFiles(folder: String? = null): List<Saved> =
  (folder?.let(::File) ?: rootFolder()).listFiles()?.filter { it.isFile }?.map { it.toSaved() }?.sortedByDescending { it.modified } ?: emptyList()

/** Every file across the root and all folders (for global search), newest first. */
fun listAllFiles(): List<Saved> = (listFiles() + listFolders().flatMap { listFiles(it.path) }).sortedByDescending { it.modified }

/** Preview outputs live in the cache and are pruned, so nothing lands in Files until the user runs the tool. */
fun saveTemp(name: String, bytes: ByteArray): File {
  val d = File(appContext.cacheDir, "preview").apply { mkdirs() }
  // Older previews are pruned, not wiped: the part the user is still reading in their PDF app must not vanish.
  d.listFiles()?.sortedByDescending { it.lastModified() }?.drop(6)?.forEach { it.delete() }
  return File(d, "${System.currentTimeMillis()}-${cleanName(name)}").apply { writeBytes(bytes) }
}

private val MIME = mapOf(
  "pdf" to "application/pdf", "zip" to "application/zip", "jpg" to "image/jpeg",
  "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
)

fun mimeOf(name: String) = MIME[name.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"

private fun contentUri(file: File): Uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.files", file)

/** Shares through the system sheet. */
fun share(path: String, name: String = File(path).name) {
  val send = Intent(Intent.ACTION_SEND).apply {
    type = mimeOf(name)
    putExtra(Intent.EXTRA_STREAM, contentUri(File(path)))
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
  }
  val ctx = hostActivity ?: appContext
  ctx.startActivity(Intent.createChooser(send, null).apply { if (ctx === appContext) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
}

/** PDFs open in the app's own viewer where the phone supports it; everything else goes to the phone's app for it. */
fun openFile(path: String, name: String = File(path).name) {
  val inApp = Viewer.open
  if (name.lowercase().endsWith(".pdf") && Viewer.supported && inApp != null) return inApp(path, name)
  openExternally(path, name)
}

/** Hands the file to whichever app the phone uses for its type. ZIPs (and phones with no PDF app) get the share sheet. */
fun openExternally(path: String, name: String = File(path).name) {
  if (name.lowercase().endsWith(".zip")) return share(path, name)
  val view = Intent(Intent.ACTION_VIEW).apply {
    setDataAndType(contentUri(File(path)), mimeOf(name))
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
  }
  val ctx = hostActivity ?: appContext
  // This app opens PDFs too: leave it out, or "another app" could be this one again. A chooser is the only way to.
  val pick = if (mimeOf(name) == "application/pdf" && Viewer.supported) {
    Intent.createChooser(view, null).putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(appContext, PDF_ALIAS)))
  } else view
  try {
    ctx.startActivity(pick.apply { if (ctx === appContext) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
  } catch (_: ActivityNotFoundException) {
    share(path, name)
  }
}

fun openUrl(url: String) {
  try {
    hostActivity?.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
  } catch (_: ActivityNotFoundException) {}
}

fun shareText(text: String) {
  hostActivity?.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }, null))
}

private fun displayName(uri: Uri): String? = try {
  appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
    if (c.moveToFirst()) c.getString(0) else null
  }
} catch (_: Exception) {
  null
}

/** Copies a picked document into the cache, so tools read an ordinary file however the picker handed it over. */
fun copyIn(uri: Uri, fallback: String): Picked? {
  val name = displayName(uri) ?: fallback
  val dir = File(appContext.cacheDir, "picked/${System.nanoTime()}").apply { mkdirs() }
  val out = File(dir, cleanName(name).ifEmpty { fallback })
  return try {
    appContext.contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } } ?: return null
    Picked(name, out.path, out.length())
  } catch (_: Exception) {
    null
  }
}

/** The system document picker for PDFs. Empty when closed. */
suspend fun pickPdfs(multi: Boolean): List<Picked> {
  val types = arrayOf("application/pdf")
  val uris = if (multi) awaitResult(ActivityResultContracts.OpenMultipleDocuments(), types)
  else listOfNotNull(awaitResult(ActivityResultContracts.OpenDocument(), types))
  return withContext(Dispatchers.IO) { uris.mapNotNull { copyIn(it, "document.pdf") } } // a big PDF must not copy on the main thread
}

/** The system photo picker. Empty when closed. */
suspend fun pickImages(multi: Boolean): List<Picked> {
  val req = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
  val uris = if (multi) awaitResult(ActivityResultContracts.PickMultipleVisualMedia(), req)
  else listOfNotNull(awaitResult(ActivityResultContracts.PickVisualMedia(), req))
  return withContext(Dispatchers.IO) { uris.mapNotNull { copyIn(it, "image.jpg") } }
}

fun mimeOfPicked(path: String): String? = try {
  val head = File(path).inputStream().use { s -> ByteArray(4).also { s.read(it) } }
  when {
    head[0] == 0x89.toByte() && head[1] == 'P'.code.toByte() -> "image/png"
    head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() -> "image/jpeg"
    else -> null
  }
} catch (_: Exception) {
  null
}

/**
 * Copies a saved file to a folder the user picks (Downloads, an SD card…). Returns that folder's name, or "" if the
 * picker was closed. Android adds " (1)" itself when the name is taken there.
 */
suspend fun saveToDevice(f: Saved): String {
  val tree = awaitResult(ActivityResultContracts.OpenDocumentTree(), null) ?: return ""
  val cr = appContext.contentResolver
  val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
  withContext(Dispatchers.IO) {
    val doc = DocumentsContract.createDocument(cr, parent, mimeOf(f.name), f.name) ?: error("Couldn't save there. Pick another folder.")
    cr.openOutputStream(doc)?.use { out -> File(f.path).inputStream().use { it.copyTo(out) } }
  }
  return Uri.decode(tree.toString()).split('/', ':').lastOrNull { it.isNotEmpty() } ?: "your folder" // …/tree/primary:Download -> Download
}

/** Save as Word doc / Save as JPEG: converted, then moved in next to the PDF (not synced: Drive sync is PDFs only). */
fun exportAs(pdf: Saved, toWord: Boolean): Saved {
  val tmp = if (toWord) pdfToDocx(pdf.path) else pdfToJpeg(pdf.path)
  val dir = File(pdf.path).parentFile ?: rootFolder()
  val f = freeFile(pdf.name.replace(Regex("\\.pdf$", RegexOption.IGNORE_CASE), ""), tmp.extension, dir)
  if (!tmp.renameTo(f)) { tmp.copyTo(f); tmp.delete() }
  Store.changed()
  return f.toSaved()
}
