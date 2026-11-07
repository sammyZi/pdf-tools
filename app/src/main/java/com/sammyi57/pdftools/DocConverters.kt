package com.sammyi57.pdftools

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.text.Html
import android.webkit.WebView
import android.webkit.WebViewClient
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.StringReader
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.min

/** Reuses PdfRender.kt pdfToJpeg helper via temp file. */
fun runPdfToJpg(bytes: ByteArray): List<Output> {
  val tmp = File(appContext.cacheDir, "input-${System.currentTimeMillis()}.pdf").apply { writeBytes(bytes) }
  return try {
    val result = pdfToJpeg(tmp.path)
    try {
      listOf(Output("pages", result.readBytes(), ext = result.extension))
    } finally {
      result.delete()
    }
  } finally {
    tmp.delete()
  }
}

/** Reuses PdfRender.kt pdfToDocx helper via temp file. */
fun runPdfToWord(bytes: ByteArray): List<Output> {
  val tmp = File(appContext.cacheDir, "input-${System.currentTimeMillis()}.pdf").apply { writeBytes(bytes) }
  return try {
    val result = pdfToDocx(tmp.path)
    try {
      listOf(Output("document", result.readBytes(), ext = "docx"))
    } finally {
      result.delete()
    }
  } finally {
    tmp.delete()
  }
}

private fun escapeXml(s: String) = buildString {
  for (ch in s) when (ch) {
    '&' -> append("&amp;")
    '<' -> append("&lt;")
    '>' -> append("&gt;")
    '"' -> append("&quot;")
    '\'' -> append("&apos;")
    else -> if (ch == '\t' || ch >= ' ') append(ch)
  }
}

// ── Word (.docx) to PDF ─────────────────────────────────────────────────────

private data class WordRun(val text: String, val bold: Boolean = false, val italic: Boolean = false)
private data class WordPara(val runs: List<WordRun>, val isHeading: Boolean = false, val pageBreakBefore: Boolean = false)

