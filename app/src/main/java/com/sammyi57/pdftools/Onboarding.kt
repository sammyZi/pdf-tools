package com.sammyi57.pdftools

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke

// The first-launch tour, three screens in a calm, friendly style: white pages, centred bold titles, one dark pill button
// with a small line under it, and Skip on top. Welcome (sticky notes), pin the tools you want at the top of Home, and a
// look to match (light, auto or dark). Always light, whatever the app's theme. Nothing animates except the swipe.

private val DARK = Color(0xFF1C1C1B)
private val SUB = Color(0xFF9B9B9B)
private val ROW = Color(0xFFF4F4F5)
private val PURPLE = Color(0xFF7A6CF2)
private val TEAL = Color(0xFF1FB58C)
private val SALMON = Color(0xFFF2735B)
private val HAND = FontFamily(Font(R.font.caveat)) // Caveat, SIL Open Font License
private const val PAGES = 3

private val TITLE = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 31.sp, lineHeight = 38.sp, letterSpacing = (-0.8).sp)
private val SMALL = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Normal, fontSize = 11.5.sp, lineHeight = 16.sp)

private class Look(val pref: ThemePref, val tint: Color, val chip: String, val body: String, val name: String, val sub: String, val kind: Int)

@Composable
private fun onboardingLooks() = listOf(
  Look(ThemePref.Light, Color(0xFFECE8FE), stringResource(R.string.onboarding_look_light_chip), stringResource(R.string.onboarding_look_light_body), stringResource(R.string.onboarding_look_light_name), stringResource(R.string.onboarding_look_light_sub), 1),
  Look(ThemePref.System, Color(0xFFFDDDD2), stringResource(R.string.onboarding_look_auto_chip), stringResource(R.string.onboarding_look_auto_body), stringResource(R.string.onboarding_look_auto_name), stringResource(R.string.onboarding_look_auto_sub), 0),
  Look(ThemePref.Dark, Color(0xFFDDE9E1), stringResource(R.string.onboarding_look_dark_chip), stringResource(R.string.onboarding_look_dark_body), stringResource(R.string.onboarding_look_dark_name), stringResource(R.string.onboarding_look_dark_sub), 2),
)

@Composable
fun Onboarding(onDone: () -> Unit) {
  val themeDark = c.dark // the app's theme, read before the tour switches to the light palette
  CompositionLocalProvider(LocalPalette provides LightPalette) { OnboardingBody(themeDark, onDone) }
}

@Composable
private fun OnboardingBody(themeDark: Boolean, onDone: () -> Unit) {
  val pager = rememberPagerState { PAGES }
  val lookList = onboardingLooks()
  val looks = rememberPagerState(initialPage = lookList.indexOfFirst { it.pref == ThemeState.pref }.coerceAtLeast(0)) { lookList.size }
  val scope = rememberCoroutineScope()
  var pinned by remember { mutableStateOf(listOf<String>()) }
  val page = pager.currentPage
  val last = PAGES - 1
  val go = { to: Int -> scope.launch { pager.animateScrollToPage(to) }; Unit }
  BackHandler(page > 0) { go(page - 1) }

  // The pages are always light, so the status bar gets dark icons for as long as the tour is up.
  val view = LocalView.current
  val ctl = WindowCompat.getInsetsController((view.context as Activity).window, view)
  val darkBars = { ctl.isAppearanceLightStatusBars = true; ctl.isAppearanceLightNavigationBars = true }
  // App() sets the bar icons for the app's own theme in a LaunchedEffect that runs after this first composition, and in a
  // dark theme that leaves white icons on the white page, so set them again once it has.
  LaunchedEffect(Unit) { delay(150); darkBars() }
  DisposableEffect(Unit) {
    darkBars()
    onDispose {
      ctl.isAppearanceLightStatusBars = !themeDark
      ctl.isAppearanceLightNavigationBars = !themeDark
    }
  }

  // Finishing applies what was chosen: the pins and the look. Skip leaves everything as it was.
  val finish = {
    pinned.forEach { if (it !in Pins.ids) Pins.toggle(it) }
    if (lookList[looks.currentPage].pref != ThemeState.pref) ThemeState.set(lookList[looks.currentPage].pref)
    onDone()
  }
  val label = listOf(stringResource(R.string.onboarding_cta_get_started), stringResource(R.string.action_continue), stringResource(R.string.onboarding_cta_my_look))[page]
  val line = listOf(stringResource(R.string.onboarding_line_welcome), stringResource(R.string.onboarding_line_pin), stringResource(R.string.onboarding_line_look))[page]

  Column(Modifier.fillMaxSize().background(Color.White).statusBarsPadding().navigationBarsPadding()) {
    Box(Modifier.weight(1f).fillMaxWidth()) {
      HorizontalPager(pager, Modifier.fillMaxSize()) { i ->
        when (i) {
          0 -> PageWelcome()
          1 -> PageTools(pinned) { id -> pinned = if (id in pinned) pinned - id else pinned + id }
          else -> PageLook(looks)
        }
      }
      if (page > 0) Box(Modifier.padding(start = 12.dp, top = 6.dp)) { IconBtn(I.chevronLeft, 26.dp, DARK, stringResource(R.string.action_back)) { go(page - 1) } }
      if (page < last) T(
        stringResource(R.string.action_skip), Type.med.copy(fontSize = 14.sp), DARK,
        Modifier.align(Alignment.TopEnd).padding(end = 12.dp, top = 8.dp).tap(shape = CircleShape, scaleTo = 0.92f, label = stringResource(R.string.acc_skip_intro), onClick = onDone).padding(horizontal = 14.dp, vertical = 10.dp),
      )
    }
    Box(
      Modifier.padding(horizontal = 24.dp).padding(top = 12.dp).fillMaxWidth().height(58.dp)
        .tap(shape = CircleShape, scaleTo = 0.97f) { if (page < last) go(page + 1) else finish() }
        .background(DARK, CircleShape),
      contentAlignment = Alignment.Center,
    ) { T(label, Type.button, Color.White, maxLines = 1) }
    T(line, SMALL, SUB, Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 14.dp), align = TextAlign.Center)
  }
}

