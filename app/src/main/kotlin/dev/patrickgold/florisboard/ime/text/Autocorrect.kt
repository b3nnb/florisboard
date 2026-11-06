/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 *
 * Simple autocorrect — loads the dictionary from assets and provides
 * synchronous spell-check + correction. No NLP pipeline dependency.
 */

package dev.patrickgold.florisboard.ime.text

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

object Autocorrect {
    private const val TAG = "Autocorrect"
    private const val MAX_EDIT_DISTANCE = 2
    private const val MIN_WORD_LENGTH = 2

    private val dictionary: ConcurrentHashMap<String, Int> = ConcurrentHashMap()
    private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        try {
            val json = context.assets.open("ime/dict/data.json").bufferedReader().readText()
            val obj = JSONObject(json)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val word = keys.next()
                dictionary[word.lowercase()] = obj.optInt(word, 0)
            }
            loaded = true
            Log.i(TAG, "Dictionary loaded: ${dictionary.size} words")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load dictionary", e)
        }
    }

    fun isLoaded(): Boolean = loaded

    fun dictSize(): Int = dictionary.size

    /**
     * Check if [word] is a valid word in the dictionary.
     * NOTE: the dictionary includes common misspellings, so this is NOT
     * a reliable "is this word correct" check. Use [correct] instead.
     */
    fun isValidWord(word: String): Boolean {
        if (!loaded || word.length < MIN_WORD_LENGTH) return true
        return dictionary.containsKey(word.lowercase())
    }

    /**
     * Find the closest correction for [word] using Levenshtein distance.
     * Returns null if no close match is found.
     * Even if the word is in the dictionary (it might be a misspelling
     * with low frequency), we still search for a higher-frequency
     * correction within edit distance 1.
     */
    fun correct(word: String): String? {
        if (!loaded) return null
        val lower = word.lowercase()
        if (lower.length < MIN_WORD_LENGTH) return null

        val currentFreq = dictionary[lower] ?: 0

        // If the word is in the dictionary with high frequency, don't correct it
        // (it's likely a valid word, not a misspelling)
        if (currentFreq > 200) return null

        var bestMatch: String? = null
        var bestDist = MAX_EDIT_DISTANCE + 1
        var bestFreq = 0

        for ((candidate, freq) in dictionary) {
            val lenDiff = Math.abs(candidate.length - lower.length)
            if (lenDiff > MAX_EDIT_DISTANCE) continue
            if (candidate == lower) continue // skip exact match

            val dist = levenshtein(lower, candidate)
            // Only correct if the candidate has significantly higher frequency
            // (at least 2x the current word's frequency)
            if (dist == 1 && freq > currentFreq * 2) {
                if (dist < bestDist || (dist == bestDist && freq > bestFreq)) {
                    bestDist = dist
                    bestFreq = freq
                    bestMatch = candidate
                }
            }
        }

        return bestMatch
    }

    private fun levenshtein(a: String, b: String): Int {
        val n = a.length
        val m = b.length
        if (n == 0) return m
        if (m == 0) return n

        var prev = IntArray(m + 1) { it }
        var curr = IntArray(m + 1)

        for (i in 1..n) {
            curr[0] = i
            for (j in 1..m) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[m]
    }
}