/** Basic Word (.docx) to PDF converter. Simple documents only. */
fun wordToPdf(bytes: ByteArray): ByteArray {
  var docXml: String? = null
  val images = HashMap<String, ByteArray>()

  ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
    var entry = zip.nextEntry
    while (entry != null) {
      val name = entry.name
      if (name == "word/document.xml") {
        docXml = zip.readBytes().toString(Charsets.UTF_8)
      } else if (name.startsWith("word/media/")) {
        images[name.substringAfterLast('/')] = zip.readBytes()
      }
      zip.closeEntry()
      entry = zip.nextEntry
    }
  }

  if (docXml == null) fail("Could not read Word document. Make sure it is a valid .docx file.")

  val paras = ArrayList<WordPara>()
  val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
  val parser = factory.newPullParser().apply { setInput(StringReader(docXml)) }

  var inP = false
  var inR = false
  var inT = false
  var isBold = false
  var isItalic = false
  var pageBreak = false
  var isHeading = false
  var curRuns = ArrayList<WordRun>()

  var event = parser.eventType
  while (event != XmlPullParser.END_DOCUMENT) {
    val tag = parser.name ?: ""
    when (event) {
      XmlPullParser.START_TAG -> when (tag) {
        "p" -> {
          inP = true
          curRuns = ArrayList()
          pageBreak = false
          isHeading = false
        }
        "pStyle" -> {
          val style = parser.getAttributeValue(null, "val") ?: ""
          if (style.contains("Heading", ignoreCase = true) || style.contains("Title", ignoreCase = true)) {
            isHeading = true
          }
        }
        "r" -> {
          inR = true
          isBold = false
          isItalic = false
        }
        "b" -> isBold = true
        "i" -> isItalic = true
        "br" -> {
          val brType = parser.getAttributeValue(null, "type")
          if (brType == "page") pageBreak = true
        }
        "t" -> inT = true
      }
      XmlPullParser.TEXT -> {
        if (inP && inR && inT) {
          val text = parser.text ?: ""
          if (text.isNotEmpty()) curRuns.add(WordRun(winAnsi(text), isBold, isItalic))
        }
      }
      XmlPullParser.END_TAG -> when (tag) {
        "t" -> inT = false
        "r" -> inR = false
        "p" -> {
          inP = false
          if (curRuns.isNotEmpty() || pageBreak) {
            paras.add(WordPara(curRuns, isHeading, pageBreak))
          }
        }
      }
    }
    event = parser.next()
  }

  if (paras.isEmpty() && images.isEmpty()) fail("No readable text found in this Word document.")

  return PDDocument().use { doc ->
    val (pageW, pageH) = SIZES["A4"] ?: (595.28f to 841.89f)
    val margin = 40f
    val usableW = pageW - margin * 2

    val fontBold = PDType1Font.HELVETICA_BOLD
    val fontReg = PDType1Font.HELVETICA

    var curPage = PDPage(PDRectangle(pageW, pageH)).also { doc.addPage(it) }
    var cs = PDPageContentStream(doc, curPage)
    var curY = pageH - margin

    fun newPage() {
      cs.close()
      curPage = PDPage(PDRectangle(pageW, pageH)).also { doc.addPage(it) }
      cs = PDPageContentStream(doc, curPage)
      curY = pageH - margin
    }

    try {
      for (p in paras) {
        if (p.pageBreakBefore && curY < pageH - margin) newPage()

        val fontSize = if (p.isHeading) 16f else 11f
        val lineHeight = if (p.isHeading) 22f else 15f
        val font = if (p.isHeading) fontBold else fontReg

        // Join run text into wrapped lines
        val fullText = p.runs.joinToString("") { it.text }
        if (fullText.isBlank()) {
          curY -= lineHeight * 0.7f
          if (curY < margin) newPage()
          continue
        }

        val words = fullText.split(' ')
        var lineBuf = StringBuilder()

        for (word in words) {
          val testLine = if (lineBuf.isEmpty()) word else "$lineBuf $word"
          val w = try { font.getStringWidth(testLine) / 1000f * fontSize } catch (_: Exception) { 0f }
          if (w > usableW && lineBuf.isNotEmpty()) {
            if (curY - lineHeight < margin) newPage()
            cs.beginText()
            cs.setFont(font, fontSize)
            if (p.isHeading) cs.setNonStrokingColor(0.1f, 0.15f, 0.25f) else cs.setNonStrokingColor(0.15f, 0.15f, 0.15f)
            cs.newLineAtOffset(margin, curY)
            cs.showText(lineBuf.toString())
            cs.endText()
            curY -= lineHeight
            lineBuf = StringBuilder(word)
          } else {
            lineBuf = StringBuilder(testLine)
          }
        }

        if (lineBuf.isNotEmpty()) {
          if (curY - lineHeight < margin) newPage()
          cs.beginText()
          cs.setFont(font, fontSize)
          if (p.isHeading) cs.setNonStrokingColor(0.1f, 0.15f, 0.25f) else cs.setNonStrokingColor(0.15f, 0.15f, 0.15f)
          cs.newLineAtOffset(margin, curY)
          cs.showText(lineBuf.toString())
          cs.endText()
          curY -= lineHeight
        }

        curY -= if (p.isHeading) 8f else 4f
      }
    } finally {
      cs.close()
    }

    doc.bytes()
  }
}

// ── PowerPoint (.pptx) to PDF ───────────────────────────────────────────────

