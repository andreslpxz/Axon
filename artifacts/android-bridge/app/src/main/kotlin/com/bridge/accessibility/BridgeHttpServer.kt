package com.bridge.accessibility

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

class BridgeHttpServer(port: Int, private val service: IAccessibilityService) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "BridgeHttpServer"
        private const val MIME_JSON = "application/json"
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        return try {
            when {
                uri == "/screen" && method == Method.GET -> handleGetScreen()
                uri == "/action" && method == Method.POST -> handlePostAction(session)
                uri == "/health" && method == Method.GET -> handleHealth()
                else -> newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    MIME_JSON,
                    json { put("error", "Not found: $uri") }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling $method $uri", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_JSON,
                json { put("error", e.message ?: "Internal error") }
            )
        }
    }

    private fun handleHealth(): Response {
        return newFixedLengthResponse(
            Response.Status.OK,
            MIME_JSON,
            json { put("status", "ok") }
        )
    }

    private fun handleGetScreen(): Response {
        val screenJson = service.getScreenJson()
        return newFixedLengthResponse(
            Response.Status.OK,
            MIME_JSON,
            screenJson.toString()
        )
    }

    private fun handlePostAction(session: IHTTPSession): Response {
        val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(contentLength)
        session.inputStream.read(body, 0, contentLength)
        val bodyStr = String(body, Charsets.UTF_8)

        val payload = try {
            JSONObject(bodyStr)
        } catch (e: Exception) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                MIME_JSON,
                json { put("error", "Invalid JSON body") }
            )
        }

        val action = payload.optString("action", "")

        return when (action) {
            "click" -> {
                val x = payload.optDouble("x", 0.0).toFloat()
                val y = payload.optDouble("y", 0.0).toFloat()
                val success = service.performClick(x, y)
                newFixedLengthResponse(
                    Response.Status.OK,
                    MIME_JSON,
                    json {
                        put("action", "click")
                        put("x", x)
                        put("y", y)
                        put("success", success)
                    }
                )
            }

            "scroll" -> {
                val x = payload.optDouble("x", 0.0).toFloat()
                val y = payload.optDouble("y", 0.0).toFloat()
                val dx = payload.optDouble("dx", 0.0).toFloat()
                val dy = payload.optDouble("dy", 0.0).toFloat()
                val success = service.performScroll(x, y, dx, dy)
                newFixedLengthResponse(
                    Response.Status.OK,
                    MIME_JSON,
                    json {
                        put("action", "scroll")
                        put("success", success)
                    }
                )
            }

            "global" -> {
                val type = payload.optString("type", "")
                if (type.isEmpty()) {
                    return newFixedLengthResponse(
                        Response.Status.BAD_REQUEST,
                        MIME_JSON,
                        json { put("error", "Missing 'type' for global action") }
                    )
                }
                val success = service.performGlobalAction(type)
                newFixedLengthResponse(
                    Response.Status.OK,
                    MIME_JSON,
                    json {
                        put("action", "global")
                        put("type", type)
                        put("success", success)
                    }
                )
            }

            "input" -> {
                val text = payload.optString("text", "")
                val success = service.performInput(text)
                newFixedLengthResponse(
                    Response.Status.OK,
                    MIME_JSON,
                    json {
                        put("action", "input")
                        put("text", text)
                        put("success", success)
                    }
                )
            }

            else -> newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                MIME_JSON,
                json { put("error", "Unknown action: '$action'. Use: click, scroll, global, input") }
            )
        }
    }

    private fun json(block: JSONObject.() -> Unit): String {
        return JSONObject().apply(block).toString()
    }
}
