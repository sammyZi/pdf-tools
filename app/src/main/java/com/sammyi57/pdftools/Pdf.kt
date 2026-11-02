package com.sammyi57.pdftools

import android.graphics.BitmapFactory
import com.tom_roush.harmony.awt.geom.AffineTransform
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// Every operation runs on-device with PdfBox: bytes in, bytes out. Geometry is a straight port of the Expo app's
// pdf-lib engine, so a file comes out the same whichever app made it.

/** A message meant for the person using the app, shown as is. */
class UserError private constructor(private val text: String?, private val id: Int, private val args: Array<out Any>) : Exception() {
  constructor(message: String) : this(message, 0, emptyArray())
  // Resolved when read, so code that only throws (and the JVM unit tests) never needs a Context.
  constructor(id: Int, vararg args: Any) : this(null, id, args)
  override val message: String get() = text ?: appContext.getString(id, *args)
}

fun fail(message: String): Nothing = throw UserError(message)
fun fail(id: Int, vararg args: Any): Nothing = throw UserError(id, *args)

class Named(val name: String, val bytes: ByteArray)

/** Page sizes in points, portrait. */
val SIZES: Map<String, Pair<Float, Float>> = linkedMapOf(
  "A4" to (595.28f to 841.89f), "A5" to (419.53f to 595.28f), "Letter" to (612f to 792f), "Legal" to (612f to 1008f),
)
private val A4 = SIZES.getValue("A4")

/** [x, y, w, h] as fractions. */
typealias Box = List<Float>

fun loadPdf(bytes: ByteArray): PDDocument = try {
  PDDocument.load(bytes)
} catch (_: InvalidPasswordException) {
  fail(appContext.getString(R.string.error_pdf_password_protected))
} catch (_: Exception) {
  fail(appContext.getString(R.string.error_pdf_unreadable))
}

fun PDDocument.bytes(): ByteArray = ByteArrayOutputStream().use { save(it); it.toByteArray() }

private val PDPage.rot get() = ((rotation % 360) + 360) % 360

/** Copies a page into this document, keeping resources it inherited from its old page tree. */
private fun PDDocument.adopt(p: PDPage): PDPage {
  val n = importPage(p)
  if (!n.cosObject.containsKey(COSName.RESOURCES)) n.resources = p.resources ?: PDResources()
  return n
}

private fun pick(src: PDDocument, indices: List<Int>): ByteArray = PDDocument().use { out ->
  for (i in indices) out.adopt(src.getPage(i))
  out.bytes()
}

// "1-3, 5, 8-" -> [[0,1,2],[4],[7..n-1]]
fun parseRanges(str: String, n: Int): List<List<Int>> {
  val parts = str.split(',').map { it.trim() }.filter { it.isNotEmpty() }
  if (parts.isEmpty()) fail(R.string.error_enter_page_ranges)
  return parts.map { part ->
    val ab = part.split('-').map { it.trim() }
    val start = max(1, ab[0].toIntOrNull() ?: 1)
    val end = min(n, if (ab.size < 2) start else ab[1].toIntOrNull() ?: n)
    if (start > end) fail(R.string.error_range_outside_pages, part, n)
    (start - 1 until end).toList()
  }
}

fun merge(files: List<Named>): ByteArray {
  val sources = files.map { loadPdf(it.bytes) }
  try {
    return PDDocument().use { out ->
      for (src in sources) for (p in src.pages) out.adopt(p)
      out.bytes()
    }
  } finally {
    sources.forEach { it.close() }
  }
}

fun alternate(files: List<Named>, reverse: Boolean): ByteArray {
  val sources = files.map { loadPdf(it.bytes) }
  try {
    val stacks = sources.mapIndexed { i, src -> src.pages.toList().let { if (reverse && i > 0) it.reversed() else it } }
    return PDDocument().use { out ->
      for (i in 0 until (stacks.maxOfOrNull { it.size } ?: 0)) for (s in stacks) s.getOrNull(i)?.let { out.adopt(it) }
      out.bytes()
    }
  } finally {
    sources.forEach { it.close() }
  }
}

// A JPEG's [width, height], read from its frame header.
fun jpegSize(b: ByteArray): Pair<Int, Int>? {
  fun u(i: Int) = b[i].toInt() and 0xFF
  var i = 2
  while (i + 8 < b.size) {
    if (u(i) != 0xFF) return null
    val m = u(i + 1)
    if (m == 0xFF) { i++; continue } // fill byte
    if (m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) return ((u(i + 7) shl 8) or u(i + 8)) to ((u(i + 5) shl 8) or u(i + 6))
    i += 2 + ((u(i + 2) shl 8) or u(i + 3))
  }
  return null
}

