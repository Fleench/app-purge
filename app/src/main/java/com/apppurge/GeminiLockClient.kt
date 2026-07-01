package com.apppurge

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

object GeminiLockClient {
    private const val Endpoint =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"

    fun evaluate(
        apiKey: String,
        lockedAppName: String,
        lockReason: String,
        requestReason: String,
        action: LockAction,
        temporaryUnlockPrompt: String = DEFAULT_TEMPORARY_UNLOCK_PROMPT,
        removeLockPrompt: String = DEFAULT_REMOVE_LOCK_PROMPT,
    ): LockDecision {
        val prompt = buildPrompt(
            lockedAppName = lockedAppName,
            lockReason = lockReason,
            requestReason = requestReason,
            action = action,
            customPrompt = when (action) {
                LockAction.Unlock -> temporaryUnlockPrompt
                LockAction.Remove -> removeLockPrompt
            },
        )

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
        val approved = json.optBoolean("approved", false)
        val grantedMinutes = json.optDouble("granted_minutes", 0.0).roundToInt()
        val cooldownMinutes = json.optDouble("cooldown_minutes", 120.0).roundToInt()
        return LockDecision(
            approved = approved,
            grantedMinutes = when {
                !approved -> 0
                action == LockAction.Unlock -> grantedMinutes.coerceIn(5, 60)
                else -> 0
            },
            cooldownMinutes = when {
                approved -> cooldownMinutes.coerceAtLeast(15)
                else -> cooldownMinutes.coerceAtLeast(120)
            }.coerceIn(0, 1440),
            reason = json.optString("reason", "Gemini returned no reason."),
        )
    }

    private fun buildPrompt(
        lockedAppName: String,
        lockReason: String,
        requestReason: String,
        action: LockAction,
        customPrompt: String,
    ): String {
        return """
            You are authorizing access to an individually locked app, not to App Purge itself.
            The locked app is: "$lockedAppName".
            The user's original lock reason is: "$lockReason".
            The user now requests: ${action.label}.
            Their stated reason is: "$requestReason".

            ${customPrompt.ifBlank { defaultPromptFor(action) }}

            Required app compatibility rules that override any custom instructions:
            - Return only JSON. Do not include Markdown, code fences, commentary, or extra text.
            - The JSON object must contain exactly these keys: approved (boolean), granted_minutes (integer 0-60), cooldown_minutes (integer 0-1440), reason (string).
            - For denied requests, approved must be false and granted_minutes must be 0.
            - For temporary unlock approvals, granted_minutes must be between 5 and 60.
            - For remove-lock approvals, granted_minutes must be 0.
            - Always set cooldown_minutes before another Gemini request.
        """.trimIndent()
    }

    private fun defaultPromptFor(action: LockAction): String {
        return when (action) {
            LockAction.Unlock -> DEFAULT_TEMPORARY_UNLOCK_PROMPT
            LockAction.Remove -> DEFAULT_REMOVE_LOCK_PROMPT
        }
    }

    const val DEFAULT_TEMPORARY_UNLOCK_PROMPT = """Decide if this temporary unlock request is consistent with the original lock intent.
Temporary unlock requests should be balanced. Approve short entertainment or leisure reasons when the user is explicit about what they want to do and the request still respects the lock intent.
Deny vague, boredom, habit, scrolling, procrastination, "just checking", or weak convenience reasons when they are not tied to a deliberate short activity.
For approved temporary unlocks, grant the shortest practical time between 5 and 60 minutes. Prefer 5-15 minutes for entertainment or leisure. Grant more than 15 minutes only when the user gives a specific, time-bounded, and compelling reason for needing a longer session.
Denials should normally cool down for at least 120 minutes."""

    const val DEFAULT_REMOVE_LOCK_PROMPT = """Decide if this permanent remove-lock request is consistent with the original lock intent.
Permanent remove-lock requests should be very strict and usually denied unless the reason clearly shows the lock is permanently no longer needed.
Denials should normally cool down for at least 120 minutes."""
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
