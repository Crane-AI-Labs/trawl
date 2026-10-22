package trawl.desktop.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import com.craneai.findr.ui.theme.LocalSunlight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import trawl.desktop.Platform
import trawl.desktop.t

/** Test and screenshot hooks for the first-screen demo. */
object FirstRunDemo {
  /** The demo currently on screen, so the screenshot driver can set an exact frame. */
  @Volatile var current: DemoState? = null

  /** True while a screenshot run holds the demo still. */
  var hold by mutableStateOf(false)

  /** A frame the screenshot run wants on screen (with [hold]): which search, how many letters typed, whether the results are showing. */
  class Pose(val scene: Int, val letters: Int, val shown: Boolean)

  var pose by mutableStateOf<Pose?>(null)
}

private class DemoArt(val cake: ImageBitmap?, val hug: ImageBitmap?, val party: ImageBitmap?, val dog1: ImageBitmap?, val dog2: ImageBitmap?)

private fun loadStill(name: String): ImageBitmap? = runCatching {
  DemoArt::class.java.classLoader.getResourceAsStream("trawl/demo/$name")?.use { loadImageBitmap(it) }
}.getOrNull()

private val ResultsHeight = 178.dp

/**
 * The first screen's animated demo: a search box that types each example description and the files it finds easing in below, on a loop. It
 * is built from the app's own pieces and a handful of small stills (see Licences), starts after the first screen has painted, and:
 *  - pauses while the pointer is over it or the keyboard is on its button (unless the person pressed Play), and has a visible Pause and Play;
 *  - stops for good once the person starts choosing folders ([engaged]);
 *  - stays still when the computer is set to reduce motion (one search and its results), and while the window is minimised;
 *  - is a single image to a screen reader, with the website's description.
 */
@Composable
fun FirstRunDemo(engaged: Boolean, modifier: Modifier = Modifier, compact: Boolean = false) {
  val tk = LocalSunlight.current
  val queries = remember { DemoPlan.scenes.map { t(it.queryKey) } }
  val state = remember { DemoState(queries).also { st -> FirstRunDemo.pose?.let { st.show(it.scene, it.letters, it.shown) } } }
  LaunchedEffect(FirstRunDemo.pose) { FirstRunDemo.pose?.let { state.show(it.scene, it.letters, it.shown) } }
  DisposableEffect(state) { FirstRunDemo.current = state; onDispose { if (FirstRunDemo.current === state) FirstRunDemo.current = null } }

  val reduce by produceState<Boolean?>(null) { value = withContext(Dispatchers.IO) { ReduceMotion.detect(Platform.os) } }
  val art by produceState(DemoArt(null, null, null, null, null)) {
    value = withContext(Dispatchers.IO) {
      DemoArt(loadStill("cake-letters-sq.webp"), loadStill("birthday-hug-sq.webp"), loadStill("party-sq.webp"), loadStill("dog-highfive-thumb.webp"), loadStill("dog-teddy-thumb.webp"))
    }
  }
  var userPaused by remember { mutableStateOf(false) }
  var userPlayed by remember { mutableStateOf(false) }
  var painted by remember { mutableStateOf(false) }
  // Nothing moves until the first screen has been drawn, and then a beat longer.
  LaunchedEffect(Unit) {
    androidx.compose.runtime.withFrameNanos { }
    delay(DemoPlan.FIRST_DELAY_MS)
    painted = true
  }
  LaunchedEffect(reduce) { if (reduce == true) userPaused = true }

  val hoverSource = remember { MutableInteractionSource() }
  val hovered by hoverSource.collectIsHoveredAsState()
  var focused by remember { mutableStateOf(false) }
  LaunchedEffect(hovered, focused) { if (!hovered && !focused) userPlayed = false }
  val heldBack = (hovered || focused) && !userPlayed

  val playing = painted && reduce != null && !userPaused && !heldBack && !engaged && !WindowActivity.minimized && !FirstRunDemo.hold
  LaunchedEffect(playing) { if (playing) state.run() }
  LaunchedEffect(userPaused, engaged) { if (userPaused || engaged) state.finish() }

  Column(modifier.hoverable(hoverSource), verticalArrangement = Arrangement.spacedBy(Sun.S12)) {
    DemoCard(state, art, compact)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Sun.S12)) {
      if (!engaged) {
        SunOutlinedButton(
          onClick = {
            if (userPaused) { userPaused = false; userPlayed = true } else { userPaused = true; state.finish() }
          },
          modifier = Modifier.onFocusChanged { focused = it.isFocused && InputModality.keyboard },
        ) {
          Icon(if (userPaused) Icons.Play else Icons.Pause, contentDescription = null, modifier = Modifier.size(Sun.IconSmall))
          Spacer(Modifier.width(Sun.S8))
          Text(if (userPaused) t("demo_play") else t("demo_pause"), style = SunType.Label)
        }
      }
      Text(t("demo_caption"), style = SunType.Caption, color = tk.ink2, modifier = Modifier.weight(1f))
    }
  }
}

