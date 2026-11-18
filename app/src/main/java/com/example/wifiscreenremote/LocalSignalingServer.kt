package com.example.wifiscreenremote

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import org.json.JSONObject

class LocalSignalingServer(
    private val context: Context,
    bindAddress: String,
    private val onOffer: (String) -> Unit,
    private val onTouch: (JSONObject) -> Unit,
    private val onKey: (String) -> Unit,
    private val answerProvider: () -> String?,
    private val statusProvider: () -> JSONObject
) : NanoHTTPD(bindAddress, PORT) {

    @Volatile
    private var answerJson: String? = null

    fun clearAnswer() {
        answerJson = null
    }

    fun publishAnswer(sdp: String) {
        answerJson = JSONObject()
            .put("type", "answer")
            .put("sdp", sdp)
            .toString()
    }

    override fun serve(session: IHTTPSession): Response {
        return try {
            if (session.method == Method.OPTIONS) {
                return withHeaders(newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", ""))
            }

            when (session.uri) {
                "/", "/index.html" -> {
                    val html = context.assets.open("index.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
                    withHeaders(newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html))
                }
                "/offer" -> {
                    if (session.method != Method.POST) return json(Response.Status.METHOD_NOT_ALLOWED, "{\"error\":\"POST required\"}")
                    val body = readBody(session)
                    val offer = JSONObject(body).optString("sdp")
                    if (offer.isBlank()) return json(Response.Status.BAD_REQUEST, "{\"error\":\"Missing SDP\"}")
                    clearAnswer()
                    onOffer(offer)
                    json(Response.Status.ACCEPTED, "{\"accepted\":true}")
                }
                "/answer" -> {
                    val current = answerJson ?: answerProvider()?.let {
                        JSONObject().put("type", "answer").put("sdp", it).toString()
                    }
                    json(Response.Status.OK, current ?: "{\"ready\":false}")
                }
                "/touch" -> {
                    if (session.method != Method.POST) return json(Response.Status.METHOD_NOT_ALLOWED, "{\"error\":\"POST required\"}")
                    onTouch(JSONObject(readBody(session)))
                    json(Response.Status.OK, "{\"ok\":true}")
                }
                "/key" -> {
                    if (session.method != Method.POST) return json(Response.Status.METHOD_NOT_ALLOWED, "{\"error\":\"POST required\"}")
                    onKey(JSONObject(readBody(session)).optString("key"))
                    json(Response.Status.OK, "{\"ok\":true}")
                }
                "/status" -> json(Response.Status.OK, statusProvider().toString())
                "/favicon.ico" -> withHeaders(newFixedLengthResponse(Response.Status.NO_CONTENT, "image/x-icon", ""))
                else -> json(Response.Status.NOT_FOUND, "{\"error\":\"Not found\"}")
            }
        } catch (e: Exception) {
            json(Response.Status.INTERNAL_ERROR, JSONObject().put("error", e.message ?: "Server error").toString())
        }
    }

    private fun readBody(session: IHTTPSession): String {
        val files = HashMap<String, String>()
        session.parseBody(files)
        return files["postData"].orEmpty()
    }

    private fun json(status: Response.IStatus, body: String): Response {
        return withHeaders(newFixedLengthResponse(status, "application/json; charset=utf-8", body))
    }

    private fun withHeaders(response: Response): Response {
        response.addHeader("Cache-Control", "no-store, no-cache, must-revalidate")
        return response
    }

    companion object {
        const val PORT = 8765
    }
}
