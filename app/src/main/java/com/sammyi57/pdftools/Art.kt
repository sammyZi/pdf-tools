package com.sammyi57.pdftools

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import kotlin.math.roundToInt

// Hand-drawn tool pictures for operations a stock icon can't show (interleave, split by bookmark, n-up…).
// One visual grammar: ink pages are your input, red pages are the result. Drawn on a 48×48 grid.

private class Kit(val fill: Color, val stroke: Color, val line: Color, val accent: Color, val soft: Color)

private const val SW = 1.5f
private val boldFace: Typeface by lazy { ResourcesCompat.getFont(appContext, R.font.poppins_semibold) ?: Typeface.DEFAULT_BOLD }
private val parsed = HashMap<String, Path>()
private fun svg(d: String) = parsed.getOrPut(d) { PathParser().parsePathString(d).toPath() }

private class Art(val s: DrawScope, val k: Kit) {
  fun page(x: Float, y: Float, w: Float, h: Float, lines: Int = 3, from: Float? = null, stripe: Boolean = false, accent: Boolean = false) {
    val f = (w * 0.3f).roundToInt().toFloat()
    val stroke = if (accent) k.accent else k.stroke
    val body = Path().apply { moveTo(x, y); lineTo(x + w - f, y); lineTo(x + w, y + f); lineTo(x + w, y + h); lineTo(x, y + h); close() }
    s.drawPath(body, if (accent) k.soft else k.fill)
    s.drawPath(body, stroke, style = Stroke(SW, join = StrokeJoin.Round))
    val ear = Path().apply { moveTo(x + w - f, y); lineTo(x + w - f, y + f); lineTo(x + w, y + f) }
    s.drawPath(ear, stroke, style = Stroke(SW, join = StrokeJoin.Round))
    val top = from ?: (y + f + 3)
    for (i in 0 until lines) {
      val ly = top + i * 3.6f
      val end = if (i == lines - 1 && lines > 1) x + w * 0.55f else x + w - 3.5f
      line(x + 3.5f, ly, end, ly, if (stripe && i % 2 == 1) k.accent else k.line)
    }
  }

  fun mark(d: String, w: Float = 1.8f, dash: Boolean = false) = s.drawPath(
    svg(d), k.accent,
    style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = if (dash) PathEffect.dashPathEffect(floatArrayOf(3f, 3f)) else null),
  )

  fun path(d: String, fill: Color? = null, stroke: Color? = null, w: Float = SW) {
    fill?.let { s.drawPath(svg(d), it) }
    stroke?.let { s.drawPath(svg(d), it, style = Stroke(w, join = StrokeJoin.Round)) }
  }

  fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Color = k.line, w: Float = 1.4f) =
    s.drawLine(color, Offset(x1, y1), Offset(x2, y2), w, StrokeCap.Round)

  fun rect(x: Float, y: Float, w: Float, h: Float, rx: Float = 0f, fill: Color? = null, stroke: Color? = null, sw: Float = 1.3f, dash: Boolean = false) {
    val r = CornerRadius(rx, rx)
    fill?.let { s.drawRoundRect(it, Offset(x, y), Size(w, h), r) }
    stroke?.let {
      s.drawRoundRect(it, Offset(x, y), Size(w, h), r, style = Stroke(sw, pathEffect = if (dash) PathEffect.dashPathEffect(floatArrayOf(2.5f, 2f)) else null))
    }
  }

  fun circle(cx: Float, cy: Float, r: Float, fill: Color, stroke: Color? = null) {
    s.drawCircle(fill, r, Offset(cx, cy))
    stroke?.let { s.drawCircle(it, r, Offset(cx, cy), style = Stroke(1.5f)) }
  }

  fun text(t: String, x: Float, y: Float, size: Float, color: Color) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = boldFace; textSize = size; this.color = color.toArgb(); textAlign = Paint.Align.CENTER }
    s.drawContext.canvas.nativeCanvas.drawText(t, x, y, paint)
  }

  fun turned(deg: Float, px: Float, py: Float, block: Art.() -> Unit) = s.rotate(deg, Offset(px, py)) { Art(this, k).block() }
}

