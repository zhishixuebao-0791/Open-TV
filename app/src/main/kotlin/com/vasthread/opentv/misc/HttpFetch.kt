package com.vasthread.webviewtv.misc

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Bounded reads with cancellation that also closes the actual HTTP request. */
object HttpFetch {
    data class Result(val url: String, val contentType: String, val bytes: ByteArray, val truncated: Boolean,
                      val status: Int = 200, val etag: String? = null)

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    suspend fun read(url: String, limit: Int, range: Boolean = false, etag: String? = null,
                     revalidate: Boolean = false): Result =
        suspendCancellableCoroutine { continuation ->
            val builder = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 11; TV) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36")
            if (range) builder.header("Range", "bytes=0-$limit")
            if (etag != null) builder.header("If-None-Match", etag)
            if (revalidate) builder.header("Cache-Control", "no-cache")
            val call = client.newCall(builder.build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            if (it.code == 304 && etag != null) {
                                return@use Result(it.request.url.toString(), "", byteArrayOf(), false, 304,
                                    it.header("ETag") ?: etag)
                            }
                            if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
                            val body = it.body ?: throw IOException("Empty response")
                            val out = ByteArrayOutputStream(minOf(limit, 8192))
                            body.byteStream().use { input ->
                                val buffer = ByteArray(8192)
                                while (out.size() <= limit) {
                                    val read = input.read(buffer, 0, minOf(buffer.size, limit + 1 - out.size()))
                                    if (read < 0) break
                                    out.write(buffer, 0, read)
                                }
                            }
                            val bytes = out.toByteArray()
                            Result(it.request.url.toString(), body.contentType()?.toString().orEmpty(),
                                bytes.copyOf(minOf(bytes.size, limit)), bytes.size > limit, it.code, it.header("ETag"))
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }
}
