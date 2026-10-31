package com.dreamos.desktop.settings.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.nayuki.qrcodegen.QrCode

/** A QR code for [text] on a white card (Ui.Qr): crisp modules, a quiet zone, scanned by a phone. */
@Composable
fun QrImage(text: String, size: Dp = 168.dp, modifier: Modifier = Modifier) {
    val qr = remember(text) { QrCode.encodeText(text, QrCode.Ecc.MEDIUM) }
    Box(modifier.size(size).background(Color.White, RoundedCornerShape(12.dp)).padding(size * 0.07f)) {
        Canvas(Modifier.size(size * 0.86f)) {
            val n = qr.size
            val cell = this.size.minDimension / n
            for (y in 0 until n) for (x in 0 until n) {
                if (qr.getModule(x, y)) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}