private fun art(f: Art.() -> Unit) = f

private val ART: Map<String, Art.() -> Unit> = mapOf(
  "scan" to art {
    page(15f, 12f, 18f, 24f)
    mark("M9 17V10H16M32 10H39V17M9 31V38H16M39 31V38H32", 2f)
  },
  "workflow" to art {
    page(4f, 12f, 17f, 23f)
    mark("M24 23.5H29.5M27.5 21.5L29.5 23.5L27.5 25.5")
    line(36f, 12f, 36f, 36f, k.accent, 1.5f)
    listOf(12f, 24f, 36f).forEachIndexed { i, cy -> circle(36f, cy, 3.4f, if (i == 2) k.accent else k.soft, k.accent) }
  },
  "merge" to art {
    page(4f, 6f, 13f, 17f, lines = 2)
    page(4f, 25f, 13f, 17f, lines = 2)
    mark("M19.5 14.5C24 14.5 23 24 27.5 24M19.5 33.5C24 33.5 23 24 27.5 24M25 21.5L27.5 24L25 26.5")
    page(30f, 12f, 15f, 24f, accent = true)
  },
  "alternate" to art {
    page(4f, 5f, 15f, 19f, lines = 2)
    page(29f, 5f, 15f, 19f, lines = 2, accent = true)
    page(14f, 19f, 20f, 25f, lines = 5, stripe = true)
  },
  "compress" to art {
    page(15f, 13f, 18f, 22f)
    mark("M24 3V10M20.5 6.5L24 10L27.5 6.5M24 45V38M20.5 41.5L24 38L27.5 41.5")
  },
  "split" to art {
    page(3f, 12f, 17f, 24f)
    page(28f, 12f, 17f, 24f, accent = true)
    mark("M24 5V43", 1.6f, dash = true)
  },
  "splitText" to art {
    page(11f, 5f, 26f, 33f, lines = 0)
    line(14.5f, 17f, 30f, 17f)
    line(14.5f, 21f, 33.5f, 21f)
    rect(13f, 23.5f, 22f, 5.5f, 1.5f, fill = k.soft)
    line(15.5f, 26.25f, 31f, 26.25f, k.accent, 1.6f)
    line(14.5f, 32f, 27f, 32f)
    mark("M9 43H43", 1.6f, dash = true)
    path("M3 40L7.5 43L3 46Z", fill = k.accent)
  },
  "splitBookmarks" to art {
    page(11f, 8f, 26f, 33f, lines = 4, from = 26f)
    path("M16 5V22L19.5 18.5L23 22V5Z", fill = k.accent, stroke = k.accent, w = 1.2f)
  },
  "splitHalf" to art {
    path("M5 13Q14 10 23 13V38Q14 35 5 38Z", fill = k.fill, stroke = k.stroke)
    path("M25 13Q34 10 43 13V38Q34 35 25 38Z", fill = k.soft, stroke = k.accent)
    for (y in listOf(19f, 23f, 27f)) {
      line(9f, y, 19f, y)
      line(29f, y, 39f, y)
    }
    mark("M24 5V44", 1.6f, dash = true)
  },
  "splitSize" to art {
    page(6f, 6f, 24f, 34f, lines = 5)
    mark("M3 24H33", 1.6f, dash = true)
    mark("M39 6V40M36.5 6H41.5M36.5 40H41.5M37 24H41", 1.6f)
  },
  "organize" to art {
    page(5f, 4f, 14f, 18f, lines = 2)
    page(27f, 4f, 14f, 18f, lines = 2)
    page(5f, 26f, 14f, 18f, lines = 2)
    rect(27f, 26f, 14f, 18f, 1.5f, stroke = k.line, sw = 1.4f, dash = true)
    turned(8f, 31f, 31f) { page(23f, 21f, 14f, 18f, lines = 2, accent = true) }
  },
  "rotate" to art {
    turned(-12f, 24f, 24f) { page(16f, 13.5f, 16f, 21f) }
    mark("M40.5 14.5A19 19 0 0 1 30.5 41.9M34.3 43.3L30.5 41.9L32.5 38.4")
  },
  "flip" to art {
    page(4f, 9f, 17f, 24f)
    s.translate(48f, 0f) { s.scale(-1f, 1f, Offset.Zero) { Art(this, k).page(4f, 9f, 17f, 24f, accent = true) } }
    mark("M24 4V36", 1.6f, dash = true)
    mark("M16 41H32M18.5 38.5L16 41L18.5 43.5M29.5 38.5L32 41L29.5 43.5", 1.6f)
  },
  "nup" to art {
    page(9f, 5f, 30f, 38f, lines = 0)
    for ((x, y) in listOf(13f to 15f, 25f to 15f, 13f to 29f, 25f to 29f)) rect(x, y, 10f, 11f, 1.2f, fill = k.soft, stroke = k.accent)
  },
  "crop" to art {
    page(12f, 11f, 24f, 29f)
    mark("M7 16H32V41M16 7V32H41", 2f)
  },
  "zip" to art {
    page(15f, 5f, 24f, 29f, lines = 0)
    page(9f, 12f, 24f, 31f, lines = 0)
    for (i in 0..4) rect(if (i % 2 == 1) 19.5f else 17f, 21f + i * 3.2f, 2.5f, 2.5f, fill = k.accent)
    rect(16.5f, 37f, 5.5f, 5f, 1.2f, fill = k.soft, stroke = k.accent)
  },
  "watermark" to art {
    page(9f, 5f, 30f, 38f, lines = 6)
    turned(-38f, 24f, 25f) { text("DRAFT", 24f, 28f, 8.5f, k.accent) }
  },
  "redact" to art {
    page(9f, 5f, 30f, 38f, lines = 0)
    line(13.5f, 17f, 30f, 17f)
    rect(12.5f, 20.5f, 18f, 4.5f, 1f, fill = k.stroke)
    line(13.5f, 29.5f, 34.5f, 29.5f)
    rect(20f, 33f, 15f, 4.5f, 1f, fill = k.accent)
  },
  "fillSign" to art {
    page(9f, 5f, 30f, 38f, lines = 0)
    line(13.5f, 13f, 34.5f, 13f)
    line(13.5f, 18f, 28f, 18f)
    s.drawPath(svg("M14 34C18 28 21 33 24 30C27 27 29 32 34 26"), k.accent, style = Stroke(2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    line(13.5f, 38f, 34.5f, 38f, k.stroke, 1.2f)
  },
  "ocr" to art {
    page(9f, 5f, 30f, 38f, lines = 0)
    line(13.5f, 13f, 34.5f, 13f)
    rect(12.5f, 18f, 22f, 8f, 1.5f, stroke = k.accent, sw = 1.5f)
    text("abc", 23.5f, 24f, 5.5f, k.accent)
    line(13.5f, 31f, 34.5f, 31f)
    line(13.5f, 35.5f, 27f, 35.5f)
  },
  "password" to art {
    page(7f, 5f, 26f, 34f, lines = 4)
    s.drawPath(svg("M27.5 29V25A5 5 0 0 1 37.5 25V29"), k.accent, style = Stroke(2.2f))
    rect(24f, 28f, 17f, 14f, 2.5f, fill = k.accent)
    circle(32.5f, 34.5f, 1.9f, k.fill)
  },
  "unlock" to art { // the padlock from Set Password, its shackle swung open
    page(7f, 5f, 26f, 34f, lines = 4)
    s.drawPath(svg("M27.5 29V21A5 5 0 0 1 37.5 21V23"), k.accent, style = Stroke(2.2f))
    rect(24f, 28f, 17f, 14f, 2.5f, fill = k.accent)
    circle(32.5f, 34.5f, 1.9f, k.fill)
  },
  "metadata" to art { // a page with a name tag on it
    page(7f, 5f, 26f, 34f, lines = 4)
    rect(21f, 27f, 21f, 13f, 3f, fill = k.accent)
    text("Aa", 31.5f, 36.6f, 6.5f, k.fill)
  },
  "pageNumbers" to art {
    page(9f, 5f, 30f, 38f, lines = 4)
    rect(18f, 34f, 12f, 6.5f, 3.25f, fill = k.accent)
    text("12", 24f, 39f, 5.5f, k.fill)
  },
  "files" to art {
    turned(-10f, 24f, 30f) { page(10f, 9f, 22f, 29f, lines = 0) }
    turned(7f, 24f, 30f) { page(16f, 9f, 22f, 29f, lines = 0) }
    page(13f, 12f, 22f, 29f, lines = 4, accent = true)
  },
  "removePages" to art {
    page(13f, 10f, 22f, 30f)
    mark("M18 19L30 31M30 19L18 31", 2.2f)
  },
  "extractPages" to art {
    page(6f, 12f, 18f, 26f)
    mark("M27 25H34M31 22L34 25L31 28")
    page(30f, 8f, 14f, 20f, accent = true)
  },
  "optimizePdf" to art {
    page(13f, 10f, 22f, 30f)
    mark("M24 16V30M19 25L24 30L29 25", 2f)
    circle(24f, 37f, 2f, k.accent)
  },
  "repairPdf" to art {
    page(13f, 10f, 22f, 30f)
    mark("M20 25H28M24 21V29", 2.2f)
  },
  "imagesToPdf" to art {
    rect(6f, 14f, 18f, 22f, 3f, fill = k.fill, stroke = k.stroke)
    circle(11f, 20f, 2f, k.accent)
    path("M8 32L14 26L18 29L22 23", stroke = k.stroke)
    mark("M26 25H31M29 22L31 25L29 28")
    page(32f, 12f, 12f, 18f, accent = true)
  },
  "wordToPdf" to art {
    page(6f, 10f, 18f, 26f)
    text("W", 15f, 26f, 11f, k.accent)
    mark("M26 23H32M29 20L32 23L29 26")
    page(32f, 12f, 12f, 18f, accent = true)
  },
  "powerpointToPdf" to art {
    page(6f, 10f, 18f, 26f)
    text("P", 15f, 26f, 11f, k.accent)
    mark("M26 23H32M29 20L32 23L29 26")
    page(32f, 12f, 12f, 18f, accent = true)
  },
  "excelToPdf" to art {
    page(6f, 10f, 18f, 26f)
    text("X", 15f, 26f, 11f, k.accent)
    mark("M26 23H32M29 20L32 23L29 26")
    page(32f, 12f, 12f, 18f, accent = true)
  },
  "htmlToPdf" to art {
    page(6f, 10f, 18f, 26f)
    text("<>", 15f, 25f, 8f, k.accent)
    mark("M26 23H32M29 20L32 23L29 26")
    page(32f, 12f, 12f, 18f, accent = true)
  },
  "pdfToJpg" to art {
    page(6f, 10f, 16f, 24f, accent = true)
    mark("M24 22H30M27 19L30 22L27 25")
    rect(30f, 12f, 14f, 18f, 2f, fill = k.fill, stroke = k.stroke)
    circle(34f, 17f, 1.5f, k.accent)
  },
  "pdfToWord" to art {
    page(6f, 10f, 16f, 24f, accent = true)
    mark("M24 22H30M27 19L30 22L27 25")
    page(30f, 10f, 14f, 22f)
    text("W", 37f, 24f, 9f, k.accent)
  },
  "pdfToPowerpoint" to art {
    page(6f, 10f, 16f, 24f, accent = true)
    mark("M24 22H30M27 19L30 22L27 25")
    page(30f, 10f, 14f, 22f)
    text("P", 37f, 24f, 9f, k.accent)
  },
  "pdfToExcel" to art {
    page(6f, 10f, 16f, 24f, accent = true)
    mark("M24 22H30M27 19L30 22L27 25")
    page(30f, 10f, 14f, 22f)
    text("X", 37f, 24f, 9f, k.accent)
  },
  "pdfToPdfa" to art {
    page(13f, 10f, 22f, 30f, lines = 2)
    rect(17f, 25f, 14f, 10f, 2f, fill = k.soft, stroke = k.accent)
    text("A", 24f, 33f, 8f, k.accent)
  },
  "pdfToMarkdown" to art {
    page(6f, 10f, 16f, 24f, accent = true)
    mark("M24 22H30M27 19L30 22L27 25")
    page(30f, 10f, 14f, 22f)
    text("M", 37f, 24f, 8f, k.accent)
  },
  "pdfForms" to art {
    page(11f, 8f, 26f, 33f, lines = 0)
    rect(16f, 16f, 6f, 6f, 1.5f, stroke = k.accent)
    mark("M17 19L19.5 21.5L24 16", 1.5f)
    line(25f, 20f, 33f, 20f)
    rect(16f, 26f, 16f, 7f, 1.5f, stroke = k.stroke)
    line(19f, 30f, 27f, 30f, k.accent)
  },
  "comparePdf" to art {
    page(4f, 10f, 17f, 25f, lines = 3)
    page(27f, 10f, 17f, 25f, lines = 3, stripe = true)
    mark("M22 22H26M23.5 20L22 22L23.5 24M24.5 20L26 22L24.5 24")
  },
)

@Composable
private fun kit() = Kit(c.page, c.ink, c.faint, c.accent, c.accentSoft)

@Composable
fun ToolArt(id: String, size: Dp = 40.dp, modifier: Modifier = Modifier) {
  val draw = ART[id] ?: return
  val k = kit()
  Canvas(modifier.size(size)) {
    scale(this.size.width / 48f, Offset.Zero) { Art(this, k).draw() }
  }
}

@Composable
fun ArtTile(id: String, size: Dp = 44.dp, modifier: Modifier = Modifier, bg: Color = c.sunken) {
  Box(
    modifier.size(size).background(bg, RoundedCornerShape(if (size > 50.dp) Radius.card else Radius.tile)),
    contentAlignment = Alignment.Center,
  ) { ToolArt(id, size * 0.8f) }
}

enum class DocKind { Pdf, Zip, Doc, Jpg }

fun kindOf(name: String): DocKind = when (name.substringAfterLast('.', "").lowercase()) {
  "zip" -> DocKind.Zip
  "docx" -> DocKind.Doc
  "jpg" -> DocKind.Jpg
  else -> DocKind.Pdf
}

/** File thumbnail for lists: a page with a PDF / ZIP / DOC / JPG label. */
@Composable
fun DocGlyph(kind: DocKind = DocKind.Pdf, size: Dp = 36.dp) {
  val k = kit()
  Canvas(Modifier.size(size)) {
    scale(this.size.width / 36f, Offset.Zero) {
      val a = Art(this, k)
      a.page(8f, 3f, 22f, 30f, lines = 0)
      a.rect(3f, 17f, 20f, 9f, 2f, fill = if (kind == DocKind.Zip) k.stroke else k.accent)
      a.text(kind.name.uppercase(), 13f, 24f, 6.5f, k.fill)
    }
  }
}

/** Folder icon for file-list rows. */
@Composable
fun FolderGlyph(size: Dp = 36.dp) {
  val k = kit()
  Canvas(Modifier.size(size)) {
    scale(this.size.width / 36f, Offset.Zero) {
      Art(this, k).path(
        "M4 10C4 8.9 4.9 8 6 8H14L17 12H30C31.1 12 32 12.9 32 14V28C32 29.1 31.1 30 30 30H6C4.9 30 4 29.1 4 28V10Z",
        fill = k.soft, stroke = k.accent,
      )
    }
  }
}
