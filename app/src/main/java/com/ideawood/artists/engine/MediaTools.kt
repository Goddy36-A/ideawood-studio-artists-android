package com.ideawood.artists.engine

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSessionCompleteCallback
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.StatisticsCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class MediaInfo(val hasAudio: Boolean, val hasVideo: Boolean, val duration: Double)

class FfmpegResult(val ok: Boolean, val cancelled: Boolean, val log: String)

/** Thin wrapper over FFmpegKit: progress, cancellation, probing. Runs ffmpeg in-process on the phone. */
object MediaTools {
    suspend fun probe(path: String): MediaInfo = withContext(Dispatchers.IO) {
        val session = FFprobeKit.getMediaInformation(path)
        val mi = session.mediaInformation ?: throw IllegalStateException("Cannot read this file.")
        val streams = mi.streams ?: emptyList()
        MediaInfo(
            hasAudio = streams.any { it.type == "audio" },
            hasVideo = streams.any { it.type == "video" },
            duration = mi.duration?.toDoubleOrNull() ?: 0.0,
        )
    }

    /** @param totalSec expected output length, used to turn ffmpeg's time counter into 0..100 */
    suspend fun run(args: List<String>, totalSec: Double, onProgress: (Double) -> Unit, cancelled: () -> Boolean): FfmpegResult =
        suspendCancellableCoroutine { cont ->
            val total = maxOf(totalSec, 0.1)
            val session = FFmpegKit.executeWithArgumentsAsync(
                args.toTypedArray(),
                FFmpegSessionCompleteCallback { s ->
                    val rc = s.returnCode
                    val log = (s.allLogsAsString ?: "").takeLast(700)
                    if (cont.isActive) cont.resume(FfmpegResult(ReturnCode.isSuccess(rc), ReturnCode.isCancel(rc), log))
                },
                null,
                StatisticsCallback { st ->
                    onProgress(minOf(99.0, st.time.toDouble() / 1000.0 / total * 100.0))
                    if (cancelled()) FFmpegKit.cancel(st.sessionId)
                },
            )
            cont.invokeOnCancellation { FFmpegKit.cancel(session.sessionId) }
        }
}
