/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.nlp.latin

import android.content.Context
import dev.patrickgold.florisboard.appContext
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.nlp.SpellingProvider
import dev.patrickgold.florisboard.ime.nlp.SpellingResult
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.SuggestionProvider
import dev.patrickgold.florisboard.ime.nlp.WordSuggestionCandidate
import dev.patrickgold.florisboard.lib.devtools.flogDebug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.florisboard.lib.android.readText
import org.florisboard.lib.kotlin.guardedByLock
import org.k3lp.runtime.K3Content

class LatinLanguageProvider(context: Context) : SpellingProvider, SuggestionProvider {
    companion object {
        // Default user ID used for all subtypes, unless otherwise specified.
        // See `ime/core/Subtype.kt` Line 210 and 211 for the default usage
        const val ProviderId = "org.florisboard.nlp.providers.latin"

        // Maximum edit distance for autocorrect suggestions.
        // 2 catches most fast-typing typos (transposed letters, doubled letters,
        // missing letters, extra letters) without too many false positives.
        private const val MAX_EDIT_DISTANCE = 2
        // Only autocorrect if the top suggestion has at least this confidence.
        private const val MIN_AUTOCONFIDENCE = 0.7
    }

    private val appContext by context.appContext()

    private val wordData = guardedByLock { mutableMapOf<String, Int>() }
    private val wordDataSerializer = MapSerializer(String.serializer(), Int.serializer())

    override val providerId = ProviderId

    override suspend fun create() {
        // Here we initialize our provider, set up all things which are not language dependent.
    }

    override suspend fun preload(subtype: Subtype) = withContext(Dispatchers.IO) {
        wordData.withLock { wordData ->
            if (wordData.isEmpty()) {
                val rawData = appContext.assets.readText("ime/dict/data.json")
                val jsonData = Json.decodeFromString(wordDataSerializer, rawData)
                wordData.putAll(jsonData)
            }
        }
    }

    override suspend fun spell(
        subtype: Subtype,
        word: String,
        precedingWords: List<String>,
        followingWords: List<String>,
        maxSuggestionCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): SpellingResult {
        val lower = word.lowercase()
        return wordData.withLock { dict ->
            if (dict.containsKey(lower)) {
                SpellingResult.validWord()
            } else {
                // Find closest words by edit distance
                val suggestions = findClosestWords(lower, maxSuggestionCount, dict)
                if (suggestions.isEmpty()) {
                    SpellingResult.validWord() // No suggestions — treat as valid (names, etc.)
                } else {
                    SpellingResult.typo(suggestions.toTypedArray())
                }
            }
        }
    }

    override suspend fun suggest(
        subtype: Subtype,
        content: K3Content,
        maxCandidateCount: Int,
        allowPossiblyOffensive: Boolean,
        isPrivateSession: Boolean,
    ): List<SuggestionCandidate> {
        val composingText = (content.compositionText ?: "").trim()
        if (composingText.isEmpty() || composingText.length < 2) return emptyList()

        val lower = composingText.lowercase()
        return wordData.withLock { dict ->
            // If the word is already correct, don't suggest corrections
            if (dict.containsKey(lower)) return@withLock emptyList()

            val suggestions = findClosestWords(lower, maxCandidateCount, dict)
            if (suggestions.isEmpty()) return@withLock emptyList()

            suggestions.mapIndexed { index, suggestion ->
                val confidence = 1.0 - (index + 1) * 0.15 // top = 0.85, second = 0.70, etc.
                WordSuggestionCandidate(
                    text = suggestion,
                    secondaryText = null,
                    confidence = confidence.coerceIn(0.0, 1.0),
                    // Only the top suggestion is eligible for auto-commit (space key autocorrect)
                    isEligibleForAutoCommit = index == 0 && confidence >= MIN_AUTOCONFIDENCE,
                    sourceProvider = this@LatinLanguageProvider,
                )
            }
        }
    }

    /**
     * Find closest words to [word] in [dict] using Levenshtein edit distance.
     * Returns words within MAX_EDIT_DISTANCE, sorted by (distance asc, frequency desc).
     * Filters by length similarity to avoid absurd suggestions for very short words.
     */
    private fun findClosestWords(
        word: String,
        maxCount: Int,
        dict: Map<String, Int>,
    ): List<String> {
        val candidates = mutableListOf<Pair<String, Int>>() // (word, distance)

        for (entry in dict) {
            val candidate = entry.key
            // Quick length filter: edit distance can't be less than length difference
            val lenDiff = kotlin.math.abs(candidate.length - word.length)
            if (lenDiff > MAX_EDIT_DISTANCE) continue

            val dist = levenshtein(word, candidate)
            if (dist <= MAX_EDIT_DISTANCE && dist > 0) {
                candidates.add(candidate to dist)
            }
        }

        // Sort by distance (primary), then by frequency (secondary — higher freq first)
        return candidates
            .sortedWith(compareBy({ it.second }, { -dict[it.first]!! }))
            .take(maxCount)
            .map { it.first }
    }

    /**
     * Classic Levenshtein edit distance between two strings.
     * Optimized for short strings (typical word length 3-12 chars).
     */
    private fun levenshtein(a: String, b: String): Int {
        val n = a.length
        val m = b.length
        if (n == 0) return m
        if (m == 0) return n

        // Use two rows for memory efficiency
        var prev = IntArray(m + 1) { it }
        var curr = IntArray(m + 1)

        for (i in 1..n) {
            curr[0] = i
            for (j in 1..m) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    prev[j] + 1,      // deletion
                    curr[j - 1] + 1,  // insertion
                    prev[j - 1] + cost // substitution
                )
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[m]
    }

    override suspend fun notifySuggestionAccepted(subtype: Subtype, candidate: SuggestionCandidate) {
        // We can use flogDebug, flogInfo, flogWarning and flogError for debug logging, which is a wrapper for Logcat
        flogDebug { candidate.toString() }
    }

    override suspend fun notifySuggestionReverted(subtype: Subtype, candidate: SuggestionCandidate) {
        flogDebug { candidate.toString() }
    }

    override suspend fun removeSuggestion(subtype: Subtype, candidate: SuggestionCandidate): Boolean {
        flogDebug { candidate.toString() }
        return false
    }

    override suspend fun getListOfWords(subtype: Subtype): List<String> {
        return wordData.withLock { it.keys.toList() }
    }

    override suspend fun getFrequencyForWord(subtype: Subtype, word: String): Double {
        return wordData.withLock { it.getOrDefault(word, 0) / 255.0 }
    }

    override suspend fun destroy() {
        // Here we have the chance to de-allocate memory and finish our work. However this might never be called if
        // the app process is killed (which will most likely always be the case).
    }
}
