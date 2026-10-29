package se.jferm.bookdriver.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.jferm.bookdriver.data.Library
import se.jferm.bookdriver.data.Settings
import se.jferm.bookdriver.playback.SleepTimer
import se.jferm.bookdriver.ui.components.BdIcons
import se.jferm.bookdriver.ui.components.RoundIconButton
import se.jferm.bookdriver.ui.components.SecondaryButton
import se.jferm.bookdriver.ui.components.SectionLabel
import se.jferm.bookdriver.ui.components.ToggleRow
import se.jferm.bookdriver.ui.theme.BD
import se.jferm.bookdriver.ui.theme.Condensed
import se.jferm.bookdriver.ui.theme.Inter
import se.jferm.bookdriver.ui.theme.Type

class SpeedSleepActions(
    val onSpeed: (Float) -> Unit,
    val onSleepMinutes: (Int) -> Unit,
    val onSleepEndOfChapter: () -> Unit,
    val onSleepOff: () -> Unit,
    val onShake: (Boolean) -> Unit,
    val onSmartRewind: (Boolean) -> Unit,
    val onSkipSilence: (Boolean) -> Unit,
    val onDone: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedSleepSheet(
    speed: Float,
    sleep: SleepTimer.State,
    shake: Boolean,
    smartRewind: Boolean,
    skipSilence: Boolean,
    actions: SpeedSleepActions,
) {
    ModalBottomSheet(
        onDismissRequest = actions.onDone,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = BD.Surface,
        scrimColor = BD.Deep.copy(alpha = 0.7f),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            // Speed
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Speed", Modifier.weight(1f))
                    RoundIconButton(BdIcons.Minus, "Slower", { actions.onSpeed(speed - 0.05f) }, iconSize = 18.dp, background = BD.Raised)
                    Text(
                        "%.2f×".format(speed), fontFamily = Condensed, fontWeight = FontWeight(800), fontSize = 44.sp,
                        color = BD.Text, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 110.dp),
                    )
                    RoundIconButton(BdIcons.Plus, "Faster", { actions.onSpeed(speed + 0.05f) }, iconSize = 18.dp, background = BD.Raised)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Settings.SPEED_PRESETS.forEach { p ->
                        Choice("%.1f".format(p), selected = kotlin.math.abs(p - speed) < 0.01f, Modifier.weight(1f), height = 44) { actions.onSpeed(p) }
                    }
                }
            }
            HorizontalDivider(color = BD.Line)

            // Sleep timer
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Sleep timer", Modifier.weight(1f))
                    if (sleep.mode != SleepTimer.Mode.OFF) {
                        Icon(BdIcons.Moon, null, tint = BD.Amber, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(
                            if (sleep.mode == SleepTimer.Mode.END_OF_CHAPTER) "End of chapter · ${Library.formatClock(sleep.remainingMs)}"
                            else "${Library.formatClock(sleep.remainingMs)} left",
                            fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = BD.Amber,
                        )
                    }
                }
                val m = sleep.mode
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Choice("Off", m == SleepTimer.Mode.OFF, Modifier.weight(1f)) { actions.onSleepOff() }
                        Choice("15 min", m == SleepTimer.Mode.MINUTES && sleep.chosenMinutes == 15, Modifier.weight(1f)) { actions.onSleepMinutes(15) }
                        Choice("30 min", m == SleepTimer.Mode.MINUTES && sleep.chosenMinutes == 30, Modifier.weight(1f)) { actions.onSleepMinutes(30) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Choice("45 min", m == SleepTimer.Mode.MINUTES && sleep.chosenMinutes == 45, Modifier.weight(1f)) { actions.onSleepMinutes(45) }
                        Choice("1 hour", m == SleepTimer.Mode.MINUTES && sleep.chosenMinutes == 60, Modifier.weight(1f)) { actions.onSleepMinutes(60) }
                        Choice("End of chapter", m == SleepTimer.Mode.END_OF_CHAPTER, Modifier.weight(1f)) { actions.onSleepEndOfChapter() }
                    }
                }
                ToggleRow("Shake to extend", "Adds 10 minutes when the timer is fading out", shake, actions.onShake)
            }
            HorizontalDivider(color = BD.Line)

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionLabel("Listening")
                ToggleRow("Smart rewind", "Backs up a few seconds after you pause, more after longer breaks", smartRewind, actions.onSmartRewind)
                ToggleRow("Skip silence", "Trims long pauses in the narration", skipSilence, actions.onSkipSilence)
            }
            SecondaryButton("Done", actions.onDone)
        }
    }
}

