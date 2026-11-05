package ai.pitcrew.app.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

private fun style(size: Int, weight: FontWeight, lineHeight: Float = 1.3f, tracking: TextUnit = 0.sp) = TextStyle(
    fontFamily = FontFamily.Default,
    fontSize = size.sp,
    fontWeight = weight,
    lineHeight = (size * lineHeight).sp,
    letterSpacing = tracking,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

/** Pleos 타이포 스케일(160dpi 기준 px=sp). 시스템 폰트를 써서 차량 UI와 같은 서체를 따른다. */
object PcTypography {
    val display = style(64, FontWeight.Bold, 1.1f, (-0.02).em)
    val headline = style(48, FontWeight.Bold, 1.15f, (-0.015).em)
    val titleLarge = style(40, FontWeight.Bold, 1.2f, (-0.01).em)
    val title = style(36, FontWeight.Bold, 1.2f)
    val titleSmall = style(32, FontWeight.SemiBold, 1.25f)
    val bodyLarge = style(30, FontWeight.Normal, 1.4f)
    val body = style(28, FontWeight.Normal, 1.4f)
    val bodyStrong = style(28, FontWeight.SemiBold, 1.4f)
    val bodySmall = style(26, FontWeight.Normal, 1.4f)
    val label = style(24, FontWeight.Medium, 1.3f)
    val labelSmall = style(20, FontWeight.Medium, 1.3f, 0.01.em)
}
