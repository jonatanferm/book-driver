package se.jferm.bookdriver.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.jferm.bookdriver.data.Book
import se.jferm.bookdriver.data.ImportStatus
import se.jferm.bookdriver.data.Library
import se.jferm.bookdriver.data.Progress
import se.jferm.bookdriver.ui.PlaybackUi
import se.jferm.bookdriver.ui.components.BdIcons
import se.jferm.bookdriver.ui.components.BookCover
import se.jferm.bookdriver.ui.components.FilterChip
import se.jferm.bookdriver.ui.components.PrimaryButton
import se.jferm.bookdriver.ui.components.ProgressLine
import se.jferm.bookdriver.ui.components.RoundIconButton
import se.jferm.bookdriver.ui.theme.BD
import se.jferm.bookdriver.ui.theme.Condensed
import se.jferm.bookdriver.ui.theme.Inter
import se.jferm.bookdriver.ui.theme.Type
import java.io.File

private enum class Filter(val label: String) { All("All"), InProgress("In progress"), New("New"), Finished("Finished") }

class LibraryActions(
    val onPlay: (String) -> Unit,
    val onOpenPlayer: () -> Unit,
    val onTogglePlay: () -> Unit,
    val onAddBook: () -> Unit,
    val onSettings: () -> Unit,
    val onRemove: (String) -> Unit,
    val onSetFinished: (String, Boolean) -> Unit,
    val onRetry: (String) -> Unit,
)

fun progressFraction(book: Book, p: Progress?): Float {
    if (p == null || book.durationMs <= 0) return 0f
    if (p.finished) return 1f
    return (book.globalMs(p.trackIndex, p.positionMs).toFloat() / book.durationMs).coerceIn(0f, 1f)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    books: List<Book>,
    progress: Map<String, Progress>,
    playback: PlaybackUi,
    account: String?,
    coversDir: File,
    actions: LibraryActions,
) {
    var filter by rememberSaveable { mutableStateOf(Filter.All) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }

    val continueBook = books
        .filter { b -> progress[b.id]?.let { !it.finished && it.lastPlayedAt > 0 } == true }
        .maxByOrNull { progress[it.id]!!.lastPlayedAt }

    val shown = books
        .filter { b ->
            val p = progress[b.id]
            when (filter) {
                Filter.All -> true
                Filter.InProgress -> p != null && !p.finished && (p.positionMs > 0 || p.trackIndex > 0)
                Filter.New -> p == null || (!p.finished && p.positionMs == 0L && p.trackIndex == 0)
                Filter.Finished -> p?.finished == true
            }
        }
        .filter { query.isBlank() || it.title.contains(query, true) || it.author?.contains(query, true) == true || it.series?.contains(query, true) == true }
        .sortedByDescending { progress[it.id]?.lastPlayedAt?.takeIf { t -> t > 0 } ?: it.addedAt }

    val playingBook = playback.bookId?.let { id -> books.firstOrNull { it.id == id } }

    Box(Modifier.fillMaxSize().background(BD.Ink)) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = if (playingBook != null) 112.dp else 32.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item(span = { GridItemSpan(2) }) {
                Header(searching, query, account, onQuery = { query = it }, onToggleSearch = { searching = !searching; if (!searching) query = "" }, actions)
            }
            if (books.isEmpty()) {
                item(span = { GridItemSpan(2) }) { EmptyLibrary(actions.onAddBook) }
            } else {
                if (continueBook != null && !searching) {
                    item(span = { GridItemSpan(2) }) {
                        ContinueCard(continueBook, progress[continueBook.id], playback, coversDir, actions)
                    }
                }
                item(span = { GridItemSpan(2) }) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Filter.entries.forEach { f -> FilterChip(f.label, f == filter) { filter = f } }
                    }
                }
                if (shown.isEmpty()) {
                    item(span = { GridItemSpan(2) }) {
                        Text("Nothing here yet.", style = Type.body, color = BD.Muted, modifier = Modifier.padding(vertical = 24.dp))
                    }
                }
                items(shown, key = { it.id }) { book ->
                    BookTile(book, progress[book.id], coversDir, actions)
                }
            }
        }

        if (playingBook != null) {
            MiniPlayer(
                playingBook, playback, coversDir, actions,
                Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(12.dp),
            )
        }
    }
}

