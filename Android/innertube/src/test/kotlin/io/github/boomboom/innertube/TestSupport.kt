package io.github.boomboom.innertube

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException
import java.util.concurrent.CountDownLatch

/**
 * Loads a recorded InnerTube answer from `<name>.json`. The files are the iPhone package's own
 * (`Packages/BoomCore/Tests/InnerTubeTests/Fixtures`), put on the test classpath in place by `build.gradle.kts`.
 */
fun fixture(name: String): JsonElement {
    val stream = checkNotNull(Thread.currentThread().contextClassLoader.getResourceAsStream("$name.json")) {
        "no recorded answer called $name.json"
    }
    return stream.use { parseJson(it.readBytes()) }
}

/** A session provider that always gives the same session. */
class FixedSession(private val session: InnerTubeSession) : InnerTubeSessionProviding {
    override suspend fun currentSession() = session
}

object Sessions {
    val signedOut = InnerTubeSession(clientVersion = "1.20261110.04.00", visitorData = "CgtWaXNpdG9yRGF0YQ%3D%3D")
    val cookies = mapOf(
        "SAPISID" to "AbCdEfGhIjKlMnOp/QrStUvWxYz012345",
        "__Secure-3PAPISID" to "AbCdEfGhIjKlMnOp/QrStUvWxYz012345",
        "SID" to "sid-value",
        "HSID" to "hsid-value",
        "LOGIN_INFO" to "login:info=",
    )
    val signedIn = InnerTubeSession(
        clientVersion = "1.20261110.04.00",
        visitorData = "CgtWaXNpdG9yRGF0YQ%3D%3D",
        language = "en",
        region = "IN",
        cookies = cookies,
        authUser = "1",
        onBehalfOfUser = "111222333444555666777",
    )
}

/**
 * The fake transport: an OkHttp interceptor that answers every request itself (so nothing touches the network)
 * and records what was sent. A fresh one per test, so tests cannot see each other's traffic.
 */
class FakeTransport(
    private var replies: Map<String, Reply> = emptyMap(),
    private var fallback: Reply = Reply.Json(200, "{}"),
) : Interceptor {
    sealed class Reply {
        class Json(val status: Int, val text: String) : Reply()
        class Data(val status: Int, val bytes: ByteArray) : Reply()
        class Failure(val error: IOException) : Reply()

        /** Never answers, so the caller can cancel. */
        data object Hang : Reply()
    }

    class Sent(val request: Request, val body: ByteArray) {
        val endpoint: String get() = request.url.encodedPath.removePrefix("/youtubei/v1/")
        private val parsed: JsonElement get() = if (body.isEmpty()) JsonNull else parseJson(body)

        /** The body without the `context` every call carries. */
        val payload: JsonElement
            get() = (parsed as? JsonObject)?.let { JsonObject(it.filterKeys { key -> key != "context" }) } ?: parsed

        val context: JsonElement get() = parsed["context"]
    }

    private val lock = Any()
    private val log = mutableListOf<Sent>()

    /** Counted down when a hanging request notices that its call was cancelled. */
    val sawCancellation = CountDownLatch(1)

    val sent: List<Sent> get() = synchronized(lock) { log.toList() }

    fun reset(replies: Map<String, Reply> = emptyMap(), fallback: Reply = Reply.Json(200, "{}")) {
        synchronized(lock) {
            this.replies = replies
            this.fallback = fallback
            log.clear()
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val buffer = Buffer()
        request.body?.writeTo(buffer)
        val sent = Sent(request, buffer.readByteArray())
        val reply = synchronized(lock) {
            log += sent
            replies[sent.endpoint] ?: fallback
        }
        return when (reply) {
            is Reply.Json -> answer(request, reply.status, reply.text.toByteArray())
            is Reply.Data -> answer(request, reply.status, reply.bytes)
            is Reply.Failure -> throw reply.error
            Reply.Hang -> {
                while (!chain.call().isCanceled()) Thread.sleep(10)
                sawCancellation.countDown()
                throw IOException("Canceled")
            }
        }
    }

    private fun answer(request: Request, status: Int, data: ByteArray): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message("stub")
            .body(data.toResponseBody("application/json".toMediaType()))
            .build()
}

/** A client over the fake transport, signed in or out as [session] says. */
fun stubbedClient(session: InnerTubeSession, transport: FakeTransport): InnerTubeClient =
    InnerTubeClient(
        FixedSession(session),
        OkHttpClient.Builder().addInterceptor(transport).build(),
    )
