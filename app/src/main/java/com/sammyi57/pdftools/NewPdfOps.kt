package com.sammyi57.pdftools

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min

/** Copies a page into this document, preserving resources. */
internal fun PDDocument.adoptPage(p: PDPage): PDPage {
  val n = importPage(p)
  if (!n.cosObject.containsKey(COSName.RESOURCES)) n.resources = p.resources ?: PDResources()
  return n
}

internal fun pickPages(src: PDDocument, indices: List<Int>): ByteArray = PDDocument().use { out ->
  for (i in indices) out.adoptPage(src.getPage(i))
  out.bytes()
}

/** Remove pages specified by ranges (e.g. "1-3, 5"). */
fun removePages(bytes: ByteArray, pages: String): ByteArray = loadPdf(bytes).use { src ->
  val n = src.numberOfPages
  if (pages.isBlank()) fail("Enter pages to remove, e.g. 1, 3-5")
  val toRemove = parseRanges(pages, n).flatten().toSet()
  val remaining = (0 until n).filter { it !in toRemove }
  if (remaining.isEmpty()) fail("Keep at least one page.")
  pickPages(src, remaining)
}

/** Extract pages specified by ranges (e.g. "1-3, 5"). */
fun extractPages(bytes: ByteArray, pages: String): ByteArray = loadPdf(bytes).use { src ->
  val n = src.numberOfPages
  if (pages.isBlank()) fail("Enter pages to extract, e.g. 1-3, 5")
  val toExtract = parseRanges(pages, n).flatten()
  if (toExtract.isEmpty()) fail("Enter pages to extract, e.g. 1-3, 5")
  pickPages(src, toExtract)
}

/** Optimize PDF by stripping redundant metadata and unreferenced stream data. */
fun optimizePdf(bytes: ByteArray): ByteArray = loadPdf(bytes).use { doc ->
  doc.documentCatalog.cosObject.removeItem(COSName.METADATA)
  for (page in doc.pages) {
    page.cosObject.removeItem(COSName.getPDFName("PieceInfo"))
    page.cosObject.removeItem(COSName.getPDFName("Thumb"))
  }
  val out = doc.bytes()
  if (out.size < bytes.size) out else bytes
}

private fun findSubsequence(source: ByteArray, target: ByteArray): Int {
  if (target.isEmpty() || source.size < target.size) return -1
  for (i in 0..source.size - target.size) {
    var match = true
    for (j in target.indices) {
      if (source[i + j] != target[j]) { match = false; break }
    }
    if (match) return i
  }
  return -1
}

/** Repair damaged or corrupted PDF structure by rebuilding page trees and stream xrefs. */
fun repairPdf(bytes: ByteArray): ByteArray {
  var raw = bytes
  val pdfHeader = "%PDF-".toByteArray(Charsets.US_ASCII)
  val headIdx = findSubsequence(raw, pdfHeader)
  if (headIdx > 0) raw = raw.copyOfRange(headIdx, raw.size)
  val tailStr = String(raw.takeLast(64).toByteArray(), Charsets.US_ASCII)
  if (!tailStr.contains("%%EOF")) {
    raw = raw + "\n%%EOF\n".toByteArray(Charsets.US_ASCII)
  }
  val doc = try {
    PDDocument.load(raw)
  } catch (_: Exception) {
    try { PDDocument.load(bytes) } catch (_: Exception) { fail("Could not repair this PDF. The file is too damaged.") }
  }
  return doc.use { src ->
    if (src.numberOfPages == 0) fail("Could not recover any pages from this file.")
    PDDocument().use { out ->
      for (page in src.pages) {
        runCatching { out.adoptPage(page) }
      }
      if (out.numberOfPages == 0) fail("Could not recover any pages from this file.")
      out.bytes()
    }
  }
}