@Composable
private fun DemoCard(state: DemoState, art: DemoArt, compact: Boolean) {
  val tk = LocalSunlight.current
  val shape = RoundedCornerShape(16.dp)
  val description = t("demo_aria")
  Column(
    Modifier.fillMaxWidth().widthIn(max = 600.dp).clip(shape).background(tk.paper).border(Sun.Border, tk.line, shape).padding(Sun.S16)
      .clearAndSetSemantics { contentDescription = description; role = Role.Image },
    verticalArrangement = Arrangement.spacedBy(Sun.S12),
  ) {
    DemoSearchBox(state)
    if (!compact) Row(horizontalArrangement = Arrangement.spacedBy(Sun.S8)) {
      DemoChip(t("filter_all"), Icons.Apps, selected = true)
      DemoChip(t("filter_photos"), Icons.Photo, selected = false)
      DemoChip(t("filter_screenshots"), Icons.Phone, selected = false)
      DemoChip(t("demo_chip_videos"), Icons.Video, selected = false)
    }
    Box(Modifier.fillMaxWidth().height(if (compact) 160.dp else ResultsHeight)) {
      AnimatedContent(
        targetState = if (state.shown) state.scene else -1,
        transitionSpec = { (fadeIn(tween(380, delayMillis = 80)) + slideInVertically(tween(380)) { it / 20 }) togetherWith fadeOut(tween(140)) },
        label = "demo-results",
      ) { scene -> if (scene >= 0) DemoScene(scene, art) }
    }
  }
}

@Composable
private fun DemoSearchBox(state: DemoState) {
  val tk = LocalSunlight.current
  val shape = RoundedCornerShape(12.dp)
  Row(
    Modifier.fillMaxWidth().heightIn(min = Sun.Field).clip(shape).background(tk.paper).border(Sun.Border, tk.line, shape).padding(horizontal = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(Icons.Search, contentDescription = null, tint = tk.ink, modifier = Modifier.size(Sun.Icon))
    Spacer(Modifier.width(10.dp))
    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
      if (state.typed == 0) Text(t("search_hint"), style = SunType.BodyLarge, color = tk.ink2) else Text(state.query, style = SunType.BodyLarge, color = tk.ink, maxLines = 1)
      if (!state.complete) Box(Modifier.padding(start = 1.dp).width(2.dp).height(20.dp).background(tk.ink))
    }
    AnimatedVisibility(state.shown, enter = fadeIn(tween(300)), exit = fadeOut(tween(100))) {
      Text(t("demo_found", DemoPlan.scenes[state.scene].foundCount), style = SunType.Caption, color = tk.ink2, maxLines = 1)
    }
  }
}