/** Basic PowerPoint (.pptx) to PDF converter. Simple documents only, text and pictures only. */
fun powerpointToPdf(bytes: ByteArray): ByteArray {
  val slideTexts = HashMap<Int, MutableList<String>>()
  val slideImages = HashMap<Int, MutableList<ByteArray>>()
  val allMedia = HashMap<String, ByteArray>()
  val slideRels = HashMap<Int, MutableMap<String, String>>()

  ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
    var entry = zip.nextEntry
    while (entry != null) {
      val name = entry.name
      if (name.startsWith("ppt/media/")) {
        allMedia[name.substringAfterLast('/')] = zip.readBytes()
      } else if (name.matches(Regex("ppt/slides/slide[0-9]+\\.xml"))) {
        val num = Regex("[0-9]+").find(name)?.value?.toIntOrNull() ?: 1
        val xml = zip.readBytes().toString(Charsets.UTF_8)
        val texts = slideTexts.getOrPut(num) { mutableListOf() }
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
        val parser = factory.newPullParser().apply { setInput(StringReader(xml)) }
        var inT = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
          when (event) {
            XmlPullParser.START_TAG -> if (parser.name == "t") inT = true
            XmlPullParser.TEXT -> if (inT) {
              val t = parser.text?.trim()
              if (!t.isNullOrEmpty()) texts.add(winAnsi(t))
            }
            XmlPullParser.END_TAG -> if (parser.name == "t") inT = false
          }
          event = parser.next()
        }
      } else if (name.matches(Regex("ppt/slides/_rels/slide[0-9]+\\.xml\\.rels"))) {
        val num = Regex("[0-9]+").find(name)?.value?.toIntOrNull() ?: 1
        val relMap = slideRels.getOrPut(num) { mutableMapOf() }
        val xml = zip.readBytes().toString(Charsets.UTF_8)
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
        val parser = factory.newPullParser().apply { setInput(StringReader(xml)) }
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
          if (event == XmlPullParser.START_TAG && parser.name == "Relationship") {
            val id = parser.getAttributeValue(null, "Id") ?: ""
            val target = parser.getAttributeValue(null, "Target") ?: ""
            if (target.contains("media/")) relMap[id] = target.substringAfterLast('/')
          }
          event = parser.next()
        }
      }
      zip.closeEntry()
      entry = zip.nextEntry
    }
  }

  val slideNums = (slideTexts.keys + slideRels.keys).sorted()
  if (slideNums.isEmpty()) fail("No slides found in this PowerPoint presentation.")

  return PDDocument().use { doc ->
    // A4 Landscape slides: 841.89 x 595.28 pt
    val (pageW, pageH) = 841.89f to 595.28f
    val margin = 50f
    val fontTitle = PDType1Font.HELVETICA_BOLD
    val fontBody = PDType1Font.HELVETICA

    for (sNum in slideNums) {
      val page = PDPage(PDRectangle(pageW, pageH))
      doc.addPage(page)
      val texts = slideTexts[sNum] ?: emptyList()
      val relMap = slideRels[sNum] ?: emptyMap()

      PDPageContentStream(doc, page).use { cs ->
        // Slide border
        cs.setStrokingColor(0.85f, 0.85f, 0.85f)
        cs.setLineWidth(1f)
        cs.addRect(margin / 2, margin / 2, pageW - margin, pageH - margin)
        cs.stroke()

        var curY = pageH - margin - 20f

        // Slide title
        val title = texts.firstOrNull()
        if (!title.isNullOrEmpty()) {
          cs.beginText()
          cs.setFont(fontTitle, 22f)
          cs.setNonStrokingColor(0.12f, 0.18f, 0.28f)
          cs.newLineAtOffset(margin + 10f, curY)
          cs.showText(title.take(70))
          cs.endText()
          curY -= 40f
        }

        // Draw slide images
        for ((_, imgName) in relMap) {
          val imgBytes = allMedia[imgName] ?: continue
          val bmp = android.graphics.BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.size) ?: continue
          val imgW = min(300f, bmp.width.toFloat())
          val imgH = imgW * bmp.height / bmp.width
          val imgXObj = try { LosslessFactory.createFromImage(doc, bmp) } catch (_: Exception) { JPEGFactory.createFromImage(doc, bmp, 0.85f) }
          bmp.recycle()
          cs.drawImage(imgXObj, pageW - margin - imgW - 10f, margin + 20f, imgW, min(imgH, pageH - margin * 2))
          break // one dominant picture per slide
        }

        // Slide body lines
        val bodyLines = texts.drop(1)
        cs.setFont(fontBody, 13f)
        cs.setNonStrokingColor(0.2f, 0.2f, 0.2f)
        for (line in bodyLines.take(12)) {
          if (curY < margin + 30f) break
          cs.beginText()
          cs.newLineAtOffset(margin + 10f, curY)
          cs.showText("• " + line.take(55))
          cs.endText()
          curY -= 22f
        }
      }
    }

    doc.bytes()
  }
}

// ── Excel (.xlsx) to PDF ────────────────────────────────────────────────────

