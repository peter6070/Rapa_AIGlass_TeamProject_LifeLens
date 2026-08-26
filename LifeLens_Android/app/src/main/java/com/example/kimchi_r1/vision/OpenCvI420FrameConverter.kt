package com.example.kimchi_r1.vision

import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/** DAT의 비압축 I420 프레임을 OpenCV로 Android Bitmap으로 변환한다. */
class OpenCvI420FrameConverter {
  private var initialized: Boolean? = null

  fun toBitmap(data: ByteArray, width: Int, height: Int): Bitmap? {
    if (initialized == null) initialized = OpenCVLoader.initLocal()
    if (initialized != true || width <= 0 || height <= 0) return null
    val expectedSize = width * height * 3 / 2
    if (data.size < expectedSize) return null

    val i420 = Mat(height + height / 2, width, CvType.CV_8UC1)
    val rgba = Mat()
    return try {
      i420.put(0, 0, data, 0, expectedSize)
      Imgproc.cvtColor(i420, rgba, Imgproc.COLOR_YUV2RGBA_I420)
      Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
        Utils.matToBitmap(rgba, bitmap)
      }
    } finally {
      i420.release()
      rgba.release()
    }
  }
}