@Composable
private fun Title(title: String, sub: String? = null) {
  T(title, TITLE, DARK, Modifier.fillMaxWidth(), align = TextAlign.Center)
  if (sub != null) T(sub, Type.small.copy(fontSize = 13.sp), SUB, Modifier.fillMaxWidth().padding(top = 8.dp), align = TextAlign.Center)
}

// ── 1. Welcome, with sticky notes ───────────────────────────────────────────────────────────────────────────────────

@Composable
private fun PageWelcome() {
  BoxWithConstraints(Modifier.fillMaxSize()) {
    val w = maxWidth
    val h = maxHeight
    Canvas(Modifier.fillMaxSize()) {
      drawRect(
        Brush.radialGradient(
          listOf(Color(0x66FFC9B5), Color(0x40FFF0B8), Color.Transparent),
          Offset(size.width * 0.5f, size.height * 0.66f), size.width * 0.72f,
        ),
      )
    }
    T(stringResource(R.string.app_name), Type.title.copy(fontSize = 21.sp, lineHeight = 28.sp, letterSpacing = (-0.4).sp), DARK, Modifier.align(Alignment.TopCenter).padding(top = 14.dp))

    Sticky(Color(0xFFFFF0B0), -2f, Modifier.offset(x = (-8).dp, y = h * 0.03f).size(112.dp, 124.dp), fold = true) {
      Icon(I.pin, 26.dp, Color(0xFFE5483B), Modifier.align(Alignment.TopStart).offset(x = 50.dp, y = (-13).dp).graphicsLayer { rotationZ = 22f })
      Hand(stringResource(R.string.onboarding_note_1), Modifier.align(Alignment.TopStart).padding(start = 18.dp, top = 40.dp))
      Canvas(Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 22.dp).size(74.dp, 8.dp)) {
        for (k in 0..1) drawLine(DARK.copy(alpha = 0.3f), Offset(0f, 2.dp.toPx() + k * 4.dp.toPx()), Offset(size.width, 2.dp.toPx() + k * 3.dp.toPx()), 1.2.dp.toPx(), StrokeCap.Round)
      }
    }
    Sticky(Color(0xFFF6F3F0), 4f, Modifier.align(Alignment.TopEnd).offset(x = 14.dp, y = h * 0.14f).size(112.dp, 130.dp)) {
      Icon(I.paperclip, 34.dp, Color(0xFF6F6AA8), Modifier.align(Alignment.TopStart).offset(x = 12.dp, y = (-10).dp))
      Hand(stringResource(R.string.onboarding_note_2), Modifier.align(Alignment.TopStart).padding(start = 14.dp, top = 52.dp))
      Icon(I.star, 16.dp, Color(0xFFF0B429), Modifier.align(Alignment.BottomStart).padding(start = 18.dp, bottom = 14.dp))
    }
    Sticky(Color(0xFFE7E1F8), -3f, Modifier.offset(x = (-14).dp, y = h * 0.68f).size(110.dp, 118.dp)) {
      Box(Modifier.align(Alignment.TopCenter).offset(y = (-7).dp).size(64.dp, 15.dp).graphicsLayer { rotationZ = -3f }.background(Color(0xFFBBD3EE).copy(alpha = 0.8f), RoundedCornerShape(1.dp)))
      Hand(stringResource(R.string.onboarding_note_3), Modifier.align(Alignment.TopStart).padding(start = 22.dp, top = 22.dp))
      Icon(I.heartOutline, 18.dp, Color(0xFF6F6AA8), Modifier.align(Alignment.BottomStart).padding(start = 36.dp, bottom = 14.dp))
    }
    Sticky(Color(0xFFFBD9D2), 3f, Modifier.align(Alignment.TopEnd).offset(x = 16.dp, y = h * 0.72f).size(112.dp, 108.dp)) {
      Icon(I.heart, 22.dp, Color(0xFFE5483B), Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 8.dp))
      Hand(stringResource(R.string.onboarding_note_4), Modifier.align(Alignment.TopStart).padding(start = 14.dp, top = 44.dp))
    }

    T(
      stringResource(R.string.onboarding_tagline),
      TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Medium, fontSize = 27.sp, lineHeight = 36.sp, letterSpacing = (-0.6).sp),
      DARK, Modifier.align(Alignment.TopCenter).offset(y = h * 0.43f), align = TextAlign.Center,
    )
    // A looped scribble that ends in an arrow pointing down at the button.
    Canvas(Modifier.align(Alignment.TopCenter).offset(x = 6.dp, y = h * 0.70f).size(w * 0.34f, h * 0.27f)) {
      val sx = size.width / 100f
      val sy = size.height / 150f
      fun p(x: Float, y: Float) = Offset(x * sx, y * sy)
      val st = DrawStroke(1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
      val ink = DARK.copy(alpha = 0.78f)
      drawPath(
        Path().apply {
          moveTo(p(100f, 6f).x, p(100f, 6f).y)
          cubicTo(p(80f, 14f).x, p(80f, 14f).y, p(55f, 18f).x, p(55f, 18f).y, p(34f, 12f).x, p(34f, 12f).y)
          cubicTo(p(14f, 7f).x, p(14f, 7f).y, p(14f, -2f).x, p(14f, -2f).y, p(26f, 0f).x, p(26f, 0f).y)
          cubicTo(p(42f, 3f).x, p(42f, 3f).y, p(34f, 28f).x, p(34f, 28f).y, p(36f, 60f).x, p(36f, 60f).y)
          cubicTo(p(37f, 90f).x, p(37f, 90f).y, p(38f, 112f).x, p(38f, 112f).y, p(40f, 144f).x, p(40f, 144f).y)
        },
        ink, style = st,
      )
      val tip = p(40f, 146f)
      drawLine(ink, tip, p(26f, 128f), st.width, StrokeCap.Round)
      drawLine(ink, tip, p(54f, 126f), st.width, StrokeCap.Round)
    }
  }
}

