package com.jiacimu.lulu

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import kotlin.math.ceil
import kotlin.math.floor

/**
 * This is the owner-supplied Alipay receiving QR, decoded from the uploaded
 * image and re-encoded as the same HTTPS payload. The 4-module quiet zone is
 * included, and the matrix was independently round-trip scanned before commit.
 * No network image dependency or author's visible display name is baked in.
 */
internal object LuluAlipayDonationQrData {
    const val expectedPayload = "https://qr.alipay.com/fkx19624otgusjgl8cznve3"

    val rows: List<String> = """
        00000000000000000000000000000000000000000
        00000000000000000000000000000000000000000
        00000000000000000000000000000000000000000
        00000000000000000000000000000000000000000
        00001111111001101001101101010011111110000
        00001000001010001110100001110010000010000
        00001011101011111101001110001010111010000
        00001011101001011101101110111010111010000
        00001011101001000100000111001010111010000
        00001000001001000010111110100010000010000
        00001111111010101010101010101011111110000
        00000000000001000111111000100000000000000
        00000111011001000000111101010000001100000
        00001010000011000101011110110011011110000
        00000001111001011001001000111101110010000
        00001111000101001001010010100011010100000
        00000011101010110001000100001101110010000
        00000000000111001111010100101101010100000
        00000011011101000101001100101010110000000
        00001011110010001101001111011110001000000
        00000111101110010110101011110110101000000
        00000111010011100011110011111010110010000
        00001101111001111000010011001101101100000
        00000001010101100010010110001010100100000
        00001111101011111001000111011001011110000
        00001001100111101101001111010011000010000
        00000001001010110000101010011010100110000
        00000100100011011010011010001000100000000
        00001000001010011111011100101111110110000
        00000000000011100101001100111000110000000
        00001111111001001110100101111010100000000
        00001000001010001010001111101000111010000
        00001011101001100101101111111111101110000
        00001011101010100000110011001001010110000
        00001011101011100100101001001111000000000
        00001000001010110100111110101111110010000
        00001111111001111001100001110010111000000
        00000000000000000000000000000000000000000
        00000000000000000000000000000000000000000
        00000000000000000000000000000000000000000
        00000000000000000000000000000000000000000
    """.trimIndent().lines()

    fun valid(): Boolean = rows.size == 41 && rows.all { row ->
        row.length == 41 && row.all { ch -> ch == '0' || ch == '1' }
    }
}

@Composable
internal fun LuluAlipayDonationQr(modifier: Modifier = Modifier) {
    val rows = LuluAlipayDonationQrData.rows
    Canvas(modifier = modifier.fillMaxWidth().aspectRatio(1f)) {
        // White is essential for contrast in both light and dark themes.
        drawRect(Color.White)
        val cell = size.minDimension / rows.size
        for (row in rows.indices) {
            for (column in rows[row].indices) {
                if (rows[row][column] != '1') continue
                // Pixel-aligned rectangles avoid tiny gaps on dense screens.
                val left = floor(column * cell)
                val top = floor(row * cell)
                val right = ceil((column + 1) * cell)
                val bottom = ceil((row + 1) * cell)
                drawRect(
                    color = Color.Black,
                    topLeft = Offset(left, top),
                    size = Size(right - left, bottom - top),
                )
            }
        }
    }
}
