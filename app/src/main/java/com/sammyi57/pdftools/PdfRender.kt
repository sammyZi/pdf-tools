package com.sammyi57.pdftools

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.util.Matrix
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// PDF work beyond PdfBox's page model: thumbnails and page sizes (Android's PdfRenderer), JPEG re-encoding, OCR,
// passwords, redaction and Word export. All of it blocks; callers run it off the main thread.

/** A path from older saved state may still be a file:// uri. */
fun pathOf(uri: String): String = if (uri.startsWith("file:")) Uri.parse(uri).path ?: uri else uri

class Thumb(val path: String, val width: Int, val height: Int)

private const val EMU_PER_PT = 12700L
private const val DOCX_MAX_W = 6120130L // A4 minus 2 cm margins, in EMU
private const val DOCX_MAX_H = 8900000L // a little under the usable height, so a picture never spills onto a blank page

private fun xml(s: String) = buildString {
  for (ch in s) when (ch) {
    '&' -> append("&amp;")
    '<' -> append("&lt;")
    '>' -> append("&gt;")
    '"' -> append("&quot;")
    else -> if (ch == '\t' || (ch >= ' ' && ch != '￾' && ch != '￿')) append(ch) // other control chars are invalid XML
  }
}

private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
private fun cache(dir: String) = File(appContext.cacheDir, dir).apply { mkdirs() }
private fun renderer(path: String) = PdfRenderer(ParcelFileDescriptor.open(File(pathOf(path)), ParcelFileDescriptor.MODE_READ_ONLY))

// Cheap pre-check so ordinary PDFs never pay for a full PdfBox parse: encryption is declared in the trailer, which sits
// in the last bytes (or near the start of linearized files).
// ponytail: only the first 16 KB and last 64 KB are searched; a PDF declaring it elsewhere reaches the tool still
// encrypted, and the engine reports it as password-protected.
private fun mentionsEncrypt(f: File): Boolean = RandomAccessFile(f, "r").use { raf ->
  val len = raf.length()
  val read = { from: Long, size: Long ->
    val bytes = ByteArray(minOf(size, len - from).toInt())
    raf.seek(from)
    raf.readFully(bytes)
    String(bytes, Charsets.ISO_8859_1)
  }
  "/Encrypt" in read(0, 16384) || "/Encrypt" in read(maxOf(0, len - 65536), 65536)
}

// Renders one page to a white-backed JPEG `width` pixels wide.
private fun PdfRenderer.Page.writeJpeg(width: Int, quality: Int, sink: OutputStream) {
  val height = (this.height * width.toFloat() / this.width).toInt().coerceAtLeast(1)
  val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) // the only format PdfRenderer accepts
  bitmap.eraseColor(Color.WHITE) // PDFs assume white paper; the bitmap starts transparent
  render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
  bitmap.compress(Bitmap.CompressFormat.JPEG, quality, sink)
  bitmap.recycle()
}

// The invisible text layer is written in Helvetica, which only encodes WinAnsi.
private fun ocrText(s: String) = winAnsi(s).trim()

// Maps the page as displayed (origin bottom-left, /Rotate applied) onto the page as stored.
private fun displayMatrix(box: PDRectangle, rot: Int): Matrix {
  val x = box.lowerLeftX
  val y = box.lowerLeftY
  return when (rot) {
    90 -> Matrix(0f, 1f, -1f, 0f, x + box.width, y)
    180 -> Matrix(-1f, 0f, 0f, -1f, x + box.width, y + box.height)
    270 -> Matrix(0f, -1f, 1f, 0f, x, y + box.height)
    else -> Matrix(1f, 0f, 0f, 1f, x, y)
  }
}

// JPEG bytes go into the PDF exactly as they sit on disk: no decode, no re-encode. Anything else is decoded once.
private fun embedImage(doc: PDDocument, path: String): PDImageXObject {
  val file = File(pathOf(path))
  val head = ByteArray(2)
  file.inputStream().use { it.read(head) }
  if (head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte()) return file.inputStream().use { JPEGFactory.createFromStream(doc, it) }
  val bitmap = BitmapFactory.decodeFile(file.path) ?: throw IllegalArgumentException(appContext.getString(R.string.error_could_not_read_file, file.name))
  try {
    return JPEGFactory.createFromImage(doc, bitmap, 0.92f)
  } finally {
    bitmap.recycle()
  }
}