/** Basic Excel (.xlsx) to PDF converter. Simple documents only. */
fun excelToPdf(bytes: ByteArray): ByteArray {
  val sharedStrings = ArrayList<String>()
  var sheetXml: String? = null

  ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
    var entry = zip.nextEntry
    while (entry != null) {
      val name = entry.name
      if (name == "xl/sharedStrings.xml") {
        val xml = zip.readBytes().toString(Charsets.UTF_8)
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
        val parser = factory.newPullParser().apply { setInput(StringReader(xml)) }
        var inT = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
          when (event) {
            XmlPullParser.START_TAG -> if (parser.name == "t") inT = true
            XmlPullParser.TEXT -> if (inT) sharedStrings.add(winAnsi(parser.text ?: ""))
            XmlPullParser.END_TAG -> if (parser.name == "t") inT = false
          }
          event = parser.next()
        }
      } else if (name == "xl/worksheets/sheet1.xml") {
        sheetXml = zip.readBytes().toString(Charsets.UTF_8)
      }
      zip.closeEntry()
      entry = zip.nextEntry
    }
  }

  if (sheetXml == null) fail("Could not read Excel worksheet. Make sure it is a valid .xlsx file.")

  val rows = ArrayList<List<String>>()
  val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
  val parser = factory.newPullParser().apply { setInput(StringReader(sheetXml)) }

  var curRow = ArrayList<String>()
  var cellType = ""
  var inV = false
  var inT = false
  var cellVal = ""

  fun colIdx(ref: String): Int {
    var c = 0
    for (ch in ref) {
      if (ch in 'A'..'Z') c = c * 26 + (ch - 'A' + 1) else break
    }
    return (c - 1).coerceAtLeast(0)
  }

  var event = parser.eventType
  while (event != XmlPullParser.END_DOCUMENT) {
    val tag = parser.name ?: ""
    when (event) {
      XmlPullParser.START_TAG -> when (tag) {
        "row" -> curRow = ArrayList()
        "c" -> {
          cellType = parser.getAttributeValue(null, "t") ?: ""
          cellVal = ""
        }
        "v" -> inV = true
        "t" -> inT = true
      }
      XmlPullParser.TEXT -> {
        if (inV || inT) cellVal += parser.text ?: ""
      }
      XmlPullParser.END_TAG -> when (tag) {
        "v", "t" -> { inV = false; inT = false }
        "c" -> {
          val v = if (cellType == "s") {
            val idx = cellVal.toIntOrNull() ?: -1
            if (idx in sharedStrings.indices) sharedStrings[idx] else cellVal
          } else cellVal
          curRow.add(winAnsi(v.trim()))
        }
        "row" -> if (curRow.any { it.isNotEmpty() }) rows.add(curRow)
      }
    }
    event = parser.next()
  }

  if (rows.isEmpty()) fail("No data found in this Excel spreadsheet.")

  return PDDocument().use { doc ->
    // A4 Landscape: 841.89 x 595.28 pt
    val (pageW, pageH) = 841.89f to 595.28f
    val margin = 36f
    val usableW = pageW - margin * 2
    val colCount = minOf(rows.maxOfOrNull { it.size } ?: 1, 8)
    val colW = usableW / colCount
    val rowH = 22f

    val fontBold = PDType1Font.HELVETICA_BOLD
    val fontReg = PDType1Font.HELVETICA

    var curPage = PDPage(PDRectangle(pageW, pageH)).also { doc.addPage(it) }
    var cs = PDPageContentStream(doc, curPage)
    var curY = pageH - margin

    fun newPage() {
      cs.close()
      curPage = PDPage(PDRectangle(pageW, pageH)).also { doc.addPage(it) }
      cs = PDPageContentStream(doc, curPage)
      curY = pageH - margin
    }

    try {
      rows.forEachIndexed { rIdx, row ->
        if (curY - rowH < margin) newPage()
        val isHead = rIdx == 0

        // Row background
        if (isHead) {
          cs.setNonStrokingColor(0.92f, 0.94f, 0.97f)
          cs.addRect(margin, curY - rowH, usableW, rowH)
          cs.fill()
        }

        // Cell borders
        cs.setStrokingColor(0.8f, 0.82f, 0.85f)
        cs.setLineWidth(0.5f)
        cs.addRect(margin, curY - rowH, usableW, rowH)
        cs.stroke()

        for (c in 1 until colCount) {
          cs.moveTo(margin + c * colW, curY)
          cs.lineTo(margin + c * colW, curY - rowH)
          cs.stroke()
        }

        // Cell text
        val font = if (isHead) fontBold else fontReg
        cs.setFont(font, if (isHead) 10f else 9f)
        cs.setNonStrokingColor(if (isHead) 0.1f else 0.2f, if (isHead) 0.15f else 0.2f, if (isHead) 0.25f else 0.2f)

        for (c in 0 until minOf(row.size, colCount)) {
          val text = row[c].take(25)
          if (text.isNotEmpty()) {
            cs.beginText()
            cs.newLineAtOffset(margin + c * colW + 4f, curY - rowH + 6f)
            cs.showText(text)
            cs.endText()
          }
        }

        curY -= rowH
      }
    } finally {
      cs.close()
    }

    doc.bytes()
  }
}

