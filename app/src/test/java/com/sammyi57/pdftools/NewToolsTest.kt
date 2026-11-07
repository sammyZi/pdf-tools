package com.sammyi57.pdftools

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NewToolsTest {

  private val expectedNewToolIds = listOf(
    "removePages", "extractPages",
    "optimizePdf", "repairPdf",
    "imagesToPdf", "wordToPdf", "powerpointToPdf", "excelToPdf", "htmlToPdf",
    "pdfToJpg", "pdfToWord", "pdfToPowerpoint", "pdfToExcel", "pdfToPdfa", "pdfToMarkdown",
    "pdfForms", "comparePdf",
  )

  @Test
  fun testSectionsOrderAndCount() {
    val expectedSections = listOf(
      "Capture", "Combine & split", "Arrange pages", "Fill & mark up", "Protect & shrink", "Automate",
      "Optimize", "Convert to PDF", "Convert from PDF", "Edit PDF",
    )
    assertEquals(expectedSections, SECTIONS)
  }

  @Test
  fun testExact17NewToolsAdded() {
    assertEquals(17, expectedNewToolIds.size)
    assertEquals(41, TOOLS.size) // 24 original + 17 new

    for (id in expectedNewToolIds) {
      val tool = toolById(id)
      assertNotNull("Tool with id $id should exist in catalogue", tool)
    }

    // Verify section assignments
    assertEquals(2, toolById("removePages")!!.section)
    assertEquals(2, toolById("extractPages")!!.section)

    assertEquals(6, toolById("optimizePdf")!!.section)
    assertEquals(6, toolById("repairPdf")!!.section)

    assertEquals(7, toolById("imagesToPdf")!!.section)
    assertEquals(7, toolById("wordToPdf")!!.section)
    assertEquals(7, toolById("powerpointToPdf")!!.section)
    assertEquals(7, toolById("excelToPdf")!!.section)
    assertEquals(7, toolById("htmlToPdf")!!.section)

    assertEquals(8, toolById("pdfToJpg")!!.section)
    assertEquals(8, toolById("pdfToWord")!!.section)
    assertEquals(8, toolById("pdfToPowerpoint")!!.section)
    assertEquals(8, toolById("pdfToExcel")!!.section)
    assertEquals(8, toolById("pdfToPdfa")!!.section)
    assertEquals(8, toolById("pdfToMarkdown")!!.section)

    assertEquals(9, toolById("pdfForms")!!.section)
    assertEquals(9, toolById("comparePdf")!!.section)
  }

  @Test
  fun testNonPdfOutputsOutOfWorkflowBuilder() {
    val nonPdfTools = listOf("pdfToJpg", "pdfToWord", "pdfToPowerpoint", "pdfToExcel", "pdfToMarkdown")
    val stepIds = stepTools().map { it.id }.toSet()
    for (id in nonPdfTools) {
      assertFalse("Non-PDF tool $id must not be in stepTools()", id in stepIds)
    }
  }

  @Test
  fun testDescriptionsContainHonestLimits() {
    val word = toolById("wordToPdf")!!.desc
    val ppt = toolById("powerpointToPdf")!!.desc
    val excel = toolById("excelToPdf")!!.desc
    val html = toolById("htmlToPdf")!!.desc
    val pptOut = toolById("pdfToPowerpoint")!!.desc
    val pdfa = toolById("pdfToPdfa")!!.desc
    val forms = toolById("pdfForms")!!.desc
    val comp = toolById("comparePdf")!!.desc

    assertTrue(word.contains("basic and simple documents only", ignoreCase = true))
    assertTrue(ppt.contains("basic and simple documents only", ignoreCase = true))
    assertTrue(ppt.contains("text and pictures only", ignoreCase = true))
    assertTrue(excel.contains("basic and simple documents only", ignoreCase = true))
    assertTrue(html.contains("local files only with WebView offline", ignoreCase = true))
    assertTrue(html.contains("network loads blocked", ignoreCase = true))
    assertTrue(html.contains("JavaScript off", ignoreCase = true))
    assertTrue(pptOut.contains("page images not editable", ignoreCase = true))
    assertTrue(pdfa.contains("best effort and not validated", ignoreCase = true))
    assertTrue(pdfa.contains("never claim certified conformance", ignoreCase = true))
    assertTrue(forms.contains("real AcroForm distinct from Fill and Sign", ignoreCase = true))
    assertTrue(forms.contains("XFA unsupported", ignoreCase = true))
    assertTrue(comp.contains("needs exactly 2 PDFs", ignoreCase = true))
  }

  private fun createSamplePdf(pageCount: Int): ByteArray {
    return PDDocument().use { doc ->
      for (i in 1..pageCount) {
        val p = PDPage(PDRectangle(595f, 842f))
        doc.addPage(p)
        PDPageContentStream(doc, p).use { cs ->
          cs.setStrokingColor(0.5f, 0.5f, 0.5f)
          cs.moveTo(10f, 10f)
          cs.lineTo(100f, 100f)
          cs.stroke()
        }
      }
      doc.bytes()
    }
  }

  @Test
  fun testRemovePages() {
    val pdf = createSamplePdf(5)
    val result = removePages(pdf, "2, 4")
    PDDocument.load(result).use { doc ->
      assertEquals(3, doc.numberOfPages)
    }
  }

  @Test
  fun testExtractPages() {
    val pdf = createSamplePdf(5)
    val result = extractPages(pdf, "1-2, 5")
    PDDocument.load(result).use { doc ->
      assertEquals(3, doc.numberOfPages)
    }
  }

  @Test
  fun testPdfFormsRead() {
    val pdf = PDDocument().use { doc ->
      val p = PDPage(PDRectangle(595f, 842f))
      doc.addPage(p)
      val form = PDAcroForm(doc)
      doc.documentCatalog.acroForm = form
      val field = com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox(form).apply {
        partialName = "agreeTerms"
      }
      form.fields.add(field)
      doc.bytes()
    }

    val fields = readFormFields(pdf)
    assertEquals(1, fields.size)
    assertEquals("agreeTerms", fields[0].name)
    assertEquals("checkbox", fields[0].type)
  }
}
