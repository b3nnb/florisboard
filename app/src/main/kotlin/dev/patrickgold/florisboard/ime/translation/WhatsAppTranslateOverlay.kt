/*
 * WhatsApp Translation Overlay with Accessibility — floating button that
 * reads Chinese text directly from the WhatsApp screen via AccessibilityService.
 * Tap 🌐 → reads visible messages on screen → translates Chinese to English via Ollama → shows chat-style result.
 *
 * Uses accessibility node bounds to classify messages as sent (right-aligned) or received (left-aligned),
 * then formats the translation result as a chat timeline.
 */

package dev.patrickgold.florisboard.ime.translation

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
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

data class ChatMessage(val text: String, val isMine: Boolean)

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

    // --- Screen reading with bounds-based alignment ---

    data class TextWithBounds(val text: String, val bounds: Rect)

    private fun collectTextWithBounds(node: AccessibilityNodeInfo?, items: MutableList<TextWithBounds>) {
        if (node == null) return
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty() && text.length > 1) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            items.add(TextWithBounds(text, rect))
        }
        for (i in 0 until node.childCount) {
            collectTextWithBounds(node.getChild(i), items)
        }
    }

    private fun getScreenMessages(): List<ChatMessage> {
        val root = rootInActiveWindow ?: return emptyList()
        val screenWidth = resources.displayMetrics.widthPixels
        val midX = screenWidth / 2

        val items = mutableListOf<TextWithBounds>()
        collectTextWithBounds(root, items)

        // Filter out likely UI elements (top bar, input field, timestamps)
        // Messages are typically in the middle area of the screen, not at the very top or bottom
        val chatItems = items.filter { ib ->
            val t = ib.text
            // Skip common UI strings
            t != "Type a message" &&
            !t.matches(Regex(".*\\d{1,2}:\\d{2}.*")) && // timestamps
            t.length > 1
        }

        // Sort by vertical position (top to bottom = chronological order)
        val sorted = chatItems.sortedBy { it.bounds.top }

        // Classify each text as mine (right-aligned) or theirs (left-aligned)
        // WhatsApp puts sent messages in the right half, received in the left half
        val messages = sorted.map { ib ->
            val isMine = ib.bounds.left >= midX - 100 // right half, with some tolerance
            ChatMessage(ib.text, isMine)
        }

        Log.i(TAG, "Screen messages: ${messages.size} (${messages.count { it.isMine }} mine, ${messages.count { !it.isMine }} theirs)")
        return messages.takeLast(15) // most recent 15 messages
    }

    private fun translateScreen() {
        val messages = getScreenMessages()
        if (messages.isEmpty()) {
            Toast.makeText(this, "No messages found on screen", Toast.LENGTH_SHORT).show()
            return
        }

        showResult("Translating...", emptyList())

        thread {
            try {
                val translated = translateViaOllama(messages)
                Log.i(TAG, "Translation done: ${translated.size} messages")
                handler.post { showResult("", translated) }
            } catch (e: Exception) {
                Log.e(TAG, "Translation failed: ${e.message}")
                handler.post { showResult("Translation failed: ${e.message}", emptyList()) }
            }
        }
    }

    private fun translateViaOllama(messages: List<ChatMessage>): List<ChatMessage> {
        // Build the chat context for the LLM
        val chatText = messages.joinToString("\n") { m ->
            val label = if (m.isMine) "[You]" else "[Them]"
            "$label ${m.text}"
        }

        val prompt = "You are translating a WhatsApp conversation from Chinese to English. " +
            "Each message is labeled [You] (sent by the user) or [Them] (received from the other person). " +
            "Translate each message naturally and conversationally. " +
            "Keep the [You]/[Them] labels and put each translated message on its own line. " +
            "Respond with ONLY the translations in the same format, nothing else:\n\n$chatText"

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
        val rawContent = if (json.has("choices")) {
            json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
        } else {
            json.getJSONObject("message").getString("content").trim()
        }

        // Parse the LLM response back into ChatMessages
        val result = mutableListOf<ChatMessage>()
        for (line in rawContent.split("\n")) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val isMine = trimmed.startsWith("[You]")
            val text = trimmed.removePrefix("[You]").removePrefix("[Them]").trim()
            if (text.isNotEmpty()) {
                result.add(ChatMessage(text, isMine))
            }
        }

        // Fallback: if parsing failed, return raw text as single message
        if (result.isEmpty() && rawContent.isNotEmpty()) {
            result.add(ChatMessage(rawContent, false))
        }

        return result
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

    private fun showResult(loadingText: String, messages: List<ChatMessage>) {
        hideResult()

        val screenWidth = resources.displayMetrics.widthPixels
        val cardWidth = (screenWidth * 0.92).toInt()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF202022.toInt())
            setPadding(24, 32, 24, 32)
        }

        // Header
        val header = TextView(this).apply {
            text = "EN Translation"
            setTextColor(0xFF8AB4F8.toInt())
            textSize = 12f
            setPadding(8, 0, 8, 16)
        }
        card.addView(header)

        if (messages.isEmpty()) {
            // Loading state
            val loading = TextView(this).apply {
                text = loadingText
                setTextColor(0xFF888888.toInt())
                textSize = 14f
                setPadding(8, 16, 8, 16)
            }
            card.addView(loading)
        } else {
            // Chat-style message list
            for (msg in messages) {
                val bubble = createMessageBubble(msg)
                card.addView(bubble)
            }
        }

        // Close button
        val closeButton = Button(this).apply {
            text = "✕ Close"
            textSize = 11f
            setBackgroundColor(0xFF363639.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { hideResult() }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END
                topMargin = 20
            }
            this.layoutParams = lp
        }
        card.addView(closeButton)

        val params = WindowManager.LayoutParams(
            cardWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 80
        }

        try {
            wm().addView(card, params)
            resultCard = card
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show result: ${e.message}")
        }
    }

    private fun createMessageBubble(msg: ChatMessage): View {
        val screenWidth = resources.displayMetrics.widthPixels
        val maxBubbleWidth = (screenWidth * 0.65).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 8
                bottomMargin = 8
                gravity = if (msg.isMine) Gravity.END else Gravity.START
            }
            this.layoutParams = lp
        }

        val bubble = TextView(this).apply {
            text = msg.text
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            setLineSpacing(3f, 1f)
            maxLines = 10
            setPadding(28, 18, 28, 18)

            if (msg.isMine) {
                // Sent message — blue bubble, right side
                setBackgroundColor(0xFF1B5E9C.toInt())
                val lp = LinearLayout.LayoutParams(
                    maxBubbleWidth,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.END
                }
                this.layoutParams = lp
            } else {
                // Received message — dark gray bubble, left side
                setBackgroundColor(0xFF363639.toInt())
                val lp = LinearLayout.LayoutParams(
                    maxBubbleWidth,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.START
                }
                this.layoutParams = lp
            }
        }

        // Sender label
        val label = TextView(this).apply {
            text = if (msg.isMine) "You" else "Them"
            setTextColor(0xFF666666.toInt())
            textSize = 10f
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = if (msg.isMine) Gravity.END else Gravity.START
                topMargin = 2
                bottomMargin = 2
            }
            this.layoutParams = lp
        }

        if (msg.isMine) {
            container.addView(bubble)
            container.addView(label)
        } else {
            container.addView(bubble)
            container.addView(label)
        }

        return container
    }

    private fun hideResult() {
        resultCard?.let {
            try { wm().removeView(it) } catch (_: Exception) {}
        }
        resultCard = null
    }
}