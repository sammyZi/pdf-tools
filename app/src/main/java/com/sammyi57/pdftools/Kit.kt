package com.sammyi57.pdftools

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.border
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

// ── Formatting ──────────────────────────────────────────────────────────────

fun pad2(n: Int) = n.toString().padStart(2, '0')
fun fmtTime(ms: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
fun fmtSize(b: Long?): String = when {
  b == null -> ""
  b < 1024 -> "$b B"
  b < 1048576 -> "${(b / 1024.0).roundToInt()} KB"
  else -> "%.1f MB".format(Locale.US, b / 1048576.0)
}

// dd/mm/yyyy everywhere a date is shown.
fun fmtDay(ms: Long): String = SimpleDateFormat("dd/MM/yyyy", Locale.US).format(Date(ms))
fun fmtDate(ms: Long): String = if (daysAgo(ms) == 0) fmtTime(ms) else fmtDay(ms)
fun plural(n: Int, singular: String, pluralForm: String) = "$n ${if (n == 1) singular else pluralForm}"

// File names in lists drop the extension: the glyph beside them already says PDF, DOC, JPG or ZIP.
fun stem(name: String) = name.replace(Regex("\\.(pdf|docx|jpg|zip)$", RegexOption.IGNORE_CASE), "")

private fun daysAgo(ms: Long): Int {
  fun day(t: Long) = Calendar.getInstance().apply {
    timeInMillis = t; set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
  }.timeInMillis
  return ((day(System.currentTimeMillis()) - day(ms)) / 86_400_000.0).roundToInt() // noon to noon: DST days still round
}

/** Whether a timestamp falls on today's calendar day. */
fun isToday(ms: Long): Boolean = daysAgo(ms) == 0

// "Today · 17/09/2026", "Yesterday · …", a weekday within the past week, then just the date.
@Composable
fun dayLabel(ms: Long): String {
  val days = daysAgo(ms)
  val word = when {
    days == 0 -> stringResource(R.string.label_today)
    days == 1 -> stringResource(R.string.label_yesterday)
    days in 2..6 -> SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(ms))
    else -> ""
  }
  return if (word.isNotEmpty()) "$word · ${fmtDay(ms)}" else fmtDay(ms)
}

/** Splits a newest-first list into runs of the same day. */
@Composable
fun <T> byDay(items: List<T>, modified: (T) -> Long): List<Pair<String, List<T>>> {
  val out = ArrayList<Pair<String, MutableList<T>>>()
  for (it in items) {
    val label = dayLabel(modified(it))
    if (out.lastOrNull()?.first != label) out.add(label to mutableListOf())
    out.last().second.add(it)
  }
  return out
}

// ── Press feedback ──────────────────────────────────────────────────────────

/**
 * Taps with a small springy press: the thing sinks a few percent and, given `pressed`, tints. No ripple: the app's
 * press feedback is deliberately quiet.
 */
fun Modifier.tap(
  enabled: Boolean = true,
  pressed: Color? = null,
  shape: Shape = RoundedCornerShape(0.dp),
  scaleTo: Float = 0.97f,
  label: String? = null,
  onLongClick: (() -> Unit)? = null,
  onClick: () -> Unit,
): Modifier = composed {
  val source = remember { MutableInteractionSource() }
  val down by source.collectIsPressedAsState()
  val scale by animateFloatAsState(if (down) scaleTo else 1f, spring(0.55f, Spring.StiffnessMediumLow), label = "press")
  this
    .graphicsLayer { scaleX = scale; scaleY = scale }
    .then(if (pressed != null && down) Modifier.background(pressed, shape) else Modifier)
    .combinedClickable(
      interactionSource = source, indication = null, enabled = enabled, onClickLabel = label,
      onLongClick = onLongClick, onClick = onClick,
    )
}

// ── Text ────────────────────────────────────────────────────────────────────

@Composable
fun T(
  text: String,
  style: TextStyle = Type.body,
  color: Color = c.ink,
  modifier: Modifier = Modifier,
  maxLines: Int = Int.MAX_VALUE,
  align: TextAlign? = null,
  overflow: TextOverflow = TextOverflow.Ellipsis,
) {
  BasicText(
    text, modifier, style.copy(color = color, textAlign = align ?: TextAlign.Unspecified),
    maxLines = maxLines, overflow = overflow,
  )
}

