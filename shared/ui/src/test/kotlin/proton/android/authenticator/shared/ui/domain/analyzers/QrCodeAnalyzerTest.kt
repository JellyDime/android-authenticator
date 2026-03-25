package proton.android.authenticator.shared.ui.domain.analyzers

import com.google.zxing.LuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodeAnalyzerTest {

    @Test
    fun `decodes inverted qr code`() {
        val payload = "otpauth://totp/Proton:test@example.com?secret=ABCDEF1234567890&issuer=Proton"
        val analyzer = QrCodeAnalyzer { _, _ -> }

        val result = analyzer.invokeTryDecode(createInvertedQrSource(payload))

        assertEquals(payload, result)
    }

    @Test
    fun `returns cropped scan area inside source bounds`() {
        val crop = QrCodeAnalyzer.calculateCropRect(
            sourceWidth = 1000,
            sourceHeight = 800
        )

        assertTrue(crop.left >= 0)
        assertTrue(crop.top >= 0)
        assertTrue(crop.right <= 1000)
        assertTrue(crop.bottom <= 800)
        assertTrue(crop.right - crop.left < 1000)
        assertTrue(crop.bottom - crop.top < 800)
    }

    @Test
    fun `crop rect excludes top edge content`() {
        val crop = QrCodeAnalyzer.calculateCropRect(
            sourceWidth = 1000,
            sourceHeight = 800
        )

        assertFalse(500 >= crop.left && 500 < crop.right && 40 >= crop.top && 40 < crop.bottom)
    }

    @Test
    fun `maps preview cutout to source crop with fill center scaling`() {
        val crop = QrCodeAnalyzer.mapPreviewRectToSourceCrop(
            PreviewSourceCropInput(
                previewWidth = 1000f,
                previewHeight = 1000f,
                cutout = FloatCropRect(
                    left = 150f,
                    top = 150f,
                    right = 850f,
                    bottom = 850f
                ),
                rotationDegrees = 0,
                sourceWidth = 1600,
                sourceHeight = 900
            )
        )

        assertEquals(485, crop.left)
        assertEquals(135, crop.top)
        assertEquals(1115, crop.right)
        assertEquals(764, crop.bottom)
    }

    @Test
    fun `uses full source when preview size is invalid`() {
        val crop = QrCodeAnalyzer.mapPreviewRectToSourceCrop(
            PreviewSourceCropInput(
                previewWidth = 0f,
                previewHeight = 0f,
                cutout = FloatCropRect(
                    left = 0f,
                    top = 0f,
                    right = 0f,
                    bottom = 0f
                ),
                rotationDegrees = 0,
                sourceWidth = 1600,
                sourceHeight = 900
            )
        )

        assertEquals(0, crop.left)
        assertEquals(0, crop.top)
        assertEquals(1600, crop.right)
        assertEquals(900, crop.bottom)
    }

    @Test
    fun `maps preview cutout to source crop when image requires rotation`() {
        val crop = QrCodeAnalyzer.mapPreviewRectToSourceCrop(
            PreviewSourceCropInput(
                previewWidth = 1000f,
                previewHeight = 1000f,
                cutout = FloatCropRect(
                    left = 200f,
                    top = 100f,
                    right = 800f,
                    bottom = 700f
                ),
                rotationDegrees = 90,
                sourceWidth = 1600,
                sourceHeight = 900
            )
        )

        assertEquals(440, crop.left)
        assertEquals(181, crop.top)
        assertEquals(980, crop.right)
        assertEquals(720, crop.bottom)
    }

    @Test
    fun `normalizes negative rotation degrees`() {
        val crop = QrCodeAnalyzer.mapPreviewRectToSourceCrop(
            PreviewSourceCropInput(
                previewWidth = 1000f,
                previewHeight = 1000f,
                cutout = FloatCropRect(
                    left = 200f,
                    top = 100f,
                    right = 800f,
                    bottom = 700f
                ),
                rotationDegrees = -90,
                sourceWidth = 1600,
                sourceHeight = 900
            )
        )

        assertEquals(620, crop.left)
        assertEquals(180, crop.top)
        assertEquals(1160, crop.right)
        assertEquals(719, crop.bottom)
    }

    private fun createInvertedQrSource(payload: String): LuminanceSource {
        val size = 256
        val bitMatrix = QRCodeWriter().encode(payload, com.google.zxing.BarcodeFormat.QR_CODE, size, size)
        val pixels = IntArray(size * size)

        for (y in 0 until size) {
            for (x in 0 until size) {
                val color = if (bitMatrix[x, y]) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
                pixels[y * size + x] = color
            }
        }

        return RGBLuminanceSource(size, size, pixels)
    }

    private fun QrCodeAnalyzer.invokeTryDecode(source: LuminanceSource): String? {
        val method = javaClass.getDeclaredMethod("tryDecode", LuminanceSource::class.java)
        method.isAccessible = true
        return method.invoke(this, source) as? String
    }
}
