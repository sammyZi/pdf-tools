package com.sammyi57.pdftools

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.io.File

// Tool catalog: what each screen asks for and which engine op it runs.

/** A tool's settings, as the UI left them: strings, booleans, and editor state (page order, boxes, items…). */
typealias Opts = JsonObject

val NoOpts = JsonObject(emptyMap())
fun Opts.str(k: String): String = (this[k] as? JsonPrimitive)?.contentOrNull ?: ""
fun Opts.bool(k: String): Boolean = (this[k] as? JsonPrimitive)?.booleanOrNull ?: false
fun Opts.with(k: String, v: String): Opts = JsonObject(this + (k to JsonPrimitive(v)))
fun Opts.with(k: String, v: Boolean): Opts = JsonObject(this + (k to JsonPrimitive(v)))
fun Opts.with(k: String, v: JsonElement): Opts = JsonObject(this + (k to v))
inline fun <reified T> Opts.get(k: String): T? = this[k]?.let { runCatching { json.decodeFromJsonElement<T>(it) }.getOrNull() }
inline fun <reified T> Opts.put(k: String, v: T): Opts = with(k, json.encodeToJsonElement(v))

sealed class OptDef(val key: String, val label: String, val visible: ((Opts) -> Boolean)?) {
  class Seg(key: String, label: String, val choices: List<Pair<String, String>>, val def: String, visible: ((Opts) -> Boolean)? = null) :
    OptDef(key, label, visible)

  class Text(
    key: String, label: String, val def: String, val placeholder: String = "", val keyboard: KeyboardType = KeyboardType.Text,
    val strip: Regex? = null, visible: ((Opts) -> Boolean)? = null,
  ) : OptDef(key, label, visible)

  class Toggle(key: String, label: String, val def: Boolean, visible: ((Opts) -> Boolean)? = null) : OptDef(key, label, visible)
}

fun defaults(defs: List<OptDef>): Opts = JsonObject(defs.associate {
  it.key to when (it) {
    is OptDef.Seg -> JsonPrimitive(it.def)
    is OptDef.Text -> JsonPrimitive(it.def)
    is OptDef.Toggle -> JsonPrimitive(it.def)
  }
})

enum class Input { Pdf, Pdfs, Images, Docs }

class Output(val name: String, val bytes: ByteArray, val ext: String = "pdf", val full: Boolean = false) // full: name already includes the source file's

class Step(val title: String, val opts: List<OptDef>, val fixed: Opts)

class Tool(
  val id: String,
  val section: Int,
  val title: String,
  val desc: String,
  val cta: String,
  val doing: String, // shown on the button while it runs
  val input: Input,
  val min: Int? = null,
  val noPreview: Boolean = false, // output isn't a PDF, or the editor already shows the result
  val opts: List<OptDef> = emptyList(),
  val step: Step? = null, // as a workflow step: its own title and options, and options fixed for it
  val run: (List<Named>, Opts) -> List<Output>,
) {
  val stepTitle get() = step?.title ?: title
  val stepOpts get() = step?.opts ?: opts
}

@Serializable
data class WorkflowStep(val id: String, val op: String, val opts: JsonObject)

// Grouped by the job you're doing, in the order those jobs usually come: get pages in, put them in order, put
// something on them, then finish up. "Capture" is shown as the Scan card on Home, and as a group only in search.
val SECTIONS = listOf(
  "Capture", "Combine & split", "Arrange pages", "Fill & mark up", "Protect & shrink", "Automate",
  "Optimize", "Convert to PDF", "Convert from PDF", "Edit PDF",
)

private fun seg(key: String, label: String, choices: List<Pair<String, String>>, def: String, visible: ((Opts) -> Boolean)? = null) =
  OptDef.Seg(key, label, choices, def, visible)

private fun text(key: String, label: String, def: String, placeholder: String = "", keyboard: KeyboardType = KeyboardType.Text) =
  OptDef.Text(key, label, def, placeholder, keyboard)

// Number keyboards still offer letters on many phones, and some have no comma or dash, so fields filter what they take.
private fun num(key: String, label: String, def: String, placeholder: String) =
  OptDef.Text(key, label, def, placeholder, KeyboardType.Decimal, Regex("[^0-9.]"))

private fun pageList(key: String, label: String, placeholder: String, visible: ((Opts) -> Boolean)? = null) =
  OptDef.Text(key, label, "", placeholder, KeyboardType.Text, Regex("[^0-9,\\-\\s]"), visible)