// Longest side each level shrinks a photo to: about 200 dpi on an A4 page for Balanced, 130 for Strong.
private val MAX_SIDE = mapOf(80 to 0, 60 to 2400, 35 to 1600)

/**
 * Re-encodes embedded JPEG photos and scans (where the bulk of a PDF's size lives), shrinking oversized ones, and
 * drops XMP metadata. Never returns a bigger file than it was given.
 */
fun compress(bytes: ByteArray, quality: Int, maxSide: Int = MAX_SIDE[quality] ?: 0): ByteArray = loadPdf(bytes).use { doc ->
  for (obj in doc.document.objects) {
    val st = obj.getObject() as? COSStream ?: continue
    if (st.getCOSName(COSName.SUBTYPE) != COSName.IMAGE) continue
    val filter = st.getDictionaryObject(COSName.FILTER)
    val jpeg = filter == COSName.DCT_DECODE || (filter is COSArray && filter.size() == 1 && filter.getObject(0) == COSName.DCT_DECODE)
    if (!jpeg) continue
    try {
      val raw = st.createRawInputStream().use { it.readBytes() }
      val out = recompressJpeg(raw, quality, maxSide) ?: continue
      val (w, h) = jpegSize(out) ?: continue
      if (out.size >= raw.size) continue
      st.createRawOutputStream().use { it.write(out) }
      st.setItem(COSName.FILTER, COSName.DCT_DECODE)
      st.setItem(COSName.COLORSPACE, COSName.DEVICERGB)
      st.setInt(COSName.BITS_PER_COMPONENT, 8)
      st.setInt(COSName.WIDTH, w)
      st.setInt(COSName.HEIGHT, h)
      st.removeItem(COSName.DECODE)
      st.removeItem(COSName.DECODE_PARMS) // a ColorTransform meant for the old JPEG would skew the new one's colours
    } catch (_: Exception) {
      // unusual JPEG flavour: keep the original image
    }
  }
  doc.documentCatalog.cosObject.removeItem(COSName.METADATA)
  val out = doc.bytes()
  if (out.size < bytes.size) out else bytes
}

// Each step trades more picture quality for size: JPEG quality, then the longest side photos are shrunk to.
private val SIZE_STEPS = listOf(80 to 0, 60 to 2400, 45 to 2000, 35 to 1600, 25 to 1200, 20 to 900)

/**
 * Compresses just enough to fit under `targetMB`: the gentlest step that fits, so quality is only given up where it
 * has to be. When nothing fits (text and drawings don't shrink), the smallest it managed.
 */
fun compressToSize(bytes: ByteArray, targetMB: String): ByteArray {
  val target = ((targetMB.toFloatOrNull() ?: 0f) * 1024 * 1024).toLong()
  if (target <= 0) fail(appContext.getString(R.string.error_enter_size_to_fit))
  if (bytes.size <= target) return compress(bytes, 80) // already fits: only the lossless tidy-up
  var best = bytes
  for ((q, side) in SIZE_STEPS) {
    val out = compress(bytes, q, side)
    if (out.size < best.size) best = out
    if (out.size <= target) return out
  }
  return best
}

/** A copy with no password or restrictions. Locked files are opened with their password before any tool sees them. */
fun removePassword(bytes: ByteArray): ByteArray = loadPdf(bytes).use { doc ->
  doc.isAllSecurityToBeRemoved = true
  doc.bytes()
}

/** Title, author, subject and keywords, as most PDF apps and file managers show them. */
fun readDetails(bytes: ByteArray): Map<String, String> = loadPdf(bytes).use { doc ->
  val info = doc.documentInformation
  mapOf("title" to info.title, "author" to info.author, "subject" to info.subject, "keywords" to info.keywords).mapValues { it.value.orEmpty() }
}

/**
 * Sets the details; an empty field removes it. The XMP copy is dropped, since apps that prefer it would otherwise keep
 * showing the old values.
 */
fun writeDetails(bytes: ByteArray, title: String, author: String, subject: String, keywords: String): ByteArray = loadPdf(bytes).use { doc ->
  val info = doc.documentInformation
  info.title = title.trim().ifEmpty { null }
  info.author = author.trim().ifEmpty { null }
  info.subject = subject.trim().ifEmpty { null }
  info.keywords = keywords.trim().ifEmpty { null }
  doc.documentInformation = info
  doc.documentCatalog.cosObject.removeItem(COSName.METADATA)
  doc.bytes()
}

