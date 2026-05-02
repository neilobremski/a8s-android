package com.a8s.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MacroParserTest {

    @Test
    fun `tap parses x and y`() {
        val steps = MacroParser.parse("tap 100 200")
        assertEquals(1, steps.size)
        assertEquals(MacroStep.Tap(100, 200), steps[0])
    }

    @Test
    fun `longtap default duration`() {
        val steps = MacroParser.parse("longtap 50 60")
        assertEquals(MacroStep.LongTap(50, 60, A11yService.LONG_TAP_MS), steps[0])
    }

    @Test
    fun `longtap explicit duration`() {
        val steps = MacroParser.parse("longtap 50 60 1500")
        assertEquals(MacroStep.LongTap(50, 60, 1500L), steps[0])
    }

    @Test
    fun `swipe four coords default ms`() {
        val steps = MacroParser.parse("swipe 10 20 30 40")
        assertEquals(MacroStep.Swipe(10, 20, 30, 40, A11yService.SWIPE_MS), steps[0])
    }

    @Test
    fun `swipe explicit duration`() {
        val steps = MacroParser.parse("swipe 10 20 30 40 750")
        assertEquals(MacroStep.Swipe(10, 20, 30, 40, 750L), steps[0])
    }

    @Test
    fun `key uppercases name`() {
        val steps = MacroParser.parse("key back")
        assertEquals(MacroStep.Key("BACK"), steps[0])
    }

    @Test
    fun `input takes literal trailing text`() {
        val steps = MacroParser.parse("input hello world this is text")
        assertEquals(MacroStep.Input("hello world this is text"), steps[0])
    }

    @Test
    fun `find takes literal trailing label`() {
        val steps = MacroParser.parse("find Sign In Button")
        assertEquals(MacroStep.Find("Sign In Button"), steps[0])
    }

    @Test
    fun `delay parses ms`() {
        val steps = MacroParser.parse("delay 1500")
        assertEquals(MacroStep.Delay(1500L), steps[0])
    }

    @Test
    fun `multiple steps separated by pipe`() {
        val steps = MacroParser.parse("tap 1 2 | delay 500 | key home")
        assertEquals(3, steps.size)
        assertEquals(MacroStep.Tap(1, 2), steps[0])
        assertEquals(MacroStep.Delay(500L), steps[1])
        assertEquals(MacroStep.Key("HOME"), steps[2])
    }

    @Test
    fun `empty segments are skipped`() {
        val steps = MacroParser.parse("tap 1 2 | | | key home")
        assertEquals(2, steps.size)
    }

    @Test
    fun `bad numeric x for tap parse error`() {
        val steps = MacroParser.parse("tap notanumber 5")
        val err = steps[0]
        assertTrue(err is MacroStep.ParseError)
        assertTrue((err as MacroStep.ParseError).reason.contains("integer x"))
    }

    @Test
    fun `unknown verb parse error`() {
        val steps = MacroParser.parse("teleport 10 10")
        val err = steps[0]
        assertTrue(err is MacroStep.ParseError)
        assertTrue((err as MacroStep.ParseError).reason.contains("unknown verb"))
    }

    @Test
    fun `tap missing y is parse error`() {
        val steps = MacroParser.parse("tap 100")
        assertTrue(steps[0] is MacroStep.ParseError)
    }

    @Test
    fun `swipe missing args is parse error`() {
        val steps = MacroParser.parse("swipe 10 20")
        assertTrue(steps[0] is MacroStep.ParseError)
    }

    @Test
    fun `input without text is parse error`() {
        val steps = MacroParser.parse("input")
        assertTrue(steps[0] is MacroStep.ParseError)
    }

    @Test
    fun `find without label is parse error`() {
        val steps = MacroParser.parse("find")
        assertTrue(steps[0] is MacroStep.ParseError)
    }

    @Test
    fun `delay without ms is parse error`() {
        val steps = MacroParser.parse("delay")
        assertTrue(steps[0] is MacroStep.ParseError)
    }

    @Test
    fun `negative coords clamp to zero`() {
        val steps = MacroParser.parse("tap -5 -10")
        assertEquals(MacroStep.Tap(0, 0), steps[0])
    }

    @Test
    fun `whitespace around segments is trimmed`() {
        val steps = MacroParser.parse("   tap 1 2   |   key home   ")
        assertEquals(2, steps.size)
        assertEquals(MacroStep.Tap(1, 2), steps[0])
        assertEquals(MacroStep.Key("HOME"), steps[1])
    }

    @Test
    fun `empty input yields empty list`() {
        assertEquals(emptyList<MacroStep>(), MacroParser.parse(""))
        assertEquals(emptyList<MacroStep>(), MacroParser.parse("   "))
    }
}
