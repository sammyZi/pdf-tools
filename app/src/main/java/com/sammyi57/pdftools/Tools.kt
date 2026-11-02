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

enum class Input { Pdf, Pdfs, Images }

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
private val sectionsCache = PerLocale { listOf(
  appContext.getString(R.string.section_capture), appContext.getString(R.string.section_combine_split),
  appContext.getString(R.string.section_arrange_pages), appContext.getString(R.string.section_fill_markup),
  appContext.getString(R.string.section_protect_shrink), appContext.getString(R.string.section_automate),
) }
val SECTIONS: List<String> get() = sectionsCache.get()

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
  fun squeeze() = seg("mode", appContext.getString(R.string.opt_compress_by_label), listOf("level" to appContext.getString(R.string.opt_level), "size" to appContext.getString(R.string.opt_fit_under_size)), "level")
  fun quality() = seg("quality", appContext.getString(R.string.opt_compression_level_label), listOf("80" to appContext.getString(R.string.opt_light), "60" to appContext.getString(R.string.opt_balanced), "35" to appContext.getString(R.string.opt_strong)), "60") { it.str("mode") != "size" }
  fun target() = OptDef.Text("targetMB", appContext.getString(R.string.opt_fit_under_mb_label), "2", "2", KeyboardType.Decimal, Regex("[^0-9.]")) { it.str("mode") == "size" }
  fun wmText() = text("text", appContext.getString(R.string.opt_watermark_text_label), appContext.getString(R.string.opt_watermark_default_text), appContext.getString(R.string.opt_watermark_default_text))
  fun wmScale() = seg("scale", appContext.getString(R.string.opt_size_label), listOf("0.6" to appContext.getString(R.string.opt_small), "1" to appContext.getString(R.string.opt_medium), "1.4" to appContext.getString(R.string.opt_large)), "1")
  fun opacity() = seg("opacity", appContext.getString(R.string.opt_opacity_label), listOf("0.1" to "10%", "0.2" to "20%", "0.35" to "35%"), "0.2")
  fun numPos() = seg("position", appContext.getString(R.string.opt_position_label), listOf("left" to appContext.getString(R.string.opt_left), "center" to appContext.getString(R.string.opt_center), "right" to appContext.getString(R.string.opt_right)), "center")
  fun numFmt() = seg("format", appContext.getString(R.string.opt_format_label), listOf("n" to "1, 2, 3", "of" to appContext.getString(R.string.opt_page_1_of_n)), "n")
  fun angle() = seg("angle", appContext.getString(R.string.opt_rotate_clockwise_label), listOf("90" to "90°", "180" to "180°", "270" to "270°"), "90")
  fun axis() = seg("axis", appContext.getString(R.string.opt_mirror_label), listOf("h" to appContext.getString(R.string.opt_horizontal), "v" to appContext.getString(R.string.opt_vertical), "both" to appContext.getString(R.string.opt_both)), "h")
  fun perSheet() = seg("perSheet", appContext.getString(R.string.opt_pages_per_sheet_label), listOf("2" to "2", "4" to "4", "6" to "6", "9" to "9", "16" to "16"), "4")
}

// The crop editor renders these itself, above the page it draws on.
private val cropOptsCache = PerLocale { listOf(
  seg("mode", appContext.getString(R.string.opt_crop_mode_label), listOf("crop" to appContext.getString(R.string.opt_crop_pages), "resize" to appContext.getString(R.string.opt_resize_pages)), "crop"),
  seg("size", appContext.getString(R.string.opt_page_size_label), SIZES.keys.map { it to it }, "A4") { it.str("mode") == "resize" },
) }
val CROP_OPTS: List<OptDef> get() = cropOptsCache.get()

private fun one(suffix: String, fn: (ByteArray, Opts) -> ByteArray) = { files: List<Named>, o: Opts -> listOf(Output(suffix, fn(files[0].bytes, o))) }
private fun many(fn: (ByteArray, Opts) -> List<Named>) = { files: List<Named>, o: Opts -> fn(files[0].bytes, o).map { Output(it.name, it.bytes) } }

// Workflow steps are the tools themselves: multi-PDF tools combine every current file, single-PDF tools run on each
// file. Excluded: camera input, custom editors, non-PDF output, and password (no step can read the PDF after it).
private val NOT_STEPS = setOf("scan", "workflow", "organize", "redact", "fillSign", "zip", "password", "unlock", "metadata")
fun stepTools() = TOOLS.filter { it.id !in NOT_STEPS }

