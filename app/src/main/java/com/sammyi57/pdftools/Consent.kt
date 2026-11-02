package com.sammyi57.pdftools

import androidx.activity.compose.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// Shown once, before anything else, on a fresh install, and again from Settings to read back. One plain screen that
// fits without scrolling: what the app does with your files, then a tick for each document.

// The contact address shown in the privacy policy (and on its web page, built from this file by scripts/build_policy_page.py).
private const val CONTACT = "bhingesamarth@gmail.com"

private val POINTS = listOf(
  I.cellphoneLock to "Every tool runs on this phone",
  I.accountOffOutline to "No account, no tracking, no ads",
  I.googleDrive to "Google Drive backup is optional",
  I.wifiOff to "Works with no connection",
)

private val PRIVACY = """1. What we collect
Nothing. PDF Tools has no accounts, no analytics, no advertising and no crash reporting. We do not collect, receive or store your documents, your name, your email address or any identifier.

2. Where your files live
Every tool runs entirely on this device. Saved PDFs are kept in the app's own private storage, which other apps cannot read. They are never sent to us, because there is nowhere to send them to.

3. Google Drive backup
Off unless you turn it on. When you do, the app signs in to the Google account you choose and copies your saved PDFs to your own Google Drive. Those files travel from this phone to Google directly. You can disconnect at any time in Settings, which revokes the app's access to your account. What Google does with files in your Drive is covered by Google's own privacy policy.

4. Permissions
Camera is used only while you are scanning a document. Photos is used only when you pick an image to turn into a PDF or to place a signature. Neither is used in the background, and the app asks the first time it needs each one.

5. Reading text from scans
Making a scanned PDF searchable uses a text recognition model built into the app. The page images and the text read from them never leave the device, and it needs no connection.

6. Children
The app is not directed at children under 13, and collects no data from anyone of any age.

7. Changes
If this policy changes in a way that matters, the app will show it to you again before you carry on.

8. Contact
Questions about this policy: $CONTACT"""

private val TERMS = """1. The app
PDF Tools is provided free of charge, as is, for use on your own device.

2. Your files are your responsibility
The app changes documents you choose, and some changes cannot be undone: redaction removes content for good, a password locks a file, cropping discards part of a page. Keep your own copy of anything you cannot afford to lose. We have no copy and cannot recover one for you.

3. Acceptable use
Do not use the app to alter documents in order to deceive anyone, to forge a signature, or for anything unlawful where you live. Placing a signature image on a document is not a statement about that document's legal standing — whether a signed PDF binds anyone depends on your local law and on the parties involved.

4. No warranty
The app comes with no warranty of any kind. Page detection, text recognition and compression are approximate by their nature, and any tool may contain errors. Check the result before you rely on it.

5. Limitation of liability
So far as the law allows, we are not liable for lost data, lost profits, or any indirect or consequential loss arising from your use of the app.

6. Third-party services
Google Drive backup is optional and is governed by Google's own terms of service.

7. Changes
If these terms change, the app will ask you to accept them again. Continuing to use it means you accept the version shown."""

/** First launch: a short tour, then the terms. Reading the terms again from Settings goes straight to them. */
@Composable
fun Consent(onAccept: (() -> Unit)? = null, onClose: (() -> Unit)? = null) {
  var intro by rememberSaveable { mutableStateOf(onAccept != null) }
  if (intro) Onboarding { intro = false } else ConsentBody(onAccept, onClose)
}