// ── HTML to PDF (Offline WebView) ──────────────────────────────────────────

/**
 * Converts local HTML file to PDF using WebView offline.
 * Network loads blocked, JavaScript turned off.
 */
fun htmlToPdf(bytes: ByteArray): ByteArray {
  val html = String(bytes, Charsets.UTF_8)
  if (html.isBlank()) fail("HTML file is empty.")

  val completed = CompletableDeferred<ByteArray>()

  runBlocking(Dispatchers.Main) {
    try {
      val webView = WebView(appContext).apply {
        settings.apply {
          javaScriptEnabled = false
          blockNetworkLoads = true
          allowFileAccess = false
          allowContentAccess = false
        }
      }

      val a4W = 595
      val a4H = 842

      webView.webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) {
          val v = view ?: return
          try {
            v.measure(
              android.view.View.MeasureSpec.makeMeasureSpec(a4W, android.view.View.MeasureSpec.EXACTLY),
              android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
            )
            val totalH = v.measuredHeight.coerceAtLeast(a4H)
            v.layout(0, 0, a4W, totalH)

            val pdfDocument = android.graphics.pdf.PdfDocument()
            var curY = 0
            var pageNum = 1
            while (curY < totalH) {
              val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(a4W, a4H, pageNum++).create()
              val page = pdfDocument.startPage(pageInfo)
              page.canvas.save()
              page.canvas.translate(0f, -curY.toFloat())
              v.draw(page.canvas)
              page.canvas.restore()
              pdfDocument.finishPage(page)
              curY += a4H
            }

            val bos = ByteArrayOutputStream()
            pdfDocument.writeTo(bos)
            pdfDocument.close()
            completed.complete(bos.toByteArray())
          } catch (e: Exception) {
            completed.completeExceptionally(e)
          }
        }
      }

      webView.loadDataWithBaseURL("about:blank", html, "text/html", "UTF-8", null)
    } catch (e: Exception) {
      completed.completeExceptionally(e)
    }
  }

  val result = runBlocking {
    withTimeoutOrNull(20000) { completed.await() }
  }

  return result ?: fallbackHtmlToPdf(html)
}