/** Convert a list of image files to a single PDF document. */
fun imagesToPdf(files: List<Named>, fitMode: String): ByteArray {
  if (files.isEmpty()) fail("Choose at least one image.")
  return PDDocument().use { doc ->
    val a4 = SIZES["A4"] ?: (595.28f to 841.89f)
    for (file in files) {
      val bmp = BitmapFactory.decodeByteArray(file.bytes, 0, file.bytes.size)
        ?: fail("Could not decode image ${file.name}.")
      val imgW = bmp.width.toFloat()
      val imgH = bmp.height.toFloat()
      val page = if (fitMode == "a4") {
        val (pw, ph) = a4
        val margin = 24f
        val k = min((pw - margin * 2) / imgW, (ph - margin * 2) / imgH)
        val dw = imgW * k
        val dh = imgH * k
        val p = PDPage(PDRectangle(pw, ph))
        doc.addPage(p)
        val imageXObj = try {
          LosslessFactory.createFromImage(doc, bmp)
        } catch (_: Exception) {
          JPEGFactory.createFromImage(doc, bmp, 0.9f)
        }
        PDPageContentStream(doc, p).use { cs ->
          cs.drawImage(imageXObj, (pw - dw) / 2f, (ph - dh) / 2f, dw, dh)
        }
        p
      } else {
        // Fit page to image aspect ratio at standard 72 pt/in based on 96 dpi screen dimensions
        val pw = imgW * 72f / 96f
        val ph = imgH * 72f / 96f
        val p = PDPage(PDRectangle(pw, ph))
        doc.addPage(p)
        val imageXObj = try {
          LosslessFactory.createFromImage(doc, bmp)
        } catch (_: Exception) {
          JPEGFactory.createFromImage(doc, bmp, 0.9f)
        }
        PDPageContentStream(doc, p).use { cs ->
          cs.drawImage(imageXObj, 0f, 0f, pw, ph)
        }
        p
      }
      bmp.recycle()
    }
    doc.bytes()
  }
}

/** Best effort PDF/A archive metadata and OutputIntent injection. Not certified. */
fun pdfToPdfa(bytes: ByteArray): ByteArray = loadPdf(bytes).use { doc ->
  doc.isAllSecurityToBeRemoved = true
  val xmp = """<?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/">
  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
    <rdf:Description rdf:about="" xmlns:pdfaid="http://www.aiim.org/pdfa/ns/id/">
      <pdfaid:part>1</pdfaid:part>
      <pdfaid:conformance>B</pdfaid:conformance>
    </rdf:Description>
  </rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>""".trimIndent()
  val meta = com.tom_roush.pdfbox.pdmodel.common.PDMetadata(doc)
  meta.importXMPMetadata(xmp.toByteArray(Charsets.UTF_8))
  doc.documentCatalog.metadata = meta

  val catalog = doc.documentCatalog.cosObject
  val intents = COSArray()
  val intent = COSDictionary().apply {
    setName(COSName.TYPE, "OutputIntent")
    setName(COSName.S, "GTS_PDFA1")
    setString(COSName.getPDFName("OutputCondition"), "sRGB IEC61966-2.1")
    setString(COSName.getPDFName("OutputConditionIdentifier"), "sRGB IEC61966-2.1")
    setString(COSName.getPDFName("RegistryName"), "http://www.color.org")
  }
  intents.add(intent)
  catalog.setItem(COSName.getPDFName("OutputIntents"), intents)

  doc.bytes()
}

/** Extract text from PDF into clean Markdown formatting. */
fun pdfToMarkdown(bytes: ByteArray): String = loadPdf(bytes).use { doc ->
  val stripper = PDFTextStripper().apply { sortByPosition = true }
  val sb = StringBuilder()
  for (i in 1..doc.numberOfPages) {
    stripper.startPage = i
    stripper.endPage = i
    val pageText = stripper.getText(doc).trim()
    if (pageText.isNotEmpty()) {
      if (sb.isNotEmpty()) sb.append("\n\n---\n\n")
      sb.append("## Page ").append(i).append("\n\n")
      val lines = pageText.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
      for (line in lines) {
        when {
          line.startsWith("#") || line.startsWith("*") || line.startsWith("-") -> sb.append(line).append("\n\n")
          line.length in 3..40 && line == line.uppercase() && line.any { it.isLetter() } -> sb.append("### ").append(line).append("\n\n")
          else -> sb.append(line).append("\n\n")
        }
      }
    }
  }
  if (sb.isEmpty()) fail("No readable text found in this PDF. If this is a scan, run OCR Scanned PDF first.")
  sb.toString()
}