@Composable
private fun Header(
    searching: Boolean,
    query: String,
    account: String?,
    onQuery: (String) -> Unit,
    onToggleSearch: () -> Unit,
    actions: LibraryActions,
) {
    Column {
        Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("LIBRARY", style = Type.display(40.sp), color = BD.Text, modifier = Modifier.weight(1f))
            RoundIconButton(if (searching) BdIcons.Close else BdIcons.Search, if (searching) "Close search" else "Search library", onToggleSearch)
            RoundIconButton(BdIcons.Plus, "Add a book", actions.onAddBook, tint = BD.Amber, background = BD.Raised)
            Box(
                Modifier.size(44.dp).clip(CircleShape).clickable(onClickLabel = "Account and settings", onClick = actions.onSettings),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Color(0xFF2F5D8A)), contentAlignment = Alignment.Center) {
                    Text((account?.firstOrNull() ?: '?').uppercase(), fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = BD.Text)
                }
            }
        }
        if (searching) {
            Box(
                Modifier.fillMaxWidth().padding(top = 8.dp).height(48.dp).clip(RoundedCornerShape(14.dp)).background(BD.Surface)
                    .border(1.dp, BD.Line, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (query.isEmpty()) Text("Title, author or series", style = Type.body, color = BD.Dim)
                BasicTextField(
                    query, onQuery, singleLine = true,
                    textStyle = Type.body.copy(color = BD.Text),
                    cursorBrush = SolidColor(BD.Amber),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search library" },
                )
            }
        }
    }
}

