package se.jferm.bookdriver.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** The design's line icons, drawn from the same 24×24 SVG paths. Tint them with Icon(tint=). */
object BdIcons {
    private fun stroke(name: String, vararg paths: String, width: Float = 2f) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach {
                addPath(
                    pathData = PathParser().parsePathString(it).toNodes(),
                    stroke = SolidColor(Color.White),
                    strokeLineWidth = width,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    private fun fill(name: String, vararg paths: String) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { addPath(pathData = PathParser().parsePathString(it).toNodes(), fill = SolidColor(Color.White)) }
        }.build()

    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r},$cy a$r,$r 0 1,0 ${2 * r},0 a$r,$r 0 1,0 ${-2 * r},0"

    private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float) =
        "M${x + r},$y h${w - 2 * r} a$r,$r 0 0,1 $r,$r v${h - 2 * r} a$r,$r 0 0,1 ${-r},$r h${-(w - 2 * r)} a$r,$r 0 0,1 ${-r},${-r} v${-(h - 2 * r)} a$r,$r 0 0,1 $r,${-r} z"

    val Search = stroke("search", circle(11f, 11f, 7f), "M20 20l-3.5-3.5")
    val Plus = stroke("plus", "M12 5v14M5 12h14", width = 2.2f)
    val Minus = stroke("minus", "M5 12h14", width = 2.2f)
    val Close = stroke("close", "M6 6l12 12M18 6L6 18")
    val ChevronDown = stroke("chevronDown", "M6 9l6 6 6-6")
    val ChevronLeft = stroke("chevronLeft", "M15 6l-6 6 6 6")
    val ChevronRight = stroke("chevronRight", "M9 6l6 6-6 6")
    val ArrowRight = stroke("arrowRight", "M5 12h14M13 6l6 6-6 6", width = 2.4f)
    val More = fill("more", circle(5f, 12f, 1.8f), circle(12f, 12f, 1.8f), circle(19f, 12f, 1.8f))
    val Play = fill("play", "M7 4.5v15l13-7.5z")
    val Pause = fill("pause", rect(6f, 4.5f, 4f, 15f, 1.2f), rect(14f, 4.5f, 4f, 15f, 1.2f))
    val PrevChapter = fill("prevChapter", "M18 5v14L8 12z", rect(5f, 5f, 2.5f, 14f, 1f))
    val NextChapter = fill("nextChapter", "M6 5v14l10-7z", rect(16.5f, 5f, 2.5f, 14f, 1f))
    val Back = stroke("back", "M4.5 12a7.5 7.5 0 1 0 2.2-5.3", "M4 4v4h4", width = 1.8f)
    val Forward = stroke("forward", "M19.5 12a7.5 7.5 0 1 1-2.2-5.3", "M20 4v4h-4", width = 1.8f)
    val Moon = stroke("moon", "M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z", width = 2.2f)
    val ListLines = stroke("list", "M4 6h16M4 12h16M4 18h10", width = 2.2f)
    val Check = stroke("check", "M5 12.5l4.5 4.5L19 7.5", width = 2.4f)
    val Bars = fill("bars", rect(3f, 9f, 3.5f, 12f, 1f), rect(10.25f, 3f, 3.5f, 18f, 1f), rect(17.5f, 12f, 3.5f, 9f, 1f))
    val Download = stroke("download", "M12 3v12M7 10l5 5 5-5M5 21h14")
    val Clock = stroke("clock", circle(12f, 12f, 9f), "M12 7v5l3 2")
    val Shield = stroke("shield", "M12 3l7 3v6c0 4.5-3 7.5-7 9-4-1.5-7-4.5-7-9V6z")
    val FileAudio = stroke("fileAudio", "M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z", "M14 3v5h5", circle(10.5f, 15.5f, 2f), "M12.5 15.5V10l3 1")
    val Folder = stroke("folder", "M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z", "M8 12h8M8 15.5h5")
    val Spinner = stroke("spinner", "M12 3a9 9 0 1 0 9 9", width = 2.6f)
    val Warning = stroke("warning", "M12 3l9.5 17h-19z", "M12 10v4", "M12 17.5v.5")
    val Logout = stroke("logout", "M15 4h3a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-3", "M10 17l5-5-5-5", "M15 12H4")
    val Copy = stroke("copy", rect(8f, 8f, 12f, 12f, 2f), "M4 16V6a2 2 0 0 1 2-2h10")
}
