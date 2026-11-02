package com.sammyi57.pdftools

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.Calendar

// A scan is a draft document: original photos plus every edit as settings, so crops, filters and strokes can be redone
// any time, even after the PDF was saved. Stored under files/scans/<id>/doc.json, the same place the Expo app used.

@Serializable
data class Stroke(val shape: String = "path", val points: List<Float> = emptyList(), val width: Float = 0.01f, val color: String = "#000000")

@Serializable
data class Shot(
  val src: String, // original photo, never modified
  val width: Int,
  val height: Int,
  val detected: List<Float>? = null, // what auto-detect found, for the crop screen's "Auto-detect"
  val corners: List<Float>? = null, // null = no crop
  val rotation: Int = 0,
  val filter: String = "original",
  val brightness: Float = 0f,
  val contrast: Float = 0f,
  val strokes: List<Stroke> = emptyList(),
  val rev: Int = 1, // bumped on every edit; a render only lands if it's still current
  val out: String? = null, // rendered page image
  val outRev: Int? = null, // the rev `out` was rendered from
  val outW: Int? = null,
  val outH: Int? = null,
  val bg: String? = null, // paper color of the render, cleanup's "Auto" fill
) {
  val rendered get() = out != null && outRev == rev
  val shownW get() = outW ?: width
  val shownH get() = outH ?: height
}

@Serializable
data class ScanPage(val id: String, val kind: String, val size: String, val shots: List<Shot>)

@Serializable
data class PdfRef(@SerialName("uri") val path: String, val name: String)

@Serializable
data class SavedScan(val name: String, val pages: List<ScanPage>, val sig: String = "")

@Serializable
data class ScanDoc(
  val id: String,
  val name: String,
  val created: Long,
  val modified: Long,
  val pages: List<ScanPage>,
  val pdf: PdfRef? = null,
  val saved: SavedScan? = null, // state at the last Save PDF, for "Discard changes"
)

private val kindLabelCache = PerLocale { linkedMapOf(
  "whiteboard" to appContext.getString(R.string.scan_kind_whiteboard), "book" to appContext.getString(R.string.scan_kind_book),
  "doc" to appContext.getString(R.string.scan_kind_document), "id" to appContext.getString(R.string.scan_kind_id_card),
  "card" to appContext.getString(R.string.scan_kind_business_card),
) }
val KIND_LABEL: Map<String, String> get() = kindLabelCache.get()
private val filtersCache = PerLocale { listOf(
  "original" to appContext.getString(R.string.scan_filter_original_color), "auto" to appContext.getString(R.string.scan_filter_auto_color),
  "light" to appContext.getString(R.string.scan_filter_light_text), "gray" to appContext.getString(R.string.scan_filter_grayscale),
  "whiteboard" to appContext.getString(R.string.scan_filter_whiteboard),
) }
val FILTERS: List<Pair<String, String>> get() = filtersCache.get()
private val pageSizesCache = PerLocale { listOf(
  "fit" to appContext.getString(R.string.page_size_original), "A4-portrait" to appContext.getString(R.string.page_size_a4_portrait),
  "A4-landscape" to appContext.getString(R.string.page_size_a4_landscape), "A5-portrait" to appContext.getString(R.string.page_size_a5_portrait),
  "A5-landscape" to appContext.getString(R.string.page_size_a5_landscape), "Letter-portrait" to appContext.getString(R.string.page_size_letter_portrait),
  "Letter-landscape" to appContext.getString(R.string.page_size_letter_landscape),
  "Legal-portrait" to appContext.getString(R.string.page_size_legal_portrait), "Legal-landscape" to appContext.getString(R.string.page_size_legal_landscape),
) }
val PAGE_SIZES: List<Pair<String, String>> get() = pageSizesCache.get()

fun uid(): String = java.lang.Long.toString(System.currentTimeMillis(), 36) + (1..5).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")

private fun scansRoot() = File(appContext.filesDir, "scans").apply { mkdirs() }
private fun dirOf(id: String) = File(scansRoot(), id).apply { mkdirs() }

fun newDoc(): ScanDoc {
  val d = Calendar.getInstance()
  val name = "${appContext.getString(R.string.doc_default_name_prefix)} ${d.get(Calendar.YEAR)}-${pad2(d.get(Calendar.MONTH) + 1)}-${pad2(d.get(Calendar.DAY_OF_MONTH))} ${pad2(d.get(Calendar.HOUR_OF_DAY))}.${pad2(d.get(Calendar.MINUTE))}"
  return ScanDoc(uid(), name, System.currentTimeMillis(), System.currentTimeMillis(), emptyList())
}

