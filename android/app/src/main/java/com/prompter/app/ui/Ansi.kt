package com.prompter.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.prompter.app.net.ScreenRun

/**
 * ANSI SGR rendering for the pane preview: converts the bridge's color runs
 * ("#rrggbb" or decimal xterm-256 index, attrs "bdiur") into Compose SpanStyle.
 */

// xterm-256: 0-15 basic, 16-231 6x6x6 cube, 232-255 grayscale ramp.
private val BASE16 = intArrayOf(
    0x000000, 0x800000, 0x008000, 0x808000, 0x000080, 0x800080, 0x008080, 0xc0c0c0,
    0x808080, 0xff0000, 0x00ff00, 0xffff00, 0x0000ff, 0xff00ff, 0x00ffff, 0xffffff,
)
private val CUBE = intArrayOf(0, 95, 135, 175, 215, 255)

fun xtermColor(idx: Int): Color {
    val i = idx.coerceIn(0, 255)
    val rgb = when {
        i < 16 -> BASE16[i]
        i < 232 -> {
            val c = i - 16
            val r = CUBE[(c / 36) % 6]
            val g = CUBE[(c / 6) % 6]
            val b = CUBE[c % 6]
            (r shl 16) or (g shl 8) or b
        }
        else -> {
            val v = 8 + (i - 232) * 10
            (v shl 16) or (v shl 8) or v
        }
    }
    return Color(rgb or 0xFF000000.toInt())
}

/** "#rrggbb" or a decimal 256-color index -> Color; null when unset/invalid. */
fun parseFgBg(spec: String?): Color? {
    if (spec == null) return null
    return if (spec.startsWith("#")) {
        runCatching { Color(android.graphics.Color.parseColor(spec)) }.getOrNull()
    } else {
        spec.toIntOrNull()?.let { xtermColor(it) }
    }
}

/**
 * SpanStyle for one run against the preview canvas colors.
 * Attrs: b=bold, d=dim (60% alpha), i=italic, u=underline, r=swap fg/bg.
 */
fun runSpanStyle(run: ScreenRun, defFg: Color, defBg: Color): SpanStyle {
    var fg = parseFgBg(run.fg) ?: defFg
    var bg = parseFgBg(run.bg) ?: defBg
    val attrs = run.attrs ?: ""
    if (attrs.contains('r')) {
        val t = fg
        fg = bg
        bg = t
    }
    var style = SpanStyle(color = if (attrs.contains('d')) fg.copy(alpha = 0.6f) else fg, background = bg)
    if (attrs.contains('b')) style = style.copy(fontWeight = FontWeight.Bold)
    if (attrs.contains('i')) style = style.copy(fontStyle = FontStyle.Italic)
    if (attrs.contains('u')) style = style.copy(textDecoration = TextDecoration.Underline)
    return style
}
