package com.opentermx.app.ui.terminal.highlight

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.opentermx.app.settings.AppSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HighlightSettingsRoundtripTest {

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    @Test
    fun `roundtrip JSON preserva defaults y customRules`() {
        val original = AppSettings(
            highlight = HighlightSettings(
                enabled = true,
                promptDetectionEnabled = true,
                keywordsEnabled = false,
                customRules = listOf(
                    CustomHighlightRule(
                        id = "user.1",
                        name = "Mi regla",
                        pattern = """\bCRITICAL\b""",
                        fgRgb = "#FF5733",
                        bgRgb = "#000000",
                        priority = 5,
                    ),
                ),
            ),
        )
        val json = mapper.writeValueAsString(original)
        val restored = mapper.readValue(json, AppSettings::class.java)

        assertTrue(restored.highlight.enabled)
        assertEquals(false, restored.highlight.keywordsEnabled)
        assertEquals(1, restored.highlight.customRules.size)
        val rule = restored.highlight.customRules.single()
        assertEquals("Mi regla", rule.name)
        assertEquals("#FF5733", rule.fgRgb)
    }

    @Test
    fun `settings legacy sin highlight deserializan con defaults`() {
        val legacy = """{ "theme": "DARK" }"""
        val restored = mapper.readValue(legacy, AppSettings::class.java)
        assertTrue(restored.highlight.enabled)
        assertTrue(restored.highlight.promptDetectionEnabled)
        assertTrue(restored.highlight.keywordsEnabled)
        assertTrue(restored.highlight.customRules.isEmpty())
    }

    @Test
    fun `customRule compile rechaza regex invalida`() {
        val bad = CustomHighlightRule(
            id = "bad", name = "bad", pattern = """[invalid""", fgRgb = "#FFFFFF",
        )
        assertEquals(null, bad.compile())
    }

    @Test
    fun `customRule compile rechaza color hex invalido`() {
        val bad = CustomHighlightRule(
            id = "bad2", name = "bad", pattern = """\w+""", fgRgb = "not-hex",
        )
        assertEquals(null, bad.compile())
    }

    @Test
    fun `customRule compile exitoso`() {
        val ok = CustomHighlightRule(
            id = "ok", name = "ok", pattern = """\bFOO\b""", fgRgb = "#ABCDEF",
        )
        val compiled = ok.compile()
        assertEquals("ok", compiled?.id)
        assertEquals(HighlightCategory.CUSTOM, compiled?.category)
    }
}