/** Fallback parser for HTML to PDF in environments where WebView printing is restricted. */
private fun fallbackHtmlToPdf(html: String): ByteArray {
  val clean = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString().trim()
  if (clean.isEmpty()) fail("No readable text found in this HTML file.")

  return PDDocument().use { doc ->
    val (pageW, pageH) = SIZES["A4"] ?: (595.28f to 841.89f)
    val margin = 40f
    val usableW = pageW - margin * 2
    val font = PDType1Font.HELVETICA
    val lineHeight = 15f

    var curPage = PDPage(PDRectangle(pageW, pageH)).also { doc.addPage(it) }
    var cs = PDPageContentStream(doc, curPage)
    var curY = pageH - margin

    fun newPage() {
      cs.close()
      curPage = PDPage(PDRectangle(pageW, pageH)).also { doc.addPage(it) }
      cs = PDPageContentStream(doc, curPage)
      curY = pageH - margin
    }

    try {
      cs.setFont(font, 11f)
      cs.setNonStrokingColor(0.15f, 0.15f, 0.15f)
      val lines = clean.split('\n')
      for (line in lines) {
        val trimmed = winAnsi(line.trim())
        if (trimmed.isEmpty()) {
          curY -= lineHeight * 0.7f
          if (curY < margin) newPage()
          continue
        }
        val words = trimmed.split(' ')
        var lineBuf = StringBuilder()
        for (w in words) {
          val test = if (lineBuf.isEmpty()) w else "$lineBuf $w"
          val width = try { font.getStringWidth(test) / 1000f * 11f } catch (_: Exception) { 0f }
          if (width > usableW && lineBuf.isNotEmpty()) {
            if (curY - lineHeight < margin) newPage()
            cs.beginText()
            cs.newLineAtOffset(margin, curY)
            cs.showText(lineBuf.toString())
            cs.endText()
            curY -= lineHeight
            lineBuf = StringBuilder(w)
          } else {
            lineBuf = StringBuilder(test)
          }
        }
        if (lineBuf.isNotEmpty()) {
          if (curY - lineHeight < margin) newPage()
          cs.beginText()
          cs.newLineAtOffset(margin, curY)
          cs.showText(lineBuf.toString())
          cs.endText()
          curY -= lineHeight
        }
      }
    } finally {
      cs.close()
    }
    doc.bytes()
  }
}

// ── PDF to PowerPoint (.pptx) ───────────────────────────────────────────────

/**
 * Converts PDF pages into PowerPoint presentation (.pptx).
 * Honest limit: Page images not editable.
 */
