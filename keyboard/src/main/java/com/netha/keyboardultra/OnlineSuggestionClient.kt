package com.netha.keyboardultra

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Fetches candidate words off the keyboard's main/UI thread.
 *
 * Sinhala: Google Input Tools transliteration candidates.
 * English: Datamuse strict prefix matches.
 *
 * Only the current unfinished word is passed to this class. No surrounding
 * message, clipboard content, or editor text is read or sent.
 */
object OnlineSuggestionClient {
    fun fetch(prefix: String, sinhala: Boolean, limit: Int = 15): List<String> {
        val clean = prefix.trim()
        if (clean.length < 2 || clean.length > 40 || clean.any { !it.isLetter() }) {
            return emptyList()
        }

        val encoded = URLEncoder.encode(clean.lowercase(Locale.ROOT), "UTF-8")
        val endpoint = if (sinhala) {
            "https://inputtools.google.com/request?text=$encoded&itc=si-t-i0-und&num=$limit&cp=0&cs=1&ie=utf-8&oe=utf-8&app=demopage"
        } else {
            "https://api.datamuse.com/words?sp=${encoded}%2A&max=$limit"
        }

        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 1400
                readTimeout = 1600
                useCaches = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "NETHA-KEYBOARD-ULTRA")
            }

            val status = connection.responseCode
            if (status !in 200..299) return emptyList()

            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (sinhala) parseGoogleCandidates(response, limit) else parseEnglishWords(response, limit)
        } catch (_: Exception) {
            emptyList()
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseGoogleCandidates(response: String, limit: Int): List<String> {
        val root = JSONArray(response)
        if (root.optString(0) != "SUCCESS") return emptyList()

        val groups = root.optJSONArray(1) ?: return emptyList()
        val result = LinkedHashSet<String>()
        for (groupIndex in 0 until groups.length()) {
            val group = groups.optJSONArray(groupIndex) ?: continue
            val candidates = group.optJSONArray(1) ?: continue
            for (candidateIndex in 0 until candidates.length()) {
                val candidate = candidates.optString(candidateIndex).trim()
                if (isUsableCandidate(candidate)) result.add(candidate)
                if (result.size >= limit) return result.toList()
            }
        }
        return result.toList()
    }

    private fun parseEnglishWords(response: String, limit: Int): List<String> {
        val array = JSONArray(response)
        val result = LinkedHashSet<String>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val word = item.optString("word").trim()
            if (isUsableCandidate(word)) result.add(word)
            if (result.size >= limit) break
        }
        return result.toList()
    }

    private fun isUsableCandidate(value: String): Boolean {
        if (value.isBlank() || value.length > 48) return false
        if (value.any { it.isISOControl() }) return false
        return value.any { it.isLetter() }
    }
}
