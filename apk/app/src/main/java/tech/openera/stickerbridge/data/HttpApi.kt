package tech.openera.stickerbridge.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

val ApiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/**
 * Refuses every request that is not HTTPS to the API host.
 * This is what makes "the app only talks to one domain" true in code,
 * including redirects.
 */
class ApiGuard(private val base: HttpUrl) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        if (!isAllowed(url)) throw IOException("Blocked request to ${url.host}")
        return chain.proceed(chain.request())
    }

    fun isAllowed(url: HttpUrl): Boolean =
        url.host == base.host && url.port == base.port && url.scheme == base.scheme &&
            (base.scheme == "https" || base.host == "localhost" || base.host == "127.0.0.1")
}

class HttpApi(
    baseUrl: String,
    userAgent: String,
    client: OkHttpClient? = null,
) : StickerApi {

    private val base = baseUrl.toHttpUrl()
    private val guard = ApiGuard(base)

    private val http: OkHttpClient = (client?.newBuilder() ?: OkHttpClient.Builder())
        .addInterceptor(guard)
        .addNetworkInterceptor(guard)
        // Times each API call in Sentry performance traces (no bodies or headers are sent).
        .addInterceptor(io.sentry.okhttp.SentryOkHttpInterceptor())
        // The API never redirects. Refusing redirects means a hijacked response can't send us elsewhere.
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
        }
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Resolves a server-given path against the base; never leaves the base host. */
    fun resolve(path: String): HttpUrl {
        val url = base.resolve(path.removePrefix("/")) ?: throw IOException("Bad path: $path")
        if (!guard.isAllowed(url)) throw IOException("Blocked request to ${url.host}")
        return url
    }

    private val jsonType = "application/json".toMediaType()

    private suspend fun call(request: Request): ByteArray = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { res ->
            val body = res.body?.bytes() ?: ByteArray(0)
            if (!res.isSuccessful) throw ApiException(res.code, "HTTP ${res.code} for ${request.url.encodedPath}")
            body
        }
    }

    private inline fun <reified T> decode(bytes: ByteArray): T = ApiJson.decodeFromString(bytes.decodeToString())

    private fun req(path: String, token: String? = null, query: Map<String, String> = emptyMap()) =
        Request.Builder().url(
            resolve(path).newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        ).apply {
            if (token != null) header("Authorization", "Bearer $token")
        }

    override suspend fun startLink(installId: String): LinkStart =
        decode(call(req("v1/link/start").post(ApiJson.encodeToString(mapOf("installId" to installId)).toRequestBody(jsonType)).build()))

    override suspend fun linkStatus(code: String, pollToken: String): LinkStatus =
        decode(call(req("v1/link/status", query = mapOf("code" to code)).header("X-Poll-Token", pollToken).build()))

    override suspend fun stickers(token: String, cursor: String?): StickerPage {
        val query = if (cursor == null) emptyMap() else mapOf("since" to cursor)
        return decode(call(req("v1/stickers", token, query).build()))
    }

    override suspend fun download(token: String?, path: String): ByteArray = call(req(path, token).build())

    override suspend fun downloadTo(path: String, dest: File, onProgress: (Long, Long) -> Unit) =
        withContext(Dispatchers.IO) {
            http.newCall(req(path).build()).execute().use { res ->
                if (!res.isSuccessful) throw ApiException(res.code, "HTTP ${res.code} for $path")
                val body = res.body ?: throw IOException("Empty download")
                val total = body.contentLength()
                var done = 0L
                dest.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            onProgress(done, total)
                        }
                    }
                }
            }
        }

    override suspend fun unlink(token: String) {
        call(req("v1/link", token).delete().build())
    }

    override suspend fun deleteData(token: String) {
        call(req("v1/me", token).delete().build())
    }

    override suspend fun config(): RemoteConfig = decode(call(req("v1/config").build()))

    override suspend fun latestApp(): AppRelease? = try {
        decode<AppRelease>(call(req("v1/app/latest").build()))
    } catch (e: ApiException) {
        if (e.code == 404) null else throw e
    }
}
