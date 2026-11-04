package com.brenyd.audiread.sync

/** Fuzzy title comparison that understands "Chapter Twelve" == "Chapter 12" == "XII". */
object TitleMatch {
    private val small = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    )
    private val tens = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
        "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90,
    )
    private val ordinals = mapOf(
        "first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5, "sixth" to 6,
        "seventh" to 7, "eighth" to 8, "ninth" to 9, "tenth" to 10,
    )
    private val stop = setOf("the", "a", "an", "of", "and", "chapter", "ch", "chap", "part", "book", "track", "unabridged", "audiobook")
    private val countWords = setOf("chapter", "ch", "chap", "part", "book", "section")
    private val roman = Regex("^[ivxlc]+$")

    private fun romanValue(s: String): Int {
        val v = mapOf('i' to 1, 'v' to 5, 'x' to 10, 'l' to 50, 'c' to 100)
        var total = 0
        for (i in s.indices) {
            val cur = v[s[i]] ?: return -1
            val next = if (i + 1 < s.length) v[s[i + 1]] ?: 0 else 0
            total += if (cur < next) -cur else cur
        }
        return total
    }

    fun tokens(s: String): List<String> {
        val raw = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().split(' ').filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        var i = 0
        while (i < raw.size) {
            val w = raw[i]
            val t = tens[w]
            when {
                t != null -> {
                    val next = raw.getOrNull(i + 1)?.let { small.indexOf(it) } ?: -1
                    if (next in 1..9) { out += (t + next).toString(); i += 2; continue }
                    out += t.toString()
                }
                small.indexOf(w) >= 0 -> out += small.indexOf(w).toString()
                ordinals[w] != null -> out += ordinals.getValue(w).toString()
                i > 0 && raw[i - 1] in countWords && roman.matches(w) && romanValue(w) > 0 -> out += romanValue(w).toString()
                w.all { it.isDigit() } -> out += w.trimStart('0').ifEmpty { "0" }
                else -> out += w
            }
            i++
        }
        return out.filter { it !in stop }
    }

    fun score(a: List<String>, b: List<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val sa = a.toSet()
        val sb = b.toSet()
        val inter = sa.intersect(sb).size.toDouble()
        var s = inter / (sa.size + sb.size - inter)
        val na = sa.filter { w -> w.all { it.isDigit() } }.toSet()
        val nb = sb.filter { w -> w.all { it.isDigit() } }.toSet()
        if (na.isNotEmpty() && nb.isNotEmpty() && na.intersect(nb).isEmpty()) s *= 0.2
        return s
    }

    fun similarity(a: String, b: String): Double = score(tokens(a), tokens(b))
}