/**
 * A copy the tools can read: the same path when the PDF isn't encrypted, a decrypted copy in the cache when `password`
 * opens it (empty works for PDFs locked only against editing), or null when it needs a (different) password.
 */
fun unlockPdf(path: String, password: String): String? {
  val file = File(pathOf(path))
  if (!mentionsEncrypt(file)) return path
  return try {
    PDDocument.load(file, password).use { doc ->
      if (!doc.isEncrypted) return path
      doc.isAllSecurityToBeRemoved = true
      val out = File(cache("unlocked"), "${System.currentTimeMillis()}.pdf")
      doc.save(out)
      out.path
    }
  } catch (_: InvalidPasswordException) {
    null
  }
}

/** One page as a JPEG `maxWidth` px wide, cached per file, page and width. */
fun renderPage(path: String, index: Int, maxWidth: Int): Thumb {
  val src = File(pathOf(path))
  val out = File(cache("pdf-pages"), md5("${src.path}-${src.lastModified()}-${src.length()}-$index-$maxWidth") + ".jpg")
  if (!out.exists()) {
    val part = File(out.path + ".part") // only a finished render takes the cached name
    renderer(path).use { r -> r.openPage(index).use { p -> BufferedOutputStream(FileOutputStream(part)).use { p.writeJpeg(maxWidth, 85, it) } } }
    part.renameTo(out)
  }
  val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  BitmapFactory.decodeFile(out.path, b)
  return Thumb(out.path, b.outWidth, b.outHeight)
}

/** The first `maxPages` pages (0 = all) as JPEGs `maxWidth` pixels wide, cached per file. */
fun renderThumbs(path: String, maxWidth: Int, maxPages: Int = 0): List<Thumb> {
  val file = File(pathOf(path))
  val root = cache("pdf-thumbnails")
  val dir = File(root, md5("${file.path}-${file.lastModified()}-${file.length()}-$maxWidth-$maxPages"))
  val meta = File(dir, "meta.txt")
  if (meta.exists()) {
    val count = meta.readText().trim().toIntOrNull() ?: 0
    val cached = (0 until count).mapNotNull { i ->
      val f = File(dir, "$i.jpg")
      val dims = File(dir, "$i.dim").takeIf { it.exists() }?.readText()?.split("x")
      if (!f.exists() || dims == null) null else Thumb(f.path, dims[0].toInt(), dims[1].toInt())
    }
    if (count > 0 && cached.size == count) return cached
  }
  try { // keep the cache to a handful of documents
    val dirs = root.listFiles()?.filter { it.isDirectory } ?: emptyList()
    if (dirs.size > 8) dirs.sortedBy { it.lastModified() }.take(dirs.size - 6).forEach { it.deleteRecursively() }
  } catch (_: Exception) {}
  dir.deleteRecursively()
  dir.mkdirs()
  return renderer(path).use { r ->
    val count = if (maxPages > 0) minOf(maxPages, r.pageCount) else r.pageCount
    (0 until count).map { i ->
      r.openPage(i).use { page ->
        val height = (page.height * maxWidth.toFloat() / page.width).toInt().coerceAtLeast(1)
        val f = File(dir, "$i.jpg")
        BufferedOutputStream(FileOutputStream(f)).use { page.writeJpeg(maxWidth, 75, it) }
        File(dir, "$i.dim").writeText("${maxWidth}x$height")
        Thumb(f.path, maxWidth, height)
      }
    }.also { meta.writeText(count.toString()) }
  }
}

/** Page sizes in points as displayed (the page's /Rotate already applied), without rendering anything. */
fun pageSizes(path: String): List<Pair<Int, Int>> = renderer(path).use { r ->
  (0 until r.pageCount).map { i -> r.openPage(i).use { it.width to it.height } }
}

/** Each page's /Rotate in degrees, which pageSizes has already applied. */
fun pageRotations(path: String): List<Int> = PDDocument.load(File(pathOf(path))).use { doc -> doc.pages.map { ((it.rotation % 360) + 360) % 360 } }

/**
 * Redaction that removes, not covers. Every page with boxes is rendered at 216 dpi with the boxes painted black, and
 * that picture becomes the page's only content. Also dropped, since they can hold copies of redacted text: those pages'
 * annotations, the form field tree, and the accessibility tag tree. `boxes`: page index -> [x, y, w, h] fractions of
 * the page as displayed.
 */
