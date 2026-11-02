package com.sammyi57.pdftools

import androidx.compose.foundation.background
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// Tokenized search: every word in the query must appear somewhere in the target. "split text" finds "Split PDF by Text".
private fun matches(target: String, tokens: List<String>) = target.lowercase().let { t -> tokens.all { it in t } }

@Composable
fun Home() {
  val nav = LocalNav.current
  var q by rememberSaveable { mutableStateOf("") }
  val files = remember(Store.version) { listAllFiles() }
  val tokens = q.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
  val searching = tokens.isNotEmpty()
  val tools = if (searching) TOOLS.filter { matches("${it.title} ${it.desc}", tokens) } else TOOLS
  val found = if (searching) files.filter { matches(it.name, tokens) } else emptyList()
  // Sections with something to show besides the camera (the hero card already opens it).
  val sections = remember { SECTIONS.indices.filter { si -> TOOLS.any { it.section == si && it.id != "scan" } } }
  var section by rememberSaveable { mutableStateOf(-1) } // -1: every section

  Screen {
    LargeTitle(stringResource(R.string.label_tools), stringResource(R.string.msg_tools_count_sub, TOOLS.size))
    SearchField(q, { q = it }, stringResource(R.string.hint_search_tools))

    if (!searching) {
      Stagger(0) { Hero(files) { nav.scan() } }
      if (files.isNotEmpty()) Stagger(1) { RecentPanel(files.take(3)) { nav.show(Tab.Files) } }
      val allTools = stringResource(R.string.label_all_tools)
      Row(
        Modifier.padding(top = 22.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Chip(allTools, section == -1) { section = -1 }
        sections.forEach { si -> Chip(SECTIONS[si], section == si) { section = si } }
      }
    }

    if (found.isNotEmpty()) {
      GroupLabel(stringResource(R.string.label_files), stringResource(R.string.action_all_files), { nav.show(Tab.Files) })
      Group {
        val shareLabel = stringResource(R.string.action_share)
        found.take(5).forEachIndexed { i, f ->
          ListRow(
            stem(f.name), first = i == 0, lead = { DocGlyph(kindOf(f.name)) }, marquee = true,
            subtitle = "${fmtSize(f.size)} · ${fmtDate(f.modified)}", onClick = { openFile(f.path, f.name) },
          ) { IconBtn(I.shareVariantOutline, 18.dp, c.sub, shareLabel) { share(f.path, f.name) } }
        }
      }
    }

    // Pinned tools first, in the order they were pinned (hold any tool, or use the pin on its screen).
    val pinned = Pins.ids.mapNotNull { toolById(it) }
    if (!searching && section == -1 && pinned.isNotEmpty()) {
      Stagger(2) {
        Column {
          GroupLabel(stringResource(R.string.label_pinned_count, pinned.size))
          ToolGrid(pinned, shared = false) { nav.tool(it.id) } // the section below owns the flying art
        }
      }
    }

    SECTIONS.forEachIndexed { si, name ->
      if (!searching && section != -1 && section != si) return@forEachIndexed
      val items = tools.filter { it.section == si && (searching || it.id != "scan") }
      if (items.isEmpty()) return@forEachIndexed
      Stagger(si + 3) {
        Column {
          GroupLabel(stringResource(R.string.msg_section_count, name, items.size))
          ToolGrid(items) { nav.tool(it.id) }
        }
      }
    }

    if (tools.isEmpty() && found.isEmpty()) {
      T(stringResource(R.string.msg_no_tools_or_files_match, q.trim()), Type.body, c.sub, Modifier.fillMaxWidth().padding(top = 40.dp, start = 24.dp, end = 24.dp), align = TextAlign.Center)
    }
  }
}

@Composable
private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
  in 5..11 -> stringResource(R.string.greeting_morning)
  in 12..16 -> stringResource(R.string.greeting_afternoon)
  else -> stringResource(R.string.greeting_evening)
}