@Composable
private fun Sticky(fill: Color, rot: Float, modifier: Modifier, fold: Boolean = false, content: @Composable BoxScope.() -> Unit) {
  val shape = RoundedCornerShape(3.dp)
  Box(modifier.graphicsLayer { rotationZ = rot }.shadow(3.dp, shape, ambientColor = Color(0x22000000), spotColor = Color(0x22000000)).clip(shape).background(fill)) {
    content()
    if (fold) Canvas(Modifier.align(Alignment.BottomEnd).size(22.dp)) {
      drawPath(Path().apply { moveTo(size.width, 0f); lineTo(size.width, size.height); lineTo(0f, size.height); close() }, Color(0xFFF2DC8A))
    }
  }
}

@Composable
private fun Hand(text: String, modifier: Modifier) {
  T(text, TextStyle(fontFamily = HAND, fontSize = 17.sp, lineHeight = 19.sp), DARK.copy(alpha = 0.88f), modifier)
}

// ── 2. Pin your tools ───────────────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun PageTools(pinned: List<String>, onToggle: (String) -> Unit) {
  Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) {
    Spacer(Modifier.height(64.dp))
    Title(stringResource(R.string.title_pin_your_tools), stringResource(R.string.msg_pin_your_tools_sub))
    Spacer(Modifier.height(22.dp))
    val unpinLabel = stringResource(R.string.acc_unpin_tool)
    val pinLabel = stringResource(R.string.acc_pin_tool)
    TOOLS.forEach { t ->
      val on = t.id in pinned || t.id in Pins.ids
      val shape = RoundedCornerShape(20.dp)
      Row(
        Modifier.padding(bottom = 8.dp).fillMaxWidth().height(64.dp)
          .tap(shape = shape, scaleTo = 0.98f, label = if (on) unpinLabel.format(t.title) else pinLabel.format(t.title)) { if (t.id !in Pins.ids) onToggle(t.id) }
          .background(ROW, shape).padding(start = 12.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        ArtTile(t.id, 42.dp, bg = Color.White)
        T(t.title, Type.item.copy(fontSize = 15.sp), DARK, Modifier.weight(1f).padding(horizontal = 14.dp), maxLines = 1)
        Icon(if (on) I.pin else I.pinOutline, 22.dp, if (on) Brand.tangerine else Color(0xFFB4B4B6))
      }
    }
    Spacer(Modifier.height(8.dp))
  }
}