fun redactPdf(bytes: ByteArray, boxes: Map<String, List<Box>>): ByteArray {
  val tmp = File(appContext.cacheDir, "redact-${System.currentTimeMillis()}.pdf").apply { writeBytes(bytes) } // PdfRenderer needs a file
  try {
    PDDocument.load(tmp).use { doc ->
      val redacted = mutableSetOf<Int>()
      renderer(tmp.path).use { r ->
        for ((key, list) in boxes) {
          val i = key.toIntOrNull() ?: continue
          if (list.isEmpty() || i !in 0 until r.pageCount) continue
          val (w, h, image) = r.openPage(i).use { p ->
            val bw = minOf(p.width * 3, 3000)
            val bh = (p.height * bw.toFloat() / p.width).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            p.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            val canvas = Canvas(bitmap)
            val black = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
            for (b in list) {
              val x = b[0] * bw
              val y = b[1] * bh
              canvas.drawRect(x, y, x + b[2] * bw, y + b[3] * bh, black)
            }
            bitmap.setHasAlpha(false) // no soft mask in the PDF
            val img = JPEGFactory.createFromImage(doc, bitmap, 0.9f)
            bitmap.recycle()
            Triple(p.width.toFloat(), p.height.toFloat(), img)
          }
          val page = doc.getPage(i)
          for (name in listOf("Annots", "Thumb", "PieceInfo", "Metadata", "StructParents", "B", "AA", "Tabs", "ArtBox", "BleedBox", "TrimBox", "UserUnit", "Group", "VP", "SeparationInfo", "ID", "PZ")) {
            page.cosObject.removeItem(COSName.getPDFName(name))
          }
          val box = PDRectangle(w, h) // the picture is already the visible area, rotated as displayed
          page.mediaBox = box
          page.cropBox = box
          page.rotation = 0
          page.resources = PDResources()
          PDPageContentStream(doc, page, PDPageContentStream.AppendMode.OVERWRITE, true).use { it.drawImage(image, 0f, 0f, w, h) }
          redacted += i
        }
      }
      if (redacted.isNotEmpty()) {
        val catalog = doc.documentCatalog.cosObject
        // The document's title, author, subject and keywords (and their XMP copy) can repeat what was redacted.
        listOf("AcroForm", "StructTreeRoot", "MarkInfo", "Metadata").forEach { catalog.removeItem(COSName.getPDFName(it)) }
        doc.documentInformation = PDDocumentInformation()
        // Annotations elsewhere can still point at the removed ones, which would keep them, and their text, in the file.
        for ((i, page) in doc.pages.withIndex()) {
          if (i in redacted) continue
          for (annot in page.annotations) {
            annot.cosObject.removeItem(COSName.getPDFName("Parent"))
            annot.cosObject.removeItem(COSName.getPDFName("IRT"))
          }
        }
      }
      return doc.bytes() // a full rewrite: unreferenced objects aren't written
    }
  } finally {
    tmp.delete()
  }
}

/** AES-256; the password both opens the PDF and unlocks every permission. Null when it's already encrypted. */
fun setPdfPassword(bytes: ByteArray, password: String): ByteArray? {
  val doc = try { PDDocument.load(bytes) } catch (_: InvalidPasswordException) { return null }
  doc.use {
    if (it.isEncrypted) return null
    it.protect(StandardProtectionPolicy(password, password, AccessPermission()).apply { encryptionKeyLength = 256 })
    return it.bytes()
  }
}

/** Every page as a JPEG at 144 dpi (at most 2400 px wide) in the cache: a .jpg for one page, a .zip for more. */
fun pdfToJpeg(path: String): File = renderer(path).use { r ->
  val n = r.pageCount
  val out = File(appContext.cacheDir, "export-${System.currentTimeMillis()}.${if (n == 1) "jpg" else "zip"}")
  val page = { i: Int, sink: OutputStream -> r.openPage(i).use { it.writeJpeg(minOf(it.width * 2, 2400), 90, sink) } }
  val sink = BufferedOutputStream(FileOutputStream(out))
  if (n == 1) {
    sink.use { page(0, it) }
  } else {
    ZipOutputStream(sink).use { zip ->
      zip.setLevel(Deflater.NO_COMPRESSION) // JPEGs don't shrink any further
      for (i in 0 until n) {
        zip.putNextEntry(ZipEntry("page-%03d.jpg".format(i + 1)))
        page(i, zip)
        zip.closeEntry()
      }
    }
  }
  out
}

