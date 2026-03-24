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
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class QrCodeAnalyzer(private val onQrCodeScanned: (String, ByteArray) -> Unit) : ImageAnalysis.Analyzer {

    private val qrCodeReader = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to arrayListOf(BarcodeFormat.QR_CODE)
    ).let { hints ->
        MultiFormatReader().apply {
            setHints(hints)
        }
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
        val qrCodeBitmap = BinaryBitmap(HybridBinarizer(qrCodeSource))

        try {
            val qrCodeValue = qrCodeReader.decode(qrCodeBitmap).text
            val qrCodeBytes = convertBitmapToByteArray(imageProxy.toBitmap())

            onQrCodeScanned(qrCodeValue, qrCodeBytes)
        } catch (_: NotFoundException) {
            // do nothing when QR code is not found
        } finally {
            imageProxy.close()
        }
    }

    fun convertBitmapToByteArray(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, IMAGE_QUALITY, stream) // Adjust format and quality as needed
        return stream.toByteArray()
    }

    private fun calculateQrScanSource(imageProxy: ImageProxy): LuminanceSource {
        val yPlane = imageProxy.planes.first()
        return PlanarYUVLuminanceSource(
            yPlane.buffer.toByteArray(),
            yPlane.rowStride,
            imageProxy.height,
            0,
            0,
            imageProxy.width,
            imageProxy.height,
            false
        )
    }

    private fun ByteBuffer.toByteArray(): ByteArray {
        rewind()

        return ByteArray(remaining()).also(::get)
    }

    private companion object {

        private const val IMAGE_QUALITY = 100

    }

}