private object O {
  // "level" (or unset, in older drafts and workflow steps) picks a strength; "size" squeezes until it fits a limit.
  val squeeze = seg("mode", "Compress by", listOf("level" to "Level", "size" to "Fit under a size"), "level")
  val quality = seg("quality", "Compression level", listOf("80" to "Light", "60" to "Balanced", "35" to "Strong"), "60") { it.str("mode") != "size" }
  val target = OptDef.Text("targetMB", "Fit under (MB)", "2", "2", KeyboardType.Decimal, Regex("[^0-9.]")) { it.str("mode") == "size" }
  val wmText = text("text", "Watermark text", "CONFIDENTIAL", "CONFIDENTIAL")
  val wmScale = seg("scale", "Size", listOf("0.6" to "Small", "1" to "Medium", "1.4" to "Large"), "1")
  val opacity = seg("opacity", "Opacity", listOf("0.1" to "10%", "0.2" to "20%", "0.35" to "35%"), "0.2")
  val numPos = seg("position", "Position", listOf("left" to "Left", "center" to "Center", "right" to "Right"), "center")
  val numFmt = seg("format", "Format", listOf("n" to "1, 2, 3", "of" to "Page 1 of N"), "n")
  val angle = seg("angle", "Rotate clockwise by", listOf("90" to "90°", "180" to "180°", "270" to "270°"), "90")
  val axis = seg("axis", "Mirror", listOf("h" to "Horizontal", "v" to "Vertical", "both" to "Both"), "h")
  val perSheet = seg("perSheet", "Pages per sheet", listOf("2" to "2", "4" to "4", "6" to "6", "9" to "9", "16" to "16"), "4")
}

// The crop editor renders these itself, above the page it draws on.
val CROP_OPTS: List<OptDef> = listOf(
  seg("mode", "Mode", listOf("crop" to "Crop pages", "resize" to "Resize pages"), "crop"),
  seg("size", "Page size", SIZES.keys.map { it to it }, "A4") { it.str("mode") == "resize" },
)

private fun one(suffix: String, fn: (ByteArray, Opts) -> ByteArray) = { files: List<Named>, o: Opts -> listOf(Output(suffix, fn(files[0].bytes, o))) }
private fun many(fn: (ByteArray, Opts) -> List<Named>) = { files: List<Named>, o: Opts -> fn(files[0].bytes, o).map { Output(it.name, it.bytes) } }

// Workflow steps are the tools themselves: multi-PDF tools combine every current file, single-PDF tools run on each
// file. Excluded: camera input, custom editors, non-PDF output, and password (no step can read the PDF after it).
private val NOT_STEPS = setOf(
  "scan", "workflow", "organize", "redact", "fillSign", "zip", "password", "unlock", "metadata",
  "imagesToPdf", "wordToPdf", "powerpointToPdf", "excelToPdf", "htmlToPdf",
  "pdfToJpg", "pdfToWord", "pdfToPowerpoint", "pdfToExcel", "pdfToMarkdown",
  "pdfForms", "comparePdf",
)
fun stepTools() = TOOLS.filter { it.id !in NOT_STEPS }

private fun runWorkflow(files: List<Named>, steps: List<WorkflowStep>): List<Output> {
  if (steps.isEmpty()) fail("Add at least one step.")
  var cur = files.map { Named(it.name.replace(Regex("\\.pdf$", RegexOption.IGNORE_CASE), ""), it.bytes) }
  for (step in steps) {
    val t = toolById(step.op) ?: fail("A step in this workflow is no longer available. Remove it and try again.")
    val opts = JsonObject(step.opts + (t.step?.fixed ?: NoOpts))
    if (t.input == Input.Pdfs) {
      val base = cur[0].name
      cur = t.run(cur, opts).map { Named("$base-${it.name}", it.bytes) }
      continue
    }
    cur = cur.flatMap { f -> // one file at a time keeps memory flat
      val outs = t.run(listOf(f), opts)
      outs.map { Named(if (outs.size > 1) "${f.name}-${it.name}" else f.name, it.bytes) }
    }
  }
  return if (cur.size == 1) listOf(Output("processed", cur[0].bytes)) else cur.map { Output("${it.name}-processed", it.bytes, full = true) }
}