@Composable
private fun ContinueCard(book: Book, p: Progress?, playback: PlaybackUi, coversDir: File, actions: LibraryActions) {
    // The loaded book: follow the player live rather than the last saved position.
    val live = playback.bookId == book.id && playback.positionMs > 0
    val track = if (live) playback.trackIndex else p?.trackIndex ?: 0
    val pos = if (live) playback.positionMs else p?.positionMs ?: 0
    val chapterIdx = book.chapterIndexAt(track, pos)
    val left = book.durationMs - book.globalMs(track, pos)
    val isThisPlaying = playback.bookId == book.id && playback.isPlaying
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(BD.Surface).border(1.dp, BD.Line, RoundedCornerShape(20.dp))
            .clickable(onClickLabel = "Open ${book.title}") { actions.onPlay(book.id) }.padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BookCover(book, coversDir, Modifier.size(112.dp), corner = 10.dp)
        Column(Modifier.weight(1f).height(112.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("CONTINUE", style = Type.overline, color = BD.Amber)
            Text(book.title, style = Type.title(20.sp, 700), color = BD.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "${book.effectiveChapters.getOrNull(chapterIdx)?.title ?: ""} · ${Library.formatDuration(left)} left",
                style = Type.small, color = BD.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ProgressLine(
                    if (live && book.durationMs > 0) (book.globalMs(track, pos).toFloat() / book.durationMs) else progressFraction(book, p),
                    Modifier.weight(1f), height = 4.dp,
                )
                RoundIconButton(
                    if (isThisPlaying) BdIcons.Pause else BdIcons.Play,
                    if (isThisPlaying) "Pause" else "Play ${book.title}",
                    onClick = { if (isThisPlaying) actions.onTogglePlay() else actions.onPlay(book.id) },
                    size = 48.dp, iconSize = 20.dp, tint = BD.OnAmber, background = BD.Amber,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookTile(book: Book, p: Progress?, coversDir: File, actions: LibraryActions) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).combinedClickable(
                onClickLabel = "Play ${book.title}",
                onLongClickLabel = "Book options",
                onClick = { if (book.status == ImportStatus.FAILED) menu = true else actions.onPlay(book.id) },
                onLongClick = { menu = true },
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BookCover(book, coversDir, Modifier.fillMaxWidth().aspectRatio(1f))
            Text(book.title, style = Type.bodyStrong.copy(fontSize = 14.sp, lineHeight = 18.sp), color = BD.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            when {
                book.status == ImportStatus.IMPORTING -> StatusLine(BdIcons.Spinner, "Reading chapters…", BD.Amber)
                book.status == ImportStatus.FAILED -> StatusLine(BdIcons.Warning, "Couldn't read · tap", BD.Danger)
                p?.finished == true -> StatusLine(BdIcons.Check, "Finished", BD.Muted)
                p == null || (p.positionMs == 0L && p.trackIndex == 0) ->
                    Text("NEW", style = Type.overline.copy(letterSpacing = 1.sp), color = BD.Amber)
                else -> ProgressLine(progressFraction(book, p), Modifier.fillMaxWidth())
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (book.status == ImportStatus.FAILED) {
                DropdownMenuItem(text = { Text("Try reading it again") }, onClick = { menu = false; actions.onRetry(book.id) })
            }
            if (p?.finished == true) {
                DropdownMenuItem(text = { Text("Mark as not finished") }, onClick = { menu = false; actions.onSetFinished(book.id, false) })
            } else {
                DropdownMenuItem(text = { Text("Mark as finished") }, onClick = { menu = false; actions.onSetFinished(book.id, true) })
            }
            DropdownMenuItem(text = { Text("Remove from library") }, onClick = { menu = false; actions.onRemove(book.id) })
        }
    }
}

@Composable
private fun StatusLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
        Text(text, style = Type.caption, color = color)
    }
}

@Composable
private fun MiniPlayer(book: Book, playback: PlaybackUi, coversDir: File, actions: LibraryActions, modifier: Modifier) {
    val chapterIdx = book.chapterIndexAt(playback.trackIndex, playback.positionMs)
    val fraction = if (book.durationMs > 0) book.globalMs(playback.trackIndex, playback.positionMs).toFloat() / book.durationMs else 0f
    Box(
        modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(16.dp)).background(BD.Raised)
            .border(1.dp, BD.Line, RoundedCornerShape(16.dp)).clickable(onClickLabel = "Open player", onClick = actions.onOpenPlayer),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BookCover(book, coversDir, Modifier.size(46.dp), corner = 8.dp)
            Column(Modifier.weight(1f)) {
                Text(book.title, style = Type.bodyStrong.copy(fontSize = 14.sp), color = BD.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${book.effectiveChapters.getOrNull(chapterIdx)?.title ?: ""} · ${Library.formatClock(playback.positionMs)}",
                    style = Type.caption, color = BD.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            RoundIconButton(if (playback.isPlaying) BdIcons.Pause else BdIcons.Play, if (playback.isPlaying) "Pause" else "Play", actions.onTogglePlay)
            Spacer(Modifier.width(2.dp))
        }
        ProgressLine(fraction, Modifier.align(Alignment.BottomStart).fillMaxWidth(), height = 2.dp, track = BD.Raised)
    }
}

@Composable
private fun EmptyLibrary(onAdd: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        se.jferm.bookdriver.ui.components.GeneratedCover("Your first book", null, null, null, Modifier.size(160.dp), corner = 18.dp)
        Text("NOTHING ON THE SHELF", style = Type.display(28.sp), color = BD.Text)
        Text("Add an audiobook from your Google Drive to start listening.", style = Type.body, color = BD.Muted)
        Spacer(Modifier.height(8.dp))
        PrimaryButton("Add a book", onAdd, icon = BdIcons.Plus)
    }
}