/** One line that slowly scrolls back and forth when it doesn't fit, instead of ending in "…". */
@Composable
fun Marquee(text: String, style: TextStyle, color: Color = c.ink, modifier: Modifier = Modifier) {
  BasicText(
    // Two passes, then still: a marquee that never ends redraws the whole screen every frame, and stutters.
    text, modifier.basicMarquee(iterations = 2, initialDelayMillis = 1200, repeatDelayMillis = 1200),
    style.copy(color = color), maxLines = 1,
  )
}

// ── Screen ──────────────────────────────────────────────────────────────────

/** The scroll container of the current screen, so a dragged item can scroll it when held near an edge. */
class ScreenScroll(val state: ScrollState) {
  var window by mutableStateOf(Rect.Zero)
}

val LocalScreenScroll = compositionLocalOf<ScreenScroll?> { null }

/** Something pinned over the bottom of the screen while the page scrolls under it (a selection bar). */
val LocalFloat = compositionLocalOf<MutableState<(@Composable () -> Unit)?>?> { null }

/**
 * A scrolling page with the app's margins. `bottom` is the room kept under the content (the floating tab bar sits
 * there on the tabs).
 */
@Composable
fun Screen(bottom: Dp = LocalBottomRoom.current, top: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
  val scroll = rememberScrollState()
  val holder = remember { ScreenScroll(scroll) }
  val float = remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
  CompositionLocalProvider(LocalScreenScroll provides holder, LocalFloat provides float) {
    Box(Modifier.fillMaxSize().background(c.bg)) {
      Column(
        Modifier
          .fillMaxSize()
          .onGloballyPositioned { holder.window = it.boundsInWindow() }
          .imePadding()
          .verticalScroll(scroll)
          .then(if (top) Modifier.statusBarsPadding() else Modifier)
          .padding(horizontal = 18.dp)
          .padding(top = 2.dp),
      ) {
        content()
        Spacer(Modifier.height(bottom + if (float.value != null) 80.dp else 0.dp))
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
      }
      val node = float.value
      AnimatedVisibility(
        node != null, Modifier.align(Alignment.BottomCenter).padding(12.dp).padding(bottom = bottom - 24.dp),
        enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(),
      ) {
        Box(Modifier.navigationBarsPadding()) { node?.invoke() }
      }
    }
  }
}

/** Pins `content` over the bottom of the enclosing Screen while `show` is true. */
@Composable
fun Floating(show: Boolean, content: @Composable () -> Unit) {
  val slot = LocalFloat.current ?: return
  val latest by rememberUpdatedState(content)
  LaunchedEffect(show) { slot.value = if (show) ({ latest() }) else null }
  androidx.compose.runtime.DisposableEffect(Unit) { onDispose { slot.value = null } }
}

@Composable
fun LargeTitle(title: String, sub: String? = null) {
  Column(Modifier.padding(top = 2.dp, bottom = 16.dp)) {
    T(title, Type.largeTitle)
    if (!sub.isNullOrEmpty()) T(sub, Type.small, c.sub, Modifier.padding(top = 2.dp))
  }
}

@Composable
fun GroupLabel(label: String, action: String? = null, onAction: (() -> Unit)? = null, first: Boolean = false, caps: Boolean = true) {
  Row(
    Modifier.fillMaxWidth().padding(top = if (first) 0.dp else 26.dp, bottom = 8.dp).padding(horizontal = 4.dp),
    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom,
  ) {
    // A small spaced-out caption, so section names read as labels rather than headings. `caps` off for emails.
    T(if (caps) label.uppercase() else label, if (caps) Type.caption else Type.label, c.sub, Modifier.weight(1f, fill = false))
    if (action != null) {
      T(action, Type.med, c.accent, Modifier.tap(enabled = onAction != null, scaleTo = 0.94f) { onAction?.invoke() })
    }
  }
}

@Composable
fun Group(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
  val shape = RoundedCornerShape(Radius.group)
  Column(
    modifier.fillMaxWidth().clip(shape).background(c.surface, shape).border(0.6.dp, c.lineStrong, shape),
    content = content,
  )
}

