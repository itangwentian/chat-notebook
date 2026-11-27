package com.chatnotebook.app.data.sync

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.serialization.SerializationException

internal sealed class PairingScanException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NotFound(cause: Throwable? = null) : PairingScanException("未识别到二维码，请重试或从图片识别", cause)
    class InvalidContent : PairingScanException("二维码内容无效，请使用本应用的配对二维码")
    class VersionMismatch : PairingScanException("二维码配对版本不兼容，请更新两台设备")
}

internal object PairingQr {
    private const val MAX_DIMENSION = 1600
    private val hints: Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )

    fun display(offer: PairingOffer): Bitmap {
        val qr = QRCodeWriter().encode(offer.encode(), BarcodeFormat.QR_CODE, 480, 480)
        val pixels = IntArray(qr.width * qr.height) { i -> if (qr[i % qr.width, i / qr.width]) 0xff000000.toInt() else -1 }
        return Bitmap.createBitmap(pixels, qr.width, qr.height, Bitmap.Config.ARGB_8888)
    }

    fun scan(bitmap: Bitmap): PairingOffer {
        val source = scaleDown(bitmap, MAX_DIMENSION)
        var cause: Throwable? = null
        try {
            for (degrees in intArrayOf(0, 90, 180, 270)) {
                val candidate = if (degrees == 0) source else rotate(source, degrees)
                try {
                    return decode(candidate)
                } catch (error: NotFoundException) {
                    cause = error
                } catch (error: IllegalArgumentException) {
                    cause = error
                } catch (error: com.google.zxing.ChecksumException) {
                    cause = error
                } catch (error: com.google.zxing.FormatException) {
                    cause = error
                } finally {
                    if (candidate !== source) candidate.recycle()
                }
            }
        } finally {
            if (source !== bitmap) source.recycle()
        }
        throw PairingScanException.NotFound(cause)
    }

    private fun decode(bitmap: Bitmap): PairingOffer {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        val text = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        return parse(text)
    }

    private fun parse(text: String): PairingOffer {
        if (!text.startsWith("chat-notebook-pair:")) throw PairingScanException.InvalidContent()
        return try {
            PairingOffer.decode(text)
        } catch (_: SerializationException) {
            throw PairingScanException.InvalidContent()
        } catch (error: IllegalArgumentException) {
            if (error.message?.contains("不兼容") == true) {
                throw PairingScanException.VersionMismatch()
            }
            throw PairingScanException.InvalidContent()
        }
    }

    private fun scaleDown(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val largest = maxOf(bitmap.width, bitmap.height)
        if (largest <= maxDimension) return bitmap
        val ratio = maxDimension.toFloat() / largest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun rotate(source: Bitmap, degrees: Int): Bitmap {
        val width = source.width
        val height = source.height
        val input = IntArray(width * height)
        source.getPixels(input, 0, width, 0, 0, width, height)
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
                for (index in input.indices) {
                    rotated[input.size - 1 - index] = input[index]
                }
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