// ── 3. Which look suits you ─────────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun PageLook(looks: PagerState) {
  val lookList = onboardingLooks()
  val look = lookList[looks.currentPage]
  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
    Spacer(Modifier.height(56.dp))
    Column(Modifier.padding(horizontal = 20.dp)) { Title(stringResource(R.string.title_which_look)) }
    Spacer(Modifier.height(8.dp))
    HorizontalPager(looks, Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 56.dp), pageSpacing = 12.dp) { i ->
      val l = lookList[i]
      Box(Modifier.fillMaxWidth().height(350.dp)) {
        Box(Modifier.padding(top = 78.dp).fillMaxSize().background(l.tint, RoundedCornerShape(34.dp)))
        Character(l.kind, Modifier.fillMaxWidth().height(176.dp).padding(top = 6.dp))
        Column(Modifier.padding(top = 188.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
          T(l.chip, Type.caption.copy(fontSize = 10.sp, letterSpacing = 1.2.sp), DARK, Modifier.background(Color.White, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 5.dp))
          T(l.body, TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 25.sp), DARK, Modifier.padding(top = 12.dp), align = TextAlign.Center)
        }
      }
    }
    T(look.name, TextStyle(fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.4).sp), DARK, Modifier.padding(top = 10.dp))
    T(look.sub, Type.small.copy(fontSize = 12.sp), SUB, Modifier.padding(top = 2.dp))
    Row(Modifier.padding(top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
      repeat(lookList.size) { i ->
        val on = i == looks.currentPage
        Box(Modifier.padding(horizontal = 5.dp).height(4.dp).width(if (on) 20.dp else 4.dp).background(if (on) SALMON else Color(0xFFD9D9DA), CircleShape))
      }
    }
  }
}