/** List row with an inset separator that starts after the leading artwork, like native grouped lists. */
@Composable
fun ListRow(
  title: String,
  modifier: Modifier = Modifier,
  lead: (@Composable () -> Unit)? = null,
  subtitle: String? = null,
  value: String? = null,
  first: Boolean = false,
  danger: Boolean = false,
  chevron: Boolean = false,
  marquee: Boolean = false,
  titleColor: Color? = null,
  onLongClick: (() -> Unit)? = null,
  onClick: (() -> Unit)? = null,
  trailing: @Composable RowScope.() -> Unit = {},
) {
  val clickable = onClick != null || onLongClick != null
  Row(
    modifier
      .fillMaxWidth()
      .then(if (clickable) Modifier.tap(pressed = c.sunken, scaleTo = 0.985f, onLongClick = onLongClick) { onClick?.invoke() } else Modifier)
      .padding(start = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (lead != null) {
      lead()
      Spacer(Modifier.size(12.dp))
    }
    Column(Modifier.weight(1f)) {
      if (!first) Box(Modifier.fillMaxWidth().height(0.6.dp).background(c.line))
      Row(Modifier.heightIn(min = 56.dp).padding(vertical = 10.dp).padding(end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          val color = titleColor ?: if (danger) c.accent else c.ink
          if (marquee) Marquee(title, Type.item, color) else T(title, Type.item, color, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
          // Room for a full sentence or two: Settings descriptions were being cut off with "…".
          if (!subtitle.isNullOrEmpty()) T(subtitle, Type.small, c.sub, Modifier.padding(top = 1.dp), maxLines = 5)
        }
        if (value != null) T(value, Type.num, c.sub, Modifier.padding(start = 6.dp))
        trailing()
        if (chevron) Icon(I.chevronRight, 20.dp, c.faint)
      }
    }
  }
}

@Composable
fun StepHead(n: Int, title: String) {
  Row(Modifier.padding(top = 30.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(24.dp).background(c.accent, CircleShape), contentAlignment = Alignment.Center) {
      T("$n", Type.label.copy(fontSize = Type.label.fontSize * 0.85f), Brand.cherry)
    }
    Spacer(Modifier.size(10.dp))
    T(title, Type.bold.copy(fontSize = Type.bold.fontSize * 1.15f))
  }
}

@Composable
fun IconBtn(glyph: Int, size: Dp = 20.dp, color: Color = c.ink, label: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
  Box(
    Modifier.size(size + 16.dp).tap(enabled = enabled, pressed = c.sunken, shape = CircleShape, scaleTo = 0.88f, label = label, onClick = onClick),
    contentAlignment = Alignment.Center,
  ) { Icon(glyph, size, color, label = label) }
}

/** A round button like the reference's play buttons: a filled circle with one icon. */
@Composable
fun RoundBtn(glyph: Int, bg: Color, fg: Color, size: Dp = 44.dp, label: String? = null, onClick: () -> Unit) {
  Box(
    Modifier.size(size).tap(shape = CircleShape, scaleTo = 0.86f, label = label, onClick = onClick).background(bg, CircleShape),
    contentAlignment = Alignment.Center,
  ) { Icon(glyph, size * 0.46f, fg, label = label) }
}

enum class BtnKind { Primary, Secondary, Danger, Accent }

@Composable
fun Btn(
  label: String,
  modifier: Modifier = Modifier,
  icon: Int? = null,
  kind: BtnKind = BtnKind.Primary,
  busy: Boolean = false,
  enabled: Boolean = true,
  height: Dp = 52.dp,
  onClick: () -> Unit,
) {
  val (bg, fg) = when (kind) {
    BtnKind.Primary -> c.ink to c.onInk
    BtnKind.Secondary -> Color.Transparent to c.ink
    BtnKind.Danger, BtnKind.Accent -> c.accent to Brand.cherry // dark text on Tangerine reads better than white
  }
  val shape = CircleShape
  val alpha by animateFloatAsState(if (enabled) 1f else 0.35f, tween(180), label = "btn")
  Row(
    modifier
      .height(height)
      .graphicsLayer { this.alpha = alpha }
      .tap(enabled = enabled && !busy, shape = shape, scaleTo = 0.96f, onClick = onClick)
      .background(bg, shape)
      .then(if (kind == BtnKind.Secondary) Modifier.border(1.dp, c.lineStrong, shape) else Modifier)
      .padding(horizontal = 18.dp),
    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
  ) {
    if (busy) {
      CircularProgressIndicator(Modifier.size(18.dp), color = fg, strokeWidth = 2.dp)
      Spacer(Modifier.size(10.dp))
    } else if (icon != null) {
      Icon(icon, 19.dp, fg)
      Spacer(Modifier.size(8.dp))
    }
    T(label, Type.button, fg, maxLines = 1)
  }
}

@Composable
fun Spinner(color: Color = c.ink, size: Dp = 22.dp, modifier: Modifier = Modifier) {
  CircularProgressIndicator(modifier.size(size), color = color, strokeWidth = 2.dp)
}

@Composable
fun Chip(label: String, active: Boolean, onClick: () -> Unit) {
  val shape = CircleShape
  val on by animateFloatAsState(if (active) 1f else 0f, tween(200), label = "chip")
  val line by androidx.compose.animation.animateColorAsState(if (active) Color.Transparent else c.lineStrong, tween(200), label = "chipLine")
  val fg by androidx.compose.animation.animateColorAsState(if (active) Brand.cherry else c.ink, tween(200), label = "chipText")
  Box(
    Modifier.tap(shape = shape, scaleTo = 0.93f, onClick = onClick).background(c.accent.copy(alpha = on), shape)
      .border(1.dp, line, shape).padding(horizontal = 15.dp, vertical = 7.dp),
  ) { T(label, Type.med.copy(fontSize = Type.med.fontSize * 0.97f), fg) }
}

/**
 * A loud surface: `colors` (base, middle, glow) as a radial glow from (cx, cy), fractions of the size, with film grain
 * on top. Warmer and quieter than a glossy linear gradient.
 */
fun Modifier.glow(colors: List<Color>, cx: Float, cy: Float) = drawWithContent {
  drawRect(colors[0])
  drawRect(Brush.radialGradient(listOf(colors[2], colors[1], colors[0]), Offset(size.width * cx, size.height * cy), size.maxDimension * 0.95f))
  drawContent()
  drawRect(grainBrush)
}

/** Film grain over anything. */
fun Modifier.grain() = drawWithContent { drawContent(); drawRect(grainBrush) }

private val grainBrush: ShaderBrush by lazy {
  val n = 192
  val r = kotlin.random.Random(42)
  val px = IntArray(n * n) {
    val v = if (r.nextBoolean()) 255 else 0
    android.graphics.Color.argb(r.nextInt(0, 22), v, v, v)
  }
  ShaderBrush(ImageShader(android.graphics.Bitmap.createBitmap(px, n, n, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
}

/** A segmented control whose selected pill slides between the choices. */
@Composable
fun <K> Segmented(
  choices: List<Pair<K, String>>,
  value: K,
  modifier: Modifier = Modifier,
  icons: List<Int>? = null,
  onChange: (K) -> Unit,
) {
  val index = choices.indexOfFirst { it.first == value }.coerceAtLeast(0)
  // A capsule track; the chosen side is a solid Tangerine pill, like a selected chip.
  val accent = c.accent
  BoxWithConstraints(modifier.fillMaxWidth().background(c.sunken, CircleShape).border(0.6.dp, c.line, CircleShape).padding(4.dp)) {
    val w = maxWidth / choices.size
    val x by animateDpAsState(w * index, spring(0.8f, Spring.StiffnessMediumLow), label = "seg")
    Box(Modifier.offset(x = x).size(w, 40.dp).shadow(4.dp, CircleShape, ambientColor = accent, spotColor = accent).background(accent, CircleShape))
    Row {
      choices.forEachIndexed { i, (k, label) ->
        val on = i == index
        val fg by androidx.compose.animation.animateColorAsState(if (on) Brand.cherry else c.sub, tween(200), label = "segText")
        Row(
          Modifier.size(w, 40.dp).tap(shape = CircleShape, scaleTo = 0.95f) { onChange(k) },
          horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
          if (icons != null) {
            Icon(icons[i], 17.dp, fg)
            Spacer(Modifier.size(6.dp))
          }
          T(label, if (on) Type.bold.copy(fontSize = Type.med.fontSize) else Type.med, fg, maxLines = 1)
        }
      }
    }
  }
}

@Composable
fun Toggle(on: Boolean, onChange: (Boolean) -> Unit) {
  Switch(
    on, onChange,
    colors = SwitchDefaults.colors(
      checkedTrackColor = c.accent, checkedThumbColor = Color.White, uncheckedTrackColor = c.lineStrong,
      uncheckedThumbColor = Color.White, uncheckedBorderColor = Color.Transparent, checkedBorderColor = Color.Transparent,
    ),
  )
}

@Composable
fun Field(
  value: String,
  onChange: (String) -> Unit,
  modifier: Modifier = Modifier,
  placeholder: String = "",
  keyboard: KeyboardType = KeyboardType.Text,
  secret: Boolean = false,
  error: Boolean = false,
  focus: FocusRequester? = null,
  selectAll: Boolean = false,
  onDone: (() -> Unit)? = null,
  trailing: @Composable RowScope.() -> Unit = {},
) {
  // A TextFieldValue, so a suggested name can start fully selected: typing replaces it, tapping keeps it.
  var tfv by remember { mutableStateOf(TextFieldValue(value, if (selectAll) TextRange(0, value.length) else TextRange(value.length))) }
  if (tfv.text != value) tfv = tfv.copy(text = value, selection = TextRange(value.length))
  val shape = RoundedCornerShape(Radius.control)
  BasicTextField(
    value = tfv,
    onValueChange = { tfv = it; if (it.text != value) onChange(it.text) },
    modifier = modifier.fillMaxWidth().then(if (focus != null) Modifier.focusRequester(focus) else Modifier),
    textStyle = Type.body.copy(color = c.ink),
    cursorBrush = SolidColor(c.accent),
    singleLine = true,
    visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
    keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false, imeAction = ImeAction.Done),
    keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
    decorationBox = { inner ->
      Row(
        Modifier.height(50.dp).background(c.surface, shape).border(1.dp, if (error) c.accent else c.line, shape).padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Box(Modifier.weight(1f)) {
          if (value.isEmpty()) T(placeholder, Type.body, c.faint, maxLines = 1)
          inner()
        }
        trailing()
      }
    },
  )
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
  val shape = RoundedCornerShape(Radius.control)
  BasicTextField(
    value = value, onValueChange = onChange, singleLine = true,
    textStyle = Type.body.copy(color = c.ink, fontSize = Type.body.fontSize * 1.03f),
    cursorBrush = SolidColor(c.accent),
    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
    modifier = modifier.fillMaxWidth(),
    decorationBox = { inner ->
      Row(Modifier.height(48.dp).background(c.sunken, shape).padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(I.magnify, 20.dp, c.sub)
        Spacer(Modifier.size(8.dp))
        Box(Modifier.weight(1f)) {
          if (value.isEmpty()) T(placeholder, Type.body, c.faint, maxLines = 1)
          inner()
        }
        if (value.isNotEmpty()) IconBtn(I.closeCircle, 18.dp, c.faint, stringResource(R.string.action_clear)) { onChange("") }
      }
    },
  )
}

// ── Options ─────────────────────────────────────────────────────────────────

@Composable
fun Options(defs: List<OptDef>, value: Opts, onChange: (Opts) -> Unit) {
  for (d in defs) {
    if (d.visible?.invoke(value) == false) continue
    key(d.key) {
      when (d) {
        is OptDef.Toggle -> Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
          T(d.label, Type.med, modifier = Modifier.weight(1f))
          Toggle(value.bool(d.key)) { onChange(value.with(d.key, it)) }
        }
        is OptDef.Seg -> Column(Modifier.padding(bottom = 18.dp)) {
          T(d.label, Type.med, modifier = Modifier.padding(bottom = 8.dp))
          if (d.choices.size <= 4) {
            Segmented(d.choices, value.str(d.key)) { onChange(value.with(d.key, it)) }
          } else {
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
              for ((k, label) in d.choices) Chip(label, value.str(d.key) == k) { onChange(value.with(d.key, k)) }
            }
          }
        }
        is OptDef.Text -> Column(Modifier.padding(bottom = 18.dp)) {
          T(d.label, Type.med, modifier = Modifier.padding(bottom = 8.dp))
          Field(
            value.str(d.key), { v -> onChange(value.with(d.key, d.strip?.replace(v, "") ?: v)) },
            placeholder = d.placeholder, keyboard = d.keyboard,
          )
        }
      }
    }
  }
}

// ── Dialogs ─────────────────────────────────────────────────────────────────

/** A centered card that springs in over a dimmed screen. */
@Composable
fun AppDialog(onDismiss: () -> Unit, title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
  Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
    val shown = remember { MutableTransitionState(false) }.apply { targetState = true }
    // The window fills the screen, so a tap on the dimmed area around the card is ours to turn into a dismiss.
    Box(
      Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onDismiss() } }.imePadding().padding(20.dp),
      contentAlignment = Alignment.Center,
    ) {
      AnimatedVisibility(
        shown,
        enter = fadeIn(tween(160)) + scaleIn(spring(0.7f, Spring.StiffnessMedium), initialScale = 0.9f),
      ) {
        val shape = RoundedCornerShape(Radius.card)
        Column(
          Modifier.widthIn(max = 420.dp).fillMaxWidth().pointerInput(Unit) { detectTapGestures { } } // taps on the card stay on it
            .background(c.surface, shape).border(1.dp, c.lineStrong, shape).padding(20.dp),
        ) {
          Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(top = 2.dp)) {
              T(title, Type.title.copy(fontSize = Type.title.fontSize * 0.8f, lineHeight = Type.title.lineHeight * 0.8f))
              if (!subtitle.isNullOrEmpty()) T(subtitle, Type.small, c.sub, Modifier.padding(top = 4.dp))
            }
            IconBtn(I.close, 20.dp, c.faint, stringResource(R.string.action_close), onClick = onDismiss)
          }
          content()
        }
      }
    }
  }
}

