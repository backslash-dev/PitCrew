package ai.pitcrew.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

object PcSpacing {
    val xxs = 4.dp
    val xs = 8.dp
    val s = 12.dp
    val m = 16.dp
    val l = 24.dp
    val xl = 32.dp
    val xxl = 48.dp
    val screen = 40.dp
}

object PcShape {
    val small = RoundedCornerShape(16.dp)
    val medium = RoundedCornerShape(24.dp)
    val large = RoundedCornerShape(32.dp)
    val pill = RoundedCornerShape(percent = 50)
}

/** 창 너비 기준 레이아웃. 1:1 분할창(약 1280dp)과 세로 창에서도 깨지지 않게 한다. */
enum class WindowWidth { COMPACT, MEDIUM, EXPANDED }

fun windowWidthOf(widthDp: Float): WindowWidth = when {
    widthDp >= 1800f -> WindowWidth.EXPANDED
    widthDp >= 1100f -> WindowWidth.MEDIUM
    else -> WindowWidth.COMPACT
}