fun split(bytes: ByteArray, mode: String, ranges: String): List<Named> = loadPdf(bytes).use { src ->
  val groups = if (mode == "ranges") parseRanges(ranges, src.numberOfPages) else (0 until src.numberOfPages).map { listOf(it) }
  groups.map { g -> Named(if (g.size > 1) "pages-${g.first() + 1}-${g.last() + 1}" else "page-${g.first() + 1}", pick(src, g)) }
}

private fun norm(s: String) = s.lowercase().replace(Regex("\\s+"), "")

fun splitByText(bytes: ByteArray, phrase: String): List<Named> {
  val needle = norm(phrase)
  if (needle.isEmpty()) fail(appContext.getString(R.string.error_enter_phrase_to_split))
  return loadPdf(bytes).use { src ->
    val stripper = PDFTextStripper()
    val groups = ArrayList<MutableList<Int>>()
    var hits = 0
    for (i in 0 until src.numberOfPages) {
      stripper.startPage = i + 1
      stripper.endPage = i + 1
      val hit = norm(runCatching { stripper.getText(src) }.getOrDefault("")).contains(needle)
      if (hit) hits++
      if (groups.isEmpty() || (hit && groups.last().isNotEmpty())) groups.add(mutableListOf())
      groups.last().add(i)
    }
    if (hits == 0) fail(appContext.getString(R.string.error_phrase_not_found, phrase))
    groups.mapIndexed { k, g -> Named("part-${k + 1}", pick(src, g)) }
  }
}

fun splitByBookmarks(bytes: ByteArray): List<Named> = loadPdf(bytes).use { src ->
  val n = src.numberOfPages
  val marks = ArrayList<Pair<String, Int>>()
  var item = src.documentCatalog.documentOutline?.firstChild
  var guard = 0
  while (item != null && guard++ < 10000) {
    val page = runCatching { item.findDestinationPage(src) }.getOrNull()
    val i = page?.let { src.pages.indexOf(it) } ?: -1
    if (i >= 0) marks.add((item.title?.takeIf { it.isNotBlank() } ?: appContext.getString(R.string.label_section_n, marks.size + 1)) to i)
    item = item.nextSibling
  }
  if (marks.isEmpty()) fail(appContext.getString(R.string.error_no_bookmarks))
  marks.sortBy { it.second }
  if (marks[0].second > 0) marks.add(0, appContext.getString(R.string.label_front_matter) to 0)
  val uniq = marks.filterIndexed { i, m -> i == 0 || m.second != marks[i - 1].second }
  uniq.mapIndexed { i, (title, page) -> Named(title, pick(src, (page until (uniq.getOrNull(i + 1)?.second ?: n)).toList())) }
}

// The page as displayed is the page as stored turned by /Rotate, so a rect drawn on screen has to be turned back
// before it can be a box on the page (and PDF y runs from the bottom).
fun unrotate(b: Box, rot: Int): Box {
  val (x, y, w, h) = b
  return when (rot) {
    90 -> listOf(y, x, h, w)
    180 -> listOf(1 - x - w, y, w, h)
    270 -> listOf(1 - y - h, 1 - x - w, h, w)
    else -> listOf(x, 1 - y - h, w, h)
  }
}

fun splitHalf(bytes: ByteArray, mode: String): List<Named> = loadPdf(bytes).use { src ->
  val n = src.numberOfPages
  if (mode == "file") {
    if (n < 2) fail(appContext.getString(R.string.error_need_2_pages))
    val h = (n + 1) / 2
    return@use listOf(Named("first-half", pick(src, (0 until h).toList())), Named("second-half", pick(src, (h until n).toList())))
  }
  // Halves of the page as displayed (left then right, or top then bottom), turned back into the page as stored, so a
  // spread saved sideways with /Rotate still splits into its two pages.
  val shown: List<Box> = if (mode == "vertical") listOf(listOf(0f, 0f, 0.5f, 1f), listOf(0.5f, 0f, 0.5f, 1f))
  else listOf(listOf(0f, 0f, 1f, 0.5f), listOf(0f, 0.5f, 1f, 0.5f))
  val out = PDDocument()
  out.use {
    for (p in src.pages) {
      val cb = p.cropBox
      for (half in shown) {
        val q = out.adopt(p)
        val (x, y, w, h) = unrotate(half, p.rot)
        val box = PDRectangle(cb.lowerLeftX + x * cb.width, cb.lowerLeftY + y * cb.height, w * cb.width, h * cb.height)
        q.mediaBox = box
        q.cropBox = box
      }
    }
    listOf(Named("sliced", out.bytes()))
  }
}

