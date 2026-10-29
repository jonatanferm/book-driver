package se.jferm.bookdriver.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.jferm.bookdriver.data.Book
import se.jferm.bookdriver.data.Library
import se.jferm.bookdriver.playback.SleepTimer
import se.jferm.bookdriver.ui.PlaybackUi
import se.jferm.bookdriver.ui.components.BdIcons
import se.jferm.bookdriver.ui.components.BookCover
import se.jferm.bookdriver.ui.components.RoundIconButton
import se.jferm.bookdriver.ui.theme.BD
import se.jferm.bookdriver.ui.theme.Inter
import se.jferm.bookdriver.ui.theme.Titling
import se.jferm.bookdriver.ui.theme.Type
import java.io.File

class PlayerActions(
    val onBack: () -> Unit,
    val onTogglePlay: () -> Unit,
    val onSkipBack: () -> Unit,
    val onSkipForward: () -> Unit,
    val onPrevChapter: () -> Unit,
    val onNextChapter: () -> Unit,
    val onSeekInChapter: (Float) -> Unit,
    val onChapters: () -> Unit,
    val onSpeedSleep: () -> Unit,
    val onMarkFinished: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    book: Book,
    playback: PlaybackUi,
    speed: Float,
    sleep: SleepTimer.State,
    coversDir: File,
    actions: PlayerActions,
) {
    val chapters = book.effectiveChapters
    val chapterIdx = book.chapterIndexAt(playback.trackIndex, playback.positionMs)
    val global = book.globalMs(playback.trackIndex, playback.positionMs)
    val chStart = book.chapterStartGlobal(chapterIdx)
    val chEnd = book.chapterEndGlobal(chapterIdx).coerceAtLeast(chStart + 1)
    val inChapter = (global - chStart).coerceAtLeast(0)
    val chLen = chEnd - chStart

    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    var menu by remember { mutableStateOf(false) }
    val sliderValue = if (dragging) dragValue else (inChapter.toFloat() / chLen).coerceIn(0f, 1f)
    val shownInChapter = if (dragging) (dragValue * chLen).toLong() else inChapter

    Column(
        Modifier.fillMaxSize().background(BD.Ink).windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(BdIcons.ChevronDown, "Back to library", actions.onBack, iconSize = 24.dp)
            Text("NOW PLAYING", style = Type.overline, color = BD.Muted, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Box {
                RoundIconButton(BdIcons.More, "More options", { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Mark as finished") }, onClick = { menu = false; actions.onMarkFinished() })
                }
            }
        }

        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            val side: Dp = minOf(maxWidth, maxHeight, 320.dp)
            BookCover(book, coversDir, Modifier.size(side).aspectRatio(1f), corner = 18.dp)
        }

        Spacer(Modifier.height(20.dp))
        Text(book.title, fontFamily = Titling, fontWeight = FontWeight(800), fontSize = 28.sp, lineHeight = 30.sp, color = BD.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val byline = listOfNotNull(book.author, book.series?.let { s -> book.seriesIndex?.let { "$s, Book $it" } ?: s }).joinToString(" · ")
        if (byline.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(byline, style = Type.body, color = BD.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(18.dp)).background(BD.Raised)
                .clickable(onClickLabel = "Show chapters", onClick = actions.onChapters).padding(start = 14.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(BdIcons.ListLines, null, tint = BD.Amber, modifier = Modifier.size(16.dp))
            Text(chapters.getOrNull(chapterIdx)?.title ?: "", style = Type.bodyStrong.copy(fontSize = 14.sp), color = BD.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 240.dp))
            Icon(BdIcons.ChevronRight, null, tint = BD.Muted, modifier = Modifier.size(16.dp))
        }

        Spacer(Modifier.height(12.dp))
        Slider(
            value = sliderValue,
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = { actions.onSeekInChapter(dragValue); dragging = false },
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = BD.Amber, inactiveTrackColor = BD.Line),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Position in chapter" },
        )
        Row(Modifier.fillMaxWidth()) {
            Text(Library.formatClock(shownInChapter), style = Type.small, color = BD.Muted, modifier = Modifier.weight(1f))
            Text("-" + Library.formatClock(chLen - shownInChapter), style = Type.small, color = BD.Muted)
        }
        val pct = if (book.durationMs > 0) (global * 100 / book.durationMs) else 0
        Text(
            "Book: ${Library.formatDuration(global)} of ${Library.formatDuration(book.durationMs)} · $pct%",
            style = Type.caption, color = BD.Dim,
        )
        playback.error?.let {
            Text(
                "Playback stopped — tap play to try again. ($it)",
                style = Type.caption, color = BD.Danger, maxLines = 3,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(BdIcons.PrevChapter, "Previous chapter", actions.onPrevChapter, size = 48.dp, iconSize = 26.dp)
            SkipButton(BdIcons.Back, "10", "Back 10 seconds", actions.onSkipBack)
            Box(
                Modifier.size(84.dp).clip(CircleShape).background(BD.Amber).clickable(onClickLabel = if (playback.isPlaying) "Pause" else "Play", onClick = actions.onTogglePlay),
                contentAlignment = Alignment.Center,
            ) {
                if (playback.buffering) {
                    CircularProgressIndicator(color = BD.OnAmber, strokeWidth = 3.dp, modifier = Modifier.size(34.dp))
                } else {
                    Icon(if (playback.isPlaying) BdIcons.Pause else BdIcons.Play, if (playback.isPlaying) "Pause" else "Play", tint = BD.OnAmber, modifier = Modifier.size(32.dp))
                }
            }
            SkipButton(BdIcons.Forward, "30", "Forward 30 seconds", actions.onSkipForward)
            RoundIconButton(BdIcons.NextChapter, "Next chapter", actions.onNextChapter, size = 48.dp, iconSize = 26.dp)
        }

        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val speedText = if (speed == speed.toInt().toFloat()) "${speed.toInt()}.0×" else "${"%.2f".format(speed).trimEnd('0')}×"
            Tile("Speed", Modifier.weight(1f), actions.onSpeedSleep) {
                Text(speedText, fontFamily = se.jferm.bookdriver.ui.theme.Condensed, fontWeight = FontWeight(800), fontSize = 18.sp, color = BD.Text)
            }
            Tile("Sleep timer", Modifier.weight(1f), actions.onSpeedSleep) {
                val active = sleep.mode != SleepTimer.Mode.OFF
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Icon(BdIcons.Moon, null, tint = if (active) BD.Amber else BD.Text, modifier = Modifier.size(16.dp))
                    Text(
                        when (sleep.mode) {
                            SleepTimer.Mode.OFF -> "Off"
                            SleepTimer.Mode.END_OF_CHAPTER -> "Chapter"
                            SleepTimer.Mode.MINUTES -> "${(sleep.remainingMs + 59_999) / 60_000}m"
                        },
                        fontFamily = se.jferm.bookdriver.ui.theme.Condensed, fontWeight = FontWeight(800), fontSize = 18.sp,
                        color = if (active) BD.Amber else BD.Text,
                    )
                }
            }
            Tile("Chapters", Modifier.weight(1f), actions.onChapters) {
                Text("${chapterIdx + 1}/${chapters.size}", fontFamily = se.jferm.bookdriver.ui.theme.Condensed, fontWeight = FontWeight(800), fontSize = 18.sp, color = BD.Text)
            }
        }
    }
}

@Composable
private fun SkipButton(icon: ImageVector, label: String, description: String, onClick: () -> Unit) {
    Box(Modifier.size(56.dp).clip(CircleShape).clickable(onClickLabel = description, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = BD.Text, modifier = Modifier.size(36.dp))
        Text(label, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = BD.Text, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun Tile(label: String, modifier: Modifier, onClick: () -> Unit, value: @Composable () -> Unit) {
    Column(
        modifier.height(56.dp).clip(RoundedCornerShape(14.dp)).background(BD.Surface).border(1.dp, BD.Line, RoundedCornerShape(14.dp))
            .clickable(onClickLabel = label, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        value()
        Text(label, style = Type.caption.copy(fontSize = 11.sp), color = BD.Muted)
    }
}
