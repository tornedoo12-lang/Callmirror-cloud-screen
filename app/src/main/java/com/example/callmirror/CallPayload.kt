package com.example.callmirror

import org.json.JSONObject

data class CallPayload(
    val status: String, // "ringing", "ended", "ping", "reject", "notification"
    val number: String? = null,
    val name: String? = null,
    val appName: String? = null,
    val title: String? = null,
    val text: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("status", status)
        if (number != null) json.put("number", number)
        if (name != null) json.put("name", name)
        if (appName != null) json.put("appName", appName)
        if (title != null) json.put("title", title)
        if (text != null) json.put("text", text)
        json.put("timestamp", timestamp)
        return json.toString()
    }

    companion object {
        const val STATUS_RINGING = "ringing"
        const val STATUS_ENDED = "ended"
        const val STATUS_PING = "ping"
        const val STATUS_REJECT = "reject"
        const val STATUS_NOTIFICATION = "notification"

        fun fromJson(jsonStr: String): CallPayload? {
            return try {
                val json = JSONObject(jsonStr)
                CallPayload(
                    status = json.optString("status", STATUS_ENDED),
                    number = if (json.has("number")) json.getString("number") else null,
                    name = if (json.has("name")) json.getString("name") else null,
                    appName = if (json.has("appName")) json.getString("appName") else null,
                    title = if (json.has("title")) json.getString("title") else null,
                    text = if (json.has("text")) json.getString("text") else null,
                    timestamp = json.optLong("timestamp", System.currentTimeMillis())
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