fun splitBySize(bytes: ByteArray, maxMB: String): List<Named> {
  val limit = (maxMB.toFloatOrNull() ?: 0f) * 1024 * 1024
  if (limit <= 0f) fail(appContext.getString(R.string.error_enter_size_limit))
  return loadPdf(bytes).use { src ->
    val parts = ArrayList<ByteArray>()
    var cur = mutableListOf<Int>()
    var last: ByteArray? = null
    // ponytail: re-saves the growing part after each page (O(n²) bytes); binary-search per part if huge files crawl.
    for (i in 0 until src.numberOfPages) {
      val b = pick(src, cur + i)
      if (b.size > limit && cur.isNotEmpty() && last != null) {
        parts.add(last)
        cur = mutableListOf(i)
        last = pick(src, cur)
      } else {
        cur.add(i)
        last = b
      }
    }
    last?.let { parts.add(it) }
    parts.mapIndexed { k, b -> Named("part-${k + 1}", b) }
  }
}

fun rotate(bytes: ByteArray, angle: Int, pages: String): ByteArray = loadPdf(bytes).use { doc ->
  val sel = if (pages.isNotBlank()) parseRanges(pages, doc.numberOfPages).flatten().toSet() else (0 until doc.numberOfPages).toSet()
  for (i in sel) doc.getPage(i).let { it.rotation = (it.rotation + angle) % 360 }
  doc.bytes()
}

/** A form XObject showing what a viewer shows of the page (its crop box), w×h, its origin at the lower-left. */
private class Shown(val form: PDFormXObject, val w: Float, val h: Float, val rot: Int) {
  // The size as a viewer shows it, turned by the source page's /Rotate.
  val shownW get() = if (rot % 180 != 0) h else w
  val shownH get() = if (rot % 180 != 0) w else h
}

private fun PDDocument.embedShown(p: PDPage): Shown {
  val cb = p.cropBox
  val form = PDFormXObject(PDStream(this, p.contents, COSName.FLATE_DECODE))
  form.resources = p.resources ?: PDResources()
  form.bBox = PDRectangle(cb.lowerLeftX, cb.lowerLeftY, cb.width, cb.height)
  form.setMatrix(AffineTransform.getTranslateInstance(-cb.lowerLeftX.toDouble(), -cb.lowerLeftY.toDouble()))
  return Shown(form, cb.width, cb.height, p.rot)
}

private fun PDPageContentStream.place(form: PDFormXObject, x: Float, y: Float, sx: Float, sy: Float = sx, deg: Float = 0f) {
  saveGraphicsState()
  transform(Matrix.getTranslateInstance(x, y))
  if (deg != 0f) transform(Matrix.getRotateInstance(Math.toRadians(deg.toDouble()), 0f, 0f))
  transform(Matrix.getScaleInstance(sx, sy))
  drawForm(form)
  restoreGraphicsState()
}

// Draws an embedded page upright as a viewer would show it (turned clockwise by its /Rotate), scaled by `s`, its
// lower-left corner at (x, y).
private fun PDPageContentStream.drawShown(e: Shown, x: Float, y: Float, s: Float) {
  val w = e.w * s
  val h = e.h * s
  val (ox, oy) = when (e.rot) { 90 -> x to y + w; 180 -> x + w to y + h; 270 -> x + h to y; else -> x to y }
  place(e.form, ox, oy, s, s, -e.rot.toFloat())
}

private fun PDDocument.newPage(w: Float, h: Float) = PDPage(PDRectangle(w, h)).also { addPage(it) }
private fun PDDocument.draw(p: PDPage, block: PDPageContentStream.() -> Unit) =
  PDPageContentStream(this, p, PDPageContentStream.AppendMode.APPEND, true, true).use(block)

fun flip(bytes: ByteArray, axis: String): ByteArray = loadPdf(bytes).use { src ->
  PDDocument().use { out ->
    for (p in src.pages) {
      val e = out.embedShown(p)
      // The page keeps its /Rotate, so mirroring it as displayed means the other axis of a page turned a quarter.
      val fx = if (e.rot % 180 != 0) axis != "h" else axis != "v"
      val fy = if (e.rot % 180 != 0) axis != "v" else axis != "h"
      val page = out.newPage(e.w, e.h)
      out.draw(page) { place(e.form, if (fx) e.w else 0f, if (fy) e.h else 0f, if (fx) -1f else 1f, if (fy) -1f else 1f) }
      page.rotation = e.rot
    }
    out.bytes()
  }
}

