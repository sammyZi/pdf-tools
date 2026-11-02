package com.sammyi57.pdftools

import android.content.Context
import android.graphics.Bitmap
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlinx.coroutines.suspendCancellableCoroutine
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// The live 3:4 camera: preview, page-edge detection on the analysis stream, QR reading, and full-resolution capture.
// Results arrive on the analysis thread; the screen posts them to its own state.
class DocCamera(context: Context) {
  val preview = PreviewView(context).apply {
    scaleType = PreviewView.ScaleType.FILL_CENTER // fills the space like a camera app; the photo keeps the full frame
    implementationMode = PreviewView.ImplementationMode.COMPATIBLE // TextureView: composites correctly under Compose
  }
  private val worker = Executors.newSingleThreadExecutor()
  private var provider: ProcessCameraProvider? = null
  private var capture: ImageCapture? = null
  private var frame = 0
  private var lastQr = ""

  @Volatile var detecting = true
  @Volatile var qr = false
  var onDetect: (List<Float>?) -> Unit = {}
  var onQr: (String) -> Unit = {}

  var flash = "off"
    set(value) {
      field = value
      capture?.flashMode = flashMode()
    }

  private fun flashMode() = when (flash) {
    "on" -> ImageCapture.FLASH_MODE_ON
    "auto" -> ImageCapture.FLASH_MODE_AUTO
    else -> ImageCapture.FLASH_MODE_OFF
  }

  /** Binds preview, capture and analysis to the back camera, or the front one when `front`. Rebinding switches lens. */
  suspend fun bind(owner: LifecycleOwner, front: Boolean = false) {
    val p = ProcessCameraProvider.awaitInstance(preview.context)
    val ratio = ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()
    val previewCase = Preview.Builder().setResolutionSelector(ratio).build().also { it.surfaceProvider = preview.surfaceProvider }
    val captureCase = ImageCapture.Builder().setResolutionSelector(ratio).setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).setFlashMode(flashMode()).build()
    val analysis = ImageAnalysis.Builder().setResolutionSelector(ratio).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
      .also { it.setAnalyzer(worker, ::analyze) }
    p.unbindAll()
    p.bindToLifecycle(owner, if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA, UseCaseGroup.Builder().addUseCase(previewCase).addUseCase(captureCase).addUseCase(analysis).build())
    provider = p
    capture = captureCase
  }

  fun release() {
    provider?.unbindAll()
    provider = null
    capture = null
    worker.shutdown()
  }

  private fun analyze(image: ImageProxy) {
    image.use {
      frame++
      if (qr && frame % 4 == 0) {
        val plane = it.planes[0]
        val w = it.width
        val h = it.height
        val bytes = ByteArray(w * h)
        val buf = plane.buffer
        for (row in 0 until h) { // copy luminance row by row; rowStride can exceed width
          buf.position(row * plane.rowStride)
          buf.get(bytes, row * w, w)
        }
        val text = try {
          QRCodeReader().decode(BinaryBitmap(HybridBinarizer(PlanarYUVLuminanceSource(bytes, w, h, 0, 0, w, h, false)))).text
        } catch (_: Exception) {
          null
        }
        if (text != null && text != lastQr) {
          lastQr = text
          onQr(text)
        }
      }
      if (!detecting || frame % 2 != 0 || !DocVision.ready()) return
      // Color, not just luminance: many page/table edges are a color change more than a brightness change.
      val bmp = it.toBitmap()
      val rgb = Mat()
      Utils.bitmapToMat(bmp, rgb)
      bmp.recycle()
      Imgproc.cvtColor(rgb, rgb, Imgproc.COLOR_RGBA2RGB)
      when (it.imageInfo.rotationDegrees) { // analysis frames arrive sensor-rotated; detect on the upright view
        90 -> Core.rotate(rgb, rgb, Core.ROTATE_90_CLOCKWISE)
        180 -> Core.rotate(rgb, rgb, Core.ROTATE_180)
        270 -> Core.rotate(rgb, rgb, Core.ROTATE_90_COUNTERCLOCKWISE)
      }
      val corners = PageDetector.detect(rgb)
      rgb.release()
      onDetect(corners?.map { c -> c.toFloat() })
    }
  }

  /** A full-resolution photo, upright, saved as a JPEG in the cache with the page found in it. */
  suspend fun takePicture(): Photo = suspendCancellableCoroutine { cont ->
    val c = capture ?: return@suspendCancellableCoroutine cont.resumeWithException(IllegalStateException(appContext.getString(R.string.error_camera_not_ready)))
    c.takePicture(worker, object : ImageCapture.OnImageCapturedCallback() {
      override fun onCaptureSuccess(image: ImageProxy) {
        try {
          val degrees = image.imageInfo.rotationDegrees.toFloat()
          val bmp = DocVision.rotate(image.toBitmap(), degrees)
          image.close()
          val file = DocVision.newFile(File(appContext.cacheDir, "doc-scans"))
          FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
          val corners = DocVision.detectBitmap(bmp)?.map { it.toFloat() }
          cont.resume(Photo(file.path, bmp.width, bmp.height, corners))
          bmp.recycle()
        } catch (e: Exception) {
          cont.resumeWithException(e)
        }
      }

      override fun onError(e: ImageCaptureException) = cont.resumeWithException(e)
    })
  }
}
