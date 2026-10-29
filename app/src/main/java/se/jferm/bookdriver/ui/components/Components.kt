package se.jferm.bookdriver.ui.components

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.selection.toggleable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import se.jferm.bookdriver.data.Book
import se.jferm.bookdriver.ui.theme.BD
import se.jferm.bookdriver.ui.theme.Compressed
import se.jferm.bookdriver.ui.theme.Inter
import se.jferm.bookdriver.ui.theme.Type
import java.io.File

// ---- Covers -----------------------------------------------------------------------------

private val coverPalette = listOf(
    Color(0xFFC2410C), Color(0xFF2F5D8A), Color(0xFF6B3FA0), Color(0xFF2E7D5B),
    Color(0xFF7A2E3A), Color(0xFF8A6A12), Color(0xFF1F6F7A),
)

/** String.hashCode is poorly spread over small moduli; mix it first so similar titles get different colours. */
private fun paletteIndex(key: String, n: Int): Int {
    var x = key.hashCode()
    x = x xor (x ushr 16); x *= 0x45d9f3b
    x = x xor (x ushr 16); x *= 0x45d9f3b
    x = x xor (x ushr 16)
    return Math.floorMod(x, n)
}

private object CoverCache {
    val bitmaps = LruCache<String, ImageBitmap>(48)
}

/** The book's embedded art, or a generated typographic cover: series + number up top, title below. */
@Composable
fun BookCover(book: Book, coversDir: File, modifier: Modifier = Modifier, corner: Dp = 12.dp) {
    val key = book.coverFile
    val bitmap by produceState(initialValue = key?.let { CoverCache.bitmaps.get(it) }, key) {
        if (key == null || value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val file = File(coversDir, key)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 600) sample *= 2
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?.asImageBitmap()?.also { CoverCache.bitmaps.put(key, it) }
            }.getOrNull()
        }
    }
    val shape = RoundedCornerShape(corner)
    val art = bitmap
    if (art != null) {
        Image(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier.clip(shape))
        return
    }
    GeneratedCover(book.title, book.series, book.seriesIndex, book.author, modifier, corner)
}

@Composable
fun GeneratedCover(title: String, series: String?, index: String?, author: String?, modifier: Modifier = Modifier, corner: Dp = 12.dp) {
    val bg = coverPalette[paletteIndex(title.lowercase() + "|" + (index ?: ""), coverPalette.size)]
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val soft = lerp(bg, Color.White, 0.78f)
    BoxWithConstraints(modifier.clip(RoundedCornerShape(corner)).background(bg)) {
        val w = maxWidth.value
        val pad = (w * 0.08f).dp
        val availDp = maxWidth - pad * 2
        val label = buildString {
            when {
                series != null -> {
                    append(series.uppercase())
                    if (index != null) append(" · ").append(index.padStart(2, '0'))
                }
                index != null -> append("BOOK ").append(index.padStart(2, '0'))
                author != null -> append(author.uppercase())
            }
        }
        Column(Modifier.fillMaxSize().padding(pad), verticalArrangement = Arrangement.SpaceBetween) {
            if (w >= 72 && label.isNotEmpty()) {
                Text(
                    label,
                    fontFamily = Compressed, fontWeight = FontWeight(800),
                    fontSize = (w * 0.06f).coerceAtLeast(9f).sp, letterSpacing = 1.sp,
                    color = soft, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            } else Spacer(Modifier.height(1.dp))
            val text = title.uppercase()
            val maxLines = if (w < 72) 3 else 4
            // Largest size (up to the design's 17% of width) where no word has to break
            // mid-word and the title fits in maxLines.
            val size = remember(text, w, maxLines) {
                val avail = with(density) { availDp.roundToPx() }
                val words = text.split(' ').filter { it.isNotBlank() }
                var f = w * 0.17f
                while (f > w * 0.07f) {
                    val style = androidx.compose.ui.text.TextStyle(
                        fontFamily = Compressed, fontWeight = FontWeight(900),
                        fontSize = f.sp, lineHeight = (f * 0.92f).sp,
                    )
                    val widest = words.maxOfOrNull { measurer.measure(it, style, maxLines = 1, softWrap = false).size.width } ?: 0
                    val lines = measurer.measure(text, style, constraints = androidx.compose.ui.unit.Constraints(maxWidth = avail)).lineCount
                    if (widest <= avail && lines <= maxLines) break
                    f *= 0.92f
                }
                f
            }
            Text(
                text,
                fontFamily = Compressed, fontWeight = FontWeight(900),
                fontSize = size.sp, lineHeight = (size * 0.92f).sp,
                color = Color(0xFFFFF6EE), maxLines = maxLines, overflow = TextOverflow.Clip,
            )
        }
    }
}

// ---- Bits -------------------------------------------------------------------------------

@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier, height: Dp = 3.dp, track: Color = BD.Line, color: Color = BD.Amber) {
    Box(modifier.height(height).clip(RoundedCornerShape(height)).background(track)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(height).background(color))
    }
}

@Composable
fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 22.dp,
    tint: Color = BD.Text,
    background: Color = Color.Transparent,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(size).clip(CircleShape).background(background),
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(iconSize))
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, trailing: ImageVector? = null, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = BD.Amber, contentColor = BD.OnAmber, disabledContainerColor = BD.Raised, disabledContentColor = BD.Muted),
        contentPadding = PaddingValues(horizontal = 20.dp),
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)) }
        Text(text, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        if (trailing != null) { Spacer(Modifier.width(10.dp)); Icon(trailing, null, Modifier.size(18.dp)) }
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = BD.Raised, contentColor = BD.Text),
    ) { Text(text, style = Type.bodyStrong) }
}

@Composable
fun FilterChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        Modifier
            .heightIn(min = 36.dp)
            .clip(shape)
            .then(if (selected) Modifier.background(BD.Text) else Modifier.border(1.dp, BD.Line, shape))
            .toggleable(value = selected, role = Role.Tab, onValueChange = { onClick() })
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontFamily = Inter, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, fontSize = 14.sp, color = if (selected) BD.Ink else BD.Text)
    }
}

@Composable
fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Type.bodyStrong, color = BD.Text)
            if (subtitle != null) Text(subtitle, style = Type.small, color = BD.Muted)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedTrackColor = BD.Amber, checkedThumbColor = BD.OnAmber, checkedBorderColor = BD.Amber,
                uncheckedTrackColor = BD.Line, uncheckedThumbColor = BD.Muted, uncheckedBorderColor = BD.Line,
            ),
        )
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = BD.Muted) {
    Text(text.uppercase(), style = Type.overline, color = color, modifier = modifier)
}
