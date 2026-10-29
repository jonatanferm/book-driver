package se.jferm.bookdriver.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.jferm.bookdriver.ui.components.BdIcons
import se.jferm.bookdriver.ui.components.PrimaryButton
import se.jferm.bookdriver.ui.theme.BD
import se.jferm.bookdriver.ui.theme.Compressed
import se.jferm.bookdriver.ui.theme.Type

@Composable
fun WelcomeScreen(onConnect: () -> Unit) {
    Box(Modifier.fillMaxSize().background(BD.Ink)) {
        CoverWall(Modifier.align(Alignment.TopCenter).offset(y = 96.dp))
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 28.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            Text(
                "BOOK\nDRIVER",
                fontFamily = Compressed, fontWeight = FontWeight(900),
                fontSize = 76.sp, lineHeight = 66.sp, letterSpacing = (-1).sp, color = BD.Text,
            )
            Spacer(Modifier.height(16.dp))
            Text("Your audiobooks, streamed straight from your own Google Drive.", style = Type.body.copy(fontSize = 18.sp, lineHeight = 26.sp), color = BD.TextSoft)
            Spacer(Modifier.height(28.dp))
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Point(BdIcons.Download, "No uploading, no syncing. Books stay where they are.")
                Point(BdIcons.Clock, "Picks up where you left off, on every device.")
                Point(BdIcons.Shield, "Only sees the files you pick. Free and open source.")
            }
            Spacer(Modifier.height(32.dp))
            PrimaryButton("Connect Google Drive", onConnect, trailing = BdIcons.ArrowRight)
            Spacer(Modifier.height(14.dp))
            Text("You'll choose a Google account next.", style = Type.small, color = BD.Dim, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Point(icon: ImageVector, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = BD.Amber, modifier = Modifier.size(22.dp))
        Text(text, style = Type.body, color = BD.Text)
    }
}

/** Decorative tilted rows of blank covers. */
@Composable
private fun CoverWall(modifier: Modifier) {
    val rowA = listOf(Color(0xFF2F5D8A), Color(0xFFC2410C), Color(0xFF2E7D5B), Color(0xFF6B3FA0), Color(0xFF7A2E3A))
    val rowB = listOf(Color(0xFF7A2E3A), BD.Raised, BD.Amber, Color(0xFF2F5D8A), Color(0xFF2E7D5B))
    Column(modifier.requiredWidth(640.dp).rotate(-8f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.offset(x = 40.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            rowA.forEach { Box(Modifier.size(120.dp).clip(RoundedCornerShape(14.dp)).background(it)) }
        }
        Row(Modifier.offset(x = (-20).dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            rowB.forEach { Box(Modifier.size(120.dp).clip(RoundedCornerShape(14.dp)).background(it)) }
        }
    }
}