/** A character with stick arms and legs: a coral diamond (winking, with a paper plane), a purple cloud or a teal star. */
@Composable
private fun Character(kind: Int, modifier: Modifier) {
  Canvas(modifier) {
    val w = size.width
    val h = size.height
    val d = density
    val line = 2.2f * d
    val cx = w / 2
    val cy = h * 0.4f
    val r = if (kind == 1) 0.2f * w * 0.62f else 0.17f * w * 0.62f
    val body = when (kind) { 0 -> SALMON; 1 -> PURPLE; else -> TEAL }
    val ink = DrawStroke(line, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val big = r * 1.55f

    // Legs, then arms with open hands.
    drawPath(Path().apply {
      moveTo(cx - big * 0.35f, cy + big * 0.85f); lineTo(cx - big * 0.5f, h - 4 * d); lineTo(cx - big * 0.85f, h - 4 * d)
      moveTo(cx + big * 0.35f, cy + big * 0.85f); lineTo(cx + big * 0.55f, h - 9 * d); lineTo(cx + big * 0.9f, h - 9 * d)
    }, DARK, style = ink)
    for (side in listOf(-1f, 1f)) {
      val hand = Offset(cx + side * big * 2.1f, cy - big * 0.45f)
      drawPath(Path().apply { moveTo(cx + side * big * 0.85f, cy + big * 0.25f); quadraticBezierTo(cx + side * big * 1.8f, cy + big * 0.55f, hand.x, hand.y) }, DARK, style = ink)
      drawCircle(Color.White, 6 * d, hand)
      drawCircle(DARK, 6 * d, hand, style = DrawStroke(1.6f * d))
      for (k in -1..1) drawLine(DARK, Offset(hand.x + k * 3.5f * d, hand.y - 5 * d), Offset(hand.x + k * 4.5f * d, hand.y - 10 * d), 1.6f * d, StrokeCap.Round)
    }

    when (kind) {
      0 -> rotate(45f, Offset(cx, cy)) { drawRoundRect(body, Offset(cx - big * 0.8f, cy - big * 0.8f), Size(big * 1.6f, big * 1.6f), CornerRadius(big * 0.5f)) }
      1 -> {
        drawRoundRect(body, Offset(cx - big, cy - big * 0.7f), Size(big * 2f, big * 1.75f), CornerRadius(big * 0.55f))
        for ((x, y, rr) in listOf(Triple(-0.55f, -0.7f, 0.5f), Triple(0f, -0.82f, 0.56f), Triple(0.55f, -0.7f, 0.5f))) drawCircle(body, rr * big, Offset(cx + x * big, cy + y * big))
      }
      else -> {
        val k = big * 1.2f
        val star = Path().apply {
          moveTo(cx, cy - k)
          quadraticBezierTo(cx + 0.45f * k, cy - 0.45f * k, cx + k, cy)
          quadraticBezierTo(cx + 0.45f * k, cy + 0.45f * k, cx, cy + k)
          quadraticBezierTo(cx - 0.45f * k, cy + 0.45f * k, cx - k, cy)
          quadraticBezierTo(cx - 0.45f * k, cy - 0.45f * k, cx, cy - k)
          close()
        }
        drawPath(star, body, style = DrawStroke(big * 0.35f, join = StrokeJoin.Round))
        drawPath(star, body)
      }
    }

    fun eye(c: Offset, rr: Float, lid: Boolean = false) {
      drawCircle(Color.White, rr, c)
      drawCircle(DARK, rr, c, style = DrawStroke(1.6f * d))
      drawCircle(DARK, rr * 0.52f, Offset(c.x + rr * 0.05f, c.y + rr * 0.1f))
      if (lid) {
        drawArc(body, 180f, 180f, true, Offset(c.x - rr, c.y - rr), Size(rr * 2, rr * 2))
        drawLine(DARK, Offset(c.x - rr, c.y), Offset(c.x + rr, c.y), 1.6f * d, StrokeCap.Round)
      }
    }
    fun smile(c: Offset, rr: Float) = drawPath(Path().apply { moveTo(c.x - rr, c.y); quadraticBezierTo(c.x, c.y + rr * 1.1f, c.x + rr, c.y) }, DARK, style = DrawStroke(2.4f * d, cap = StrokeCap.Round))
    when (kind) {
      0 -> {
        eye(Offset(cx - big * 0.28f, cy - big * 0.1f), big * 0.3f)
        drawPath(Path().apply { moveTo(cx + big * 0.2f, cy - big * 0.08f); quadraticBezierTo(cx + big * 0.45f, cy - big * 0.32f, cx + big * 0.7f, cy - big * 0.08f) }, DARK, style = ink)
        smile(Offset(cx + big * 0.1f, cy + big * 0.35f), big * 0.2f)
        // The paper plane, with a dotted arc over it.
        val plane = Path().apply {
          moveTo(cx + big * 3.2f, cy - big * 1.55f); lineTo(cx + big * 2.1f, cy - big * 1.0f); lineTo(cx + big * 2.55f, cy - big * 0.88f)
          lineTo(cx + big * 2.45f, cy - big * 0.45f); lineTo(cx + big * 2.8f, cy - big * 0.8f); close()
        }
        drawPath(plane, Color(0xFFFCC9BA))
        drawPath(plane, DARK, style = DrawStroke(1.5f * d, join = StrokeJoin.Round))
        drawPath(Path().apply { moveTo(cx + big * 1.5f, cy - big * 1.7f); quadraticBezierTo(cx + big * 2.5f, cy - big * 2.05f, cx + big * 3.3f, cy - big * 1.85f) }, DARK.copy(alpha = 0.5f), style = DrawStroke(1.3f * d, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3 * d, 4 * d))))
      }
      1 -> {
        eye(Offset(cx - big * 0.4f, cy), big * 0.38f)
        eye(Offset(cx + big * 0.4f, cy), big * 0.38f)
        smile(Offset(cx, cy + big * 0.55f), big * 0.12f)
      }
      else -> {
        eye(Offset(cx - big * 0.36f, cy), big * 0.26f, lid = true)
        eye(Offset(cx + big * 0.36f, cy), big * 0.26f, lid = true)
        smile(Offset(cx, cy + big * 0.42f), big * 0.14f)
      }
    }
  }
}