/**
 * A .docx in the cache: each page's text as paragraphs, starting on a new page. Pages with (almost) no text, such as
 * scans and photos, go in as a picture of the page instead.
 * ponytail: text only on text pages, so their images, tables and columns are lost.
 */
fun pdfToDocx(path: String): File {
  val out = File(appContext.cacheDir, "export-${System.currentTimeMillis()}.docx")
  PDDocument.load(File(pathOf(path))).use { doc ->
    renderer(path).use { r ->
      ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use { zip ->
        val put = { name: String, body: String ->
          zip.putNextEntry(ZipEntry(name))
          zip.write(body.toByteArray())
          zip.closeEntry()
        }
        val stripper = PDFTextStripper().apply { sortByPosition = true; lineSeparator = " "; paragraphEnd = "\n" }
        val body = StringBuilder()
        val rels = StringBuilder()
        for (i in 0 until doc.numberOfPages) {
          val newPage = if (i > 0) "<w:pPr><w:pageBreakBefore/></w:pPr>" else ""
          stripper.startPage = i + 1
          stripper.endPage = i + 1
          val paras = stripper.getText(doc).split('\n').map { it.trim() }.filter { it.isNotEmpty() }
          if (paras.sumOf { it.length } >= 50) {
            paras.forEachIndexed { k, t ->
              body.append("<w:p>${if (k == 0) newPage else ""}<w:r><w:t xml:space=\"preserve\">${xml(t)}</w:t></w:r></w:p>")
            }
            continue
          }
          val id = "page${i + 1}"
          zip.putNextEntry(ZipEntry("word/media/$id.jpeg"))
          val (w, h) = r.openPage(i).use { it.writeJpeg(minOf(it.width * 2, 1600), 85, zip); it.width to it.height }
          zip.closeEntry()
          val k = minOf(DOCX_MAX_W.toDouble() / (w * EMU_PER_PT), DOCX_MAX_H.toDouble() / (h * EMU_PER_PT))
          val cx = (w * EMU_PER_PT * k).toLong()
          val cy = (h * EMU_PER_PT * k).toLong()
          rels.append("<Relationship Id=\"$id\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/$id.jpeg\"/>")
          body.append(
            "<w:p>$newPage<w:r><w:drawing><wp:inline><wp:extent cx=\"$cx\" cy=\"$cy\"/><wp:docPr id=\"${i + 1}\" name=\"Page ${i + 1}\"/>" +
              "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:pic>" +
              "<pic:nvPicPr><pic:cNvPr id=\"${i + 1}\" name=\"$id.jpeg\"/><pic:cNvPicPr/></pic:nvPicPr>" +
              "<pic:blipFill><a:blip r:embed=\"$id\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
              "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>" +
              "</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>",
          )
        }
        val head = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        put(
          "[Content_Types].xml",
          "$head<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/><Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>" +
            "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>",
        )
        put(
          "_rels/.rels",
          "$head<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>",
        )
        put("word/_rels/document.xml.rels", "$head<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">$rels</Relationships>")
        put(
          "word/document.xml",
          "$head<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" " +
            "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
            "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" " +
            "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
            "xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><w:body>$body" +
            "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\" " +
            "w:header=\"0\" w:footer=\"0\" w:gutter=\"0\"/></w:sectPr></w:body></w:document>",
        )
      }
    }
  }
  return out
}

/**
 * Makes a scanned PDF searchable: every page that has no real text is rendered, read by ML Kit, and given an invisible
 * text layer over the words, so find, select and copy work while the page looks the same. Null when nothing was read.
 */
fun ocrPdf(bytes: ByteArray): ByteArray? {
  val tmp = File(appContext.cacheDir, "ocr-${System.currentTimeMillis()}.pdf").apply { writeBytes(bytes) }
  val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
  try {
    PDDocument.load(tmp).use { doc ->
      val stripper = PDFTextStripper()
      var added = 0
      renderer(tmp.path).use { r ->
        for (i in 0 until minOf(doc.numberOfPages, r.pageCount)) {
          stripper.startPage = i + 1
          stripper.endPage = i + 1
          if (stripper.getText(doc).trim().length >= 50) continue // already searchable
          // Boxes come back as fractions of the page, so the render resolution never reaches the text layer.
          val lines = r.openPage(i).use { p ->
            val bw = minOf(p.width * 3, 3000) // ~216 dpi: enough for ML Kit to read small print
            val bh = (p.height * bw.toFloat() / p.width).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            p.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            try {
              Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), 120, TimeUnit.SECONDS)
                .textBlocks.flatMap { block -> block.lines }
                .mapNotNull { line ->
                  val box = line.boundingBox ?: return@mapNotNull null
                  val text = ocrText(line.text)
                  // getStringWidth rejects anything Helvetica can't write, so a bad line is dropped here.
                  val width = try { PDType1Font.HELVETICA.getStringWidth(text) } catch (_: Exception) { 0f }
                  if (text.isEmpty() || width <= 0f || box.width() <= 0 || box.height() <= 0) null
                  else Triple(text, RectF(box.left / bw.toFloat(), box.top / bh.toFloat(), box.right / bw.toFloat(), box.bottom / bh.toFloat()), width)
                }
            } finally {
              bitmap.recycle()
            }
          }
          if (lines.isEmpty()) continue
          val page = doc.getPage(i)
          val rot = ((page.rotation % 360) + 360) % 360
          val box = page.cropBox
          val pw = if (rot % 180 == 0) box.width else box.height
          val ph = if (rot % 180 == 0) box.height else box.width
          PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            cs.transform(displayMatrix(box, rot))
            cs.beginText()
            cs.setRenderingMode(RenderingMode.NEITHER) // there for search and selection only, never drawn
            for ((text, rect, width) in lines) {
              val size = ((rect.bottom - rect.top) * ph).coerceAtLeast(1f)
              cs.setFont(PDType1Font.HELVETICA, size)
              // Stretch the line to the width of the words underneath, so a selection lands on them.
              cs.setHorizontalScaling((rect.right - rect.left) * pw / (width / 1000f * size) * 100f)
              cs.setTextMatrix(Matrix.getTranslateInstance(rect.left * pw, ph - rect.bottom * ph))
              cs.showText(text)
              added++
            }
            cs.endText()
          }
        }
      }
      return if (added == 0) null else doc.bytes()
    }
  } finally {
    recognizer.close()
    tmp.delete()
  }
}

