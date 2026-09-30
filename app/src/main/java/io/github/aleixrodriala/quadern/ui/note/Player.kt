package io.github.aleixrodriala.quadern.ui.note

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import kotlinx.coroutines.delay

@Stable
class NotePlayer(context: Context) {
    val exo: ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()

    var playing by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    var speed by mutableFloatStateOf(1f)
        private set

    private var loaded: File? = null

    init {
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) durationMs = exo.duration.coerceAtLeast(0)
                if (state == Player.STATE_ENDED) {
                    exo.pause()
                    exo.seekTo(0)
                    positionMs = 0
                }
            }
        })
    }

    fun load(file: File) {
        if (loaded == file) return
        loaded = file
        exo.setMediaItem(MediaItem.fromUri(file.toURI().toString()))
        exo.prepare()
    }

    fun toggle() = if (exo.isPlaying) exo.pause() else exo.play()

    fun seekTo(fraction: Float) {
        val d = exo.duration.takeIf { it > 0 } ?: durationMs
        val target = (d * fraction).toLong()
        exo.seekTo(target)
        positionMs = target
    }

    fun skip(deltaMs: Long) {
        val target = (exo.currentPosition + deltaMs).coerceIn(0, exo.duration.coerceAtLeast(0))
        exo.seekTo(target)
        positionMs = target
    }

    fun cycleSpeed() {
        speed = when (speed) {
            1f -> 1.5f
            1.5f -> 2f
            else -> 1f
        }
        exo.playbackParameters = PlaybackParameters(speed)
    }

    internal fun poll() {
        positionMs = exo.currentPosition
        if (durationMs <= 0 && exo.duration > 0) durationMs = exo.duration
    }

    fun release() = exo.release()
}

@Composable
fun rememberNotePlayer(): NotePlayer {
    val context = LocalContext.current
    val player = remember { NotePlayer(context) }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(player.playing) {
        while (player.playing) {
            player.poll()
            delay(50)
        }
        player.poll()
    }
    return player
}