fun pdfToPowerpoint(bytes: ByteArray): ByteArray {
  val tmp = File(appContext.cacheDir, "ppt-in-${System.currentTimeMillis()}.pdf").apply { writeBytes(bytes) }
  val out = File(appContext.cacheDir, "ppt-out-${System.currentTimeMillis()}.pptx")

  try {
    val r = PdfRenderer(ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY))
    val n = r.pageCount

    ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use { zip ->
      val put = { name: String, body: String ->
        zip.putNextEntry(ZipEntry(name))
        zip.write(body.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
      }

      val contentTypes = StringBuilder()
      val sldRels = StringBuilder()
      val sldEntries = StringBuilder()

      for (i in 0 until n) {
        val sId = i + 1
        contentTypes.append("<Override PartName=\"/ppt/slides/slide$sId.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>")
        sldRels.append("<Relationship Id=\"rId$sId\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" Target=\"slides/slide$sId.xml\"/>")
        sldEntries.append("<p:sldId id=\"${255 + sId}\" r:id=\"rId$sId\"/>")

        // Render page to JPEG
        zip.putNextEntry(ZipEntry("ppt/media/image$sId.jpeg"))
        r.openPage(i).use { page ->
          val bw = minOf(page.width * 2, 1920)
          val bh = (page.height * bw.toFloat() / page.width).toInt().coerceAtLeast(1)
          val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
          page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
          bmp.compress(Bitmap.CompressFormat.JPEG, 85, zip)
          bmp.recycle()
        }
        zip.closeEntry()

        // slideX.xml.rels
        put(
          "ppt/slides/_rels/slide$sId.xml.rels",
          "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rIdImg\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"../media/image$sId.jpeg\"/>" +
            "</Relationships>",
        )

        // slideX.xml (EMU: 9144000 x 5143500 for standard 16:9 slide)
        put(
          "ppt/slides/slide$sId.xml",
          "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<p:sld xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
            "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
            "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">" +
            "<p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>" +
            "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>" +
            "<p:pic><p:nvPicPr><p:cNvPr id=\"2\" name=\"Page $sId\"/><p:cNvPicPr><a:picLocks noChangeAspect=\"1\"/></p:cNvPicPr><p:nvPr/></p:nvPicPr>" +
            "<p:blipFill><a:blip r:embed=\"rIdImg\"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>" +
            "<p:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"9144000\" cy=\"5143500\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr>" +
            "</p:pic></p:spTree></p:cSld></p:sld>",
        )
      }

      r.close()

      val head = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
      put(
        "[Content_Types].xml",
        "$head<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
          "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
          "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
          "<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>" +
          "<Override PartName=\"/ppt/presentation.xml\" ContentType=\"application/vnd.openxmlformats-presentationml.presentation.main+xml\"/>" +
          "$contentTypes</Types>",
      )

      put(
        "_rels/.rels",
        "$head<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
          "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"ppt/presentation.xml\"/>" +
          "</Relationships>",
      )

      put(
        "ppt/presentation.xml",
        "$head<p:presentation xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
          "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
          "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">" +
          "<p:sldIdLst>$sldEntries</p:sldIdLst><p:sldSz cx=\"9144000\" cy=\"5143500\"/></p:presentation>",
      )

      put("ppt/_rels/presentation.xml.rels", "$head<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">$sldRels</Relationships>")
    }

    return out.readBytes()
  } finally {
    tmp.delete()
    out.delete()
  }
}

// ── PDF to Excel (.xlsx) ────────────────────────────────────────────────────

/** Converts PDF text and tables to an Excel spreadsheet (.xlsx). */
fun pdfToExcel(bytes: ByteArray): ByteArray {
  val lines = ArrayList<String>()
  loadPdf(bytes).use { doc ->
    val stripper = PDFTextStripper().apply { sortByPosition = true }
    val text = stripper.getText(doc)
    for (line in text.split('\n')) {
      val t = line.trim()
      if (t.isNotEmpty()) lines.add(t)
    }
  }

  if (lines.isEmpty()) fail("No readable text found in this PDF.")

  val out = File(appContext.cacheDir, "xlsx-out-${System.currentTimeMillis()}.xlsx")
  try {
    ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use { zip ->
      val put = { name: String, body: String ->
        zip.putNextEntry(ZipEntry(name))
        zip.write(body.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
      }

      val sharedStrings = ArrayList<String>()
      val stringMap = HashMap<String, Int>()

      fun strIdx(s: String): Int = stringMap.getOrPut(s) {
        val idx = sharedStrings.size
        sharedStrings.add(s)
        idx
      }

      fun colLetter(idx: Int): String {
        var n = idx
        var res = ""
        while (n >= 0) {
          res = ('A'.code + (n % 26)).toChar() + res
          n = (n / 26) - 1
        }
        return res
      }

      val sheetRows = StringBuilder()
      lines.forEachIndexed { rIdx, line ->
        val rowNum = rIdx + 1
        val tokens = line.split(Regex("\\t|\\s{2,}|,"))
        sheetRows.append("<row r=\"$rowNum\">")
        tokens.forEachIndexed { cIdx, token ->
          val cellRef = "${colLetter(cIdx)}$rowNum"
          val clean = escapeXml(token.trim())
          val sIdx = strIdx(clean)
          sheetRows.append("<c r=\"$cellRef\" t=\"s\"><v>$sIdx</v></c>")
        }
        sheetRows.append("</row>")
      }

      val head = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"

      put(
        "[Content_Types].xml",
        "$head<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
          "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
          "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
          "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
          "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
          "<Override PartName=\"/xl/sharedStrings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml\"/>" +
          "</Types>",
      )

      put(
        "_rels/.rels",
        "$head<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
          "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
          "</Relationships>",
      )

      put(
        "xl/workbook.xml",
        "$head<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
          "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
          "<sheets><sheet name=\"Sheet1\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>",
      )

      put(
        "xl/_rels/workbook.xml.rels",
        "$head<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
          "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
          "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings\" Target=\"sharedStrings.xml\"/>" +
          "</Relationships>",
      )

      put(
        "xl/worksheets/sheet1.xml",
        "$head<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
          "<sheetData>$sheetRows</sheetData></worksheet>",
      )

      val ssXml = StringBuilder()
      sharedStrings.forEach { s ->
        ssXml.append("<si><t xml:space=\"preserve\">$s</t></si>")
      }
      put(
        "xl/sharedStrings.xml",
        "$head<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"${sharedStrings.size}\" uniqueCount=\"${sharedStrings.size}\">" +
          "$ssXml</sst>",
      )
    }

    return out.readBytes()
  } finally {
    out.delete()
  }
}