private val GRID = mapOf("2" to (2 to 1), "4" to (2 to 2), "6" to (2 to 3), "9" to (3 to 3), "16" to (4 to 4))

fun nUp(bytes: ByteArray, perSheet: String, lines: Boolean): ByteArray {
  val (c0, r0) = GRID[perSheet] ?: fail(appContext.getString(R.string.error_choose_pages_per_sheet))
  return loadPdf(bytes).use { src ->
    PDDocument().use { out ->
      val shown = src.pages.map { out.embedShown(it) }
      val m = 18f
      // The grid as given on its natural sheet, or turned with the sheet turned too (2 slides stack on a portrait
      // sheet); whichever shows the first page bigger.
      val portrait = A4
      val landscape = A4.second to A4.first
      val w0 = shown.firstOrNull()?.shownW ?: 1f
      val h0 = shown.firstOrNull()?.shownH ?: 1f
      data class L(val cols: Int, val rows: Int, val w: Float, val h: Float)
      val a = if (c0 > r0) L(c0, r0, landscape.first, landscape.second) else L(c0, r0, portrait.first, portrait.second)
      val b = if (c0 > r0) L(r0, c0, portrait.first, portrait.second) else L(r0, c0, landscape.first, landscape.second)
      fun fit(l: L) = min((l.w - m * 2) / l.cols / w0, (l.h - m * 2) / l.rows / h0)
      val (cols, rows, W, H) = if (fit(b) > fit(a)) b else a
      val cw = (W - m * 2) / cols
      val ch = (H - m * 2) / rows
      var cs: PDPageContentStream? = null
      try {
        shown.forEachIndexed { i, e ->
          val k = i % (cols * rows)
          if (k == 0) {
            cs?.close()
            cs = PDPageContentStream(out, out.newPage(W, H)).also { s ->
              if (lines) { // hairline dividers in the gaps between cells
                s.setStrokingColor(0.6f, 0.6f, 0.6f)
                s.setLineWidth(0.5f)
                for (col in 1 until cols) { s.moveTo(m + col * cw, m); s.lineTo(m + col * cw, H - m) }
                for (row in 1 until rows) { s.moveTo(m, H - m - row * ch); s.lineTo(W - m, H - m - row * ch) }
                s.stroke()
              }
            }
          }
          val s = min((cw - 8) / e.shownW, (ch - 8) / e.shownH)
          val col = k % cols
          val row = k / cols
          cs!!.drawShown(e, m + col * cw + (cw - e.shownW * s) / 2, H - m - (row + 1) * ch + (ch - e.shownH * s) / 2, s)
        }
      } finally {
        cs?.close()
      }
      out.bytes()
    }
  }
}

// What to keep on a page, as fractions of the page as it is displayed, y from the top. Keyed by page index.
fun cropResize(bytes: ByteArray, mode: String, size: String, boxes: Map<String, Box>): ByteArray = loadPdf(bytes).use { src ->
  if (mode == "crop") {
    if (boxes.isEmpty()) fail(appContext.getString(R.string.error_pull_corners_or_auto_crop))
    src.pages.forEachIndexed { i, p ->
      val box = boxes[i.toString()] ?: return@forEachIndexed // pages you didn't crop are left at their full size
      val cb = p.cropBox
      val (x, y, w, h) = unrotate(box, p.rot)
      if (w * cb.width < 1 || h * cb.height < 1) fail(appContext.getString(R.string.error_crop_too_small, i + 1))
      p.cropBox = PDRectangle(cb.lowerLeftX + x * cb.width, cb.lowerLeftY + y * cb.height, w * cb.width, h * cb.height)
    }
    return@use src.bytes()
  }
  val (a, b) = SIZES[size] ?: A4
  PDDocument().use { out ->
    for (p in src.pages) {
      val e = out.embedShown(p)
      val (W, H) = if (e.shownW > e.shownH) b to a else a to b
      val s = min(W / e.shownW, H / e.shownH)
      val page = out.newPage(W, H)
      out.draw(page) { drawShown(e, (W - e.shownW * s) / 2, (H - e.shownH * s) / 2, s) }
    }
    out.bytes()
  }
}

// The standard fonts are WinAnsi, so a character they cannot write is folded to its plain form or dropped, rather
// than failing the whole document. Editors show text through this too, so what you see is what the PDF gets.
fun winAnsi(s: String): String = s
  .replace(Regex("[‘’]"), "'").replace(Regex("[“”]"), "\"").replace(Regex("[–—]"), "-").replace(Regex("₹\\s?"), "Rs. ")
  .filter { it.code in 0x20..0x7E || it.code in 0xA0..0xFF }