/**
 * A mode's starting edits, like Adobe Scan: auto crop (full frame for whiteboards), Auto-color for paper, original
 * color for cards. Also what Reset goes back to.
 */
fun Shot.withDefaults(kind: String, detected: List<Float>?) = copy(
  corners = if (kind == "whiteboard") null else detected,
  rotation = 0,
  filter = when (kind) { "whiteboard" -> "whiteboard"; "id", "card" -> "original"; else -> "auto" },
  brightness = 0f,
  contrast = 0f,
  strokes = emptyList(),
)

/** Moves a camera/gallery photo into the scan's folder and applies the mode's defaults. */
fun makeShot(docId: String, photo: Photo, kind: String): Shot {
  val dest = File(dirOf(docId), "src-${uid()}.jpg")
  val src = File(pathOf(photo.path))
  if (!src.renameTo(dest)) src.copyTo(dest, true) // gallery files we may not own
  return Shot(dest.path, photo.width, photo.height, photo.corners).withDefaults(kind, photo.corners)
}

/** Renders the shot's settings to a fresh file (a new name each time, so an image never shows a stale copy). */
suspend fun renderShot(docId: String, shot: Shot): Shot = withContext(Dispatchers.Default) {
  val r = DocVision.process(
    appContext, shot.src,
    ProcessOptions(shot.corners, shot.rotation, shot.filter, shot.brightness, shot.contrast, shot.strokes, 2400, File(dirOf(docId), "out-${uid()}.jpg").path),
  )
  shot.copy(out = r.path, outW = r.width, outH = r.height, bg = r.bg, outRev = shot.rev)
}

suspend fun processImage(path: String, o: ProcessOptions): Processed = withContext(Dispatchers.Default) { DocVision.process(appContext, path, o) }

/** Finds the page in an existing photo (gallery import). Corners are null when none is found. */
suspend fun detectPage(path: String): Photo = withContext(Dispatchers.Default) {
  val bmp = DocVision.load(appContext, path, 1600)
  val corners = DocVision.detectBitmap(bmp)?.map { it.toFloat() }
  Photo(path, bmp.width, bmp.height, corners).also { bmp.recycle() } // only the shape matters: corners are fractions
}

/** What to keep of a rendered PDF page: [x, y, w, h] fractions, y from the top. Null when there's nothing to trim. */
suspend fun autoCropPage(path: String): List<Float>? = withContext(Dispatchers.Default) {
  val bmp = DocVision.load(appContext, path, 1200)
  try { DocVision.autoCrop(bmp)?.map { it.toFloat() } } finally { bmp.recycle() }
}

fun deleteQuietly(path: String?) {
  if (path != null) runCatching { File(pathOf(path)).delete() }
}

fun saveDoc(doc: ScanDoc) {
  runCatching { File(dirOf(doc.id), "doc.json").writeText(json.encodeToString(doc.copy(modified = System.currentTimeMillis()))) }
}

fun loadDoc(id: String): ScanDoc? = try {
  json.decodeFromString<ScanDoc>(File(File(scansRoot(), id), "doc.json").readText())
} catch (_: Exception) {
  null
}

fun listDocs(): List<ScanDoc> = (scansRoot().listFiles() ?: emptyArray()).filter { it.isDirectory }
  .mapNotNull { loadDoc(it.name) }.filter { it.pages.isNotEmpty() }.sortedByDescending { it.modified }

fun deleteDoc(id: String) {
  runCatching { File(scansRoot(), id).deleteRecursively() }
}

// Everything a user can change, minus render output, so re-renders don't count as edits.
private fun ScanPage.edits() = listOf(id, kind, size, shots.map { listOf(pathOf(it.src), it.corners, it.rotation, it.filter, it.brightness, it.contrast, it.strokes) })

/** True for a draft that was never saved, or a saved scan edited since. */
fun ScanDoc.isDirty(): Boolean = saved == null || saved.name != name || saved.pages.map { it.edits() } != pages.map { it.edits() }

val ScanDoc.isRendered get() = pages.all { p -> p.shots.all { it.rendered } }

