/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// HevcDecoder - On-device HEVC decode for the live preview
//
// Decodes the compressed HEVC frames delivered by the DAT SDK and renders them directly
// to a Surface via MediaCodec (the GPU handles YUV->RGB). Replicates the SDK's
// VideoDecoder NAL-parsing / enqueue logic: parse NAL units, cache config, activate on
// the first keyframe, then feed each NAL separately.

package com.example.kimchi_r1.stream

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class HevcDecoder(
    private val onFrameRendered: () -> Unit = {},
    private val onFatalError: (Throwable) -> Unit = {},
) {

  companion object {
    private const val TAG = "HevcDecoder"
    // A large queue makes a live preview faithfully play stale frames. Keep only a short runway;
    // when decoding falls behind enqueuePrivate drops the oldest NAL instead of adding latency.
    private const val DATA_QUEUE_CAPACITY = 12
    private val BLOCKED_DECODERS = setOf("OMX.Exynos.hevc.dec", "c2.mtk.hevc.decoder")
  }

  private class DecoderFrame(
      val data: ByteBuffer,
      val offset: Int = 0,
      val size: Int = data.remaining(),
      val presentationTimeUs: Long = 0L,
      val isKeyFrame: Boolean = false,
      val isConfigFrame: Boolean = false,
  ) {
    val flags: Int
      get() {
        var bitmask = 0
        if (isKeyFrame) bitmask = bitmask or MediaCodec.BUFFER_FLAG_KEY_FRAME
        if (isConfigFrame) bitmask = bitmask or MediaCodec.BUFFER_FLAG_CODEC_CONFIG
        return bitmask
      }
  }

  // These references are torn down from stop() on the caller thread while MediaCodec callbacks may
  // still fire on the decoder thread. @Volatile is sufficient: each is a single reference assigned
  // on setup and nulled on teardown — no compound state, only publication visibility.
  @Volatile private var decoder: MediaCodec? = null
  @Volatile private var decoderThread: HandlerThread? = null
  private val incomingDataQueue = LinkedBlockingQueue<DecoderFrame>(DATA_QUEUE_CAPACITY)

  @Volatile private var mediaFormat: MediaFormat? = null
  @Volatile private var cachedVideoCodec: ByteBuffer? = null
  @Volatile private var active = false
  @Volatile private var firstInputFrame = true
  @Volatile private var receivedKeyframe = false
  @Volatile private var dropUntilKeyframe = false
  @Volatile private var outputSurface: Surface? = null

  fun start(width: Int, height: Int, surface: Surface) {
    outputSurface = surface
    mediaFormat =
        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height).also { format
          ->
          format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
          format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
          format.setInteger(MediaFormat.KEY_BIT_RATE, 750000)
          format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height)
          format.setInteger(MediaFormat.KEY_PRIORITY, 0)
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
          }
        }
    try {
      ensureCodecsCreated()
    } catch (e: Exception) {
      Log.e(TAG, "Failed to create HEVC decoder: ${e.message}", e)
      onFatalError(e)
    }
  }

  fun decodeFrame(data: ByteArray, presentationTimeUs: Long) {
    if (data.isEmpty()) return

    // A DAT VideoFrame is one complete Annex-B access unit. Scan only to derive flags; splitting
    // NAL units before MediaCodec destroys frame boundaries on vendor hardware decoders.
    var hasKeyFrame = false
    var hasConfig = false
    var hasVcl = false
    var index = 0
    val prefixFlags = BooleanArray(3)

    index = findNalUnit(data, index, data.size, prefixFlags)
    while (index < data.size) {
      val unitType = getH265NalUnitType(data, index)
      hasKeyFrame = hasKeyFrame || isIrapNalType(unitType)
      hasConfig = hasConfig || unitType in 32..34
      hasVcl = hasVcl || unitType in 0..31
      index = findNalUnit(data, index + 1, data.size, prefixFlags)
    }

    val configOnly = hasConfig && !hasVcl
    if (configOnly) cachedVideoCodec = ByteBuffer.wrap(data.copyOf())
    if (hasKeyFrame) {
      if (!active) {
        active = true
        cachedVideoCodec?.let(::enqueuePublic)
      }
      receivedKeyframe = true
    }
    enqueuePrivate(
        DecoderFrame(
            data = ByteBuffer.wrap(data),
            presentationTimeUs = presentationTimeUs,
            isKeyFrame = hasKeyFrame,
            isConfigFrame = configOnly,
        )
    )
  }

  fun stop() {
    active = false
    incomingDataQueue.clear()
    try {
      decoder?.stop()
      decoder?.release()
    } catch (e: Exception) {
      Log.e(TAG, "Error stopping decoder: ${e.message}", e)
    }
    decoder = null
    decoderThread?.quit()
    decoderThread = null
    firstInputFrame = true
    receivedKeyframe = false
    dropUntilKeyframe = false
    cachedVideoCodec = null
    outputSurface = null
  }

  // Mirrors SDK VideoDecoder's public enqueue(ByteBuffer) — recursive entry for cached config
  private fun enqueuePublic(buffer: ByteBuffer) {
    val readable = buffer.duplicate().apply { rewind() }
    enqueuePrivate(DecoderFrame(data = readable, isConfigFrame = true))
  }

  // Mirrors SDK VideoDecoder's private enqueue(VideoFrame)
  private fun enqueuePrivate(frame: DecoderFrame) {
    if (!active) return
    if (!frame.isConfigFrame && !receivedKeyframe) return
    if (dropUntilKeyframe && !frame.isKeyFrame && !frame.isConfigFrame) return
    if (frame.isKeyFrame) dropUntilKeyframe = false
    if (firstInputFrame) {
      firstInputFrame = false
      activateDecoder()
    }
    if (!incomingDataQueue.offer(frame)) {
      incomingDataQueue.clear()
      dropUntilKeyframe = !frame.isKeyFrame
      if (frame.isKeyFrame || frame.isConfigFrame) incomingDataQueue.offer(frame)
      Log.w(TAG, "Decoder behind; waiting for a fresh keyframe")
    }
  }

  private fun ensureCodecsCreated() {
    if (decoder == null) {
      decoder = createHevcDecoder()
    }
  }

  // Complete access units allow a compatible hardware decoder to provide the lowest latency.
  private fun createHevcDecoder(): MediaCodec {
    val mime = MediaFormat.MIMETYPE_VIDEO_HEVC
    val candidates =
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { info ->
          !info.isEncoder &&
              !info.isAlias &&
              info.name !in BLOCKED_DECODERS &&
              info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
    val selected = candidates.firstOrNull { it.isHardwareAccelerated }
        ?: candidates.firstOrNull { it.isSoftwareOnly }
        ?: error("No compatible HEVC decoder")
    Log.i(
        TAG,
        "Using ${if (selected.isHardwareAccelerated) "hardware" else "software"} HEVC decoder: ${selected.name}",
    )
    return MediaCodec.createByCodecName(selected.name)
  }

  private fun activateDecoder() {
    try {
      ensureCodecsCreated()
      decoder?.let { codec ->
        decoderThread?.quit()
        val thread = HandlerThread("HevcDecoderThread", Process.THREAD_PRIORITY_VIDEO)
        thread.start()
        decoderThread = thread

        codec.reset()
        codec.configure(mediaFormat, outputSurface, null, 0)
        codec.setCallback(
            object : MediaCodec.Callback() {
              override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                onInputBuffer(codec, index)
              }

              override fun onOutputBufferAvailable(
                  codec: MediaCodec,
                  index: Int,
                  info: MediaCodec.BufferInfo,
              ) {
                onOutputBuffer(codec, index, info)
              }

              override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Log.e(TAG, "Codec error: ${e.message}")
                active = false
                onFatalError(e)
              }

              override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}
            },
            // Deliver MediaCodec callbacks on our own decoder thread.
            Handler(thread.looper),
        )
        codec.start()
      }
    } catch (e: MediaCodec.CodecException) {
      Log.e(TAG, "Decoder activation codec exception: ${e.message}", e)
      onFatalError(e)
    } catch (e: Throwable) {
      Log.e(TAG, "Decoder activation exception: ${e.message}", e)
      onFatalError(e)
    }
  }

  // Mirrors VideoDecoderBufferHandler.onInputBuffer — feeds ENTIRE buffer with offset
  private fun onInputBuffer(codec: MediaCodec, index: Int) {
    var bufferQueued = false
    try {
      val inputBuffer = codec.getInputBuffer(index)
      // Input/output callbacks share one thread. A tiny wait avoids a hot callback loop without
      // holding decoded output for the previous one-second timeout.
      val frame = incomingDataQueue.poll(2, TimeUnit.MILLISECONDS)

      if (frame == null || inputBuffer == null || !active) {
        codec.queueInputBuffer(index, 0, 0, 0, 0)
        bufferQueued = true
        return
      }

      frame.data.rewind()
      inputBuffer.clear()
      inputBuffer.put(frame.data)
      inputBuffer.flip()
      val clampedSize = minOf(frame.size, inputBuffer.limit() - frame.offset)
      codec.queueInputBuffer(
          index,
          frame.offset,
          clampedSize,
          frame.presentationTimeUs,
          frame.flags,
      )
      bufferQueued = true
    } catch (e: Throwable) {
      Log.e(TAG, "Input buffer error: ${e.message}", e)
      if (active) {
        active = false
        onFatalError(e)
      }
    } finally {
      if (!bufferQueued) {
        try {
          codec.queueInputBuffer(index, 0, 0, 0, 0)
        } catch (_: Throwable) {}
      }
    }
  }

  private fun onOutputBuffer(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
    try {
      if (!active || info.size == 0) {
        codec.releaseOutputBuffer(index, false)
        return
      }
      // Render directly to the Surface — the GPU handles YUV→RGB conversion.
      codec.releaseOutputBuffer(index, true)
      onFrameRendered()
    } catch (e: Throwable) {
      Log.e(TAG, "Output buffer error: ${e.message}", e)
      try {
        codec.releaseOutputBuffer(index, false)
      } catch (_: Throwable) {}
      if (active) {
        active = false
        onFatalError(e)
      }
    }
  }

  // --- NalUnitUtil (copied from SDK) ---

  private fun findNalUnit(
      data: ByteArray,
      startOffset: Int,
      endOffset: Int,
      prefixFlags: BooleanArray,
  ): Int {
    val length = endOffset - startOffset
    if (length == 0) return endOffset

    when {
      prefixFlags[0] -> {
        clearPrefixFlags(prefixFlags)
        return startOffset - 3
      }
      length > 1 && prefixFlags[1] && data[startOffset].toInt() == 1 -> {
        clearPrefixFlags(prefixFlags)
        return startOffset - 2
      }
      length > 2 &&
          prefixFlags[2] &&
          data[startOffset].toInt() == 0 &&
          data[startOffset + 1].toInt() == 1 -> {
        clearPrefixFlags(prefixFlags)
        return startOffset - 1
      }
    }

    val limit = endOffset - 1
    var i = startOffset + 2
    while (i < limit) {
      if ((data[i].toInt() and 0xFE) != 0) {
        // no NAL prefix here or next two positions
      } else if (data[i - 2].toInt() == 0 && data[i - 1].toInt() == 0 && data[i].toInt() == 1) {
        clearPrefixFlags(prefixFlags)
        return i - 2
      } else {
        i -= 2
      }
      i += 3
    }

    prefixFlags[0] =
        if (length > 2)
            (data[endOffset - 3].toInt() == 0 &&
                data[endOffset - 2].toInt() == 0 &&
                data[endOffset - 1].toInt() == 1)
        else
            (if (length == 2)
                (prefixFlags[2] &&
                    data[endOffset - 2].toInt() == 0 &&
                    data[endOffset - 1].toInt() == 1)
            else (prefixFlags[1] && data[endOffset - 1].toInt() == 1))
    prefixFlags[1] =
        if (length > 1) (data[endOffset - 2].toInt() == 0 && data[endOffset - 1].toInt() == 0)
        else (prefixFlags[2] && data[endOffset - 1].toInt() == 0)
    prefixFlags[2] = data[endOffset - 1].toInt() == 0

    return endOffset
  }

  private fun clearPrefixFlags(prefixFlags: BooleanArray) {
    prefixFlags[0] = false
    prefixFlags[1] = false
    prefixFlags[2] = false
  }

  private fun getH265NalUnitType(data: ByteArray, offset: Int): Int {
    if (offset + 3 >= data.size) return -1
    return (data[offset + 3].toInt() and 0x7E) shr 1
  }

  // Any IRAP picture (BLA 16-18, IDR 19-20, CRA 21) is a decode-refresh point the decoder can
  // (re)activate on. Detecting only IDR misses CRA keyframes some encoders emit, which leaves the
  // preview black after the decoder deactivates on a full queue. Must match the recorder's keyframe
  // definition so preview and recording stay consistent.
  private fun isIrapNalType(unitType: Int): Boolean = unitType in 16..21

  private fun cloneByteBuffer(original: ByteBuffer): ByteBuffer {
    val clone: ByteBuffer =
        if (original.isDirect) ByteBuffer.allocateDirect(original.capacity())
        else ByteBuffer.allocate(original.capacity())
    original.rewind()
    clone.put(original)
    original.rewind()
    clone.flip()
    return clone
  }
}