class WatermarkLayout(val angle: Float, val size: Float, val textWidth: Float)

// Diagonal watermark geometry in page points; the tool preview uses it too so both match.
fun watermarkLayout(text: String, width: Float, height: Float, scale: Float): WatermarkLayout {
  val w1 = PDType1Font.HELVETICA_BOLD.getStringWidth(text) / 1000f
  val k = if (scale > 0) scale else 1f
  val size = min(110 * k, hypot(width, height) * 0.65f * k / w1)
  return WatermarkLayout(atan2(height, width), size, w1 * size)
}

private fun PDPageContentStream.alpha(doc: PDDocument, a: Float) {
  if (a >= 1f) return
  setGraphicsStateParameters(PDExtendedGraphicsState().apply { nonStrokingAlphaConstant = a; strokingAlphaConstant = a })
}

private fun PDPageContentStream.putText(font: PDType1Font, size: Float, text: String, x: Float, y: Float, deg: Float) {
  beginText()
  setFont(font, size)
  setTextMatrix(Matrix.getRotateInstance(Math.toRadians(deg.toDouble()), x, y))
  showText(text)
  endText()
}

fun watermark(bytes: ByteArray, typed: String, opacity: Float, scale: Float): ByteArray {
  if (typed.isBlank()) fail(appContext.getString(R.string.error_enter_watermark_text))
  val text = winAnsi(typed).trim()
  if (text.isEmpty()) fail(appContext.getString(R.string.error_watermark_latin_only))
  return loadPdf(bytes).use { doc ->
    val font = PDType1Font.HELVETICA_BOLD
    for (p in doc.pages) {
      // Centred on what a viewer shows (the crop box), as the preview is, not on the full media box.
      val cb = p.cropBox
      val width = cb.width
      val height = cb.height
      val l = watermarkLayout(text, width, height, scale)
      val cs = cos(l.angle)
      val sn = sin(l.angle)
      val up = l.size * 0.35f
      doc.draw(p) {
        alpha(doc, opacity)
        setNonStrokingColor(0.82f, 0.27f, 0.17f)
        putText(
          font, l.size, text, cb.lowerLeftX + width / 2 - cs * l.textWidth / 2 + sn * up,
          cb.lowerLeftY + height / 2 - sn * l.textWidth / 2 - cs * up, (l.angle * 180 / PI).toFloat(),
        )
      }
    }
    doc.bytes()
  }
}

private const val DESCENT = 0.79f // Helvetica sits about a fifth of its size below the baseline

class Placement(val x: Float, val y: Float, val w: Float, val h: Float)

/**
 * Where to draw an item placed on the page as displayed: the origin to draw from and the item's natural size. The
 * origin is the item's own bottom-left corner, which lands on a different corner of the page as it turns.
 */
fun placeOnPage(rect: Box, rot: Int, W: Float, H: Float): Placement {
  val (fx, fy, fw, fh) = unrotate(rect, rot)
  val (X, Y, RW, RH) = listOf(fx * W, fy * H, fw * W, fh * H)
  return when (rot) {
    90 -> Placement(X + RW, Y, RH, RW)
    180 -> Placement(X + RW, Y + RH, RW, RH)
    270 -> Placement(X, Y + RH, RH, RW)
    else -> Placement(X, Y, RW, RH)
  }
}

fun pageNumbers(bytes: ByteArray, position: String, format: String): ByteArray = loadPdf(bytes).use { doc ->
  val font = PDType1Font.HELVETICA
  val n = doc.numberOfPages
  doc.pages.forEachIndexed { i, p ->
    val label = if (format == "of") appContext.getString(R.string.msg_page_x_of_n, i + 1, n) else "${i + 1}"
    val tw = font.getStringWidth(label) / 1000f * 10
    // Placed on the page as displayed, so a page saved sideways with /Rotate still gets its number upright at the
    // bottom: the baseline 20pt up, 28pt in from the side.
    val cb = p.cropBox
    val rot = p.rot
    val (dw, dh) = if (rot % 180 != 0) cb.height to cb.width else cb.width to cb.height
    val lx = when (position) { "left" -> 28f; "right" -> dw - 28 - tw; else -> (dw - tw) / 2 }
    val at = placeOnPage(listOf(lx / dw, (dh - 20 - 10 * DESCENT) / dh, 0f, 10 * DESCENT / dh), rot, cb.width, cb.height)
    doc.draw(p) {
      setNonStrokingColor(0.2f, 0.2f, 0.2f)
      putText(font, 10f, label, cb.lowerLeftX + at.x, cb.lowerLeftY + at.y, rot.toFloat())
    }
  }
  doc.bytes()
}

