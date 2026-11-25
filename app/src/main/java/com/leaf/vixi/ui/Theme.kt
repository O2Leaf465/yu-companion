package com.leaf.vixi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

private val Ink = Color(0xFF0D0F12)
private val Hairline = Color(0xFF1B1E23)
private val Panel = Color(0xFF23272E)
private val Her = Color(0xFF1E2228)
private val Mine = Color(0xFF2B4A40)
private val Text = Color(0xFFE7E9EC)
private val Muted = Color(0xFF7B8289)
private val Accent = Color(0xFF93BCAA)
private val Danger = Color(0xFFE08A7A)

private val VixiColors = darkColorScheme(
    primary = Accent,
    onPrimary = Ink,
    background = Ink,
    onBackground = Text,
    surface = Ink,
    onSurface = Text,
    surfaceVariant = Her,
    onSurfaceVariant = Muted,
    outline = Hairline,
    error = Danger,
)

/** 只做暗色：亮底会把"深夜聊天"的体感毁掉 */
@Composable
fun VixiTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = VixiColors, content = content)
}

object VixiPalette {
    val ink = Ink
    val hairline = Hairline
    val panel = Panel
    val her = Her
    val mine = Mine
    val text = Text
    val muted = Muted
    val accent = Accent
    val danger = Danger
}

/** 点按不要涟漪和灰罩：聊天界面里多余的高亮很出戏 */
@Composable
fun Modifier.clickableNoIndication(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    return this.clickable(
        enabled = enabled,
        indication = null,
        interactionSource = source,
        onClick = onClick,
    )
}