val TOOLS: List<Tool> = listOf(
  Tool(
    "scan", 0, "Scan Document", "Use your camera to scan any document and export it as a PDF, processed on your phone.",
    "Create PDF", "Building PDF", Input.Images,
  ) { _, _ -> fail("Open the scanner from the Scan button.") }, // the scanner builds its own PDF
  Tool(
    "workflow", 5, "PDF Workflow Builder",
    "Pick one or more PDFs, then chain any tools in order — merge, watermark, compress, split and more in one go.",
    "Run workflow", "Running your steps", Input.Pdfs, min = 1,
  ) { files, o -> runWorkflow(files, o.get<List<WorkflowStep>>("steps") ?: emptyList()) },
  Tool(
    "merge", 1, "Merge PDFs", "Combine multiple PDFs into one document. Reorder files before merging.", "Merge PDFs", "Merging", Input.Pdfs,
  ) { files, _ -> listOf(Output("merged", merge(files))) },
  Tool(
    "alternate", 1, "Alternate & Mix PDF", "Interleave the pages of two or more PDFs, one page from each in turn.",
    "Mix pages", "Interleaving pages", Input.Pdfs,
    opts = listOf(OptDef.Toggle("reverse", "Reverse later files (back sides of a duplex scan)", false)),
  ) { files, o -> listOf(Output("mixed", alternate(files, o.bool("reverse")))) },
  Tool(
    "compress", 4, "Compress PDF", "Shrink the photos and scans inside a PDF so the file gets much smaller. Text stays sharp.",
    "Compress PDF", "Recompressing images", Input.Pdf, opts = listOf(O.squeeze, O.quality, O.target),
    run = one("compressed") { b, o ->
      if (o.str("mode") == "size") compressToSize(b, o.str("targetMB")) else compress(b, o.str("quality").toIntOrNull() ?: 60)
    },
  ),
  Tool(
    "split", 1, "Split PDF", "Separate PDF into individual pages or extract specific ranges.", "Split PDF", "Splitting", Input.Pdf,
    opts = listOf(
      seg("mode", "Split into", listOf("every" to "Every page", "ranges" to "Custom ranges"), "every"),
      pageList("ranges", "Page ranges — one file per range", "1-3, 4-6, 7-") { it.str("mode") == "ranges" },
    ),
    run = many { b, o -> split(b, o.str("mode"), o.str("ranges")) },
  ),
  Tool(
    "splitText", 1, "Split PDF by Text", "Start a new file at every page containing a phrase you choose.",
    "Split by text", "Reading every page", Input.Pdf, opts = listOf(text("phrase", "Start a new file at pages containing", "", "e.g. Invoice No.")),
    run = many { b, o -> splitByText(b, o.str("phrase")) },
  ),
  Tool(
    "splitBookmarks", 1, "Split PDF by Bookmarks", "Turn each chapter or bookmarked section into its own PDF file.",
    "Split by bookmarks", "Reading bookmarks", Input.Pdf, run = many { b, _ -> splitByBookmarks(b) },
  ),
  Tool(
    "splitHalf", 1, "Split PDF in Half", "Cut a PDF into two files, or slice every page down the middle for book scans.",
    "Split in half", "Cutting pages", Input.Pdf,
    opts = listOf(seg("mode", "How to split", listOf("file" to "Two files", "vertical" to "Left | Right", "horizontal" to "Top / Bottom"), "file")),
    run = many { b, o -> splitHalf(b, o.str("mode")) },
  ),
  Tool(
    "splitSize", 1, "Split PDF by Size", "Break a large PDF into parts that each stay under a file size limit.",
    "Split by size", "Measuring parts", Input.Pdf, opts = listOf(num("maxMB", "Max size per part (MB)", "5", "5")),
    run = many { b, o -> splitBySize(b, o.str("maxMB")) },
  ),
  Tool(
    "organize", 2, "Organize Pages", "Rotate, delete, reorder, or rearrange PDF pages easily.", "Save pages", "Saving your page order",
    Input.Pdf, noPreview = true,
  ) { files, o -> listOf(Output("organized", organize(files[0].bytes, o.get<List<PageOrder>>("order") ?: emptyList()))) },
  Tool(
    "rotate", 2, "Rotate PDF", "Rotate individual pages or the entire PDF — 90°, 180°, 270°.", "Rotate PDF", "Rotating", Input.Pdf,
    opts = listOf(O.angle, pageList("pages", "Pages (leave blank for all)", "e.g. 1, 3-5")),
    run = one("rotated") { b, o -> rotate(b, o.str("angle").toIntOrNull() ?: 90, o.str("pages")) },
  ),
  Tool(
    "flip", 2, "Flip PDF", "Mirror every page of a PDF horizontally, vertically or both.", "Flip PDF", "Mirroring pages", Input.Pdf,
    opts = listOf(O.axis), run = one("flipped") { b, o -> flip(b, o.str("axis")) },
  ),
  Tool(
    "nup", 2, "Multiple Pages Per Sheet", "Place 2, 4, 6, 9 or 16 PDF pages on a single sheet to save paper.",
    "Arrange on sheets", "Laying out sheets", Input.Pdf, opts = listOf(O.perSheet, OptDef.Toggle("lines", "Thin lines between pages", false)),
    run = one("n-up") { b, o -> nUp(b, o.str("perSheet"), o.bool("lines")) },
  ),
  Tool(
    "crop", 2, "Crop & Resize",
    "Trim margins automatically or pull the corners in to keep just the part you want, or resize every page to a standard size (A4, Letter).",
    "Apply", "Resizing pages", Input.Pdf, opts = CROP_OPTS,
    step = Step("Resize pages", listOf(seg("size", "Page size", SIZES.keys.map { it to it }, "A4")), JsonObject(mapOf("mode" to JsonPrimitive("resize")))),
    run = one("resized") { b, o -> cropResize(b, o.str("mode").ifEmpty { "crop" }, o.str("size").ifEmpty { "A4" }, o.get<Map<String, List<Float>>>("boxes") ?: emptyMap()) },
  ),
  Tool(
    "zip", 1, "PDF to ZIP", "Bundle multiple PDFs into a single compressed ZIP archive.", "Create ZIP", "Zipping", Input.Pdfs, min = 1, noPreview = true,
  ) { files, _ -> listOf(Output("bundle", zipFiles(files), ext = "zip")) },
  Tool(
    "fillSign", 3, "Fill & Sign",
    "Type straight onto a form and drop in a picture of your signature, anywhere on any page — a real form or a scan of one.",
    "Apply", "Writing on the pages", Input.Pdf,
    run = one("signed") { b, o ->
      val items = o.get<List<SignItem>>("items") ?: emptyList()
      if (items.isEmpty()) fail("Add some text or a picture to a page first.")
      val images = HashMap<String, ByteArray>()
      for (it in items) {
        if (it.kind != "image" || it.uri in images) continue
        // A resumed draft can point at a picture the OS has since cleared from the cache.
        val f = File(pathOf(it.uri))
        if (!f.exists()) fail("A picture you added on page ${it.page + 1} is no longer available. Remove it and add it again.")
        images[it.uri] = f.readBytes()
      }
      fillSign(b, items, images)
    },
  ),
  Tool(
    "watermark", 3, "Add Watermark", "Stamp diagonal text like CONFIDENTIAL or DRAFT across every page.",
    "Add watermark", "Stamping every page", Input.Pdf, opts = listOf(O.wmText, O.wmScale, O.opacity),
    run = one("watermarked") { b, o -> watermark(b, o.str("text"), o.str("opacity").toFloatOrNull() ?: 0.2f, o.str("scale").toFloatOrNull() ?: 1f) },
  ),
  Tool(
    "pageNumbers", 3, "Add Page Numbers", "Number every page at the bottom, left, center or right.", "Add page numbers", "Numbering pages",
    Input.Pdf, opts = listOf(O.numPos, O.numFmt), run = one("numbered") { b, o -> pageNumbers(b, o.str("position"), o.str("format")) },
  ),
  Tool(
    "redact", 3, "Redact PDF", "Black out names, numbers or anything private for good, so it can’t be copied, searched or uncovered.",
    "Redact PDF", "Blacking out", Input.Pdf,
  ) { files, o ->
    val boxes = o.get<Map<String, List<List<Float>>>>("boxes") ?: emptyMap()
    if (boxes.isEmpty()) fail("Drag across at least one area to black it out.")
    listOf(Output("redacted", redactPdf(files[0].bytes, boxes)))
  },
  Tool(
    "ocr", 4, "OCR Scanned PDF",
    "Read the text in a scanned PDF and lay it invisibly over the pages, so it can be searched, selected and copied. Runs on your phone.",
    "Make searchable", "Reading every page", Input.Pdf,
    run = one("searchable") { b, _ -> ocrPdf(b) ?: fail("No text found. This PDF may already be searchable, or be too blurry to read.") },
  ),
  Tool(
    "unlock", 4, "Remove Password", "Save a copy of a locked PDF that opens without its password and has no restrictions.",
    "Remove password", "Unlocking", Input.Pdf, noPreview = true,
    run = one("unlocked") { b, _ -> removePassword(b) }, // the screen asks for the password when the file is picked
  ),
  Tool(
    "metadata", 3, "Edit PDF Details", "Change the title, author, subject and keywords other apps and Drive show for a PDF.",
    "Save details", "Saving details", Input.Pdf, noPreview = true,
  ) { files, o -> listOf(Output("details", writeDetails(files[0].bytes, o.str("title"), o.str("author"), o.str("subject"), o.str("keywords")))) },
  Tool(
    "password", 4, "Set Password", "Lock a PDF so it asks for a password before it opens.", "Set password", "Encrypting", Input.Pdf,
    noPreview = true, opts = listOf(text("password", "Password", "", "Type a password", KeyboardType.Password)),
    run = one("protected") { b, o ->
      val pw = o.str("password")
      if (pw.isEmpty()) fail("Enter a password.")
      setPdfPassword(b, pw) ?: fail("This PDF already has a password.")
    },
  ),
  Tool(
    "removePages", 2, "Remove Pages", "Delete selected pages or page ranges from your PDF document.",
    "Remove pages", "Removing pages", Input.Pdf,
    opts = listOf(pageList("pages", "Pages to remove", "e.g. 1, 3-5")),
    run = one("removed") { b, o -> removePages(b, o.str("pages")) },
  ),
  Tool(
    "extractPages", 2, "Extract Pages", "Extract specific pages or page ranges into a new PDF document.",
    "Extract pages", "Extracting pages", Input.Pdf,
    opts = listOf(pageList("pages", "Pages to extract", "e.g. 1-3, 5")),
    run = one("extracted") { b, o -> extractPages(b, o.str("pages")) },
  ),
  Tool(
    "optimizePdf", 6, "Optimize PDF", "Optimize PDF structure, compress streams, and remove redundant metadata and unreferenced objects.",
    "Optimize PDF", "Optimizing", Input.Pdf,
    run = one("optimized") { b, _ -> optimizePdf(b) },
  ),
  Tool(
    "repairPdf", 6, "Repair PDF", "Recover readable pages and rebuild structure from damaged or corrupted PDF files.",
    "Repair PDF", "Repairing", Input.Pdf,
    run = one("repaired") { b, _ -> repairPdf(b) },
  ),
  Tool(
    "imagesToPdf", 7, "Images to PDF", "Convert photos and images into a single PDF document.",
    "Create PDF", "Converting images", Input.Images, min = 1,
    opts = listOf(seg("fit", "Page size", listOf("fit" to "Fit image size", "a4" to "A4 portrait"), "fit")),
    run = { files, o -> listOf(Output("converted", imagesToPdf(files, o.str("fit")))) },
  ),
  Tool(
    "wordToPdf", 7, "Word to PDF", "Convert Word documents (.docx) to PDF. Basic and simple documents only.",
    "Convert to PDF", "Converting Word document", Input.Docs, min = 1,
    run = one("converted") { b, _ -> wordToPdf(b) },
  ),
  Tool(
    "powerpointToPdf", 7, "PowerPoint to PDF", "Convert PowerPoint presentations (.pptx) to PDF. Basic and simple documents only, text and pictures only.",
    "Convert to PDF", "Converting slides", Input.Docs, min = 1,
    run = one("converted") { b, _ -> powerpointToPdf(b) },
  ),
  Tool(
    "excelToPdf", 7, "Excel to PDF", "Convert Excel spreadsheets (.xlsx) to PDF. Basic and simple documents only.",
    "Convert to PDF", "Converting spreadsheet", Input.Docs, min = 1,
    run = one("converted") { b, _ -> excelToPdf(b) },
  ),
  Tool(
    "htmlToPdf", 7, "HTML to PDF", "Convert HTML files to PDF. Local files only with WebView offline, network loads blocked and JavaScript off.",
    "Convert to PDF", "Rendering HTML", Input.Docs, min = 1,
    run = one("converted") { b, _ -> htmlToPdf(b) },
  ),
  Tool(
    "pdfToJpg", 8, "PDF to JPG", "Extract every page as a high-quality JPG image.",
    "Convert to JPG", "Converting pages", Input.Pdf, noPreview = true,
    run = { files, _ -> runPdfToJpg(files[0].bytes) },
  ),
  Tool(
    "pdfToWord", 8, "PDF to Word", "Convert PDF pages into an editable Word document (.docx).",
    "Convert to Word", "Converting to Word", Input.Pdf, noPreview = true,
    run = { files, _ -> runPdfToWord(files[0].bytes) },
  ),
  Tool(
    "pdfToPowerpoint", 8, "PDF to PowerPoint", "Convert PDF pages into PowerPoint slides (.pptx). Page images not editable.",
    "Convert to PowerPoint", "Building presentation", Input.Pdf, noPreview = true,
    run = { files, _ -> listOf(Output("presentation", pdfToPowerpoint(files[0].bytes), ext = "pptx")) },
  ),
  Tool(
    "pdfToExcel", 8, "PDF to Excel", "Extract tables and structured data from PDF into an Excel spreadsheet (.xlsx).",
    "Convert to Excel", "Extracting tables", Input.Pdf, noPreview = true,
    run = { files, _ -> listOf(Output("spreadsheet", pdfToExcel(files[0].bytes), ext = "xlsx")) },
  ),
  Tool(
    "pdfToPdfa", 8, "PDF to PDF/A", "Convert PDF to PDF/A archive format for long-term preservation. Best effort and not validated, never claim certified conformance.",
    "Convert to PDF/A", "Converting to PDF/A", Input.Pdf,
    run = one("archived") { b, _ -> pdfToPdfa(b) },
  ),
  Tool(
    "pdfToMarkdown", 8, "PDF to Markdown", "Extract text from PDF formatted as clean Markdown text.",
    "Convert to Markdown", "Extracting Markdown", Input.Pdf, noPreview = true,
    run = { files, _ -> listOf(Output("extracted", pdfToMarkdown(files[0].bytes).toByteArray(Charsets.UTF_8), ext = "md")) },
  ),
  Tool(
    "pdfForms", 9, "PDF Forms", "Fill out interactive AcroForm fields in PDF documents. Real AcroForm distinct from Fill and Sign, XFA unsupported.",
    "Save PDF", "Saving form fields", Input.Pdf,
    run = one("filled") { b, o ->
      val values = o.get<Map<String, String>>("fields") ?: emptyMap()
      fillFormFields(b, values)
    },
  ),
  Tool(
    "comparePdf", 9, "Compare PDF", "Compare two PDF documents side by side to find differences. Needs exactly 2 PDFs.",
    "Compare PDFs", "Comparing", Input.Pdfs, min = 2,
    run = { files, _ -> listOf(Output("comparison", comparePdf(files))) },
  ),
)