@Composable
fun ConfirmDialog(
  title: String,
  message: String,
  confirmLabel: String = stringResource(R.string.action_confirm),
  danger: Boolean = false,
  requireTyping: String? = null,
  onDismiss: () -> Unit,
  onConfirm: () -> Unit,
) {
  var typed by remember { mutableStateOf("") }
  val match = requireTyping == null || typed.trim().equals(requireTyping, ignoreCase = true)
  val go = { if (match) { onDismiss(); onConfirm() } }
  AppDialog(onDismiss, title) {
    T(message, Type.body, c.sub, Modifier.padding(top = 12.dp, bottom = 16.dp))
    if (requireTyping != null) {
      T(stringResource(R.string.msg_type_to_confirm, requireTyping), Type.small, modifier = Modifier.padding(bottom = 8.dp))
      Field(typed, { typed = it }, Modifier.padding(bottom = 18.dp), placeholder = stringResource(R.string.hint_type_value, requireTyping), onDone = go)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Btn(stringResource(R.string.action_cancel), Modifier.weight(1f), kind = BtnKind.Secondary, onClick = onDismiss)
      Btn(confirmLabel, Modifier.weight(1f), kind = if (danger) BtnKind.Danger else BtnKind.Primary, enabled = match, onClick = go)
    }
  }
}

