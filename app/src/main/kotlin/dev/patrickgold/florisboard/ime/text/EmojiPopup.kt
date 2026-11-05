/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 *
 * Emoji popup — a lightweight popup grid of common emojis that appears above
 * the keyboard when the emoji key (next to spacebar) is pressed. Tapping an
 * emoji inserts it into the text field and dismisses the popup.
 */

package dev.patrickgold.florisboard.ime.text

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.ime.keyboard3.ImeController

// Common emojis grouped by category — recent + frequently used
private val EMOJI_LIST = listOf(
    // Smileys & emotions
    "😀", "😃", "😄", "😁", "😆", "😅", "🤣", "😂", "🙂", "🙃",
    "😉", "😊", "😇", "🥰", "😍", "🤩", "😘", "😗", "☺️", "😚",
    "😋", "😛", "😜", "🤪", "😝", "🤑", "🤗", "🤭", "🤔", "🤐",
    "😐", "😑", "😶", "😏", "😒", "🙄", "😬", "😮‍💨", "🤥", "😴",
    "🥱", "🤤", "😪", "😵", "🤯", "🤠", "🥳", "🥺", "😎", "🤓",
    "🧐", "😕", "😟", "🙁", "☹️", "😮", "😯", "😲", "😳", "🥵",
    "🥶", "😱", "😨", "😰", "😥", "😢", "😭", "😤", "😠", "😡",
    "🤬", "😈", "👿", "💀", "💩", "🤡", "👻", "👽", "🤖", "👾",
    // Hearts & emotions
    "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "🤎", "💔",
    "❣️", "💕", "💞", "💓", "💗", "💖", "💘", "💝", "💟", "🔥",
    // Hand gestures
    "👍", "👎", "👌", "✌️", "🤞", "🤟", "🤙", "👈", "👉", "👆",
    "👇", "☝️", "✋", "🤚", "👋", "🤝", "🙏", "💪", "✊", " fist",
    // Animals
    "🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻", "🐼", "🐨", "🐯",
    "🦁", "🐮", "🐷", "🐸", "🐵", "🐔", "🐧", "🐦", "🦆", "🦉",
    // Food & drink
    "🍎", "🍊", "🍋", "🍌", "🍉", "🍇", "🍓", "🫐", "🍒", "🍑",
    "🥭", "🍍", "🥥", "🥝", "🍅", "🥑", "🍔", "🍟", "🍕", "🌮",
    "☕", "🍵", "🍺", "🍷", "🍸", "🍹", "🥃", "🥂", "🍻", "🥤",
    // Activities & travel
    "⚽", "🏀", "🏈", "⚾", "🎾", "🏐", "🏉", "🥏", "🎱", "🏓",
    "🚗", "🚕", "🚙", "🏍️", "🚲", "✈️", "🚀", "🛸", "⛵", "🚤",
    // Objects
    "💻", "📱", "⌚", "📷", "🎮", "🕹️", "🎧", "🎵", "🎸", "🎹",
    "💡", "📱", "🔋", "🔧", "🔨", "🛠️", "⚙️", "🧰", "🔑", "🔒",
    // Symbols
    "✅", "❌", "⚠️", "❓", "❗", "‼️", "⁉️", "💯", "🔍", "📌",
    "🎉", "🎊", "🎁", "🏆", "🥇", "🥈", "🥉", "🎵", "✨", "💫",
)

@Composable
fun EmojiPopup(
    imeController: ImeController,
) {
    if (!imeController.showEmojiPopup) return

    var dismissByTapOutside by remember { mutableStateOf(false) }

    Popup(
        alignment = Alignment.BottomCenter,
        onDismissRequest = {
            imeController.showEmojiPopup = false
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .background(Color(0xFF1F1F1F), RoundedCornerShape(16.dp, 16.dp, 0.dp, 0.dp))
                .padding(8.dp),
        ) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(40.dp),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(EMOJI_LIST) { emoji ->
                    Text(
                        text = emoji,
                        fontSize = 24.sp,
                        fontFamily = FontFamily(Font(resId = 0)), // system emoji font
                        modifier = Modifier
                            .size(40.dp)
                            .clickable {
                                val ic = FlorisImeService.currentInputConnection()
                                ic?.commitText(emoji, 1)
                                imeController.showEmojiPopup = false
                            },
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}