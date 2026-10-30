package se.jferm.bookdriver

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import se.jferm.bookdriver.auth.GoogleAuth
import se.jferm.bookdriver.data.LibraryRepository
import se.jferm.bookdriver.data.Settings
import se.jferm.bookdriver.drive.BookImporter
import se.jferm.bookdriver.drive.DriveApi
import se.jferm.bookdriver.drive.LibrarySync
import se.jferm.bookdriver.drive.ProgressSync
import se.jferm.bookdriver.drive.buildDriveHttpClient
import se.jferm.bookdriver.playback.SleepTimer

/** App-wide singletons, shared by the UI and the playback service (same process). */
class BookDriverApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var auth: GoogleAuth
        private set
    lateinit var http: OkHttpClient
        private set
    lateinit var drive: DriveApi
        private set
    lateinit var library: LibraryRepository
        private set
    lateinit var settings: Settings
        private set
    lateinit var importer: BookImporter
        private set
    lateinit var progressSync: ProgressSync
        private set
    lateinit var librarySync: LibrarySync
        private set
    lateinit var sleepTimer: SleepTimer
        private set

    override fun onCreate() {
        super.onCreate()
        auth = GoogleAuth(this, getSharedPreferences("auth", MODE_PRIVATE))
        http = buildDriveHttpClient(auth)
        drive = DriveApi(http)
        library = LibraryRepository(this, appScope)
        settings = Settings(this)
        importer = BookImporter(drive, library, appScope)
        progressSync = ProgressSync(drive, library, appScope)
        librarySync = LibrarySync(drive, library, appScope)
        library.onLibraryChanged = { if (auth.account.value != null) librarySync.schedule() }
        sleepTimer = SleepTimer(this, settings)
    }
}
