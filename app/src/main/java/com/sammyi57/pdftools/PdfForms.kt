package com.sammyi57.pdftools

import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDNonTerminalField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField

data class FormFieldInfo(
  val name: String,
  val label: String,
  val type: String, // "text", "checkbox", "choice"
  val value: String,
  val options: List<String> = emptyList(),
)

/**
 * Reads interactive AcroForm fields from a PDF.
 * Real AcroForm distinct from Fill and Sign. XFA unsupported.
 */
fun readFormFields(bytes: ByteArray): List<FormFieldInfo> = loadPdf(bytes).use { doc ->
  val catalog = doc.documentCatalog
  val acro = catalog.getAcroForm(null) ?: return emptyList()
  if (acro.xfa != null && acro.fields.isEmpty()) {
    // XFA unsupported, no standard AcroForm fields found
    return emptyList()
  }
  val list = ArrayList<FormFieldInfo>()
  for (f in acro.fields) {
    addFieldRecursive(f, list)
  }
  list
}

private fun addFieldRecursive(field: PDField, out: MutableList<FormFieldInfo>) {
  val kids = (field as? PDNonTerminalField)?.children
  if (!kids.isNullOrEmpty()) {
    for (kid in kids) addFieldRecursive(kid, out)
    return
  }
  val name = field.fullyQualifiedName ?: field.partialName ?: return
  val type = when (field) {
    is PDCheckBox -> "checkbox"
    is PDRadioButton -> "radio"
    is PDChoice -> "choice"
    else -> "text"
  }
  val currentVal = runCatching { field.valueAsString }.getOrDefault("")
  val opts = if (field is PDChoice) runCatching { field.options }.getOrDefault(emptyList()) else emptyList()
  val label = field.alternateFieldName?.ifBlank { null } ?: name
  out.add(FormFieldInfo(name, label, type, currentVal, opts))
}

/** Sets values on AcroForm fields and writes updated PDF. */
fun fillFormFields(bytes: ByteArray, values: Map<String, String>): ByteArray = loadPdf(bytes).use { doc ->
  val acro = doc.documentCatalog.getAcroForm(null) ?: fail("This PDF has no form fields.")
  if (acro.defaultResources == null) {
    acro.defaultResources = com.tom_roush.pdfbox.pdmodel.PDResources()
  }
  for ((name, v) in values) {
    val f = acro.getField(name) ?: continue
    runCatching {
      if (f is PDCheckBox) {
        if (v.equals("true", ignoreCase = true) || v.equals("yes", ignoreCase = true) || v == "1") {
          f.check()
        } else {
          f.unCheck()
        }
      } else {
        f.setValue(v)
      }
    }
  }
  doc.bytes()
}