@Composable
private fun ConsentBody(onAccept: (() -> Unit)?, onClose: (() -> Unit)?) {
  var doc by remember { mutableStateOf<String?>(null) }
  var privacy by remember { mutableStateOf(false) }
  var terms by remember { mutableStateOf(false) }
  if (onClose != null) BackHandler(doc == null, onClose)
  val privacyTitle = stringResource(R.string.title_privacy_policy)
  val termsTitle = stringResource(R.string.title_terms_conditions)

  Box(Modifier.fillMaxSize().background(c.bg)) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
      if (onClose != null) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
          IconBtn(I.close, 24.dp, label = stringResource(R.string.action_close), onClick = onClose)
        }
      }
      Column(
        Modifier.weight(1f).fillMaxWidth().padding(horizontal = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
      ) {
        val pop = remember { Animatable(0.6f) }
        LaunchedEffect(Unit) { pop.animateTo(1f, spring(0.5f, Spring.StiffnessLow)) }
        Box(
          Modifier.size(84.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value }
            .clip(RoundedCornerShape(26.dp)).glow(c.hero, 0.85f, 1f),
          contentAlignment = Alignment.Center,
        ) { Icon(I.filePdfBox, 46.dp, Color.White) }
        T(stringResource(R.string.app_name), Type.largeTitle, modifier = Modifier.padding(top = 18.dp))
        T(stringResource(R.string.msg_documents_stay_on_phone), Type.body, c.sub, Modifier.padding(top = 4.dp), align = TextAlign.Center)
        val points = listOf(
          I.cellphoneLock to stringResource(R.string.onboarding_point_on_device),
          I.accountOffOutline to stringResource(R.string.onboarding_point_no_tracking),
          I.googleDrive to stringResource(R.string.onboarding_point_drive_optional),
          I.wifiOff to stringResource(R.string.onboarding_point_offline),
        )
        Column(Modifier.fillMaxWidth().padding(top = 30.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
          points.forEachIndexed { i, (glyph, label) -> Stagger(i) { Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).background(c.sunken, RoundedCornerShape(Radius.tile)), contentAlignment = Alignment.Center) { Icon(glyph, 18.dp, c.sub) }
            Spacer(Modifier.size(12.dp))
            T(label, Type.body.copy(fontSize = Type.body.fontSize * 0.97f))
          } } }
        }
      }
      Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp)) {
        if (onAccept != null) {
          Tick(privacy, stringResource(R.string.label_i_have_read_the), privacyTitle, { privacy = !privacy }) { doc = "privacy" }
          Tick(terms, stringResource(R.string.label_i_agree_to_the), termsTitle, { terms = !terms }) { doc = "terms" }
          Btn(stringResource(R.string.action_continue), Modifier.fillMaxWidth().padding(top = 14.dp), enabled = privacy && terms, onClick = onAccept)
        } else {
          T(privacyTitle, Type.item, c.accent, Modifier.tap { doc = "privacy" }.padding(vertical = 11.dp))
          T(termsTitle, Type.item, c.accent, Modifier.tap { doc = "terms" }.padding(vertical = 11.dp))
        }
        T(stringResource(R.string.msg_last_updated, Terms.UPDATED), Type.small, c.faint, Modifier.fillMaxWidth().padding(top = 14.dp), align = TextAlign.Center)
      }
    }

    AnimatedVisibility(doc != null, enter = slideInVertically(tween(380)) { it }, exit = slideOutVertically(tween(300)) { it }) {
      val shown = remember { mutableStateOf(doc) }
      if (doc != null) shown.value = doc
      val title = if ((shown.value ?: "privacy") == "privacy") privacyTitle else termsTitle
      val body = stringResource(if ((shown.value ?: "privacy") == "privacy") R.string.privacy_policy_body else R.string.terms_body)
      BackHandler { doc = null }
      Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
          T(title, Type.title.copy(fontSize = Type.title.fontSize * 0.75f), modifier = Modifier.weight(1f))
          IconBtn(I.close, 24.dp, label = stringResource(R.string.action_close)) { doc = null }
        }
        T(body, Type.small.copy(fontSize = Type.small.fontSize * 1.07f, lineHeight = Type.small.lineHeight * 1.15f), c.sub,
          Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 40.dp))
      }
    }
  }
}

@Composable
private fun Tick(on: Boolean, before: String, link: String, toggle: () -> Unit, open: () -> Unit) {
  Row(Modifier.fillMaxWidth().tap(scaleTo = 0.98f, onClick = toggle).padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
    val box = RoundedCornerShape(7.dp)
    Box(
      Modifier.size(22.dp).background(if (on) c.accent else Color.Transparent, box).border(2.dp, if (on) c.accent else c.lineStrong, box),
      contentAlignment = Alignment.Center,
    ) { if (on) Icon(I.check, 15.dp, Color.White) }
    Spacer(Modifier.size(12.dp))
    T("$before ", Type.small.copy(fontSize = Type.small.fontSize * 1.07f), c.sub)
    T(link, Type.small.copy(fontSize = Type.small.fontSize * 1.07f, fontWeight = Type.item.fontWeight), c.accent, Modifier.tap(scaleTo = 0.94f, onClick = open))
  }
}

/**
 * Fades and lifts its content in, a beat after the one before it. Plays once per screen: coming back to a screen (from a
 * tool, or another tab) shows it as it was, instead of blank while every section fades in again.
 */
@Composable
fun Stagger(index: Int, content: @Composable () -> Unit) {
  var played by rememberSaveable { mutableStateOf(false) }
  val t = remember { Animatable(if (played) 1f else 0f) }
  LaunchedEffect(Unit) {
    if (played) return@LaunchedEffect
    played = true
    delay(60L * index.coerceAtMost(8))
    t.animateTo(1f, tween(380))
  }
  Box(Modifier.graphicsLayer { alpha = t.value; translationY = (1 - t.value) * 24f }) { content() }
}
