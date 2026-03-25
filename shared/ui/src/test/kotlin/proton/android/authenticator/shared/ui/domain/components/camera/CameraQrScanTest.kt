package proton.android.authenticator.shared.ui.domain.components.camera

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraQrScanTest {

    @Test
    fun `returns center point for non-empty cutout`() {
        val point = calculateFocusPoint(Rect(left = 100f, top = 50f, right = 300f, bottom = 250f))

        assertEquals(200f, point?.x)
        assertEquals(150f, point?.y)
    }

    @Test
    fun `returns null for empty cutout`() {
        val point = calculateFocusPoint(Rect.Zero)

        assertNull(point)
    }
}
