package com.mobilecoder.ide.feature.terminal

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/** ensureReadableTextColor：低对比前景向主题前景色靠拢，高对比原样保留。 */
class TermFgReadableTest {

    @Test
    fun lightTextOnLightSurfaceIsDarkened() {
        val themeFg = Color(0xFF1C1B19)
        val lightBg = Color(0xFFF7F5EF)
        val out = ensureReadableTextColor(Color.White, lightBg, themeFg)
        assert(out != Color.White)
    }

    @Test
    fun highContrastColorsUnchanged() {
        val themeFg = Color(0xFF1C1B19)
        // 深色底上的白字：对比 21:1，原样保留
        assertEquals(Color.White, ensureReadableTextColor(Color.White, Color.Black, themeFg))
        // 浅底深字：足够对比，原样保留
        val red = Color(0xFFB31D28)
        assertEquals(red, ensureReadableTextColor(red, Color(0xFFF7F5EF), themeFg))
    }
}
