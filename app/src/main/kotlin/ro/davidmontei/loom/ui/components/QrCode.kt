package ro.davidmontei.loom.ui.components

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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import ro.davidmontei.loom.ui.theme.LoomColors

/** The light margin [QrCode] draws around the code, on both sides together: it takes [size] plus this. */
val QR_MARGINS = 28.dp

/** A QR code drawn module by module: dark on off-white, which every phone camera reads. */
@Composable
fun QrCode(content: String, modifier: Modifier = Modifier, size: Dp = 240.dp) {
    val matrix = remember(content) {
        QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
    }
    Box(
        modifier
            .semantics { contentDescription = "QR code for $content" }
            .background(LoomColors.Text, RoundedCornerShape(12.dp))
            .padding(QR_MARGINS / 2),
    ) {
        Canvas(Modifier.size(size)) {
            val cell = this.size.width / matrix.width
            for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
                if (matrix[x, y]) drawRect(LoomColors.Background, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}
