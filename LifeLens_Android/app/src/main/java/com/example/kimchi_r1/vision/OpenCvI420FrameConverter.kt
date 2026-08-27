package com.example.kimchi_r1.vision

import android.graphics.Bitmap
import java.nio.ByteBuffer
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/** DAT의 비압축 I420 프레임을 OpenCV로 Android Bitmap으로 변환한다. */
class OpenCvI420FrameConverter {
  private var initialized: Boolean? = null
  private var frameWidth = 0
  private var frameHeight = 0
  private var i420Bytes = ByteArray(0)
  private var i420: Mat? = null
  private var rgba: Mat? = null
  private var bitmapPool = emptyArray<Bitmap>()
  private var nextBitmapIndex = 0

  /** Converts into a small rotating pool: no per-frame ByteArray, Mat, or Bitmap allocation. */
  @Synchronized
  fun toBitmap(buffer: ByteBuffer, width: Int, height: Int): Bitmap? {
    if (initialized == null) initialized = OpenCVLoader.initLocal()
    if (initialized != true || width <= 0 || height <= 0) return null
    val expectedSize = width * height * 3 / 2
    if (buffer.remaining() < expectedSize) return null
    ensureBuffers(width, height, expectedSize)

    val readable = buffer.duplicate()
    readable.get(i420Bytes, 0, expectedSize)
    val i420Mat = i420 ?: return null
    val rgbaMat = rgba ?: return null
    i420Mat.put(0, 0, i420Bytes, 0, expectedSize)
    Imgproc.cvtColor(i420Mat, rgbaMat, Imgproc.COLOR_YUV2RGBA_I420)
    val bitmap = bitmapPool[nextBitmapIndex]
    nextBitmapIndex = (nextBitmapIndex + 1) % bitmapPool.size
    Utils.matToBitmap(rgbaMat, bitmap)
    return bitmap
  }

  @Synchronized
  fun close() {
    i420?.release()
    rgba?.release()
    i420 = null
    rgba = null
    bitmapPool.forEach { it.recycle() }
    bitmapPool = emptyArray()
    i420Bytes = ByteArray(0)
  }

  private fun ensureBuffers(width: Int, height: Int, expectedSize: Int) {
    if (width == frameWidth && height == frameHeight && bitmapPool.isNotEmpty()) return
    i420?.release()
    rgba?.release()
    bitmapPool.forEach { it.recycle() }
    frameWidth = width
    frameHeight = height
    i420Bytes = ByteArray(expectedSize)
    i420 = Mat(height + height / 2, width, CvType.CV_8UC1)
    rgba = Mat(height, width, CvType.CV_8UC4)
    bitmapPool = Array(BITMAP_POOL_SIZE) { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }
    nextBitmapIndex = 0
  }

  private companion object {
    const val BITMAP_POOL_SIZE = 4
  }
}
