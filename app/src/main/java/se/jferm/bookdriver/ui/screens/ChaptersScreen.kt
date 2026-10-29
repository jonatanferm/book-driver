package se.jferm.bookdriver.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.jferm.bookdriver.data.Book
import se.jferm.bookdriver.data.Library
import se.jferm.bookdriver.ui.PlaybackUi
import se.jferm.bookdriver.ui.components.BdIcons
import se.jferm.bookdriver.ui.components.ProgressLine
import se.jferm.bookdriver.ui.components.RoundIconButton
import se.jferm.bookdriver.ui.theme.BD
import se.jferm.bookdriver.ui.theme.Condensed
import se.jferm.bookdriver.ui.theme.Type

@Composable
fun ChaptersScreen(book: Book, playback: PlaybackUi, onBack: () -> Unit, onPick: (Int) -> Unit) {
    val chapters = book.effectiveChapters
    val current = book.chapterIndexAt(playback.trackIndex, playback.positionMs)
    val global = book.globalMs(playback.trackIndex, playback.positionMs)
    val list = rememberLazyListState()
    LaunchedEffect(book.id) { list.scrollToItem((current - 2).coerceAtLeast(0)) }

    Column(Modifier.fillMaxSize().background(BD.Ink).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.padding(start = 12.dp, end = 16.dp, top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RoundIconButton(BdIcons.ChevronLeft, "Back to player", onBack, iconSize = 24.dp)
            Column {
                Text("CHAPTERS", style = Type.display(30.sp), color = BD.Text)
                Text(
                    "${book.title} · ${chapters.size} chapters · ${Library.formatDuration(book.durationMs)}",
                    style = Type.small, color = BD.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ProgressLine(if (book.durationMs > 0) global.toFloat() / book.durationMs else 0f, Modifier.weight(1f), height = 4.dp)
            Text("${Library.formatDuration(book.durationMs - global)} left", style = Type.caption, color = BD.Muted)
        }
        LazyColumn(state = list, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(chapters) { i, ch ->
                val start = book.chapterStartGlobal(i)
                val end = book.chapterEndGlobal(i)
                val isCurrent = i == current
                val done = i < current
                val shape = RoundedCornerShape(14.dp)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = if (isCurrent) 60.dp else 52.dp).clip(shape)
                        .then(if (isCurrent) Modifier.background(BD.AmberTint).border(1.dp, BD.AmberLine, shape) else Modifier)
                        .clickable(onClickLabel = "Play ${ch.title}") { onPick(i) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        "${i + 1}", fontFamily = Condensed, fontWeight = FontWeight(if (isCurrent) 800 else 700), fontSize = if (isCurrent) 18.sp else 16.sp,
                        color = when { isCurrent -> BD.Amber; done -> BD.Faint; else -> BD.Muted }, modifier = Modifier.width(32.dp),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(ch.title, style = if (isCurrent) Type.bodyStrong else Type.body, color = if (done) BD.Muted else BD.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (isCurrent && end > start) {
                            ProgressLine((global - start).toFloat() / (end - start), Modifier.fillMaxWidth(), track = BD.AmberLine)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        when {
                            isCurrent -> {
                                Icon(BdIcons.Bars, null, tint = BD.Amber, modifier = Modifier.size(14.dp))
                                Text("-" + Library.formatClock(end - global), style = Type.small, color = BD.Amber)
                            }
                            else -> {
                                if (done) Icon(BdIcons.Check, "Listened", tint = BD.Dim, modifier = Modifier.size(14.dp))
                                Text(if (end > start) Library.formatClock(end - start) else "", style = Type.small, color = BD.Dim)
                            }
                        }
                    }
                }
            }
        }
    }
}