// The loud card: a warm gradient and one plain sentence about what's on the phone, with little icons in the words.
@Composable
private fun Hero(files: List<Saved>, onScan: () -> Unit) {
  val shape = RoundedCornerShape(Radius.hero)
  val today = files.count { isToday(it.modified) }
  val bytes = files.sumOf { it.size }
  val soft = Brand.cool
  // Short on purpose: the numbers sit in the stats row below, so the sentence only says hello and points at the camera.
  val hello = greeting()
  val readyPrefix = stringResource(R.string.hero_ready_prefix)
  val readySuffix = stringResource(R.string.hero_ready_suffix)
  val sentence = buildAnnotatedString {
    withStyle(SpanStyle(color = Color.White)) { append(hello) }
    withStyle(SpanStyle(color = soft)) { append(readyPrefix) }
    appendInlineContent("cam", "[camera]")
    withStyle(SpanStyle(color = Color.White)) { append(readySuffix) }
  }
  // Sized in sp like the words, so it follows the font scale. The glyph's body spans 2..20 of its 24 box: dropping it by
  // 4/24 sits the camera on the baseline, about as tall as a capital.
  val camSize = Type.hero.fontSize
  val drop = with(androidx.compose.ui.platform.LocalDensity.current) { (camSize * (4f / 24f)).toDp() }
  val inline = mapOf(
    "cam" to InlineTextContent(Placeholder(1.1.em, 1.em, PlaceholderVerticalAlign.AboveBaseline)) {
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        BasicText(
          String(Character.toChars(I.cameraOutline)), Modifier.offset(y = drop),
          TextStyle(fontFamily = Mdi, fontSize = camSize, lineHeight = camSize, color = Color.White),
        )
      }
    },
  )
  Column(
    Modifier.padding(top = 16.dp).fillMaxWidth().tap(shape = shape, scaleTo = 0.98f, label = stringResource(R.string.label_scan_document), onClick = onScan)
      .clip(shape).glow(c.hero, 0.9f, 1.05f).padding(22.dp), // Black Cherry with Tangerine rising from the bottom right
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      T(SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(Date()), Type.bold.copy(fontSize = Type.bold.fontSize * 1.1f), Color.White.copy(alpha = 0.85f), Modifier.weight(1f))
      if (Backup.state.enabled) {
        Row(Modifier.background(Color.White.copy(alpha = 0.16f), CircleShape).padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
          Icon(I.googleDrive, 14.dp, Color.White)
          Spacer(Modifier.size(5.dp))
          T(if (Backup.syncing) stringResource(R.string.badge_syncing) else stringResource(R.string.badge_synced), Type.num, Color.White)
        }
      }
    }
    BasicText(sentence, Modifier.padding(top = 14.dp), Type.hero, inlineContent = inline)
    Spacer(Modifier.height(26.dp))
    Row(verticalAlignment = Alignment.Bottom) {
      Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Stat(stringResource(R.string.label_today), plural(today, stringResource(R.string.unit_file_one), stringResource(R.string.unit_file_many)))
        Stat(stringResource(R.string.label_on_phone), fmtSize(bytes).ifEmpty { stringResource(R.string.label_zero_kb) })
      }
      Row(
        Modifier.background(Brand.cool, CircleShape).padding(start = 14.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        T(stringResource(R.string.action_open_camera), Type.label, Brand.cherry)
        Spacer(Modifier.size(4.dp))
        Icon(I.arrowRight, 16.dp, Brand.cherry)
      }
    }
  }
}

// Two cards a row, each the height of the taller one, so titles and descriptions line up across the grid.
@Composable
private fun ToolGrid(items: List<Tool>, shared: Boolean = true, onOpen: (Tool) -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    items.chunked(2).forEach { pair ->
      Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        pair.forEach { t -> ToolCard(t, Modifier.weight(1f), shared) { onOpen(t) } }
        if (pair.size == 1) Spacer(Modifier.weight(1f))
      }
    }
  }
}

@Composable
private fun ToolCard(t: Tool, modifier: Modifier, shared: Boolean, onClick: () -> Unit) {
  val shape = RoundedCornerShape(Radius.group)
  val pinned = t.id in Pins.ids
  Box(
    modifier.fillMaxHeight().tap(shape = shape, scaleTo = 0.96f, label = t.title, onClick = onClick)
      .background(c.surface, shape).border(0.6.dp, c.lineStrong, shape),
  ) {
    Column(Modifier.padding(14.dp)) {
      ArtTile(t.id, 46.dp, if (shared) Modifier.sharedArt(t.id) else Modifier)
      T(t.title, Type.bold, modifier = Modifier.padding(top = 12.dp), maxLines = 2)
      T(t.desc, Type.small.copy(fontSize = 12.sp, lineHeight = 17.sp), c.sub, Modifier.padding(top = 4.dp), maxLines = 2)
    }
    // Always there, so pinning is found without a hidden long-press: faint outline off, solid Tangerine on.
    Box(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
      IconBtn(if (pinned) I.pin else I.pinOutline, 18.dp, if (pinned) c.accent else c.faint, if (pinned) stringResource(R.string.acc_unpin_tool, t.title) else stringResource(R.string.acc_pin_tool_to_top, t.title)) { Pins.toggle(t.id) }
    }
  }
}

@Composable
private fun Stat(label: String, value: String) {
  Column {
    T(label, Type.num.copy(fontSize = Type.num.fontSize * 0.95f), Color.White.copy(alpha = 0.7f))
    T(value, Type.bold, Color.White)
  }
}

// The quiet card under the loud one: Cool Blue with Black Cherry type, kept compact: small label, one line per file,
// a small round share button.
@Composable
private fun RecentPanel(recent: List<Saved>, onAll: () -> Unit) {
  val shape = RoundedCornerShape(Radius.card)
  val ink = Brand.cherry
  val dim = ink.copy(alpha = 0.62f)
  Column(Modifier.padding(top = 12.dp).fillMaxWidth().clip(shape).background(Brand.cool, shape).grain().padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 6.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      T(stringResource(R.string.label_recent_caps), Type.caption, dim, Modifier.weight(1f))
      T(stringResource(R.string.action_all_files), Type.small, dim, Modifier.tap(scaleTo = 0.92f, onClick = onAll).padding(horizontal = 6.dp, vertical = 2.dp))
    }
    val openLabel = stringResource(R.string.acc_open_file)
    val shareLabel = stringResource(R.string.action_share)
    recent.forEach { f ->
      Row(
        Modifier.fillMaxWidth().tap(scaleTo = 0.98f, label = openLabel.format(stem(f.name))) { openFile(f.path, f.name) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(Modifier.weight(1f)) {
          Marquee(stem(f.name), Type.item, ink)
          T("${kindOf(f.name).name.uppercase()} · ${fmtSize(f.size)} · ${fmtDate(f.modified)}", Type.small.copy(fontSize = 11.5.sp, lineHeight = 15.sp), dim, maxLines = 1)
        }
        Spacer(Modifier.size(8.dp))
        RoundBtn(I.shareVariantOutline, ink, Brand.cool, 34.dp, shareLabel) { share(f.path, f.name) }
      }
    }
  }
}
