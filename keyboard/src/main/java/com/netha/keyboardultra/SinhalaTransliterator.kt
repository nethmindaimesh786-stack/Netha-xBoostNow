package com.netha.keyboardultra

import java.util.Locale

/**
 * Small, offline phonetic converter.
 *
 * The converter is deliberately isolated from the touch/UI path. New spelling
 * rules are constant-time dictionary lookups; the regular rule engine is used
 * only to render the current composing word.
 */
object SinhalaTransliterator {
    private val exactWords = mapOf(
        "ayiya" to "අයිය",
        "thaaththas" to "තාත්තා",
        "kariya" to "කැරියා",
        "prashna" to "ප්‍රශ්න",
        "prashnaya" to "ප්‍රශ්නය",
        "qaas" to "දාස්",
        "amma" to "අම්ම",
        "thaththa" to "තාත්තා",
        "nangi" to "නංගි",
        "malli" to "මල්ලි",
        "kohomada" to "කොහොමද",
        "oyata" to "ඔයාට",
        "mama" to "මම",
        "hari" to "හරි"
    )

    private val vowels = listOf(
        "aa" to Pair("ආ", "ා"),
        "ae" to Pair("ඇ", "ැ"),
        "ee" to Pair("ඒ", "ේ"),
        "ii" to Pair("ඊ", "ී"),
        "uu" to Pair("ඌ", "ූ"),
        "oo" to Pair("ඕ", "ෝ"),
        "ai" to Pair("ඓ", "ෛ"),
        "au" to Pair("ඖ", "ෞ"),
        "a" to Pair("අ", ""),
        "e" to Pair("එ", "ෙ"),
        "i" to Pair("ඉ", "ි"),
        "u" to Pair("උ", "ු"),
        "o" to Pair("ඔ", "ො")
    )

    // Shift + vowel keys select independent/long Sinhala vowel forms.
    private val shiftedVowels = mapOf(
        'A' to Pair("ඇ", "ැ"),
        'E' to Pair("ඒ", "ේ"),
        'I' to Pair("ඊ", "ී"),
        'U' to Pair("ඌ", "ූ"),
        'O' to Pair("ඕ", "ෝ")
    )

    // Shift + consonant keys expose additional Sinhala consonants.
    // The selected consonant remains composable with the next vowel key.
    private val shiftedConsonants = mapOf(
        'K' to "ඛ",
        'G' to "ඝ",
        'C' to "ඡ",
        'J' to "ඣ",
        'T' to "ඨ",
        'D' to "ඪ",
        'N' to "ණ",
        'P' to "ඵ",
        'B' to "භ",
        'Q' to "ධ",
        'S' to "ෂ",
        'L' to "ළ",
        'M' to "ඹ",
        'Y' to "ඥ",
        'Z' to "ඦ"
    )

    private val consonants = listOf(
        "th" to "ත", "dh" to "ද", "sh" to "ශ", "ch" to "ච",
        "kh" to "ඛ", "gh" to "ඝ", "ph" to "ඵ", "bh" to "භ",
        "ng" to "ඟ", "ny" to "ඤ", "nj" to "ඦ",
        "k" to "ක", "g" to "ග", "c" to "ක", "j" to "ජ",
        "t" to "ට", "d" to "ඩ", "n" to "න", "p" to "ප",
        "b" to "බ", "m" to "ම", "y" to "ය", "r" to "ර",
        "l" to "ල", "v" to "ව", "w" to "ව", "s" to "ස",
        "h" to "හ", "f" to "ෆ", "q" to "ද", "z" to "ස"
    )

    fun convert(input: String): String {
        if (input.isEmpty()) return ""
        if (input == "A") return "ඇ"
        val lower = input.lowercase(Locale.ROOT)
        // Exact-word shortcuts are lowercase-only; uppercase is reserved for Shift mappings.
        if (input == lower) exactWords[lower]?.let { return it }

        val out = StringBuilder()
        var pending: String? = null
        var index = 0

        while (index < input.length) {
            val currentChar = input[index]
            val shiftedVowel = shiftedVowels[currentChar]
            if (shiftedVowel != null) {
                if (pending == null) {
                    out.append(shiftedVowel.first)
                } else {
                    out.append(pending).append(shiftedVowel.second)
                    pending = null
                }
                index++
                continue
            }

            val shiftedConsonant = shiftedConsonants[currentChar]
            if (shiftedConsonant != null) {
                if (pending != null) out.append(pending).append("්")
                pending = shiftedConsonant
                index++
                continue
            }

            val vowel = vowels.firstOrNull { (token, _) ->
                lower.startsWith(token, index)
            }
            if (vowel != null) {
                val mark = vowel.second.second
                if (pending == null) {
                    out.append(vowel.second.first)
                } else {
                    out.append(pending).append(mark)
                    pending = null
                }
                index += vowel.first.length
                continue
            }

            val consonant = consonants.firstOrNull { (token, _) ->
                lower.startsWith(token, index)
            }
            if (consonant != null) {
                if (pending != null) out.append(pending).append("්")
                pending = consonant.second
                index += consonant.first.length
                continue
            }

            if (pending != null) {
                out.append(pending).append("්")
                pending = null
            }
            out.append(input[index])
            index++
        }

        if (pending != null) out.append(pending).append("්")
        return out.toString()
    }

    fun commit(input: String): String {
        if (input == "A") return "ඇ"
        val lower = input.lowercase(Locale.ROOT)
        if (input == lower) exactWords[lower]?.let { return it }
        return convert(input)
    }
}
