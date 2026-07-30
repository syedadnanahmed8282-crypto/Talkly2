package com.family.talkly.ui.components

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.family.talkly.ui.theme.SecondaryLightSage
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

@Composable
fun TalklyVideoPlayer(
    videoUrl: String,
    modifier: Modifier = Modifier,
    autoPlay: Boolean = true
) {
    val context = LocalContext.current
    var videoViewRef by remember { mutableStateOf<VideoView?>(null) }
    var isPlaying by remember { mutableStateOf(autoPlay) }
    var isEnded by remember { mutableStateOf(false) }
    var currentPosMs by remember { mutableIntStateOf(0) }
    var durationMs by remember { mutableIntStateOf(1) }
    var isSeeking by remember { mutableStateOf(false) }
    var sliderPos by remember { mutableFloatStateOf(0f) }
    var showControls by remember { mutableStateOf(true) }

    // Parse URI from HTTP, Base64, or File
    val parsedUri = remember(videoUrl) {
        resolveVideoUri(context, videoUrl)
    }

    // Timer loop to update position while playing
    LaunchedEffect(isPlaying, isSeeking) {
        while (isPlaying && !isSeeking) {
            videoViewRef?.let { vv ->
                if (vv.isPlaying) {
                    currentPosMs = vv.currentPosition
                    if (durationMs > 0) {
                        sliderPos = (currentPosMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                    }
                }
            }
            delay(250)
        }
    }

    // Auto hide controls after 4s
    LaunchedEffect(showControls, isPlaying) {
        if (showControls && isPlaying) {
            delay(4000)
            showControls = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                showControls = !showControls
            },
        contentAlignment = Alignment.Center
    ) {
        if (parsedUri != null) {
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        setVideoURI(parsedUri)
                        setOnPreparedListener { mp ->
                            durationMs = if (mp.duration > 0) mp.duration else 1
                            mp.isLooping = false
                            if (autoPlay) {
                                start()
                                isPlaying = true
                            }
                        }
                        setOnCompletionListener {
                            isPlaying = false
                            isEnded = true
                            currentPosMs = durationMs
                            sliderPos = 1f
                            showControls = true
                        }
                        setOnErrorListener { _, _, _ ->
                            Log.e("TalklyVideoPlayer", "Error playing video URI: $parsedUri")
                            true
                        }
                        videoViewRef = this
                    }
                },
                update = { vv ->
                    videoViewRef = vv
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = "Unable to load video source",
                color = Color.White,
                fontSize = 14.sp
            )
        }

        // Overlay Controls
        AnimatedVisibility(
            visible = showControls || !isPlaying,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
            ) {
                // Center Controls Row: Rewind 10s | Play/Pause/Replay | Forward 10s
                Row(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Rewind 10s (-10 Sec)
                    IconButton(
                        onClick = {
                            videoViewRef?.let { vv ->
                                val targetMs = (vv.currentPosition - 10000).coerceAtLeast(0)
                                vv.seekTo(targetMs)
                                currentPosMs = targetMs
                                if (durationMs > 0) {
                                    sliderPos = (targetMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                                }
                                if (isEnded) isEnded = false
                            }
                        },
                        modifier = Modifier
                            .size(52.dp)
                            .background(Color.White.copy(alpha = 0.25f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FastRewind,
                            contentDescription = "Rewind 10s",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(28.dp))

                    // Play / Pause / Replay Button
                    IconButton(
                        onClick = {
                            videoViewRef?.let { vv ->
                                if (isEnded) {
                                    vv.seekTo(0)
                                    vv.start()
                                    isPlaying = true
                                    isEnded = false
                                } else if (vv.isPlaying) {
                                    vv.pause()
                                    isPlaying = false
                                } else {
                                    vv.start()
                                    isPlaying = true
                                }
                            }
                        },
                        modifier = Modifier
                            .size(68.dp)
                            .background(SecondaryLightSage, CircleShape)
                    ) {
                        Icon(
                            imageVector = when {
                                isEnded -> Icons.Default.Replay
                                isPlaying -> Icons.Default.Pause
                                else -> Icons.Default.PlayArrow
                            },
                            contentDescription = "Play/Pause",
                            tint = Color.Black,
                            modifier = Modifier.size(40.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(28.dp))

                    // Fast Forward 10s (+10 Sec)
                    IconButton(
                        onClick = {
                            videoViewRef?.let { vv ->
                                val targetMs = (vv.currentPosition + 10000).coerceAtMost(durationMs)
                                vv.seekTo(targetMs)
                                currentPosMs = targetMs
                                if (durationMs > 0) {
                                    sliderPos = (targetMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                                }
                            }
                        },
                        modifier = Modifier
                            .size(52.dp)
                            .background(Color.White.copy(alpha = 0.25f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FastForward,
                            contentDescription = "Forward 10s",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                // Bottom Timeline & Seeking Bar
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 20.dp, vertical = 24.dp)
                ) {
                    // Time Counter Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatTimeMs(currentPosMs),
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = formatTimeMs(durationMs),
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Interactive Seeking Slider Bar
                    Slider(
                        value = sliderPos,
                        onValueChange = { newVal ->
                            isSeeking = true
                            sliderPos = newVal
                        },
                        onValueChangeFinished = {
                            videoViewRef?.let { vv ->
                                val targetMs = (sliderPos * durationMs).toInt().coerceIn(0, durationMs)
                                vv.seekTo(targetMs)
                                currentPosMs = targetMs
                                if (isEnded && targetMs < durationMs) isEnded = false
                            }
                            isSeeking = false
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = SecondaryLightSage,
                            activeTrackColor = SecondaryLightSage,
                            inactiveTrackColor = Color.White.copy(alpha = 0.35f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }

    DisposableEffect(parsedUri) {
        onDispose {
            try {
                videoViewRef?.stopPlayback()
            } catch (e: Exception) { }
        }
    }
}

private fun resolveVideoUri(context: Context, url: String): Uri? {
    if (url.isBlank()) return null
    return try {
        if (url.startsWith("data:") || url.startsWith("base64:")) {
            val commaIndex = url.indexOf(",")
            val base64Str = if (commaIndex != -1) url.substring(commaIndex + 1) else url.removePrefix("base64:")
            val bytes = Base64.decode(base64Str, Base64.DEFAULT)
            val cacheFile = File(context.cacheDir, "temp_video_play_${url.hashCode()}.mp4")
            if (!cacheFile.exists()) {
                FileOutputStream(cacheFile).use { fos ->
                    fos.write(bytes)
                    fos.flush()
                }
            }
            Uri.fromFile(cacheFile)
        } else if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("content://") || url.startsWith("file://")) {
            Uri.parse(url)
        } else {
            Uri.fromFile(File(url))
        }
    } catch (e: Exception) {
        Log.e("TalklyVideoPlayer", "Error resolving video URI: ${e.localizedMessage}")
        null
    }
}

private fun formatTimeMs(ms: Int): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}
