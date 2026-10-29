package se.jferm.bookdriver.drive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class DriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: Long?,
    val parents: List<String>,
) {
    val isFolder get() = mimeType == DriveApi.FOLDER_MIME
    val isAudio get() = mimeType.startsWith("audio/") || mimeType == "video/mp4" ||
        AUDIO_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }
    val hasAudioExtension get() = AUDIO_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }

    companion object {
        private val AUDIO_EXTENSIONS = listOf(".mp3", ".m4a", ".m4b", ".aac", ".ogg", ".opus", ".flac", ".wav")
    }
}

class DriveException(val code: Int, val reason: String?, message: String) : Exception("HTTP $code: $message")

/** Thin Drive v3 REST wrapper. No Google client library: OkHttp + org.json is all we need. */
class DriveApi(val http: OkHttpClient) {

    companion object {
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private const val BASE = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        private const val FILE_FIELDS = "id,name,mimeType,size,parents"
    }

    fun mediaUrl(fileId: String) = "$BASE/files/$fileId?alt=media"

    suspend fun get(fileId: String): DriveFile = withContext(Dispatchers.IO) {
        val url = "$BASE/files/$fileId".toHttpUrl().newBuilder()
            .addQueryParameter("fields", FILE_FIELDS)
            .addQueryParameter("supportsAllDrives", "true")
            .build()
        parseFile(JSONObject(execute(Request.Builder().url(url).build())))
    }

    /** Email of the signed-in user. Works with drive.file alone. */
    suspend fun aboutEmail(): String = withContext(Dispatchers.IO) {
        val json = JSONObject(execute(Request.Builder().url("$BASE/about?fields=user(emailAddress)").build()))
        json.getJSONObject("user").getString("emailAddress")
    }

    suspend fun listChildren(folderId: String): List<DriveFile> =
        list("'$folderId' in parents and trashed = false", space = null)

    private suspend fun list(q: String, space: String?): List<DriveFile> = withContext(Dispatchers.IO) {
        val out = mutableListOf<DriveFile>()
        var pageToken: String? = null
        do {
            val url: HttpUrl = "$BASE/files".toHttpUrl().newBuilder()
                .addQueryParameter("q", q)
                .addQueryParameter("fields", "nextPageToken,files($FILE_FIELDS)")
                .addQueryParameter("pageSize", "1000")
                .apply {
                    if (space != null) addQueryParameter("spaces", space)
                    else {
                        addQueryParameter("supportsAllDrives", "true")
                        addQueryParameter("includeItemsFromAllDrives", "true")
                    }
                    pageToken?.let { addQueryParameter("pageToken", it) }
                }
                .build()
            val json = JSONObject(execute(Request.Builder().url(url).build()))
            val files = json.optJSONArray("files") ?: JSONArray()
            for (i in 0 until files.length()) out += parseFile(files.getJSONObject(i))
            pageToken = json.optString("nextPageToken").ifEmpty { null }
        } while (pageToken != null)
        out
    }

    /** Read [length] bytes at [offset] (fewer at end of file) with a Range request. */
    suspend fun readRange(fileId: String, offset: Long, length: Int): ByteArray = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(mediaUrl(fileId))
            .header("Range", "bytes=$offset-${offset + length - 1}")
            .build()
        http.newCall(req).execute().use { res ->
            if (res.code != 206 && res.code != 200) {
                throw DriveException(res.code, null, "range read failed for $fileId")
            }
            val source = res.body.source()
            if (res.code == 200 && offset > 0) source.skip(offset) // server ignored Range
            val out = okio.Buffer()
            var remaining = length.toLong()
            while (remaining > 0) {
                val n = source.read(out, remaining)
                if (n == -1L) break
                remaining -= n
            }
            out.readByteArray()
        }
    }

    /** Fetch the first 16 bytes. Returns HTTP status (206 = content readable + ranges work). */
    suspend fun probeContent(fileId: String): Int = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(mediaUrl(fileId)).header("Range", "bytes=0-15").build()
        http.newCall(req).execute().use { it.code }
    }

    // ---- appDataFolder (hidden per-app storage in the user's own Drive) ----------------

    suspend fun findAppDataFile(name: String): DriveFile? =
        list("name = '${name.replace("'", "\\'")}'", space = "appDataFolder").firstOrNull()

    suspend fun downloadText(fileId: String): String = withContext(Dispatchers.IO) {
        execute(Request.Builder().url(mediaUrl(fileId)).build())
    }

    suspend fun createAppDataFile(name: String, json: String): String = withContext(Dispatchers.IO) {
        val meta = JSONObject().put("name", name).put("parents", JSONArray().put("appDataFolder"))
        val body = MultipartBody.Builder().setType("multipart/related".toMediaType())
            .addPart(meta.toString().toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .addPart(json.toRequestBody("application/json".toMediaType()))
            .build()
        val url = "$UPLOAD/files?uploadType=multipart&fields=id"
        JSONObject(execute(Request.Builder().url(url).post(body).build())).getString("id")
    }

    suspend fun updateFileContent(fileId: String, json: String): Unit = withContext(Dispatchers.IO) {
        val url = "$UPLOAD/files/$fileId?uploadType=media&fields=id"
        execute(Request.Builder().url(url).patch(json.toRequestBody("application/json".toMediaType())).build())
    }

    // ---- helpers ---------------------------------------------------------------------

    private fun execute(req: Request): String =
        http.newCall(req).execute().use { res ->
            val body = res.body.string()
            if (!res.isSuccessful) {
                val err = runCatching { JSONObject(body).getJSONObject("error") }.getOrNull()
                val reason = err?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason")
                throw DriveException(res.code, reason, err?.optString("message") ?: body.take(300))
            }
            body
        }

    private fun parseFile(o: JSONObject): DriveFile {
        val parents = o.optJSONArray("parents")
        return DriveFile(
            id = o.getString("id"),
            name = o.optString("name"),
            mimeType = o.optString("mimeType"),
            size = if (o.has("size")) o.optString("size").toLongOrNull() else null,
            parents = (0 until (parents?.length() ?: 0)).map { parents!!.getString(it) },
        )
    }
}