/** Compare exactly two PDF documents side by side. */
fun comparePdf(files: List<Named>): ByteArray {
  if (files.size != 2) fail("Compare PDF needs exactly 2 PDFs.")
  val tmp1 = File(appContext.cacheDir, "comp1-${System.currentTimeMillis()}.pdf").apply { writeBytes(files[0].bytes) }
  val tmp2 = File(appContext.cacheDir, "comp2-${System.currentTimeMillis()}.pdf").apply { writeBytes(files[1].bytes) }
  try {
    val r1 = PdfRenderer(ParcelFileDescriptor.open(tmp1, ParcelFileDescriptor.MODE_READ_ONLY))
    val r2 = PdfRenderer(ParcelFileDescriptor.open(tmp2, ParcelFileDescriptor.MODE_READ_ONLY))
    val doc1 = PDDocument.load(tmp1)
    val doc2 = PDDocument.load(tmp2)
    val stripper = PDFTextStripper()
    val totalPages = maxOf(r1.pageCount, r2.pageCount)

    return PDDocument().use { outDoc ->
      // A4 Landscape: 841.89 x 595.28 pt
      val pageW = 841.89f
      val pageH = 595.28f
      val font = PDType1Font.HELVETICA_BOLD
      val fontRegular = PDType1Font.HELVETICA

      for (i in 0 until totalPages) {
        val page = PDPage(PDRectangle(pageW, pageH))
        outDoc.addPage(page)

        // Text comparison
        val t1 = if (i < doc1.numberOfPages) {
          stripper.startPage = i + 1; stripper.endPage = i + 1; stripper.getText(doc1).trim()
        } else ""
        val t2 = if (i < doc2.numberOfPages) {
          stripper.startPage = i + 1; stripper.endPage = i + 1; stripper.getText(doc2).trim()
        } else ""
        val statusText = when {
          i >= r1.pageCount -> "Missing in File 1"
          i >= r2.pageCount -> "Missing in File 2"
          t1 == t2 -> "Page ${i + 1}: Identical"
          else -> "Page ${i + 1}: Differences found"
        }

        // Draw header
        PDPageContentStream(outDoc, page, PDPageContentStream.AppendMode.OVERWRITE, true).use { cs ->
          cs.setNonStrokingColor(0.2f, 0.2f, 0.2f)
          cs.beginText()
          cs.setFont(font, 12f)
          cs.newLineAtOffset(28f, pageH - 28f)
          cs.showText("File 1: ${winAnsi(files[0].name)} (p. ${if (i < r1.pageCount) (i + 1).toString() else "-"})")
          cs.endText()

          cs.beginText()
          cs.setFont(font, 12f)
          cs.newLineAtOffset(pageW / 2 + 14f, pageH - 28f)
          cs.showText("File 2: ${winAnsi(files[1].name)} (p. ${if (i < r2.pageCount) (i + 1).toString() else "-"})")
          cs.endText()

          cs.beginText()
          cs.setFont(fontRegular, 10f)
          if ("Identical" in statusText) cs.setNonStrokingColor(0.1f, 0.6f, 0.2f) else cs.setNonStrokingColor(0.8f, 0.2f, 0.1f)
          cs.newLineAtOffset(pageW / 2 - 60f, pageH - 28f)
          cs.showText(statusText)
          cs.endText()

          // Center divider line
          cs.setStrokingColor(0.7f, 0.7f, 0.7f)
          cs.setLineWidth(0.8f)
          cs.moveTo(pageW / 2, 20f)
          cs.lineTo(pageW / 2, pageH - 36f)
          cs.stroke()
        }

        // Render and draw page 1 on left half
        val halfW = (pageW / 2) - 40f
        val halfH = pageH - 60f
        if (i < r1.pageCount) {
          r1.openPage(i).use { p1 ->
            val bw = minOf(p1.width * 2, 1600)
            val bh = (p1.height * bw.toFloat() / p1.width).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            p1.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            val img = JPEGFactory.createFromImage(outDoc, bmp, 0.85f)
            bmp.recycle()
            val scale = min(halfW / p1.width, halfH / p1.height)
            val dw = p1.width * scale
            val dh = p1.height * scale
            PDPageContentStream(outDoc, page, PDPageContentStream.AppendMode.APPEND, true).use { cs ->
              cs.drawImage(img, 20f + (halfW - dw) / 2f, 15f + (halfH - dh) / 2f, dw, dh)
            }
          }
        }

        // Render and draw page 2 on right half
        if (i < r2.pageCount) {
          r2.openPage(i).use { p2 ->
            val bw = minOf(p2.width * 2, 1600)
            val bh = (p2.height * bw.toFloat() / p2.width).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            p2.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            val img = JPEGFactory.createFromImage(outDoc, bmp, 0.85f)
            bmp.recycle()
            val scale = min(halfW / p2.width, halfH / p2.height)
            val dw = p2.width * scale
            val dh = p2.height * scale
            PDPageContentStream(outDoc, page, PDPageContentStream.AppendMode.APPEND, true).use { cs ->
              cs.drawImage(img, (pageW / 2) + 20f + (halfW - dw) / 2f, 15f + (halfH - dh) / 2f, dw, dh)
            }
          }
        }
      }

      r1.close()
      r2.close()
      doc1.close()
      doc2.close()

      outDoc.bytes()
    }
  } finally {
    tmp1.delete()
    tmp2.delete()
  }
}
