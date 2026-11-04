/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 *
 * Smart-bar translation toolbar. A compact row shown above the keyboard keys
 * containing the DL/AI mode toggle, a live translation preview, and the sticky
 * translate on/off toggle (long-press cycles the target language ZH <-> ES).
 */

package dev.patrickgold.florisboard.ime.smartbar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.patrickgold.florisboard.ime.keyboard3.LocalImeController
import dev.patrickgold.florisboard.ime.translation.TranslationManager

// Gboard-dark palette (matches the FridayIME preview bar).
private val BarBackground = Color(0xFF202022)
private val ButtonBackground = Color(0xFF363639)
private val ButtonActiveBackground = Color(0xFF4285F4)
private val PreviewColor = Color(0xFF8AB4F8)
private val TextOn = Color(0xFFFFFFFF)
private val TextOff = Color(0xFF9AA0A6)

/**
 * Translation toolbar. Renders the DL/AI toggle, the live DeepL preview, and the
 * sticky translate on/off toggle. The toggle's long-press cycles ZH -> ES.
 */
@Composable
fun TranslationBar(modifier: Modifier = Modifier) {
    val translateOn by TranslationManager.translateOn.collectAsState()
    val targetLang by TranslationManager.targetLang.collectAsState()
    val smartMode by TranslationManager.smartMode.collectAsState()
    val previewText by TranslationManager.previewText.collectAsState()

    val imeController = LocalImeController.current
    val imeState by imeController.activeState.collectAsState()
    val textBefore = imeState.content.surroundingText.textBefore

    // Live preview: translate the text before the cursor via DeepL as the user types.
    LaunchedEffect(translateOn, textBefore) {
        if (translateOn) {
            val trimmed = textBefore.trim()
            if (trimmed.isNotEmpty()) {
                TranslationManager.updatePreview(trimmed)
            } else {
                TranslationManager.clearPreview()
            }
        } else {
            TranslationManager.clearPreview()
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(BarBackground)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // DL / AI smart-mode toggle.
        ModePill(
            label = if (smartMode) "AI" else "DL",
            active = smartMode,
            modifier = Modifier.padding(end = 6.dp),
            onClick = { TranslationManager.toggleSmartMode() },
        )

        // Live translation preview.
        Text(
            text = if (translateOn) previewText else "",
            color = PreviewColor,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .wrapContentWidth(Alignment.Start),
        )

        Spacer(modifier = Modifier.width(6.dp))

        // Translate button — triggers translation of text before cursor.
        ModePill(
            label = "🌐",
            active = false,
            onClick = { TranslationManager.triggerTranslate() },
        )

        Spacer(modifier = Modifier.width(6.dp))

        // Sticky translate on/off toggle (long-press cycles ZH -> ES).
        ModePill(
            label = if (translateOn) targetLang else "OFF",
            active = translateOn,
            onClick = { TranslationManager.toggleTranslateOn() },
            onLongClick = { TranslationManager.cycleTargetLang() },
        )
    }
}

@Composable
private fun ModePill(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
) {
    val background = if (active) ButtonActiveBackground else ButtonBackground
    val color = if (active) TextOn else TextOff
    Row(
        modifier = modifier
            .height(30.dp)
            .background(background, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp)
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                } else {
                    Modifier.clickable(onClick = onClick)
                }
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, color = color, fontSize = 12.sp, maxLines = 1)
    }
}