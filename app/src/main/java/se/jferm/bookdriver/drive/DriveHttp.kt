package se.jferm.bookdriver.drive

import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import se.jferm.bookdriver.DiagLog
import se.jferm.bookdriver.auth.GoogleAuth
import java.util.concurrent.TimeUnit

/**
 * One OkHttp client for both Drive API calls and ExoPlayer media streaming.
 *  - AuthInterceptor adds a fresh Bearer token to every request
 *  - TokenAuthenticator retries once on 401 with a new token (handles expiry mid-book)
 *  - TrafficLogger logs method, Range header and status so we can see 206s happen
 */
fun buildDriveHttpClient(auth: GoogleAuth): OkHttpClient =
    OkHttpClient.Builder()
        .addInterceptor(AuthInterceptor(auth))
        .addNetworkInterceptor(TrafficLogger())
        .authenticator(TokenAuthenticator(auth))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

private class AuthInterceptor(private val auth: GoogleAuth) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (req.header("Authorization") != null) return chain.proceed(req)
        val token = auth.blockingToken()
        return chain.proceed(req.newBuilder().header("Authorization", "Bearer $token").build())
    }
}

private class TokenAuthenticator(private val auth: GoogleAuth) : Authenticator {
    override fun authenticate(route: okhttp3.Route?, response: Response): okhttp3.Request? {
        if (response.priorResponse != null) return null // already retried once
        val stale = response.request.header("Authorization")?.removePrefix("Bearer ") ?: return null
        DiagLog.i("http", "401 → refreshing token and retrying ${response.request.url.encodedPath}")
        auth.invalidate(stale)
        val fresh = try { auth.blockingToken() } catch (t: Throwable) { return null }
        return response.request.newBuilder().header("Authorization", "Bearer $fresh").build()
    }
}

private class TrafficLogger : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val started = System.nanoTime()
        val res = chain.proceed(req)
        val ms = (System.nanoTime() - started) / 1_000_000
        val isMedia = req.url.queryParameter("alt") == "media"
        val what = if (isMedia) {
            "MEDIA ${req.url.pathSegments.lastOrNull()?.take(10)}… Range=${req.header("Range") ?: "none"}" +
                " → ${res.code} Content-Range=${res.header("Content-Range") ?: "-"} len=${res.header("Content-Length") ?: "?"}"
        } else {
            "${req.method} ${req.url.encodedPath} → ${res.code}"
        }
        DiagLog.i("http", "$what (${ms}ms)")
        return res
    }
}
