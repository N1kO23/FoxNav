package app.nullpointr.foxnav.convoy

import app.nullpointr.foxnav.shared.convoy.Invite
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InviteScannerTest {
    @Test
    fun readsAnInviteQrCodeFromACameraFrame() {
        val link = Invite("http://192.168.3.202:8080", ByteArray(16) { it.toByte() }, ByteArray(16) { 1 }, ByteArray(32) { 2 }, keyEpoch = 1).toLink()
        val code = QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, 360, 360, mapOf(EncodeHintType.MARGIN to 2))
        // A frame bigger than the code, with padding after each row, as camera buffers have.
        val width = 640
        val height = 480
        val rowStride = 704
        val frame = ByteArray(rowStride * height) { 200.toByte() }
        for (y in 0 until code.height) {
            for (x in 0 until code.width) if (code[x, y]) frame[(y + 60) * rowStride + x + 140] = 20
        }
        assertEquals(link, decodeQr(frame, rowStride, width, height))
    }

    @Test
    fun aFrameWithoutACodeReadsAsNothing() {
        assertNull(decodeQr(ByteArray(704 * 480) { 128.toByte() }, 704, 640, 480))
    }
}