private fun runWorkflow(files: List<Named>, steps: List<WorkflowStep>): List<Output> {
  if (steps.isEmpty()) fail(appContext.getString(R.string.tool_workflow_fail_no_steps))
  var cur = files.map { Named(it.name.replace(Regex("\\.pdf$", RegexOption.IGNORE_CASE), ""), it.bytes) }
  for (step in steps) {
    val t = toolById(step.op) ?: fail(appContext.getString(R.string.tool_workflow_fail_step_unavailable))
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

private val toolsCache = PerLocale { listOf(
  Tool(
    "scan", 0, appContext.getString(R.string.tool_scan_title), appContext.getString(R.string.tool_scan_desc),
    appContext.getString(R.string.tool_scan_cta), appContext.getString(R.string.tool_scan_doing), Input.Images,
  ) { _, _ -> fail(appContext.getString(R.string.tool_scan_fail_use_button)) }, // the scanner builds its own PDF
  Tool(
    "workflow", 5, appContext.getString(R.string.tool_workflow_title),
    appContext.getString(R.string.tool_workflow_desc),
    appContext.getString(R.string.tool_workflow_cta), appContext.getString(R.string.tool_workflow_doing), Input.Pdfs, min = 1,
  ) { files, o -> runWorkflow(files, o.get<List<WorkflowStep>>("steps") ?: emptyList()) },
  Tool(
    "merge", 1, appContext.getString(R.string.tool_merge_title), appContext.getString(R.string.tool_merge_desc), appContext.getString(R.string.tool_merge_cta), appContext.getString(R.string.tool_merge_doing), Input.Pdfs,
  ) { files, _ -> listOf(Output("merged", merge(files))) },
  Tool(
    "alternate", 1, appContext.getString(R.string.tool_alternate_title), appContext.getString(R.string.tool_alternate_desc),
    appContext.getString(R.string.tool_alternate_cta), appContext.getString(R.string.tool_alternate_doing), Input.Pdfs,
    opts = listOf(OptDef.Toggle("reverse", appContext.getString(R.string.opt_alternate_reverse_label), false)),
  ) { files, o -> listOf(Output("mixed", alternate(files, o.bool("reverse")))) },
  Tool(
    "compress", 4, appContext.getString(R.string.tool_compress_title), appContext.getString(R.string.tool_compress_desc),
    appContext.getString(R.string.tool_compress_cta), appContext.getString(R.string.tool_compress_doing), Input.Pdf, opts = listOf(O.squeeze(), O.quality(), O.target()),
    run = one("compressed") { b, o ->
      if (o.str("mode") == "size") compressToSize(b, o.str("targetMB")) else compress(b, o.str("quality").toIntOrNull() ?: 60)
    },
  ),
  Tool(
    "split", 1, appContext.getString(R.string.tool_split_title), appContext.getString(R.string.tool_split_desc), appContext.getString(R.string.tool_split_cta), appContext.getString(R.string.tool_split_doing), Input.Pdf,
    opts = listOf(
      seg("mode", appContext.getString(R.string.opt_split_into_label), listOf("every" to appContext.getString(R.string.opt_every_page), "ranges" to appContext.getString(R.string.opt_custom_ranges)), "every"),
      pageList("ranges", appContext.getString(R.string.opt_split_ranges_label), "1-3, 4-6, 7-") { it.str("mode") == "ranges" },
    ),
    run = many { b, o -> split(b, o.str("mode"), o.str("ranges")) },
  ),
  Tool(
    "splitText", 1, appContext.getString(R.string.tool_split_text_title), appContext.getString(R.string.tool_split_text_desc),
    appContext.getString(R.string.tool_split_text_cta), appContext.getString(R.string.tool_split_text_doing), Input.Pdf, opts = listOf(text("phrase", appContext.getString(R.string.opt_split_text_phrase_label), "", appContext.getString(R.string.opt_split_text_phrase_placeholder))),
    run = many { b, o -> splitByText(b, o.str("phrase")) },
  ),
  Tool(
    "splitBookmarks", 1, appContext.getString(R.string.tool_split_bookmarks_title), appContext.getString(R.string.tool_split_bookmarks_desc),
    appContext.getString(R.string.tool_split_bookmarks_cta), appContext.getString(R.string.tool_split_bookmarks_doing), Input.Pdf, run = many { b, _ -> splitByBookmarks(b) },
  ),
  Tool(
    "splitHalf", 1, appContext.getString(R.string.tool_split_half_title), appContext.getString(R.string.tool_split_half_desc),
    appContext.getString(R.string.tool_split_half_cta), appContext.getString(R.string.tool_split_half_doing), Input.Pdf,
    opts = listOf(seg("mode", appContext.getString(R.string.opt_split_half_mode_label), listOf("file" to appContext.getString(R.string.opt_two_files), "vertical" to appContext.getString(R.string.opt_left_right), "horizontal" to appContext.getString(R.string.opt_top_bottom)), "file")),
    run = many { b, o -> splitHalf(b, o.str("mode")) },
  ),
  Tool(
    "splitSize", 1, appContext.getString(R.string.tool_split_size_title), appContext.getString(R.string.tool_split_size_desc),
    appContext.getString(R.string.tool_split_size_cta), appContext.getString(R.string.tool_split_size_doing), Input.Pdf, opts = listOf(num("maxMB", appContext.getString(R.string.opt_split_size_max_mb_label), "5", "5")),
    run = many { b, o -> splitBySize(b, o.str("maxMB")) },
  ),
  Tool(
    "organize", 2, appContext.getString(R.string.tool_organize_title), appContext.getString(R.string.tool_organize_desc), appContext.getString(R.string.tool_organize_cta), appContext.getString(R.string.tool_organize_doing),
    Input.Pdf, noPreview = true,
  ) { files, o -> listOf(Output("organized", organize(files[0].bytes, o.get<List<PageOrder>>("order") ?: emptyList()))) },
  Tool(
    "rotate", 2, appContext.getString(R.string.tool_rotate_title), appContext.getString(R.string.tool_rotate_desc), appContext.getString(R.string.tool_rotate_cta), appContext.getString(R.string.tool_rotate_doing), Input.Pdf,
    opts = listOf(O.angle(), pageList("pages", appContext.getString(R.string.opt_rotate_pages_label), appContext.getString(R.string.opt_rotate_pages_placeholder))),
    run = one("rotated") { b, o -> rotate(b, o.str("angle").toIntOrNull() ?: 90, o.str("pages")) },
  ),
  Tool(
    "flip", 2, appContext.getString(R.string.tool_flip_title), appContext.getString(R.string.tool_flip_desc), appContext.getString(R.string.tool_flip_cta), appContext.getString(R.string.tool_flip_doing), Input.Pdf,
    opts = listOf(O.axis()), run = one("flipped") { b, o -> flip(b, o.str("axis")) },
  ),
  Tool(
    "nup", 2, appContext.getString(R.string.tool_nup_title), appContext.getString(R.string.tool_nup_desc),
    appContext.getString(R.string.tool_nup_cta), appContext.getString(R.string.tool_nup_doing), Input.Pdf, opts = listOf(O.perSheet(), OptDef.Toggle("lines", appContext.getString(R.string.opt_nup_lines_label), false)),
    run = one("n-up") { b, o -> nUp(b, o.str("perSheet"), o.bool("lines")) },
  ),
  Tool(
    "crop", 2, appContext.getString(R.string.tool_crop_title),
    appContext.getString(R.string.tool_crop_desc),
    appContext.getString(R.string.action_apply), appContext.getString(R.string.tool_crop_doing), Input.Pdf, opts = CROP_OPTS,
    step = Step(appContext.getString(R.string.opt_crop_resize_pages_step_title), listOf(seg("size", appContext.getString(R.string.opt_page_size_label), SIZES.keys.map { it to it }, "A4")), JsonObject(mapOf("mode" to JsonPrimitive("resize")))),
    run = one("resized") { b, o -> cropResize(b, o.str("mode").ifEmpty { "crop" }, o.str("size").ifEmpty { "A4" }, o.get<Map<String, List<Float>>>("boxes") ?: emptyMap()) },
  ),
  Tool(
    "zip", 1, appContext.getString(R.string.tool_zip_title), appContext.getString(R.string.tool_zip_desc), appContext.getString(R.string.tool_zip_cta), appContext.getString(R.string.tool_zip_doing), Input.Pdfs, min = 1, noPreview = true,
  ) { files, _ -> listOf(Output("bundle", zipFiles(files), ext = "zip")) },
  Tool(
    "fillSign", 3, appContext.getString(R.string.tool_fill_sign_title),
    appContext.getString(R.string.tool_fill_sign_desc),
    appContext.getString(R.string.action_apply), appContext.getString(R.string.tool_fill_sign_doing), Input.Pdf,
    run = one("signed") { b, o ->
      val items = o.get<List<SignItem>>("items") ?: emptyList()
      if (items.isEmpty()) fail(appContext.getString(R.string.tool_fill_sign_fail_empty))
      val images = HashMap<String, ByteArray>()
      for (it in items) {
        if (it.kind != "image" || it.uri in images) continue
        // A resumed draft can point at a picture the OS has since cleared from the cache.
        val f = File(pathOf(it.uri))
        if (!f.exists()) fail(appContext.getString(R.string.tool_fill_sign_fail_picture_missing, it.page + 1))
        images[it.uri] = f.readBytes()
      }
      fillSign(b, items, images)
    },
  ),
  Tool(
    "watermark", 3, appContext.getString(R.string.tool_watermark_title), appContext.getString(R.string.tool_watermark_desc),
    appContext.getString(R.string.tool_watermark_cta), appContext.getString(R.string.tool_watermark_doing), Input.Pdf, opts = listOf(O.wmText(), O.wmScale(), O.opacity()),
    run = one("watermarked") { b, o -> watermark(b, o.str("text"), o.str("opacity").toFloatOrNull() ?: 0.2f, o.str("scale").toFloatOrNull() ?: 1f) },
  ),
  Tool(
    "pageNumbers", 3, appContext.getString(R.string.tool_page_numbers_title), appContext.getString(R.string.tool_page_numbers_desc), appContext.getString(R.string.tool_page_numbers_cta), appContext.getString(R.string.tool_page_numbers_doing),
    Input.Pdf, opts = listOf(O.numPos(), O.numFmt()), run = one("numbered") { b, o -> pageNumbers(b, o.str("position"), o.str("format")) },
  ),
  Tool(
    "redact", 3, appContext.getString(R.string.tool_redact_title), appContext.getString(R.string.tool_redact_desc),
    appContext.getString(R.string.tool_redact_cta), appContext.getString(R.string.tool_redact_doing), Input.Pdf,
  ) { files, o ->
    val boxes = o.get<Map<String, List<List<Float>>>>("boxes") ?: emptyMap()
    if (boxes.isEmpty()) fail(appContext.getString(R.string.tool_redact_fail_no_area))
    listOf(Output("redacted", redactPdf(files[0].bytes, boxes)))
  },
  Tool(
    "ocr", 4, appContext.getString(R.string.tool_ocr_title),
    appContext.getString(R.string.tool_ocr_desc),
    appContext.getString(R.string.tool_ocr_cta), appContext.getString(R.string.tool_ocr_doing), Input.Pdf,
    run = one("searchable") { b, _ -> ocrPdf(b) ?: fail(appContext.getString(R.string.tool_ocr_fail_no_text)) },
  ),
  Tool(
    "unlock", 4, appContext.getString(R.string.tool_unlock_title), appContext.getString(R.string.tool_unlock_desc),
    appContext.getString(R.string.tool_unlock_cta), appContext.getString(R.string.tool_unlock_doing), Input.Pdf, noPreview = true,
    run = one("unlocked") { b, _ -> removePassword(b) }, // the screen asks for the password when the file is picked
  ),
  Tool(
    "metadata", 3, appContext.getString(R.string.tool_metadata_title), appContext.getString(R.string.tool_metadata_desc),
    appContext.getString(R.string.tool_metadata_cta), appContext.getString(R.string.tool_metadata_doing), Input.Pdf, noPreview = true,
  ) { files, o -> listOf(Output("details", writeDetails(files[0].bytes, o.str("title"), o.str("author"), o.str("subject"), o.str("keywords")))) },
  Tool(
    "password", 4, appContext.getString(R.string.tool_password_title), appContext.getString(R.string.tool_password_desc), appContext.getString(R.string.tool_password_cta), appContext.getString(R.string.tool_password_doing), Input.Pdf,
    noPreview = true, opts = listOf(text("password", appContext.getString(R.string.opt_password_label), "", appContext.getString(R.string.opt_password_placeholder), KeyboardType.Password)),
    run = one("protected") { b, o ->
      val pw = o.str("password")
      if (pw.isEmpty()) fail(appContext.getString(R.string.tool_password_fail_empty))
      setPdfPassword(b, pw) ?: fail(appContext.getString(R.string.tool_password_fail_already_set))
    },
  ),
) }
val TOOLS: List<Tool> get() = toolsCache.get()

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
