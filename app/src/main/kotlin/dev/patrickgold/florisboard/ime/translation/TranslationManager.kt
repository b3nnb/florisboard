/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 *
 * Translation feature for FlorisBoard — ported from the FridayIME custom keyboard.
 * Provides a sticky on/off toggle, a DL/AI smart-mode toggle, a target language
 * cycler (ZH <-> ES), a live DeepL preview, and Enter/Send interception that
 * replaces the text before the cursor with its translation.
 *
 * State is persisted in a dedicated SharedPreferences file using the exact keys
 * required by the spec: "translate_on", "target_lang", "smart_mode",
 * "deepl_api_key", "openrouter_key".
 */

package dev.patrickgold.florisboard.ime.translation

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.InputConnection
import android.widget.Toast
import dev.patrickgold.florisboard.FlorisApplication
import dev.patrickgold.florisboard.FlorisImeService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Singleton holding the translation state and the DeepL / LLM backends.
 *
 * Backends (faithful port of FridayIME.translateDeepL / translateLLM / llmRequest):
 *  - DeepL free tier: POST https://api-free.deepl.com/v2/translate (auth_key from "deepl_api_key")
 *  - LLM fallback: local Ollama (qwen3:32b) -> OpenRouter (key from "openrouter_key")
 *
 * When [smartMode] is on the LLM is tried first; if it is unreachable we silently
 * fall back to DeepL. When [smartMode] is off DeepL is used directly (and itself
 * falls back to the LLM when no DeepL key is configured).
 */
object TranslationManager {

    private const val PREFS_NAME = "florisboard_translation"

    // Spec-required SharedPreferences keys.
    private const val KEY_TRANSLATE_ON = "translate_on"
    private const val KEY_TARGET_LANG = "target_lang"
    private const val KEY_SMART_MODE = "smart_mode"
    private const val KEY_DEEPL_API_KEY = "deepl_api_key"
    private const val KEY_OPENROUTER_KEY = "openrouter_key"

    // Backend endpoints.
    private const val DEEPL_API_URL = "https://api-free.deepl.com/v2/translate"
    private const val OLLAMA_URL = "http://10.2.0.102:11434/api/chat"
    private const val OLLAMA_MODEL = "qwen3.5:9b"
    private const val OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var prefs: android.content.SharedPreferences

    // --- Observable state for the Compose UI ---
    private val _translateOn = MutableStateFlow(false)
    val translateOn: StateFlow<Boolean> = _translateOn.asStateFlow()

    private val _targetLang = MutableStateFlow("ZH")
    val targetLang: StateFlow<String> = _targetLang.asStateFlow()

    private val _smartMode = MutableStateFlow(false)
    val smartMode: StateFlow<Boolean> = _smartMode.asStateFlow()

    private val _previewText = MutableStateFlow("")
    val previewText: StateFlow<String> = _previewText.asStateFlow()

    private fun initialized(): Boolean = ::prefs.isInitialized

    /**
     * Must be called once from the IME service / application start so the
     * SharedPreferences handle is available. Safe to call multiple times.
     */
    fun init(context: Context) {
        if (initialized()) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _translateOn.value = prefs.getBoolean(KEY_TRANSLATE_ON, false)
        _targetLang.value = prefs.getString(KEY_TARGET_LANG, "ZH") ?: "ZH"
        _smartMode.value = prefs.getBoolean(KEY_SMART_MODE, false)
    }

    private fun saveState() {
        if (!initialized()) return
        prefs.edit().apply {
            putBoolean(KEY_TRANSLATE_ON, _translateOn.value)
            putString(KEY_TARGET_LANG, _targetLang.value)
            putBoolean(KEY_SMART_MODE, _smartMode.value)
        }.apply()
    }

    private fun getPref(key: String, default: String = ""): String {
        if (!initialized()) return default
        return prefs.getString(key, default) ?: default
    }

    // --- Toggle actions (called from the smart bar UI) ---

    fun toggleTranslateOn() {
        _translateOn.value = !_translateOn.value
        _previewText.value = ""
        saveState()
    }

    fun toggleSmartMode() {
        _smartMode.value = !_smartMode.value
        saveState()
        toast(if (_smartMode.value) "AI mode — natural phrasing" else "DeepL — fast")
    }

    /** Long-press on the toggle cycles the target language ZH -> ES -> ZH. */
    fun cycleTargetLang() {
        _targetLang.value = if (_targetLang.value == "ZH") "ES" else "ZH"
        saveState()
        toast("Target: ${if (_targetLang.value == "ZH") "Chinese" else "Spanish"}")
    }

    fun clearPreview() {
        _previewText.value = ""
    }

    // --- Live preview (DeepL for speed) ---

    /**
     * Translate [text] via DeepL and publish the result to [previewText]. Called
     * by the smart bar as the user types. Best-effort — on failure shows "…".
     */
    fun updatePreview(text: String) {
        if (!initialized()) return
        if (text.isBlank()) {
            _previewText.value = ""
            return
        }
        val lang = _targetLang.value
        scope.launch {
            try {
                val translated = translateDeepL(text, lang)
                if (_translateOn.value) _previewText.value = translated
            } catch (_: Exception) {
                if (_translateOn.value) _previewText.value = "…"
            }
        }
    }

    // --- Enter / Send interception ---

    /**
     * Triggered by the Translate button in TranslationBar. Grabs the text
     * before the cursor from the current InputConnection and translates it.
     */
    fun triggerTranslate() {
        val ic = FlorisImeService.currentInputConnection()
        val textBefore = ic?.getTextBeforeCursor(10000, 0)?.toString().orEmpty()
        if (ic != null && textBefore.isNotEmpty()) {
            handleEnterTranslate(textBefore, ic)
        } else {
            toast("Nothing to translate")
        }
    }