@Composable
private fun Choice(text: String, selected: Boolean, modifier: Modifier, height: Int = 52, onClick: () -> Unit) {
    val shape = RoundedCornerShape(if (height < 50) 12.dp else 14.dp)
    Box(
        modifier.height(height.dp).clip(shape)
            .then(
                when {
                    selected && height < 50 -> Modifier.background(BD.Amber)
                    selected -> Modifier.background(BD.AmberTint).border(2.dp, BD.Amber, shape)
                    else -> Modifier.border(1.dp, BD.Line, shape)
                }
            )
            .semantics { this.selected = selected }
            .clickable(role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, fontFamily = Inter, fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            fontSize = if (text.length > 10) 14.sp else 15.sp, lineHeight = 16.sp, textAlign = TextAlign.Center,
            color = if (selected && height < 50) BD.OnAmber else BD.Text,
        )
    }
}

class SettingsActions(
    val onSwitchAccount: () -> Unit,
    val onSignOut: () -> Unit,
    val onCopyDiagnostics: () -> Unit,
    val onSmartRewind: (Boolean) -> Unit,
    val onSkipSilence: (Boolean) -> Unit,
    val onPauseForOtherSounds: (Boolean) -> Unit,
    val onBluetoothKeepAlive: (Boolean) -> Unit,
    val onDismiss: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(account: String?, smartRewind: Boolean, skipSilence: Boolean, pauseForOtherSounds: Boolean, bluetoothKeepAlive: Boolean, version: String, actions: SettingsActions) {
    ModalBottomSheet(
        onDismissRequest = actions.onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = BD.Surface,
        scrimColor = BD.Deep.copy(alpha = 0.7f),
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionLabel("Account")
                Text(account ?: "Not connected", style = Type.bodyStrong, color = BD.Text)
                Text("Book Driver only sees the files you pick in Drive.", style = Type.small, color = BD.Muted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingButton("Switch account", BdIcons.ChevronRight, Modifier.weight(1f), actions.onSwitchAccount)
                SettingButton("Sign out", BdIcons.Logout, Modifier.weight(1f), actions.onSignOut)
            }
            HorizontalDivider(color = BD.Line)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionLabel("Listening")
                ToggleRow("Smart rewind", "Backs up a few seconds after you pause", smartRewind, actions.onSmartRewind)
                ToggleRow("Skip silence", "Trims long pauses in the narration", skipSilence, actions.onSkipSilence)
                ToggleRow(
                    "Pause for other sounds",
                    "Pause during navigation prompts and notifications instead of playing on quietly. Can cause short dropouts over Bluetooth in some cars.",
                    pauseForOtherSounds, actions.onPauseForOtherSounds,
                )
                ToggleRow(
                    "Bluetooth keep-alive",
                    "Adds a faint noise floor over Bluetooth so car stereos don't cut out in quiet moments. Turn off if you hear a hiss on headphones.",
                    bluetoothKeepAlive, actions.onBluetoothKeepAlive,
                )
            }
            HorizontalDivider(color = BD.Line)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionLabel("About")
                Text("Book Driver $version · free and open source (Apache 2.0)", style = Type.small, color = BD.Muted)
                SettingButton("Copy diagnostics log", BdIcons.Copy, Modifier.fillMaxWidth(), actions.onCopyDiagnostics)
            }
        }
    }
}

@Composable
private fun SettingButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.height(52.dp).clip(RoundedCornerShape(14.dp)).background(BD.Raised).clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, tint = BD.Amber, modifier = Modifier.size(18.dp))
        Text(text, style = Type.bodyStrong.copy(fontSize = 14.sp), color = BD.Text)
    }
}
