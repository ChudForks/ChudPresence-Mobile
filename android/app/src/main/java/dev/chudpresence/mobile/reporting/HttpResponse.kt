package dev.chudpresence.mobile.reporting

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A body can fail after headers arrive, when OkHttp no longer calls onFailure for us. */
internal suspend fun Call.awaitTextResponse(): Pair<Int, String> = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, error: IOException) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }

        override fun onResponse(call: Call, response: Response) {
            val result = try {
                response.use {
                    if (!continuation.isActive) return
                    it.code to it.body?.string().orEmpty()
                }
            } catch (error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
                return
            }
            if (continuation.isActive) continuation.resume(result)
        }
    })
}
