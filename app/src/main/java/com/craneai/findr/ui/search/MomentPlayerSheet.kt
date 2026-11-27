package com.craneai.findr.ui.search

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.craneai.findr.R
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.IndexedFile
import com.craneai.findr.ui.common.FileActions
import com.craneai.findr.ui.common.formatDuration
import com.craneai.findr.ui.theme.LocalSunlight
import com.craneai.findr.ui.theme.Sun
import com.craneai.findr.ui.theme.SunType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Start position with 1.5 s pre-roll so the matched spoken word or scene is never clipped. */
internal fun preRollStartMs(momentMs: Long): Long = maxOf(0L, momentMs - FileActions.OPEN_LEAD_MS)

/** Clamps a requested seek target into [0, durationMs] when duration is known. */
internal fun clampedSeekMs(targetMs: Long, durationMs: Long): Long =
  if (durationMs > 0L) targetMs.coerceIn(0L, durationMs) else targetMs.coerceAtLeast(0L)

/**
 * In-app seekable video and audio player for matched timestamp hits.
 *
 * External gallery apps (Samsung Gallery, Google Photos) ignore `ACTION_VIEW` start-position extras and always restart
 * from `0:00`. Using the platform [MediaPlayer] + [TextureView] directly (zero new dependencies, zero `INTERNET`
 * permission) guarantees frame-accurate `SEEK_CLOSEST` playback starting at `max(0, momentMs - 1500)`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MomentPlayerSheet(
  file: IndexedFile,
  momentMs: Long,
  hidden: Boolean,
  onOpenExternal: (Long) -> Unit,
  onShare: () -> Unit,
  onReveal: () -> Unit,
  onDismiss: () -> Unit,
) {
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = MaterialTheme.colorScheme.background,
    tonalElevation = 0.dp,
  ) {
    key(file.uri, momentMs, hidden) {
      MomentPlayerContent(
        file = file,
        momentMs = momentMs,
        hidden = hidden,
        onOpenExternal = onOpenExternal,
        onShare = onShare,
        onReveal = onReveal,
      )
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MomentPlayerContent(
  file: IndexedFile,
  momentMs: Long,
  hidden: Boolean,
  onOpenExternal: (Long) -> Unit,
  onShare: () -> Unit,
  onReveal: () -> Unit,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val t = LocalSunlight.current
  val isVideo = file.kind == FileKind.VIDEO
  val initialSeekMs = remember(momentMs) { preRollStartMs(momentMs) }

  var player by remember { mutableStateOf<MediaPlayer?>(null) }
  var prepared by remember { mutableStateOf(false) }
  var playing by remember { mutableStateOf(false) }
  var failed by remember { mutableStateOf(false) }
  var durationMs by remember { mutableLongStateOf(file.durationMs ?: 0L) }
  var positionMs by remember { mutableLongStateOf(initialSeekMs) }
  var videoAspect by remember { mutableFloatStateOf(16f / 9f) }
  var videoSurface by remember { mutableStateOf<Surface?>(null) }
  var scrubbing by remember { mutableStateOf(false) }

  val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }
  val audioAttributes = remember(isVideo) {
    AudioAttributes.Builder()
      .setUsage(AudioAttributes.USAGE_MEDIA)
      .setContentType(if (isVideo) AudioAttributes.CONTENT_TYPE_MOVIE else AudioAttributes.CONTENT_TYPE_SPEECH)
      .build()
  }
  val focusListener = remember {
    AudioManager.OnAudioFocusChangeListener { change ->
      if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
        player?.let { mp ->
          runCatching {
            if (mp.isPlaying) {
              mp.pause()
              playing = false
            }
          }
        }
      }
    }
  }
  val focusRequest = remember(audioAttributes) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(audioAttributes)
        .setOnAudioFocusChangeListener(focusListener)
        .build()
    } else null
  }

  fun requestFocus() {
    val am = audioManager ?: return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
      runCatching { am.requestAudioFocus(focusRequest) }
    } else {
      @Suppress("DEPRECATION")
      runCatching { am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) }
    }
  }

  fun abandonFocus() {
    val am = audioManager ?: return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
      runCatching { am.abandonAudioFocusRequest(focusRequest) }
    } else {
      @Suppress("DEPRECATION")
      runCatching { am.abandonAudioFocus(focusListener) }
    }
  }

  fun seekPlayer(mp: MediaPlayer, targetMs: Long) {
    val clamped = clampedSeekMs(targetMs, durationMs)
    positionMs = clamped
    runCatching { mp.seekTo(clamped, MediaPlayer.SEEK_CLOSEST) }
  }

  LaunchedEffect(file.uri, momentMs, hidden) {
    if (hidden) return@LaunchedEffect
    var active = true
    var initialSeekPending = true
    val mp = MediaPlayer()
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_PAUSE && active) {
        runCatching {
          if (mp.isPlaying) {
            mp.pause()
            playing = false
            abandonFocus()
          }
        }
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    try {
      mp.setAudioAttributes(audioAttributes)
      mp.setOnVideoSizeChangedListener { _, w, h ->
        if (active && w > 0 && h > 0) videoAspect = (w.toFloat() / h.toFloat()).coerceIn(0.55f, 2.4f)
      }
      mp.setOnSeekCompleteListener { p ->
        if (!active) return@setOnSeekCompleteListener
        if (initialSeekPending) {
          initialSeekPending = false
          runCatching {
            requestFocus()
            p.start()
            playing = true
          }
        }
        positionMs = runCatching { p.currentPosition.toLong().coerceAtLeast(0L) }.getOrDefault(positionMs)
      }
      mp.setOnPreparedListener { p ->
        if (!active) return@setOnPreparedListener
        val dur = p.duration.toLong().coerceAtLeast(0L)
        if (dur > 0L) durationMs = dur
        prepared = true
        val startAt = clampedSeekMs(initialSeekMs, dur)
        positionMs = startAt
        videoSurface?.let { s -> if (s.isValid) runCatching { p.setSurface(s) } }
        if (startAt > 0L) {
          runCatching { p.seekTo(startAt, MediaPlayer.SEEK_CLOSEST) }.onFailure {
            initialSeekPending = false
            runCatching { requestFocus(); p.start(); playing = true }
          }
        } else {
          initialSeekPending = false
          runCatching { requestFocus(); p.start(); playing = true }
        }
      }
      mp.setOnCompletionListener {
        if (active) {
          playing = false
          abandonFocus()
          if (durationMs > 0L) positionMs = durationMs
        }
      }
      mp.setOnErrorListener { _, _, _ ->
        if (active) {
          failed = true
          playing = false
          abandonFocus()
        }
        true
      }
      withContext(Dispatchers.IO) {
        mp.setDataSource(context, file.uri.toUri())
      }
      player = mp
      mp.prepareAsync()
      kotlinx.coroutines.awaitCancellation()
    } catch (e: CancellationException) {
      throw e
    } catch (_: Throwable) {
      if (active) failed = true
    } finally {
      active = false
      lifecycleOwner.lifecycle.removeObserver(observer)
      abandonFocus()
      player = null
      runCatching { mp.setSurface(null) }
      runCatching { if (mp.isPlaying) mp.stop() }
      runCatching { mp.release() }
    }
  }

  LaunchedEffect(player, prepared, playing, scrubbing) {
    val mp = player ?: return@LaunchedEffect
    while (prepared && playing) {
      if (!scrubbing) {
        positionMs = runCatching { mp.currentPosition.toLong().coerceAtLeast(0L) }.getOrDefault(positionMs)
      }
      delay(200)
    }
  }

  Column(
    Modifier
      .fillMaxWidth()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = Sun.Gutter)
      .padding(bottom = Sun.S16)
      .navigationBarsPadding(),
    verticalArrangement = Arrangement.spacedBy(Sun.S12),
  ) {
    Row(
      Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(Sun.S8),
    ) {
      Text(
        file.name,
        style = SunType.BodyLarge.copy(fontWeight = FontWeight.Bold),
        color = t.ink,
        maxLines = 2,
        overflow = TextOverflow.MiddleEllipsis,
        modifier = Modifier.weight(1f),
      )
      Pill(stringResource(R.string.badge_at_time, formatDuration(momentMs)), strong = true)
    }

    if (hidden) {
      Column(
        Modifier
          .fillMaxWidth()
          .clip(MaterialTheme.shapes.small)
          .background(t.paper2)
          .border(Sun.Border, t.line, MaterialTheme.shapes.small)
          .padding(Sun.S16),
        verticalArrangement = Arrangement.spacedBy(Sun.S12),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Text(stringResource(R.string.preview_hidden), style = SunType.Body, color = t.ink2)
        Row(
          Modifier
            .heightIn(min = Sun.Target)
            .clip(MaterialTheme.shapes.small)
            .background(t.action)
            .clickable(role = Role.Button, onClick = onReveal)
            .padding(horizontal = Sun.S16, vertical = Sun.S12),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(Sun.S8),
        ) {
          Icon(Icons.Outlined.Visibility, contentDescription = null, tint = t.onAction, modifier = Modifier.size(18.dp))
          Text(stringResource(R.string.action_show_preview), style = SunType.Label, color = t.onAction)
        }
      }
      return
    }

    if (isVideo) {
      Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
          Modifier
            .heightIn(max = 320.dp)
            .aspectRatio(videoAspect)
            .clip(MaterialTheme.shapes.small)
            .background(Color.Black),
          contentAlignment = Alignment.Center,
        ) {
          if (failed) {
            Text(stringResource(R.string.player_error), style = SunType.Body, color = Color.White, modifier = Modifier.padding(Sun.S16))
          } else {
            AndroidView(
              factory = { ctx ->
                TextureView(ctx).apply {
                  surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                      val s = Surface(st)
                      videoSurface = s
                      player?.let { mp -> runCatching { mp.setSurface(s) } }
                    }
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                      player?.let { mp -> runCatching { mp.setSurface(null) } }
                      videoSurface?.let { runCatching { it.release() } }
                      videoSurface = null
                      return true
                    }
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                  }
                }
              },
              onRelease = {
                player?.let { mp -> runCatching { mp.setSurface(null) } }
                videoSurface?.let { runCatching { it.release() } }
                videoSurface = null
              },
              modifier = Modifier.fillMaxSize(),
            )
            if (!prepared) {
              Text(stringResource(R.string.player_loading), style = SunType.Caption, color = Color.White)
            }
          }
        }
      }
    } else {
      Row(
        Modifier
          .fillMaxWidth()
          .clip(MaterialTheme.shapes.small)
          .background(t.paper2)
          .border(Sun.Border, t.line2, MaterialTheme.shapes.small)
          .padding(Sun.S12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Sun.S12),
      ) {
        Icon(Icons.Outlined.AudioFile, contentDescription = null, tint = t.ink, modifier = Modifier.size(28.dp))
        Text(
          when {
            failed -> stringResource(R.string.player_error)
            !prepared -> stringResource(R.string.player_loading)
            else -> stringResource(R.string.strip_peek_around, formatDuration(momentMs))
          },
          style = SunType.Body,
          color = t.ink2,
        )
      }
    }

    if (!failed) {
      val maxSlider = durationMs.coerceAtLeast(1L).toFloat()
      val seekDescription = stringResource(R.string.player_seek_position)
      Column(Modifier.fillMaxWidth()) {
        Slider(
          value = positionMs.coerceIn(0L, maxSlider.toLong()).toFloat(),
          onValueChange = {
            scrubbing = true
            positionMs = it.toLong()
          },
          onValueChangeFinished = {
            scrubbing = false
            player?.let { mp -> if (prepared) seekPlayer(mp, positionMs) }
          },
          enabled = prepared,
          valueRange = 0f..maxSlider,
          modifier = Modifier.semantics { contentDescription = seekDescription },
          colors = SliderDefaults.colors(
            thumbColor = t.action,
            activeTrackColor = t.action,
            inactiveTrackColor = t.line2.copy(alpha = 0.4f),
          ),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          Text(formatDuration(positionMs), style = SunType.Caption, color = t.ink2)
          Text(formatDuration(durationMs), style = SunType.Caption, color = t.ink2)
        }
      }

      FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Sun.S12, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(Sun.S8, Alignment.CenterVertically),
      ) {
        IconButton(
          onClick = { player?.let { mp -> if (prepared) seekPlayer(mp, positionMs - 10_000L) } },
          enabled = prepared,
        ) {
          Icon(Icons.Outlined.Replay10, contentDescription = stringResource(R.string.player_rewind_10), tint = t.ink)
        }
        Box(
          Modifier
            .size(Sun.Target)
            .clip(CircleShape)
            .background(t.action)
            .clickable(
              enabled = prepared,
              role = Role.Button,
              onClickLabel = stringResource(if (playing) R.string.player_pause else R.string.player_play),
            ) {
              val mp = player ?: return@clickable
              runCatching {
                if (mp.isPlaying) {
                  mp.pause()
                  playing = false
                  abandonFocus()
                } else {
                  requestFocus()
                  mp.start()
                  playing = true
                }
              }
            },
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = stringResource(if (playing) R.string.player_pause else R.string.player_play),
            tint = t.onAction,
            modifier = Modifier.size(28.dp),
          )
        }
        IconButton(
          onClick = { player?.let { mp -> if (prepared) seekPlayer(mp, positionMs + 10_000L) } },
          enabled = prepared,
        ) {
          Icon(Icons.Outlined.Forward10, contentDescription = stringResource(R.string.player_forward_10), tint = t.ink)
        }
        Row(
          Modifier
            .heightIn(min = Sun.Target)
            .clip(MaterialTheme.shapes.small)
            .background(t.marker)
            .border(1.dp, t.onMarker, MaterialTheme.shapes.small)
            .clickable(enabled = prepared, role = Role.Button) {
              player?.let { mp -> seekPlayer(mp, initialSeekMs) }
            }
            .padding(horizontal = Sun.S12, vertical = Sun.S8),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            stringResource(R.string.player_jump_match, formatDuration(momentMs)),
            style = SunType.CaptionBold,
            color = t.onMarker,
          )
        }
      }
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Sun.S8)) {
      PlayerActionButton(
        icon = Icons.AutoMirrored.Outlined.OpenInNew,
        label = stringResource(R.string.player_open_external),
        onClick = { onOpenExternal(positionMs) },
        modifier = Modifier.weight(1f),
      )
      PlayerActionButton(
        icon = Icons.Outlined.Share,
        label = stringResource(R.string.action_share),
        onClick = onShare,
        modifier = Modifier.weight(1f),
      )
    }
  }
}

@Composable
private fun PlayerActionButton(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  label: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val t = LocalSunlight.current
  val shape = MaterialTheme.shapes.small
  Row(
    modifier
      .heightIn(min = Sun.Target)
      .clip(shape)
      .background(t.paper, shape)
      .border(Sun.Border, t.line, shape)
      .clickable(role = Role.Button, onClick = onClick)
      .padding(horizontal = Sun.S12, vertical = Sun.S8),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(Sun.S8, Alignment.CenterHorizontally),
  ) {
    Icon(icon, contentDescription = null, tint = t.ink, modifier = Modifier.size(18.dp))
    Text(label, style = SunType.Label, color = t.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
  }
}