@Serializable
data class PageOrder(val i: Int, val rot: Int = 0, val del: Boolean = false) // del: greyed out, left out of the output

fun organize(bytes: ByteArray, all: List<PageOrder>): ByteArray {
  val order = all.filter { !it.del }
  if (order.isEmpty()) fail(appContext.getString(R.string.error_keep_at_least_one_page))
  return loadPdf(bytes).use { src ->
    PDDocument().use { out ->
      for (o in order) out.adopt(src.getPage(o.i)).let { it.rotation = (it.rotation + o.rot) % 360 }
      out.bytes()
    }
  }
}

// Something dropped onto a page in Fill & Sign. Positions are fractions of the page as displayed, y from the top.
// Text keeps no width: it is as wide as it needs. `angle` turns the item clockwise about its centre as seen.
@Serializable
data class SignItem(
  val page: Int,
  val kind: String, // text | image
  val x: Float,
  val y: Float,
  val size: Float = 0f,
  val text: String = "",
  val color: String? = null,
  val w: Float = 0f,
  val h: Float = 0f,
  val uri: String = "",
  val angle: Float = 0f,
  val opacity: Float = 1f,
)

const val INK = "#0F172A" // text's default colour: a dark blue-black, like a pen

class Turned(val x: Float, val y: Float, val rotate: Float)

/**
 * Where to draw an item that sits, as displayed, with its top-left at fractions (x, y) of the page, is w×h points, and
 * is turned `angle` degrees clockwise about its centre. `oy` lifts the drawing origin above the item's bottom edge (a
 * text's baseline). Returns that origin in the page's stored frame and the rotation to draw at there.
 */
fun placeTurned(x: Float, y: Float, w: Float, h: Float, oy: Float, angle: Float, rot: Int, W: Float, H: Float): Turned {
  val (dw, dh) = if (rot % 180 != 0) H to W else W to H
  val cx = x * dw + w / 2
  val cy = dh - y * dh - h / 2 // centre in the displayed frame, y up
  val t = -angle * PI.toFloat() / 180 // clockwise on screen is negative in PDF's y-up frame
  val lx = -w / 2
  val ly = oy - h / 2
  val X = cx + lx * cos(t) - ly * sin(t)
  val Y = cy + lx * sin(t) + ly * cos(t)
  val (u, v) = when (rot) { 90 -> (W - Y) to X; 180 -> (W - X) to (H - Y); 270 -> Y to (H - X); else -> X to Y }
  return Turned(u, v, rot - angle)
}

private fun hexRgb(hex: String?): Triple<Float, Float, Float> {
  val m = Regex("^#?([0-9a-fA-F]{6})$").find(hex ?: INK) ?: Regex("^#?([0-9a-fA-F]{6})$").find(INK)!!
  val n = m.groupValues[1].toInt(16)
  return Triple(((n shr 16) and 255) / 255f, ((n shr 8) and 255) / 255f, (n and 255) / 255f)
}

fun fillSign(bytes: ByteArray, items: List<SignItem>, images: Map<String, ByteArray>): ByteArray = loadPdf(bytes).use { doc ->
  val embedded = HashMap<String, PDImageXObject>()
  val font = PDType1Font.HELVETICA
  for (it in items) {
    if (it.page !in 0 until doc.numberOfPages) continue
    val p = doc.getPage(it.page)
    val rot = p.rot
    // Item fractions are of the page as the editor shows it: the crop box, so a cropped page lines up too.
    val cb = p.cropBox
    val W = cb.width
    val H = cb.height
    val (dw, dh) = if (rot % 180 != 0) H to W else W to H // the page as displayed, which item fractions are of
    val op = it.opacity.coerceIn(0.05f, 1f)
    if (it.kind == "image") {
      val img = embedded[it.uri] ?: run {
        val b = images[it.uri] ?: return@run null
        when {
          b.size > 1 && b[0] == 0x89.toByte() ->
            LosslessFactory.createFromImage(doc, BitmapFactory.decodeByteArray(b, 0, b.size) ?: fail(appContext.getString(R.string.error_could_not_read_picture, it.page + 1)))
          b.size > 1 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> JPEGFactory.createFromByteArray(doc, b)
          else -> fail(appContext.getString(R.string.error_picture_not_jpeg_png, it.page + 1))
        }.also { x -> embedded[it.uri] = x }
      } ?: continue
      val w = it.w * dw
      val h = it.h * dh
      val at = placeTurned(it.x, it.y, w, h, 0f, it.angle, rot, W, H)
      doc.draw(p) {
        alpha(doc, op)
        transform(Matrix.getTranslateInstance(cb.lowerLeftX + at.x, cb.lowerLeftY + at.y))
        transform(Matrix.getRotateInstance(Math.toRadians(at.rotate.toDouble()), 0f, 0f))
        drawImage(img, 0f, 0f, w, h)
      }
      continue
    }
    val text = winAnsi(it.text)
    if (text.isEmpty()) continue
    // The item's box is as tall as the font size and as wide as the text; the baseline sits a descender up from its
    // bottom edge, which is where drawing starts.
    val size = it.size * dh
    val at = placeTurned(it.x, it.y, font.getStringWidth(text) / 1000f * size, size, size * (1 - DESCENT), it.angle, rot, W, H)
    val (r, g, b) = hexRgb(it.color)
    doc.draw(p) {
      alpha(doc, op)
      setNonStrokingColor(r, g, b)
      putText(font, size, text, cb.lowerLeftX + at.x, cb.lowerLeftY + at.y, at.rotate)
    }
  }
  doc.bytes()
}

