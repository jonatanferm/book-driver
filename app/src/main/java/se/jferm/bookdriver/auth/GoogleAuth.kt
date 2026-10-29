package se.jferm.bookdriver.auth

import android.accounts.Account
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.edit
import com.google.android.gms.auth.api.identity.AuthorizationClient
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationRequest.ResourceParameter
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await
import se.jferm.bookdriver.DiagLog
import java.util.concurrent.TimeUnit

// IOException so OkHttp/ExoPlayer surface it as a normal network failure.
class NotAuthorizedException(msg: String) : java.io.IOException(msg)

/**
 * All Google auth goes through Play services' AuthorizationClient. There is no
 * refresh-token handling on our side: authorize() hands back a valid access token
 * silently once the user has granted the scopes, and clearToken() forces a new one.
 *
 * Three kinds of request:
 *  - connect: drive.file + drive.appdata, with account chooser (the "sign in" button)
 *  - picker:  drive.file ONLY (Picker rule) + trigger_onepick → Google's Drive picker UI
 *  - token:   same scopes as connect, silent, used by OkHttp for every API/media call
 */
class GoogleAuth(context: Context, private val prefs: SharedPreferences) {

    companion object {
        const val SCOPE_FILE = "https://www.googleapis.com/auth/drive.file"
        const val SCOPE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
        private const val KEY_ACCOUNT = "account_email"
        private const val TOKEN_TTL_MS = 45 * 60 * 1000L // access tokens live ~60 min
        private const val TAG = "auth"
    }

    private val client: AuthorizationClient = Identity.getAuthorizationClient(context)

    private val _account = MutableStateFlow(prefs.getString(KEY_ACCOUNT, null))
    val account: StateFlow<String?> = _account

    @Volatile private var cachedToken: String? = null
    @Volatile private var cachedAt = 0L

    private val sessionScopes = listOf(Scope(SCOPE_FILE), Scope(SCOPE_APPDATA))

    private fun androidAccount(): Account? = _account.value?.let { Account(it, "com.google") }

    // ---- Requests -------------------------------------------------------------------

    fun connectRequest(): AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(sessionScopes)
            .setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT)
            .build()

    fun pickerRequest(allowFolders: Boolean): AuthorizationRequest {
        val b = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(SCOPE_FILE)))
            .setOptOutIncludingGrantedScopes(true)
            .addResourceParameter(ResourceParameter.PICKER_OAUTH_TRIGGER, "true")
            .addResourceParameter(ResourceParameter.PICKER_ALLOW_MULTIPLE, "true")
            .addResourceParameter(ResourceParameter.PICKER_ALLOW_FOLDER_SELECTION, allowFolders.toString())
        val acct = androidAccount()
        if (acct != null) {
            b.setAccount(acct).setPrompt(AuthorizationRequest.Prompt.CONSENT)
        } else {
            b.setPrompt(AuthorizationRequest.Prompt.CONSENT or AuthorizationRequest.Prompt.SELECT_ACCOUNT)
        }
        return b.build()
    }

    private fun tokenRequest(): AuthorizationRequest {
        val b = AuthorizationRequest.builder()
            .setRequestedScopes(sessionScopes)
        androidAccount()?.let { b.setAccount(it) }
        return b.build()
    }

    // ---- UI-driven flows --------------------------------------------------------------

    /** Returns the result; caller launches result.pendingIntent if hasResolution(). */
    suspend fun authorize(request: AuthorizationRequest): AuthorizationResult =
        client.authorize(request).await()

    fun resultFromIntent(data: Intent?): AuthorizationResult =
        client.getAuthorizationResultFromIntent(data)

    /** Record account + token from a completed (no-resolution) result. Returns picked IDs. */
    fun onGranted(result: AuthorizationResult, source: String): List<String> {
        // Deprecated, but it's the only accessor for the chosen account on AuthorizationResult.
        @Suppress("DEPRECATION")
        val email = result.toGoogleSignInAccount()?.email
        DiagLog.i(TAG, "$source granted. account=${email ?: "?"} scopes=${result.grantedScopes}")
        if (email != null) setAccountEmail(email)
        val params = result.tokenResponseParams
        if (params != null && !params.isEmpty) {
            DiagLog.i(TAG, "tokenResponseParams keys=${params.keySet()}")
        }
        // Only cache tokens that carry both scopes; picker tokens are drive.file-only.
        if (result.grantedScopes.containsAll(listOf(SCOPE_FILE, SCOPE_APPDATA))) {
            cachedToken = result.accessToken
            cachedAt = System.currentTimeMillis()
        }
        @Suppress("DEPRECATION")
        val picked = params?.get("picked_file_ids")?.toString().orEmpty()
        return picked.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Remember which account is connected (from the result, or from Drive's about endpoint). */
    fun setAccountEmail(email: String) {
        if (email == _account.value) return
        prefs.edit { putString(KEY_ACCOUNT, email) }
        _account.value = email
        DiagLog.i(TAG, "account = $email")
    }

    suspend fun signOut() {
        val acct = androidAccount()
        try {
            if (acct != null) {
                client.revokeAccess(
                    RevokeAccessRequest.builder()
                        .setAccount(acct)
                        .setScopes(listOf(Scope(SCOPE_FILE), Scope(SCOPE_APPDATA)))
                        .build()
                ).await()
                DiagLog.i(TAG, "revoked access for ${acct.name}")
            }
        } catch (t: Throwable) {
            DiagLog.e(TAG, "revoke failed", t)
        }
        cachedToken = null
        prefs.edit { remove(KEY_ACCOUNT) }
        _account.value = null
    }

    // ---- Background token access (OkHttp threads) ------------------------------------

    /** Blocking. Never call on the main thread. */
    fun blockingToken(): String {
        val t = cachedToken
        if (t != null && System.currentTimeMillis() - cachedAt < TOKEN_TTL_MS) return t
        return refreshBlocking()
    }

    @Synchronized
    private fun refreshBlocking(): String {
        cachedToken?.let { if (System.currentTimeMillis() - cachedAt < TOKEN_TTL_MS) return it }
        val result = Tasks.await(client.authorize(tokenRequest()), 30, TimeUnit.SECONDS)
        if (result.hasResolution()) {
            throw NotAuthorizedException("Needs user interaction — tap Connect")
        }
        val token = result.accessToken ?: throw NotAuthorizedException("No access token returned")
        cachedToken = token
        cachedAt = System.currentTimeMillis()
        DiagLog.i(TAG, "fetched fresh access token (…${token.takeLast(6)})")
        return token
    }

    /** Called on HTTP 401: drop the token from our cache and Play services' cache. */
    fun invalidate(token: String) {
        synchronized(this) {
            if (cachedToken == token) cachedToken = null
        }
        try {
            Tasks.await(client.clearToken(ClearTokenRequest.builder().setToken(token).build()), 10, TimeUnit.SECONDS)
            DiagLog.i(TAG, "cleared stale token (…${token.takeLast(6)})")
        } catch (t: Throwable) {
            DiagLog.e(TAG, "clearToken failed", t)
        }
    }
}
