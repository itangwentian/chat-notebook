package com.chatnotebook.app.data.sync

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class PairingQrTest {
    @Test fun codeRoundTripPreservesTheFullDeviceFingerprint() {
        val offer = PairingTicket("f".repeat(64), "192.168.1.20", 20_000) { 0L }.offer
        assertEquals(offer, PairingQr.scan(PairingQr.display(offer)))
    }

    @Test fun rotatedCodesAreStillRecognized() {
        val offer = PairingTicket("a".repeat(64), "192.168.1.20", 20_000) { 0L }.offer
        val displayed = PairingQr.display(offer)
        for (degrees in intArrayOf(90, 180, 270)) {
            val rotated = rotate(displayed, degrees)
            assertEquals(offer, PairingQr.scan(rotated))
            rotated.recycle()
        }
        displayed.recycle()
    }

    @Test fun blankImageReportsMissingCode() {
        val blank = Bitmap.createBitmap(480, 480, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        assertTrue(failure(blank) is PairingScanException.NotFound)
        blank.recycle()
    }

    @Test fun nonPairingCodeIsRejected() {
        val bitmap = qrBitmap("https://example.com/not-a-pairing-code")
        assertTrue(failure(bitmap) is PairingScanException.InvalidContent)
        bitmap.recycle()
    }

    @Test fun incompatibleVersionIsReported() {
        val payload = "chat-notebook-pair:" +
            """{"protocol":3,"discoveryId":"${UUID.randomUUID()}","fingerprint":"${"f".repeat(64)}","token":"${"a".repeat(48)}"}"""
        val bitmap = qrBitmap(payload)
        assertTrue(failure(bitmap) is PairingScanException.VersionMismatch)
        bitmap.recycle()
    }

    private fun failure(bitmap: Bitmap): PairingScanException = try {
        PairingQr.scan(bitmap)
        throw AssertionError("应识别失败")
    } catch (error: PairingScanException) {
        error
    }

    private fun qrBitmap(text: String): Bitmap {
        val qr = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 480, 480)
        val pixels = IntArray(qr.width * qr.height) { i -> if (qr[i % qr.width, i / qr.width]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, qr.width, qr.height, Bitmap.Config.ARGB_8888)
    }

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val input = IntArray(width * height)
        bitmap.getPixels(input, 0, width, 0, 0, width, height)
        val rotated = IntArray(input.size)
        when (degrees) {
            90 -> {
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        rotated[x * height + (height - 1 - y)] = input[y * width + x]
                    }
                }
                return Bitmap.createBitmap(rotated, height, width, Bitmap.Config.ARGB_8888)
            }
            180 -> {
                for (index in input.indices) rotated[input.size - 1 - index] = input[index]
                return Bitmap.createBitmap(rotated, width, height, Bitmap.Config.ARGB_8888)
            }
            else -> {
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        rotated[(width - 1 - x) * height + y] = input[y * width + x]
                    }
                }
                return Bitmap.createBitmap(rotated, height, width, Bitmap.Config.ARGB_8888)
            }
        }
    }
}
