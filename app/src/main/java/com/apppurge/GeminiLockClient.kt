package com.apppurge

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

object GeminiLockClient {
    private const val Endpoint =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"

    fun evaluate(apiKey: String, lockReason: String, requestReason: String, action: LockAction): LockDecision {
        val prompt = """
            You are authorizing an app self-control lock. The user's original lock reason is: "$lockReason".
            The user now requests: ${action.label}.
            Their stated reason is: "$requestReason".

            Decide if this request is consistent with the original lock intent. Be strict but reasonable.
            For unlock requests, you may approve up to 180 minutes (3 hours) and set a cooldown in minutes before another request.
            For remove-lock requests, approve only if the reason shows the lock is no longer needed.
            Return only JSON with keys: approved (boolean), granted_minutes (integer 0-180), cooldown_minutes (integer 0-1440), reason (string).
        """.trimIndent()

        val request = JSONObject()
            .put(
                "contents",
                org.json.JSONArray().put(
                    JSONObject().put(
                        "parts",
                        org.json.JSONArray().put(JSONObject().put("text", prompt)),
                    ),
                ),
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("responseMimeType", "application/json")
                    .put("temperature", 0.2),
            )

        val encodedKey = URLEncoder.encode(apiKey, "UTF-8")
        val connection = (URL("$Endpoint?key=$encodedKey").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }

        connection.outputStream.use { it.write(request.toString().toByteArray()) }
        val body = if (connection.responseCode in 200..299) {
            connection.inputStream.bufferedReader().use { it.readText() }
        } else {
            val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            throw IllegalStateException("Gemini request failed (${connection.responseCode}): $error")
        }

        val text = JSONObject(body)
            .getJSONArray("candidates")
            .getJSONObject(0)
            .getJSONObject("content")
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")
        val json = JSONObject(text)
        return LockDecision(
            approved = json.optBoolean("approved", false),
            grantedMinutes = json.optDouble("granted_minutes", 0.0).roundToInt().coerceIn(0, 180),
            cooldownMinutes = json.optDouble("cooldown_minutes", 60.0).roundToInt().coerceIn(0, 1440),
            reason = json.optString("reason", "Gemini returned no reason."),
        )
    }
}

enum class LockAction(val label: String) {
    Unlock("temporary unlock"),
    Remove("remove the lock"),
}

data class LockDecision(
    val approved: Boolean,
    val grantedMinutes: Int,
    val cooldownMinutes: Int,
    val reason: String,
)
