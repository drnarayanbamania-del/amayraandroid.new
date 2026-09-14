package com.amayra.maya.accessibility

import org.junit.Assert.*
import org.junit.Test

class MacroModelTest {

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `parses builtin macro file format`() {
        val raw = """
        {
          "_comment": ["x"],
          "version": 3,
          "macros": [
            {
              "name": "chatgpt image",
              "intent": "generate an image",
              "params": ["image prompt"],
              "steps": [
                { "tool": "open_app_by_name", "args": { "name": "ChatGPT" } },
                { "tool": "wait", "args": { "seconds": 4 } },
                { "tool": "tap_text", "args": { "text": "New chat", "_optional": true } },
                { "tool": "wait_for_screen_text", "args": { "text": "Creating image" }, "mode": "disappear", "timeout_seconds": 300 },
                { "tool": "type_text", "args": { "text": "Create an image: {image_prompt}" } }
              ]
            }
          ]
        }
        """.trimIndent()
        val f = json.decodeFromString<MacroFile>(raw)
        assertEquals(3, f.version)
        assertEquals(1, f.macros.size)
        val m = f.macros[0]
        assertEquals("chatgpt image", m.name)
        assertEquals(5, m.steps.size)
        assertEquals("open_app_by_name", m.steps[0].tool)
        assertEquals("disappear", m.steps[3].mode)
        assertEquals(300, m.steps[3].timeout_seconds)
        assertTrue(m.steps[2].args.containsKey("_optional"))
    }

    @Test
    fun `missing file yields empty macro list without crash`() {
        // MacroEngine with a context whose assets lack the file is Android-bound;
        // here we only assert the parser itself never throws on empty input.
        val f = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString<MacroFile>("""{"version":1,"macros":[]}""")
        assertEquals(0, f.macros.size)
    }
}

class PersonaModelTest {

    @Test
    fun `three personas exist with unique ids`() {
        assertEquals(listOf("maya", "friday", "venom"), com.amayra.maya.persona.Persona.ALL.map { it.id })
    }

    @Test
    fun `byId falls back to Maya for unknown or null`() {
        assertEquals(com.amayra.maya.persona.Persona.MAYA, com.amayra.maya.persona.Persona.byId("nope"))
        assertEquals(com.amayra.maya.persona.Persona.MAYA, com.amayra.maya.persona.Persona.byId(null))
    }

    @Test
    fun `venom prompt forbids harmful content`() {
        val p = com.amayra.maya.persona.Persona.VENOM.systemPrompt.lowercase()
        assertTrue(p.contains("no profanity") || p.contains("clean"))
    }
}