fun toolById(id: String?) = TOOLS.find { it.id == id }

/** Tools pinned to the top of Home, in the order they were pinned. */
object Pins {
  private val file get() = File(appContext.filesDir, "pinned-tools.txt")
  var ids by mutableStateOf(runCatching { file.readLines().filter { toolById(it) != null } }.getOrDefault(emptyList()))
    private set

  fun toggle(id: String) {
    ids = if (id in ids) ids - id else ids + id
    runCatching { file.writeText(ids.joinToString("\n")) }
  }
}

// ── The tool left unfinished ────────────────────────────────────────────────
// Its picked files and options, kept after going back or the app closing, so it can be reopened as you left it.

@Serializable
data class ToolDraft(val tool: String, val files: List<DraftFile>, val opts: JsonObject)

@Serializable
data class DraftFile(val name: String, @SerialName("uri") val path: String, val size: Long? = null)

object Drafts {
  private val file get() = File(appContext.filesDir, "tool-draft.json") // same file the Expo app used
  var current by mutableStateOf(read())
    private set

  private fun read(): ToolDraft? = try {
    val d = json.decodeFromString<ToolDraft>(file.readText())
    // Device picks live in the cache folder, which the OS may have cleared since.
    val files = d.files.map { it.copy(path = pathOf(it.path)) }.filter { File(it.path).exists() }
    if (files.isEmpty()) null else d.copy(files = files)
  } catch (_: Exception) {
    null
  }

  fun set(d: ToolDraft?) {
    current = d
    try {
      if (d == null) file.delete() else file.writeText(json.encodeToString(d))
    } catch (_: Exception) {} // disk trouble: the draft just won't survive a restart
  }
}

fun Picked.toDraft() = DraftFile(name, path, size)
fun DraftFile.toPicked() = Picked(name, path, size)