/**
 * A scan's PDF, built from the page images on disk and streamed straight to a cache file. Each sheet item is an index
 * into `images`, a crop in image fractions, and where to place it (points, y from the bottom).
 */
fun buildScanPdf(sheets: List<Sheet>, images: List<String>): File {
  val out = File(appContext.cacheDir, "scan-${System.currentTimeMillis()}.pdf")
  PDDocument().use { doc ->
    val embedded = arrayOfNulls<PDImageXObject>(images.size) // a book's two halves share one embedded image
    for (sheet in sheets) {
      val page = PDPage(PDRectangle(sheet.W, sheet.H))
      doc.addPage(page)
      PDPageContentStream(doc, page).use { cs ->
        for (it in sheet.items) {
          val image = embedded[it.img] ?: embedImage(doc, images[it.img]).also { e -> embedded[it.img] = e }
          // The box shows only `crop` of the image, so draw the whole image scaled up and clip away the rest.
          val fullW = it.w / it.crop.w
          val fullH = it.h / it.crop.h
          cs.saveGraphicsState()
          cs.addRect(it.x, it.y, it.w, it.h)
          cs.clip()
          cs.drawImage(image, it.x - it.crop.x * fullW, it.y - (1 - it.crop.y - it.crop.h) * fullH, fullW, fullH)
          cs.restoreGraphicsState()
        }
      }
    }
    doc.save(out)
  }
  return out
}

/** Re-encodes one JPEG at `quality`, its long side at most `maxSide` px (0 keeps it). Null if Android can't decode it. */
fun recompressJpeg(bytes: ByteArray, quality: Int, maxSide: Int): ByteArray? {
  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
  val long = maxOf(bounds.outWidth, bounds.outHeight)
  var sample = 1 // decode near the target size rather than whole: a 6000 px scan is ~140 MB as pixels
  while (maxSide > 0 && long / (sample * 2) >= maxSide) sample *= 2
  val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
  val k = if (maxSide > 0) minOf(1f, maxSide.toFloat() / maxOf(decoded.width, decoded.height)) else 1f
  val bitmap = if (k < 1f) {
    Bitmap.createScaledBitmap(decoded, (decoded.width * k).toInt().coerceAtLeast(1), (decoded.height * k).toInt().coerceAtLeast(1), true)
      .also { if (it !== decoded) decoded.recycle() }
  } else decoded
  try {
    return ByteArrayOutputStream(bytes.size / 2).use { out ->
      bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)
      out.toByteArray()
    }
  } finally {
    bitmap.recycle()
  }
}
