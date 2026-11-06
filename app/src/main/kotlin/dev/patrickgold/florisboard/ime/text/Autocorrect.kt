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

        // Check contractions first (e.g. "youre" → "you're")
        CONTRACTIONS[lower]?.let { return it }

        val currentFreq = dictionary[lower] ?: 0

        // If the word is in the dictionary with high frequency (>200), it's
        // likely a valid word, not a misspelling. Don't correct it.
        if (currentFreq > 200) return null

        var bestMatch: String? = null
        var bestFreq = 0

        // For known words (in dict with low freq), require correction to have
        // 1.5x the frequency (filters out abbreviations like "msg" → "ms").
        // For unknown words (not in dict), require correction freq > 220
        // (very common words only — prevents correcting proper nouns/slang).
        val minCorrectionFreq = if (currentFreq > 0) {
            (currentFreq * 1.5).toInt() + 1
        } else {
            220
        }

        for ((candidate, freq) in dictionary) {
            val lenDiff = Math.abs(candidate.length - lower.length)
            if (lenDiff > 1) continue
            if (candidate == lower) continue

            val dist = damerauLevenshtein(lower, candidate)
            if (dist == 1 && freq >= minCorrectionFreq) {
                if (freq > bestFreq) {
                    bestFreq = freq
                    bestMatch = candidate
                }
            }
        }

        return bestMatch
    }

    private val CONTRACTIONS = mapOf(
        "youre" to "you're", "dont" to "don't", "cant" to "can't",
        "wont" to "won't", "im" to "i'm", "ive" to "i've",
        "youve" to "you've", "theyre" to "they're", "whats" to "what's",
        "wheres" to "where's", "isnt" to "isn't", "wasnt" to "wasn't",
        "arent" to "aren't", "wouldnt" to "wouldn't", "couldnt" to "couldn't",
        "shouldnt" to "shouldn't", "didnt" to "didn't", "doesnt" to "doesn't",
        "hasnt" to "hasn't", "havent" to "haven't", "hadnt" to "hadn't",
        "thats" to "that's", "heres" to "here's", "theres" to "there's",
        "lets" to "let's", "shell" to "she'll", "hell" to "he'll",
        "well" to "we'll", "ill" to "i'll", "youll" to "you'll",
        "theyll" to "they'll", "shes" to "she's", "hes" to "he's",
        "werent" to "weren't", "couldve" to "could've",
        "wouldve" to "would've", "shouldve" to "should've",
        "mightve" to "might've", "mustve" to "must've",
        "hes" to "he's", "shes" to "she's", "its" to "it's",
        "wasnt" to "wasn't", "werent" to "weren't",
    )

    private fun damerauLevenshtein(a: String, b: String): Int {
        val n = a.length
        val m = b.length
        if (n == 0) return m
        if (m == 0) return n

        val d = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) d[i][0] = i
        for (j in 0..m) d[0][j] = j

        for (i in 1..n) {
            for (j in 1..m) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                // Transposition check
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
                }
            }
        }
        return d[n][m]
    }
}