/**
 * Asks for one line of text. `onDismiss` is back, a tap outside or the ✕, when that shouldn't mean the same as the
 * cancel button (saving a result keeps it under the suggested name).
 */
@Composable
fun PromptDialog(
  title: String,
  initial: String = "",
  subtitle: String? = null,
  placeholder: String = "",
  submitLabel: String = stringResource(R.string.action_save),
  cancelLabel: String = stringResource(R.string.action_cancel),
  keyboard: KeyboardType = KeyboardType.Text,
  onClose: () -> Unit,
  onDismiss: () -> Unit = onClose,
  onSubmit: (String) -> Unit,
) {
  var v by remember { mutableStateOf(initial) }
  val focus = remember { FocusRequester() }
  val submit = { val t = v.trim(); if (t.isNotEmpty()) { onClose(); onSubmit(t) } }
  AppDialog(onDismiss, title, subtitle) {
    Field(v, { v = it }, Modifier.padding(top = 14.dp, bottom = 18.dp), placeholder = placeholder, keyboard = keyboard, focus = focus, selectAll = true, onDone = submit)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Btn(cancelLabel, Modifier.weight(1f), kind = BtnKind.Secondary, onClick = onClose)
      Btn(submitLabel, Modifier.weight(1f), enabled = v.isNotBlank(), onClick = submit)
    }
  }
  LaunchedEffect(Unit) {
    delay(120) // the dialog's window has to be focusable before it can raise the keyboard
    runCatching { focus.requestFocus() }
  }
}

