package org.jellyfin.mobile.downloads

import android.net.Uri
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.junit.jupiter.api.Test
import java.io.IOException

class FileDownloaderTest {
    private val api = mockk<ApiClient> {
        every { clientInfo } returns ClientInfo("test", "1.0")
        every { deviceInfo } returns DeviceInfo("device", "Test device")
        every { accessToken } returns "token"
    }
    private val uri = mockk<Uri> { every { this@mockk.toString() } returns "https://example.com/download" }

    /** A server that answers each request with the next status code, and records the requests. */
    private class FakeServer(vararg codes: Int) {
        val requests = mutableListOf<Request>()
        private val remaining = codes.toMutableList()

        val client = mockk<OkHttpClient> {
            every { newCall(any()) } answers {
                val request = firstArg<Request>().also(requests::add)
                mockk<Call> {
                    every { cancel() } returns Unit
                    every { enqueue(any()) } answers {
                        val code = remaining.removeAt(0)
                        if (code == 0) {
                            firstArg<Callback>().onFailure(self as Call, IOException("Connection reset"))
                        } else {
                            firstArg<Callback>().onResponse(self as Call, response(request, code))
                        }
                    }
                }
            }
        }

        private fun response(request: Request, code: Int) = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("")
            .body("".toResponseBody())
            .build()
    }

    @Test
    fun `a new download does not send a Range header`() = runBlocking<Unit> {
        val server = FakeServer(200)
        val (_, restarted) = FileDownloader(server.client).request(api, uri, rangeStart = 0)

        server.requests.single().header("Range") shouldBe null
        restarted shouldBe false
    }

    @Test
    fun `a partial download resumes where it stopped`() = runBlocking<Unit> {
        val server = FakeServer(206)
        val (_, restarted) = FileDownloader(server.client).request(api, uri, rangeStart = 100)

        server.requests.single().header("Range") shouldBe "bytes=100-"
        restarted shouldBe false
    }

    @Test
    fun `a rejected resume starts the download over`() = runBlocking<Unit> {
        val server = FakeServer(500, 200)
        val (response, restarted) = FileDownloader(server.client).request(api, uri, rangeStart = 100)

        server.requests.map { it.header("Range") } shouldBe listOf("bytes=100-", null)
        response.code shouldBe 200
        restarted shouldBe true
    }

    @Test
    fun `a connection error keeps the partial download for the next retry`() = runBlocking<Unit> {
        val server = FakeServer(0)
        shouldThrow<IOException> { FileDownloader(server.client).request(api, uri, rangeStart = 100) }

        server.requests.size shouldBe 1
    }

    @Test
    fun `an error on a new download is not retried`() = runBlocking<Unit> {
        val server = FakeServer(500)
        shouldThrow<IOException> { FileDownloader(server.client).request(api, uri, rangeStart = 0) }

        server.requests.size shouldBe 1
    }
}