@Composable
private fun DemoChip(label: String, icon: ImageVector, selected: Boolean) {
  val tk = LocalSunlight.current
  val shape = CircleShape
  Row(
    Modifier.heightIn(min = 28.dp).clip(shape).background(if (selected) tk.marker else tk.paper).border(if (selected) Sun.Border else Sun.Hairline, if (selected) tk.onMarker else tk.line2, shape)
      .padding(horizontal = 10.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Icon(icon, contentDescription = null, tint = if (selected) tk.onMarker else tk.ink, modifier = Modifier.size(14.dp))
    Text(label, style = SunType.Caption, color = if (selected) tk.onMarker else tk.ink, maxLines = 1)
  }
}

@Composable
private fun DemoScene(scene: Int, art: DemoArt) {
  val tk = LocalSunlight.current
  Column(verticalArrangement = Arrangement.spacedBy(Sun.S8)) {
    when (DemoPlan.scenes[scene]) {
      DemoPlan.Scene.BIRTHDAY -> {
        Text(t("demo_head_photos"), style = SunType.CaptionBold, color = tk.ink2)
        Row(horizontalArrangement = Arrangement.spacedBy(Sun.S8)) {
          Still(art.cake, Modifier.weight(1f).aspectRatio(1.5f))
          Still(art.hug, Modifier.weight(1f).aspectRatio(1.5f))
          Still(art.party, Modifier.weight(1f).aspectRatio(1.5f))
        }
      }
      DemoPlan.Scene.WIFI -> {
        Text(t("demo_head_screenshots"), style = SunType.CaptionBold, color = tk.ink2)
        DemoFile(Icons.Phone, "Screenshot_20260912.png", t("demo_meta_wifi"), t("demo_snip_wifi"), null)
      }
      DemoPlan.Scene.RECEIPT -> {
        Text(t("demo_head_docs"), style = SunType.CaptionBold, color = tk.ink2)
        DemoFile(Icons.Pdf, "Receipt_0412.pdf", t("demo_meta_receipt"), t("demo_snip_receipt"), null)
      }
      DemoPlan.Scene.DOGS -> {
        Text(t("demo_head_photos"), style = SunType.CaptionBold, color = tk.ink2)
        Row(horizontalArrangement = Arrangement.spacedBy(Sun.S8)) {
          Still(art.dog1, Modifier.weight(1f).aspectRatio(2.2f), time = "0:06")
          Still(art.dog2, Modifier.weight(1f).aspectRatio(2.2f), time = "0:04")
        }
      }
      DemoPlan.Scene.VOICE -> {
        Text(t("demo_head_voice"), style = SunType.CaptionBold, color = tk.ink2)
        DemoFile(Icons.Mic, "PTT-20261003-WA0007.opus", t("demo_meta_voice"), t("demo_snip_voice"), t("demo_at", "0:42"))
      }
    }
  }
}

@Composable
private fun Still(bitmap: ImageBitmap?, modifier: Modifier, time: String? = null) {
  val tk = LocalSunlight.current
  val shape = RoundedCornerShape(10.dp)
  Box(modifier.clip(shape).background(tk.paper2).border(Sun.Hairline, tk.line2, shape)) {
    if (bitmap != null) Image(bitmap, contentDescription = null, modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop)
    if (time != null) {
      // Over a photograph, so its own dark chip with white type, the one place the demo is not paper and ink.
      Text(
        time, style = SunType.CaptionBold, color = Color.White, maxLines = 1,
        modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.72f)).padding(horizontal = 6.dp, vertical = 1.dp),
      )
    }
  }
}

@Composable
private fun DemoFile(icon: ImageVector, name: String, meta: String, snippet: String, pill: String?) {
  val tk = LocalSunlight.current
  val shape = RoundedCornerShape(10.dp)
  Row(
    Modifier.fillMaxWidth().clip(shape).background(tk.paper2).border(Sun.Border, tk.line, shape).padding(Sun.S12),
    horizontalArrangement = Arrangement.spacedBy(Sun.S12), verticalAlignment = Alignment.Top,
  ) {
    val tile = RoundedCornerShape(8.dp)
    Box(Modifier.size(44.dp).background(tk.paper, tile).border(Sun.Hairline, tk.line2, tile), contentAlignment = Alignment.Center) {
      Icon(icon, contentDescription = null, tint = tk.ink, modifier = Modifier.size(22.dp))
    }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(name, style = SunType.Label, color = tk.ink, maxLines = 1)
      Text(meta, style = SunType.Caption, color = tk.ink2, maxLines = 1)
      Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(Sun.S8), verticalAlignment = Alignment.Top) {
        if (pill != null) Badge(pill, marker = true)
        SnippetText(snippet, lines = 2)
      }
    }
  }
}