/** A full-screen page over everything (pickers, documents, editors), rising in from below. */
@Composable
fun FullSheet(onDismiss: () -> Unit, bg: Color = c.bg, content: @Composable () -> Unit) {
  Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
    val shown = remember { MutableTransitionState(false) }.apply { targetState = true }
    AnimatedVisibility(
      shown,
      enter = slideInVertically(tween(380, easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f))) { it / 4 } + fadeIn(tween(220)),
    ) {
      Box(Modifier.fillMaxSize().background(bg)) { content() }
    }
  }
}

/** A row in a menu or chooser dialog: icon, title, optional subtitle. */
@Composable
fun MenuRow(glyph: Int, title: String, sub: String? = null, color: Color = c.ink, trailing: @Composable () -> Unit = {}, onClick: () -> Unit) {
  Row(
    Modifier.fillMaxWidth().tap(pressed = c.sunken, shape = RoundedCornerShape(Radius.tile), scaleTo = 0.98f, onClick = onClick)
      .padding(horizontal = 10.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(glyph, 22.dp, color)
    Spacer(Modifier.size(12.dp))
    Column(Modifier.weight(1f)) {
      T(title, Type.item, color)
      if (sub != null) T(sub, Type.small, c.sub, maxLines = 2)
    }
    trailing()
  }
}

// ── Images ──────────────────────────────────────────────────────────────────

private val bitmaps = object : LruCache<String, ImageBitmap>(48 * 1024 * 1024) {
  override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
}

/** A JPEG/PNG on disk as an ImageBitmap, decoded off the main thread near `maxSide` px and cached. */
@Composable
fun rememberBitmap(path: String?, maxSide: Int = 1600): ImageBitmap? {
  val k = path?.let { "$it@$maxSide" }
  return produceState(k?.let { bitmaps.get(it) }, k) {
    if (path == null || k == null) { value = null; return@produceState }
    value = bitmaps.get(k) ?: withContext(Dispatchers.IO) { decodeBitmap(path, maxSide) }?.also { bitmaps.put(k, it) }
  }.value
}

fun decodeBitmap(path: String, maxSide: Int): ImageBitmap? = try {
  val file = File(pathOf(path))
  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  BitmapFactory.decodeFile(file.path, bounds)
  var sample = 1
  while (maxSide > 0 && max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
  BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
} catch (_: Exception) {
  null
}

// ── Drag to reorder ─────────────────────────────────────────────────────────

private class ReorderState<T>(val keyOf: (T) -> Any) {
  val order = mutableStateListOf<T>()
  var dragging by mutableStateOf<Any?>(null)
  var delta by mutableStateOf(Offset.Zero)
  var pointerY = 0f
  val slots = HashMap<Any, Rect>() // each item's slot in the layout, by key

  fun indexOf(k: Any) = order.indexOfFirst { keyOf(it) == k }

  // Moves the dragged item into the slot its centre is over, keeping it under the finger.
  fun settle() {
    val k = dragging ?: return
    val from = indexOf(k)
    val home = slots[k] ?: return
    val centre = home.center + delta
    val target = order.indices.minByOrNull { i -> slots[keyOf(order[i])]?.let { (it.center - centre).getDistanceSquared() } ?: Float.MAX_VALUE } ?: return
    if (target == from) return
    val targetRect = slots[keyOf(order[target])] ?: return
    order.add(target, order.removeAt(from))
    delta -= targetRect.topLeft - home.topLeft
    slots[k] = targetRect // until the next layout pass reports it
  }
}

/**
 * A list or grid whose items can be dragged into a new order: a long press anywhere on an item, or (with `handle`)
 * a drag on the modifier passed to the item's handle. The others slide out of the way.
 */
@Composable
fun <T> Reorderable(
  items: List<T>,
  keyOf: (T) -> Any,
  onReorder: (List<T>) -> Unit,
  modifier: Modifier = Modifier,
  columns: Int = 1,
  gap: Dp = 0.dp,
  handle: Boolean = false,
  item: @Composable (item: T, index: Int, handle: Modifier) -> Unit,
) {
  val state = remember { ReorderState(keyOf) }
  if (state.dragging == null && (state.order.size != items.size || state.order.indices.any { keyOf(state.order[it]) != keyOf(items[it]) })) {
    state.order.clear()
    state.order.addAll(items)
  } else if (state.dragging == null) {
    for (i in items.indices) if (state.order[i] !== items[i]) state.order[i] = items[i] // same keys, newer values
  }
  val reorder by rememberUpdatedState(onReorder)
  val scroll = LocalScreenScroll.current
  val density = LocalDensity.current
  val edge = with(density) { 90.dp.toPx() }

  // Held near the top or bottom of the screen, the page scrolls and the item rides along.
  LaunchedEffect(state.dragging != null) {
    if (state.dragging == null || scroll == null) return@LaunchedEffect
    while (true) {
      withFrameNanos { }
      val win = scroll.window
      val y = state.pointerY
      val speed = when {
        y < win.top + edge -> -(win.top + edge - y) / edge * 22f
        y > win.bottom - edge -> (y - (win.bottom - edge)) / edge * 22f
        else -> 0f
      }
      if (speed != 0f) {
        val used = scroll.state.dispatchRawDelta(speed)
        state.delta += Offset(0f, used)
        state.settle()
      }
    }
  }

  val gapPx = with(density) { gap.roundToPx() }
  Layout(
    modifier = modifier,
    content = {
      state.order.forEachIndexed { index, it ->
        val k = keyOf(it)
        key(k) {
          val dragged = state.dragging == k
          val lift by animateFloatAsState(if (dragged) 1.04f else 1f, spring(0.6f), label = "lift")
          val gesture = Modifier.pointerInput(k) {
            val start = { _: Offset -> state.dragging = k; state.delta = Offset.Zero }
            val end = { if (state.dragging == k) { state.dragging = null; state.delta = Offset.Zero; reorder(state.order.toList()) } }
            val move = { change: androidx.compose.ui.input.pointer.PointerInputChange, amount: Offset ->
              change.consume()
              state.delta += amount
              state.settle()
            }
            if (handle) detectDragGestures(start, end, end, move) else detectDragGesturesAfterLongPress(start, end, end, move)
          }
          Box(
            Modifier
              .zIndex(if (dragged) 1f else 0f)
              .animatePlacement(!dragged)
              .offset { if (dragged) state.delta.round() else IntOffset.Zero }
              // where the lifted item is on screen, for scrolling when it's held near an edge
              .onGloballyPositioned { co -> if (dragged) state.pointerY = co.boundsInWindow().center.y }
              .graphicsLayer { scaleX = lift; scaleY = lift; shadowElevation = if (dragged) 18f else 0f }
              .then(if (handle) Modifier else gesture),
          ) { item(it, index, if (handle) gesture else Modifier) }
        }
      }
    },
  ) { measurables, constraints ->
    val width = constraints.maxWidth
    val cell = (width - gapPx * (columns - 1)) / columns
    val placeables = measurables.map { it.measure(Constraints.fixedWidth(cell.coerceAtLeast(0))) }
    val rows = placeables.chunked(columns)
    val rowH = rows.map { r -> r.maxOf { it.height } }
    val height = rowH.sum() + gapPx * (rows.size - 1).coerceAtLeast(0)
    layout(width, height) {
      var y = 0
      rows.forEachIndexed { r, row ->
        row.forEachIndexed { col, p ->
          val i = r * columns + col
          val x = col * (cell + gapPx)
          state.order.getOrNull(i)?.let { item -> state.slots[keyOf(item)] = Rect(x.toFloat(), y.toFloat(), (x + p.width).toFloat(), (y + p.height).toFloat()) }
          p.place(x, y)
        }
        y += rowH[r] + gapPx
      }
    }
  }
}

/** Slides to its new place when the layout moves it, instead of jumping. */
fun Modifier.animatePlacement(enabled: Boolean = true): Modifier = composed {
  val scope = rememberCoroutineScope()
  var target by remember { mutableStateOf<IntOffset?>(null) }
  val anim = remember { mutableStateOf<Animatable<IntOffset, *>?>(null) }
  this
    .onPlaced { target = it.positionInParent().round() }
    .offset {
      val t = target ?: return@offset IntOffset.Zero
      val a = anim.value ?: Animatable(t, IntOffset.VectorConverter).also { anim.value = it }
      if (!enabled) {
        if (a.targetValue != t || a.value != t) scope.launch { a.snapTo(t) }
        return@offset IntOffset.Zero
      }
      if (a.targetValue != t) scope.launch { a.animateTo(t, spring(0.8f, Spring.StiffnessMediumLow)) }
      a.value - t
    }
}

/** Moves item `from` to `to` (swap with a neighbour), for the arrow buttons that stand in for dragging. */
fun <T> List<T>.swap(a: Int, b: Int): List<T> {
  if (b !in indices || a !in indices) return this
  return toMutableList().also { val t = it[a]; it[a] = it[b]; it[b] = t }
}

/** An empty-state block: artwork, a title, a line of help, and actions under it. */
@Composable
fun Empty(art: @Composable () -> Unit, title: String, body: String, actions: @Composable ColumnScope.() -> Unit = {}) {
  Column(Modifier.fillMaxWidth().padding(top = 36.dp, start = 12.dp, end = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    art()
    T(title, Type.title.copy(fontSize = Type.title.fontSize * 0.92f), modifier = Modifier.padding(top = 14.dp), align = TextAlign.Center)
    T(body, Type.body, c.sub, Modifier.padding(top = 6.dp), align = TextAlign.Center)
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = actions)
  }
}

val NoPadding = PaddingValues(0.dp)

/** A tick that eases in, used in pickers and selection lists. */
@Composable
fun CheckMark(on: Boolean, color: Color = c.accent) {
  Icon(if (on) I.checkboxMarked else I.checkboxBlankOutline, 22.dp, if (on) color else c.faint)
}

fun Float.near(o: Float) = abs(this - o) < 0.01f
