/*
 * WhatsApp Translation Overlay with Accessibility — floating button that
 * reads Chinese text directly from the WhatsApp screen via AccessibilityService.
 * Tap 🌐 → reads visible text on screen → translates Chinese to English via Ollama → shows result.
 */

package dev.patrickgold.florisboard.ime.translation

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class WhatsAppTranslateOverlay : AccessibilityService() {

    companion object {
        private const val TAG = "WATranslateOverlay"
        private const val OLLAMA_URL = "http://10.2.0.182:11434/api/chat"
        private const val OLLAMA_MODEL = "qwen3.5:9b"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var floatingButton: View? = null
    private var resultCard: View? = null

    private fun wm(): WindowManager =
        getSystemService(WINDOW_SERVICE) as WindowManager

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Overlay accessibility service connected")
        showButton()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        Log.w(TAG, "Overlay service interrupted")
    }

    override fun onDestroy() {
        hideButton()
        hideResult()
        Log.i(TAG, "Overlay service destroyed")
        super.onDestroy()
    }

    // --- Screen reading ---

    private fun collectText(node: AccessibilityNodeInfo?, texts: MutableList<String>) {
        if (node == null) return
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty() && text.length > 1) {
            texts.add(text)
        }
        for (i in 0 until node.childCount) {
            collectText(node.getChild(i), texts)
        }
    }

    private fun getScreenText(): String {
        val root = rootInActiveWindow ?: return ""
        val texts = mutableListOf<String>()
        collectText(root, texts)
        val result = texts.takeLast(15).joinToString("\n")
        Log.i(TAG, "Screen text (${texts.size} nodes): '${result.take(100)}'")
        return result
    }

    private fun translateScreen() {
        val screenText = getScreenText()
        if (screenText.isBlank()) {
            Toast.makeText(this, "No text found on screen", Toast.LENGTH_SHORT).show()
            return
        }

        showResult("Translating...")

        thread {
            try {
                val translated = translateViaOllama(screenText)
                Log.i(TAG, "Translation: '${translated.take(80)}'")
                handler.post { showResult(translated) }
            } catch (e: Exception) {
                Log.e(TAG, "Translation failed: ${e.message}")
                handler.post { showResult("Translation failed: ${e.message}") }
            }
        }
    }

    private fun translateViaOllama(text: String): String {
        val prompt = "You are translating casual WhatsApp text messages from Chinese to English. Translate naturally and conversationally, not word-for-word. Keep the conversational tone. Respond with ONLY the English translation, nothing else:\n\n$text"
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

        val conn = (URL(OLLAMA_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 5000
            readTimeout = 15000
        }
        conn.outputStream.use { it.write(body.toByteArray()) }
        if (conn.responseCode != 200) throw Exception("Ollama ${conn.responseCode}")
        val resp = conn.inputStream.bufferedReader().readText()
        val json = JSONObject(resp)
        return if (json.has("choices")) {
            json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
        } else {
            json.getJSONObject("message").getString("content").trim()
        }
    }

    // --- Floating UI ---

    private fun showButton() {
        if (floatingButton != null) return

        val button = Button(this).apply {
            text = "🌐"
            textSize = 20f
            setBackgroundColor(0x00000000)
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(32, 16, 32, 16)
            setOnClickListener { translateScreen() }
            setOnLongClickListener { hideResult(); true }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 24
            y = 300
        }

        try {
            wm().addView(button, params)
            floatingButton = button
            Log.i(TAG, "Floating button shown")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show button: ${e.message}")
        }
    }

    private fun hideButton() {
        floatingButton?.let {
            try { wm().removeView(it) } catch (_: Exception) {}
        }
        floatingButton = null
    }

    private fun showResult(resultText: String) {
        hideResult()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF202022.toInt())
            setPadding(48, 32, 48, 32)
        }

        val header = TextView(this).apply {
            text = "EN translation:"
            setTextColor(0xFF8AB4F8.toInt())
            textSize = 11f
            setPadding(0, 0, 0, 16)
        }

        val result = TextView(this).apply {
            setText(resultText)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            setLineSpacing(4f, 1f)
            maxLines = 20
        }

        val closeButton = Button(this).apply {
            text = "✕"
            textSize = 12f
            setBackgroundColor(0xFF363639.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { hideResult() }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END
                topMargin = 24
            }
            this.layoutParams = lp
        }

        card.addView(header)
        card.addView(result)
        card.addView(closeButton)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 100
        }

        try {
            wm().addView(card, params)
            resultCard = card
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show result: ${e.message}")
        }
    }

    private fun hideResult() {
        resultCard?.let {
            try { wm().removeView(it) } catch (_: Exception) {}
        }
        resultCard = null
    }
}