package com.brenyd.audiread.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * Audiobooks are usually low-bitrate HE-AAC (AAC with SBR / parametric stereo). Some phones' hardware
 * AAC decoders mishandle it and output at half the sample rate, so narration plays slow and very deep.
 * Android's own software decoders handle every AAC profile correctly and are cheap to run for audio,
 * so they are tried first; the device decoders stay as fallbacks.
 */
@OptIn(UnstableApi::class)
object AudioDecoders {
    private fun isSoftware(info: MediaCodecInfo): Boolean {
        val n = info.name.lowercase()
        return n.startsWith("c2.android.") || n.startsWith("omx.google.") || info.softwareOnly
    }

    private val selector = MediaCodecSelector { mimeType, requiresSecure, requiresTunneling ->
        val all = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecure, requiresTunneling)
        if (MimeTypes.isAudio(mimeType)) all.sortedByDescending { isSoftware(it) } else all
    }

    fun renderersFactory(ctx: Context): DefaultRenderersFactory =
        DefaultRenderersFactory(ctx)
            .setMediaCodecSelector(selector)
            .setEnableDecoderFallback(true)
}