fun zipFiles(files: List<Named>): ByteArray = ByteArrayOutputStream().use { bos ->
  ZipOutputStream(bos).use { zip ->
    val seen = HashSet<String>()
    files.forEachIndexed { i, f ->
      val name = if (f.name in seen) "${i + 1}-${f.name}" else f.name
      seen.add(name)
      zip.putNextEntry(ZipEntry(name))
      zip.write(f.bytes)
      zip.closeEntry()
    }
  }
  bos.toByteArray()
}

// ── Scanned pages ──────────────────────────────────────────────────────────
// Page kinds follow Adobe Scan's capture modes; sizes follow its Resize menu. The review screen draws pages from the
// same layout, so what you see is what the PDF gets.

@Serializable
data class Crop(val x: Float, val y: Float, val w: Float, val h: Float) // fractions of the image, y from the top

data class Placed(val img: Int, val crop: Crop, val x: Float, val y: Float, val w: Float, val h: Float) // points, y from the bottom
data class Sheet(val W: Float, val H: Float, val items: List<Placed>)

private val FULL = Crop(0f, 0f, 1f, 1f)
private const val CARD_W = 85.6f * 2.8346f // ISO ID-1 card width in points, so ID copies print at real size
private const val MARGIN = 24f

/** `kind`: doc | book | id | card | whiteboard. `size`: fit, or e.g. "A4-portrait". `imgs`: each image's (w, h). */
fun layoutScanPage(kind: String, size: String, imgs: List<Pair<Int, Int>>): List<Sheet> {
  val (fw, fh) = imgs.first().let { it.first.toFloat() to it.second.toFloat() }
  val paper: Pair<Float, Float>? = if (size == "fit") null else {
    val (name, orient) = size.split('-').let { it[0] to it.getOrElse(1) { "portrait" } }
    val (a, b) = SIZES[name] ?: A4
    if (orient == "landscape") b to a else a to b
  }
  // One image region per sheet: full-bleed at A4 width when 'fit', else centered inside the margins.
  fun single(crop: Crop, aw: Float, ah: Float, fitW: Float = A4.first): Sheet {
    if (paper == null) return Sheet(fitW, fitW * ah / aw, listOf(Placed(0, crop, 0f, 0f, fitW, fitW * ah / aw)))
    val (W, H) = paper
    val k = min((W - 2 * MARGIN) / aw, (H - 2 * MARGIN) / ah)
    return Sheet(W, H, listOf(Placed(0, crop, (W - aw * k) / 2, (H - ah * k) / 2, aw * k, ah * k)))
  }
  return when (kind) {
    "book" -> listOf(0f, 0.5f).map { x -> single(Crop(x, 0f, 0.5f, 1f), fw / 2, fh) }
    "card" -> listOf(single(FULL, fw, fh, 252f))
    "id" -> { // front above back
      val (W, H) = paper ?: A4
      val cw = min(CARD_W, W - 2 * MARGIN)
      listOf(Sheet(W, H, imgs.mapIndexed { i, (iw, ih) ->
        val h = cw * ih / iw
        Placed(i, FULL, (W - cw) / 2, if (i == 0) H / 2 + 12 else H / 2 - 12 - h, cw, h)
      }))
    }
    else -> listOf(single(FULL, fw, fh))
  }
}