    /**
     * Called by [triggerTranslate].  The text before the cursor is translated and
     * the InputConnection is updated: the original text before the cursor is deleted
     * and the translation is committed in its place.
     */
    fun handleEnterTranslate(textBeforeCursor: String, ic: InputConnection?) {
        if (ic == null || textBeforeCursor.isEmpty()) return
        val smart = _smartMode.value
        val lang = _targetLang.value
        val original = textBeforeCursor.trim()
        scope.launch {
            try {
                val translated = if (smart) {
                    try {
                        translateLLM(textBeforeCursor, lang)
                    } catch (_: Exception) {
                        toast("AI unreachable — using DeepL")
                        translateDeepL(textBeforeCursor, lang)
                    }
                } else {
                    translateDeepL(textBeforeCursor, lang)
                }
                mainHandler.post {
                    ic.deleteSurroundingText(textBeforeCursor.length, 0)
                    ic.commitText(translated, 1)
                }
                _previewText.value = ""
                // Back-translate the Chinese to English so Benn can verify
                try {
                    val backTranslated = if (smart) {
                        try {
                            translateLLM(translated, "EN")
                        } catch (_: Exception) {
                            translateDeepL(translated, "EN")
                        }
                    } else {
                        translateDeepL(translated, "EN")
                    }
                    mainHandler.post {
                        toast("Back-EN: $backTranslated")
                    }
                } catch (_: Exception) {
                    // Back-translation is best-effort — don't fail the whole thing
                }
            } catch (e: Exception) {
                toast("Translation failed: ${e.message}")
            }
        }
    }

    // --- Translation backends (ported from FridayIME) ---

    private fun translateDeepL(text: String, targetLang: String): String {
        val apiKey = getPref(KEY_DEEPL_API_KEY)
        if (apiKey.isEmpty()) return translateLLM(text, targetLang)

        val url = URL("$DEEPL_API_URL?auth_key=$apiKey")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        val body = JSONObject().apply {
            put("text", JSONArray().put(text))
            put("target_lang", targetLang)
        }.toString()
        conn.outputStream.use { it.write(body.toByteArray()) }

        if (conn.responseCode != 200) throw Exception("DeepL ${conn.responseCode}")
        val resp = conn.inputStream.bufferedReader().readText()
        return JSONObject(resp)
            .getJSONArray("translations")
            .getJSONObject(0)
            .getString("text")
    }

    private fun translateLLM(text: String, targetLang: String): String {
        val langName = when (targetLang) {
            "ZH" -> "Simplified Chinese (Mandarin)"
            "ES" -> "Spanish"
            "EN" -> "English"
            else -> targetLang
        }
        val prompt = "You ARE a native $langName speaker who grew up in a $langName-speaking city. You're not translating — you're rewriting what this person meant to say, as if YOU were texting your own friend.\n\n" +
            "Rules:\n" +
            "1. Write how a LOCAL actually texts. Not textbook, not polite-formal. Real texting — the way someone your age talks to a friend on WeChat or WhatsApp.\n" +
            "2. Slang, internet language, abbreviations, emojis if the vibe fits — use what a local would use. If the English is casual and slangy, match that energy in $langName.\n" +
            "3. DON'T translate idioms literally. Find the $langName equivalent that hits the same feeling. If someone says 'that's sick' in English, don't translate to something about illness — find what a $langName speaker says when they think something is cool/impressive.\n" +
            "4. Match the emotional register exactly. Angry, teasing, sweet, dismissive, excited — same energy in $langName. Use particles and tone markers naturally ($langName specific: 啊/了/呢/吧 for Chinese, etc.).\n" +
            "5. If the English has typos, fast-typing shorthand, or missing punctuation — replicate that casual energy. Don't clean it up into formal writing.\n\n" +
            "Respond with ONLY the $langName text, nothing else:\n\n$text"
        val body = JSONObject().apply {
            put("model", OLLAMA_MODEL)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
            put("stream", false)
            put("think", false)
        }.toString()

        // Try local Ollama first, then fall back to OpenRouter.
        return try {
            llmRequest(OLLAMA_URL, body, bearerKey = "")
        } catch (e: Exception) {
            llmRequest(OPENROUTER_URL, body, getPref(KEY_OPENROUTER_KEY))
        }
    }

    private fun llmRequest(urlStr: String, body: String, bearerKey: String): String {
        val url = URL(urlStr)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (bearerKey.isNotEmpty()) setRequestProperty("Authorization", "Bearer $bearerKey")
            // Short timeout for local Ollama, longer for cloud OpenRouter.
            val isLocal = urlStr.contains("10.2.") || urlStr.contains("10.200.") || urlStr.contains("127.0.")
            connectTimeout = if (isLocal) 3000 else 8000
            readTimeout = if (isLocal) 8000 else 15000
        }
        conn.outputStream.use { it.write(body.toByteArray()) }
        if (conn.responseCode != 200) throw Exception("LLM ${conn.responseCode}")
        val resp = conn.inputStream.bufferedReader().readText()
        val json = JSONObject(resp)
        // Ollama /api/chat returns {"message": {"content": "..."}} directly.
        // OpenRouter returns {"choices": [{"message": {"content": "..."}}]}.
        return if (json.has("choices")) {
            json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
        } else {
            json.getJSONObject("message").getString("content").trim()
        }
    }

    private fun toast(msg: String) {
        val app = try { FlorisApplication.getHack() } catch (_: Exception) { null } ?: return
        mainHandler.post { Toast.makeText(app, msg, Toast.LENGTH_SHORT).show() }
    }
}