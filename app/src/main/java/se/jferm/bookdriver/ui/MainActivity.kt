package se.jferm.bookdriver.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.launch
import se.jferm.bookdriver.DiagLog
import se.jferm.bookdriver.ui.screens.AddBookScreen
import se.jferm.bookdriver.ui.screens.ChaptersScreen
import se.jferm.bookdriver.ui.screens.LibraryActions
import se.jferm.bookdriver.ui.screens.LibraryScreen
import se.jferm.bookdriver.ui.screens.PlayerActions
import se.jferm.bookdriver.ui.screens.PlayerScreen
import se.jferm.bookdriver.ui.screens.SettingsActions
import se.jferm.bookdriver.ui.screens.SettingsSheet
import se.jferm.bookdriver.ui.screens.SpeedSleepActions
import se.jferm.bookdriver.ui.screens.SpeedSleepSheet
import se.jferm.bookdriver.ui.screens.WelcomeScreen
import se.jferm.bookdriver.ui.theme.BD
import se.jferm.bookdriver.ui.theme.BookDriverTheme

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()
    private var pendingKind: AuthKind? = null

    private val authLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val kind = pendingKind ?: return@registerForActivityResult
        pendingKind = null
        try {
            val result = vm.auth.resultFromIntent(res.data)
            if (res.resultCode == RESULT_OK) vm.onAuthResult(result, kind)
            else DiagLog.i("auth", "$kind cancelled")
        } catch (e: ApiException) {
            DiagLog.e("auth", "$kind failed: status ${e.statusCode} ${e.status.statusMessage}", e)
            if (res.resultCode == RESULT_OK || e.statusCode != 16) vm.onAuthError(authErrorMessage(e.statusCode))
        }
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun startAuth(kind: AuthKind) {
        lifecycleScope.launch {
            val request = when (kind) {
                AuthKind.CONNECT -> vm.auth.connectRequest()
                AuthKind.PICK -> vm.auth.pickerRequest(allowFolders = true)
            }
            try {
                val result = vm.auth.authorize(request)
                val pi = result.pendingIntent
                if (result.hasResolution() && pi != null) {
                    pendingKind = kind
                    authLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                } else {
                    vm.onAuthResult(result, kind)
                }
            } catch (e: ApiException) {
                DiagLog.e("auth", "$kind authorize() failed: status ${e.statusCode}", e)
                vm.onAuthError(authErrorMessage(e.statusCode))
            } catch (t: Throwable) {
                DiagLog.e("auth", "$kind authorize() failed", t)
                vm.onAuthError("Couldn't reach Google. Check your connection and try again.")
            }
        }
    }

    private fun authErrorMessage(status: Int) = when (status) {
        10 -> "Google doesn't recognise this build of the app (developer error 10)."
        7 -> "No connection. Try again when you're online."
        else -> "Sign-in didn't work (code $status). Try again."
    }

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingKind = savedInstanceState?.getString(KEY_PENDING)?.let { AuthKind.valueOf(it) }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""

        setContent {
            BookDriverTheme {
                val stack by vm.stack.collectAsStateWithLifecycle()
                val sheet by vm.sheet.collectAsStateWithLifecycle()
                val books by vm.library.books.collectAsStateWithLifecycle()
                val progress by vm.library.progress.collectAsStateWithLifecycle()
                val playback by vm.playback.collectAsStateWithLifecycle()
                val account by vm.auth.account.collectAsStateWithLifecycle()
                val import by vm.import.collectAsStateWithLifecycle()
                val speed by vm.settings.speed.collectAsStateWithLifecycle()
                val sleep by vm.sleepTimer.state.collectAsStateWithLifecycle()
                val smartRewind by vm.settings.smartRewind.collectAsStateWithLifecycle()
                val skipSilence by vm.settings.skipSilence.collectAsStateWithLifecycle()
                val pauseForSounds by vm.settings.pauseForOtherSounds.collectAsStateWithLifecycle()
                val shake by vm.settings.shakeToExtend.collectAsStateWithLifecycle()
                val toast by vm.toast.collectAsStateWithLifecycle()

                LaunchedEffect(toast) {
                    toast?.let { Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show(); vm.consumeToast() }
                }

                BackHandler(enabled = stack.size > 1 || sheet != Sheet.None) { vm.back() }

                val screen = stack.last()
                val currentBook = playback.bookId?.let { id -> books.firstOrNull { it.id == id } }

                Box(Modifier.fillMaxSize().background(BD.Ink)) {
                    AnimatedContent(targetState = screen, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen") { s ->
                        when (s) {
                            Screen.Welcome -> WelcomeScreen(onConnect = { startAuth(AuthKind.CONNECT) })
                            Screen.Library -> LibraryScreen(
                                books, progress, playback, account, vm.coversDir,
                                LibraryActions(
                                    onPlay = { askForNotifications(); vm.play(it) },
                                    onOpenPlayer = { vm.open(Screen.Player) },
                                    onTogglePlay = vm::togglePlay,
                                    onAddBook = { vm.open(Screen.AddBook) },
                                    onSettings = { vm.showSheet(Sheet.Settings) },
                                    onRemove = vm::removeBook,
                                    onSetFinished = vm::setFinished,
                                    onRetry = vm::retryImport,
                                ),
                            )
                            Screen.Player -> if (currentBook == null) {
                                LaunchedEffect(Unit) { kotlinx.coroutines.delay(1500); if (vm.currentBook() == null) vm.back() }
                            } else PlayerScreen(
                                currentBook, playback, speed, sleep, vm.coversDir,
                                PlayerActions(
                                    onBack = { vm.back() },
                                    onTogglePlay = vm::togglePlay,
                                    onSkipBack = { vm.skipBack() },
                                    onSkipForward = { vm.skipForward() },
                                    onPrevChapter = { vm.previousChapter() },
                                    onNextChapter = { vm.nextChapter() },
                                    onSeekInChapter = vm::seekInChapter,
                                    onChapters = { vm.open(Screen.Chapters) },
                                    onSpeedSleep = { vm.showSheet(Sheet.SpeedSleep) },
                                    onMarkFinished = { vm.setFinished(currentBook.id, true); vm.back() },
                                ),
                            )
                            Screen.Chapters -> if (currentBook == null) {
                                LaunchedEffect(Unit) { kotlinx.coroutines.delay(1500); if (vm.currentBook() == null) vm.back() }
                            } else ChaptersScreen(
                                currentBook, playback,
                                onBack = { vm.back() },
                                onPick = { vm.seekToChapter(it); vm.back() },
                            )
                            Screen.AddBook -> AddBookScreen(
                                import = import,
                                justAdded = import.bookIds.mapNotNull { id -> books.firstOrNull { it.id == id } },
                                coversDir = vm.coversDir,
                                onBack = { vm.back() },
                                onPick = { startAuth(AuthKind.PICK) },
                                onOpen = { askForNotifications(); vm.replaceAll(Screen.Library); vm.play(it) },
                                onRetry = vm::retryImport,
                            )
                        }
                    }

                    when (sheet) {
                        Sheet.SpeedSleep -> SpeedSleepSheet(
                            speed, sleep, shake, smartRewind, skipSilence,
                            SpeedSleepActions(
                                onSpeed = vm.settings::setSpeed,
                                onSleepMinutes = vm.sleepTimer::setMinutes,
                                onSleepEndOfChapter = vm.sleepTimer::setEndOfChapter,
                                onSleepOff = vm.sleepTimer::cancel,
                                onShake = vm.settings::setShakeToExtend,
                                onSmartRewind = vm.settings::setSmartRewind,
                                onSkipSilence = vm.settings::setSkipSilence,
                                onDone = { vm.showSheet(Sheet.None) },
                            ),
                        )
                        Sheet.Settings -> SettingsSheet(
                            account, smartRewind, skipSilence, pauseForSounds, version,
                            SettingsActions(
                                onSwitchAccount = { vm.showSheet(Sheet.None); startAuth(AuthKind.CONNECT) },
                                onSignOut = vm::signOut,
                                onCopyDiagnostics = {
                                    getSystemService(ClipboardManager::class.java)
                                        .setPrimaryClip(ClipData.newPlainText("Book Driver log", vm.copyDiagnostics()))
                                    Toast.makeText(this@MainActivity, "Diagnostics copied", Toast.LENGTH_SHORT).show()
                                },
                                onSmartRewind = vm.settings::setSmartRewind,
                                onSkipSilence = vm.settings::setSkipSilence,
                                onPauseForOtherSounds = vm.settings::setPauseForOtherSounds,
                                onDismiss = { vm.showSheet(Sheet.None) },
                            ),
                        )
                        Sheet.None -> Unit
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingKind?.let { outState.putString(KEY_PENDING, it.name) }
    }

    companion object {
        private const val KEY_PENDING = "pending_auth_kind"
    }
}
