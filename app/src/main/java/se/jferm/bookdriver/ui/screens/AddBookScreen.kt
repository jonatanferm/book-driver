package se.jferm.bookdriver.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.jferm.bookdriver.data.Book
import se.jferm.bookdriver.data.ImportStatus
import se.jferm.bookdriver.data.Library
import se.jferm.bookdriver.ui.ImportUi
import se.jferm.bookdriver.ui.components.BdIcons
import se.jferm.bookdriver.ui.components.BookCover
import se.jferm.bookdriver.ui.components.PrimaryButton
import se.jferm.bookdriver.ui.components.RoundIconButton
import se.jferm.bookdriver.ui.components.SectionLabel
import se.jferm.bookdriver.ui.theme.BD
import se.jferm.bookdriver.ui.theme.Type
import java.io.File

@Composable
fun AddBookScreen(
    import: ImportUi,
    justAdded: List<Book>,
    coversDir: File,
    onBack: () -> Unit,
    onPick: () -> Unit,
    onOpen: (String) -> Unit,
    onRetry: (String) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(BD.Ink).windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(Modifier.padding(start = 0.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(BdIcons.ChevronLeft, "Back to library", onBack, iconSize = 24.dp, modifier = Modifier.padding(end = 4.dp))
            Text("ADD A BOOK", style = Type.display(30.sp), color = BD.Text)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Pick the audio files for one book in Google Drive. Book Driver only ever sees the files you choose.",
            style = Type.body.copy(fontSize = 16.sp, lineHeight = 24.sp), color = BD.TextSoft,
        )
        Spacer(Modifier.height(20.dp))
        Hint(BdIcons.FileAudio, "One file, like an .m4b", "Tap the file. Chapters inside it are found automatically.")
        Spacer(Modifier.height(10.dp))
        Hint(BdIcons.Folder, "A folder of chapter files", "Open the folder, long-press the first file, then tap the rest. They're sorted into chapter order for you.")
        Spacer(Modifier.height(20.dp))
        PrimaryButton(if (import.running) "Reading your selection…" else "Choose files in Google Drive", onPick, icon = BdIcons.Plus, enabled = !import.running)

        import.message?.let {
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(BD.AmberTint).border(1.dp, BD.AmberLine, RoundedCornerShape(14.dp)).padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(BdIcons.Warning, null, tint = BD.Amber, modifier = Modifier.size(18.dp))
                Text(it, style = Type.small, color = BD.Text)
            }
        }

        if (justAdded.isNotEmpty()) {
            Spacer(Modifier.height(32.dp))
            SectionLabel("Just added", Modifier.padding(bottom = 10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                justAdded.forEach { AddedCard(it, coversDir, onOpen, onRetry) }
            }
        }

        Spacer(Modifier.height(32.dp))
        Text(
            "Picking a whole folder won't work: Google only shares the files you select, which keeps the rest of your Drive private.",
            style = Type.small, color = BD.Dim,
        )
    }
}

@Composable
private fun Hint(icon: ImageVector, title: String, body: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(BD.Surface).border(1.dp, BD.Line, RoundedCornerShape(18.dp)).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(BD.Raised), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = BD.Amber, modifier = Modifier.size(22.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = Type.bodyStrong, color = BD.Text)
            Text(body, style = Type.small.copy(fontSize = 14.sp, lineHeight = 20.sp), color = BD.Muted)
        }
    }
}

@Composable
private fun AddedCard(book: Book, coversDir: File, onOpen: (String) -> Unit, onRetry: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(BD.Surface).border(1.dp, BD.Line, RoundedCornerShape(18.dp))
            .clickable(enabled = book.status != ImportStatus.IMPORTING) {
                if (book.status == ImportStatus.FAILED) onRetry(book.id) else onOpen(book.id)
            }
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(book, coversDir, Modifier.size(64.dp), corner = 10.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(book.title, style = Type.bodyStrong, color = BD.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val files = if (book.tracks.size == 1) "1 file" else "${book.tracks.size} files"
            val size = "%.1f GB".format(book.sizeBytes / 1_073_741_824.0).takeIf { book.sizeBytes > 900_000_000 } ?: "${book.sizeBytes / 1_048_576} MB"
            Text(listOf(files, size, Library.formatDuration(book.durationMs).takeIf { book.durationMs > 0 }).filterNotNull().joinToString(" · "), style = Type.small, color = BD.Muted)
            when (book.status) {
                ImportStatus.IMPORTING -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CircularProgressIndicator(color = BD.Amber, strokeWidth = 2.dp, modifier = Modifier.size(12.dp))
                    Text("Reading chapters…", style = Type.small, color = BD.Amber)
                }
                ImportStatus.READY -> Text("${book.effectiveChapters.size} chapters · tap to play", style = Type.small, color = BD.Amber)
                ImportStatus.FAILED -> Text("Couldn't read it · tap to retry", style = Type.small, color = BD.Danger)
            }
        }
    }
}
