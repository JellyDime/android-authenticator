/*
 * Copyright (c) 2025 Proton AG
 * This file is part of Proton AG and Proton Authenticator.
 *
 * Proton Authenticator is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Proton Authenticator is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Proton Authenticator.  If not, see <https://www.gnu.org/licenses/>.
 */

package proton.android.authenticator.shared.ui.domain.analyzers

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class QrCodeAnalyzer(
    private val previewWidthProvider: () -> Float = { 0f },
    private val previewHeightProvider: () -> Float = { 0f },
    private val cutoutRectProvider: () -> Rect = { Rect() },
    private val onQrCodeScanned: (String, ByteArray) -> Unit
) : ImageAnalysis.Analyzer {

    private val qrCodeReader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to arrayListOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.ALSO_INVERTED to true
            )
        )
    }

    private val supportedQrCodeImageFormats = setOf(
        ImageFormat.YUV_420_888,
        ImageFormat.YUV_422_888,
        ImageFormat.YUV_444_888
    )

    override fun analyze(imageProxy: ImageProxy) {
        if (imageProxy.format !in supportedQrCodeImageFormats) {
            imageProxy.close()
            return
        }

        val qrCodeSource = calculateQrScanSource(imageProxy)

        try {
            val qrCodeValue = tryDecode(qrCodeSource) ?: return
            val qrCodeBytes = convertBitmapToByteArray(imageProxy.toBitmap())
            onQrCodeScanned(qrCodeValue, qrCodeBytes)
        } finally {
            imageProxy.close()
        }
    }

    private fun convertBitmapToByteArray(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, IMAGE_QUALITY, stream)
        return stream.toByteArray()
    }

    private fun tryDecode(source: LuminanceSource): String? {
        try {
            // First pass: HybridBinarizer (better for real photos)
            try {
                return qrCodeReader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
            } catch (_: ReaderException) {
            }
            // Second pass: GlobalHistogramBinarizer (better for screens / low contrast)
            return try {
                qrCodeReader.decodeWithState(BinaryBitmap(GlobalHistogramBinarizer(source))).text
            } catch (_: ReaderException) {
                null
            }
        } finally {
            qrCodeReader.reset()
        }
    }

    private fun calculateQrScanSource(imageProxy: ImageProxy): LuminanceSource {
        val yPlane = imageProxy.planes.first()
        val sourceWidth = imageProxy.width
        val sourceHeight = imageProxy.height
        val cutoutRect = cutoutRectProvider()
        val crop = mapPreviewRectToSourceCrop(
            PreviewSourceCropInput(
                previewWidth = previewWidthProvider(),
                previewHeight = previewHeightProvider(),
                cutout = FloatCropRect(
                    left = cutoutRect.left.toFloat(),
                    top = cutoutRect.top.toFloat(),
                    right = cutoutRect.right.toFloat(),
                    bottom = cutoutRect.bottom.toFloat()
                ),
                rotationDegrees = imageProxy.imageInfo.rotationDegrees,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight
            )
        )
        val cropRect = crop.toAndroidRect()

        return PlanarYUVLuminanceSource(
            yPlane.buffer.toByteArray(),
            yPlane.rowStride,
            sourceHeight,
            cropRect.left,
            cropRect.top,
            cropRect.width(),
            cropRect.height(),
            false
        )
    }

    private fun ByteBuffer.toByteArray(): ByteArray {
        rewind()

        return ByteArray(remaining()).also(::get)
    }

    companion object {

        private const val IMAGE_QUALITY = 100
        internal const val SCAN_WINDOW_SIZE_RATIO = 0.7f
        private const val SCAN_WINDOW_TOP_RATIO = 1f / 6f
        private const val FULL_ROTATION_DEGREES = 360
        private const val RIGHT_ANGLE_DEGREES = 90
        private const val UPSIDE_DOWN_DEGREES = 180
        private const val THREE_QUARTER_TURN_DEGREES = 270

        internal fun calculateCropRect(sourceWidth: Int, sourceHeight: Int): CropRect {
            val cropSize = (minOf(sourceWidth, sourceHeight) * SCAN_WINDOW_SIZE_RATIO).toInt()
            val left = ((sourceWidth - cropSize) / 2f).toInt()
            val top = (sourceHeight * SCAN_WINDOW_TOP_RATIO).toInt()
                .coerceAtMost(sourceHeight - cropSize)
                .coerceAtLeast(0)

            return CropRect(
                left = left,
                top = top,
                right = left + cropSize,
                bottom = top + cropSize
            )
        }

        internal fun mapPreviewRectToSourceCrop(input: PreviewSourceCropInput): CropRect {
            if (input.previewWidth <= 0f || input.previewHeight <= 0f) {
                return CropRect(0, 0, input.sourceWidth, input.sourceHeight)
            }

            val normalizedRotation =
                (input.rotationDegrees % FULL_ROTATION_DEGREES + FULL_ROTATION_DEGREES) % FULL_ROTATION_DEGREES
            val isQuarterTurnRotation =
                normalizedRotation == RIGHT_ANGLE_DEGREES || normalizedRotation == THREE_QUARTER_TURN_DEGREES
            val displaySourceWidth = if (isQuarterTurnRotation) {
                input.sourceHeight
            } else {
                input.sourceWidth
            }
            val displaySourceHeight = if (isQuarterTurnRotation) {
                input.sourceWidth
            } else {
                input.sourceHeight
            }

            val scale = maxOf(input.previewWidth / displaySourceWidth, input.previewHeight / displaySourceHeight)
            val scaledSourceWidth = displaySourceWidth * scale
            val scaledSourceHeight = displaySourceHeight * scale
            val offsetX = (scaledSourceWidth - input.previewWidth) / 2f
            val offsetY = (scaledSourceHeight - input.previewHeight) / 2f

            val displayCrop = CropRect(
                left = ((input.cutout.left + offsetX) / scale).toInt().coerceIn(0, displaySourceWidth),
                top = ((input.cutout.top + offsetY) / scale).toInt().coerceIn(0, displaySourceHeight),
                right = ((input.cutout.right + offsetX) / scale).toInt().coerceIn(1, displaySourceWidth),
                bottom = ((input.cutout.bottom + offsetY) / scale).toInt().coerceIn(1, displaySourceHeight)
            ).normalized()

            return when (normalizedRotation) {
                RIGHT_ANGLE_DEGREES -> CropRect(
                    left = displayCrop.top,
                    top = input.sourceHeight - displayCrop.right,
                    right = displayCrop.bottom,
                    bottom = input.sourceHeight - displayCrop.left
                )
                UPSIDE_DOWN_DEGREES -> CropRect(
                    left = input.sourceWidth - displayCrop.right,
                    top = input.sourceHeight - displayCrop.bottom,
                    right = input.sourceWidth - displayCrop.left,
                    bottom = input.sourceHeight - displayCrop.top
                )
                THREE_QUARTER_TURN_DEGREES -> CropRect(
                    left = input.sourceWidth - displayCrop.bottom,
                    top = displayCrop.left,
                    right = input.sourceWidth - displayCrop.top,
                    bottom = displayCrop.right
                )
                else -> displayCrop
            }.normalized()
        }

    }

}

internal data class PreviewSourceCropInput(
    val previewWidth: Float,
    val previewHeight: Float,
    val cutout: FloatCropRect,
    val rotationDegrees: Int,
    val sourceWidth: Int,
    val sourceHeight: Int
)

internal data class FloatCropRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

internal data class CropRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    fun width(): Int = right - left
    fun height(): Int = bottom - top
    fun toAndroidRect(): Rect = Rect(left, top, right, bottom)
    fun normalized(): CropRect {
        val normalizedLeft = minOf(left, right)
        val normalizedTop = minOf(top, bottom)
        val normalizedRight = maxOf(left, right)
        val normalizedBottom = maxOf(top, bottom)
        return CropRect(normalizedLeft, normalizedTop, normalizedRight, normalizedBottom)
    }
}