/** Whether saved scans get OCR'd so their text can be searched and copied (Settings → Scanning). On unless turned off. */
object ScanPrefs {
  private val file get() = File(appContext.filesDir, "scan-ocr.txt")
  var searchable by androidx.compose.runtime.mutableStateOf(runCatching { !file.exists() || file.readText().trim() != "off" }.getOrDefault(true))
    private set

  fun save(on: Boolean) {
    searchable = on
    runCatching { file.writeText(if (on) "on" else "off") }
  }
}

/** Builds the PDF into Files. Re-saving an edited scan replaces its earlier PDF under the same name. */
suspend fun exportPdf(doc: ScanDoc): ScanDoc = withContext(Dispatchers.IO) {
  if (doc.pages.isEmpty()) fail(appContext.getString(R.string.msg_at_least_one_page_required))
  val images = ArrayList<String>()
  val sheets = doc.pages.flatMap { p ->
    val first = images.size
    images.addAll(p.shots.map { pathOf(it.out ?: it.src) })
    layoutScanPage(p.kind, p.size, p.shots.map { it.shownW to it.shownH }).map { s -> s.copy(items = s.items.map { it.copy(img = first + it.img) }) }
  }
  val tmp = buildScanPdf(sheets, images)
  // An invisible text layer over the words, so the scan can be searched and copied. Best effort: a page OCR can't
  // read (or any OCR failure) just leaves the PDF as plain pictures.
  if (ScanPrefs.searchable) runCatching { ocrPdf(tmp.readBytes()) }.getOrNull()?.let { tmp.writeBytes(it) }
  // Replace the earlier PDF wherever it is now: moves and renames in Files keep `pdf` pointing at it. Only ever that
  // exact file: if it's gone, the new PDF is saved fresh rather than replacing some other file with the same name.
  val old = doc.pdf?.let { File(pathOf(it.path)) }?.takeIf { it.isFile }
  val folder = old?.parent
  if (old != null) removeFile(old.path)
  val file = adoptTemp(tmp, doc.name, "pdf", folder)
  val next = doc.copy(pdf = PdfRef(file.path, file.name), saved = SavedScan(doc.name, doc.pages))
  saveDoc(next)
  cleanFiles(next)
  next
}

/** After its PDF is renamed in Files: the scan takes the new name, so "Edit scan" still finds it and a re-save keeps it. */
fun relinkPdf(oldPath: String, pdf: Saved) {
  val doc = listDocs().find { it.pdf?.let { p -> pathOf(p.path) } == oldPath } ?: return
  val name = pdf.name.replace(Regex("\\.pdf$", RegexOption.IGNORE_CASE), "")
  val clean = doc.saved != null && !doc.isDirty() // a saved scan stays "Saved" rather than turning into "Unsaved changes"
  val next = doc.copy(name = name, pdf = PdfRef(pdf.path, pdf.name))
  saveDoc(if (clean) next.copy(saved = doc.saved!!.copy(name = name)) else next)
}

/** After a folder in Files is renamed: scans whose PDF is inside it follow it there. */
fun relinkFolder(oldDir: String, newDir: String) {
  for (doc in listDocs()) {
    val pdf = doc.pdf ?: continue
    val p = pathOf(pdf.path)
    if (p.startsWith(oldDir + File.separator)) saveDoc(doc.copy(pdf = pdf.copy(path = newDir + p.substring(oldDir.length))))
  }
}

/** The most recent scan with unsaved work, to offer "Continue" when the scanner opens. */
fun latestDraft(): ScanDoc? = listDocs().find { it.isDirty() }

/** Throws away unsaved work: a never-saved scan is deleted (null), a saved one goes back to its last save. */
fun discardChanges(doc: ScanDoc): ScanDoc? {
  if (doc.saved == null) {
    deleteDoc(doc.id)
    return null
  }
  val next = doc.copy(name = doc.saved.name, pages = doc.saved.pages)
  saveDoc(next)
  cleanFiles(next)
  return next
}

// Photos and renders stay on disk while anything (current pages or the last save) points at them; this sweeps the rest.
fun cleanFiles(doc: ScanDoc) {
  val keep = HashSet<String>()
  for (p in doc.pages + (doc.saved?.pages ?: emptyList())) for (sh in p.shots) {
    keep.add(File(pathOf(sh.src)).name)
    sh.out?.let { keep.add(File(pathOf(it)).name) }
  }
  runCatching { dirOf(doc.id).listFiles()?.forEach { if (it.isFile && it.name != "doc.json" && it.name !in keep) it.delete() } }
}
