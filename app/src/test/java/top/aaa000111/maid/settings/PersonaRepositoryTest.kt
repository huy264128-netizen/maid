package top.aaa000111.maid.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException

class PersonaRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun scanPersonaAssets_scansTopLevelAndNestedFiles_ignoringEmptyFilesAndDirectories() {
        val fakeTree: Map<String, List<String>> = mapOf(
            "persona" to listOf("gentle_maid.md", "empty_dir", "empty_file.txt", "styles"),
            "persona/styles" to listOf("tsundere.txt", "concise"),
            "persona/empty_dir" to emptyList(),
        )
        val fakeFiles: Map<String, String> = mapOf(
            "persona/gentle_maid.md" to "  温柔体贴的女仆助手，说话轻声细语，回答条理清晰。  \n",
            "persona/empty_file.txt" to "   \n  ",
            "persona/styles/tsundere.txt" to "表面傲娇但内心非常关心主人，回答高效准确。",
            "persona/styles/concise" to "简洁干练，直接给重点。",
        )

        val presets = PersonaRepository.scanPersonaAssets(
            listEntries = { path -> fakeTree[path]?.toTypedArray() },
            openEntry = { path ->
                val text = fakeFiles[path] ?: throw FileNotFoundException(path)
                ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))
            },
        )

        assertEquals(3, presets.size)
        assertEquals(
            listOf("gentle_maid.md", "styles/concise", "styles/tsundere.txt"),
            presets.map { it.id },
        )
        assertEquals(
            listOf("gentle_maid", "styles/concise", "styles/tsundere"),
            presets.map { it.name },
        )
        assertEquals("persona/gentle_maid.md", presets[0].assetPath)
        assertEquals(
            "温柔体贴的女仆助手，说话轻声细语，回答条理清晰。",
            presets[0].content,
        )
    }

    @Test
    fun scanPersonaAssets_returnsEmptyList_whenPersonaDirDoesNotExist() {
        val presets = PersonaRepository.scanPersonaAssets(
            listEntries = { null },
            openEntry = { throw FileNotFoundException(it) },
        )

        assertTrue(presets.isEmpty())
    }

    @Test
    fun resolveDefaultPersonaConfig_prefersDefaultRigorousPresetFromAssets() {
        val other = PersonaPreset(
            id = "a_casual.md",
            name = "a_casual",
            assetPath = "persona/a_casual.md",
            content = "轻松聊天风格",
        )
        val defaultRigorous = PersonaPreset(
            id = PersonaRepository.DEFAULT_PRESET_ID,
            name = "默认严谨",
            assetPath = "persona/${PersonaRepository.DEFAULT_PRESET_ID}",
            content = "先问清楚需求，直接指出不合理之处。",
        )

        val resolved = PersonaRepository.resolveDefaultPersonaConfig(listOf(other, defaultRigorous))
        assertEquals(PersonaRepository.DEFAULT_PRESET_ID, resolved.selectedPresetId)
        assertEquals("先问清楚需求，直接指出不合理之处。", resolved.personaText)

        val fallback = PersonaRepository.resolveDefaultPersonaConfig(listOf(other))
        assertEquals("a_casual.md", fallback.selectedPresetId)
        assertEquals("轻松聊天风格", fallback.personaText)

        val empty = PersonaRepository.resolveDefaultPersonaConfig(emptyList())
        assertEquals(PersonaConfig(), empty)
    }

    @Test
    fun customPersonaCrud_createsScansDeduplicatesAndDeletesSafely() {
        val customDir = tempFolder.newFolder("persona")

        val first = PersonaRepository.createCustomPersonaInDir(
            customDir = customDir,
            name = "温柔女仆",
            content = "说话温柔体贴，主动关心用户。",
        )
        val duplicateName = PersonaRepository.createCustomPersonaInDir(
            customDir = customDir,
            name = "温柔女仆",
            content = "第二版温柔女仆人设。",
        )

        assertTrue(first.isCustom)
        assertTrue(first.id.startsWith(PersonaRepository.CUSTOM_PERSONA_ID_PREFIX))
        assertTrue(duplicateName.id != first.id)

        val scanned = PersonaRepository.scanCustomPersonaDir(customDir)
        assertEquals(2, scanned.size)
        assertTrue(scanned.all { it.isCustom })

        assertTrue(PersonaRepository.deleteCustomPersonaInDir(customDir, first.id))
        assertFalse(PersonaRepository.deleteCustomPersonaInDir(customDir, "默认严谨.md"))
        assertFalse(PersonaRepository.deleteCustomPersonaInDir(customDir, "custom/../escape.md"))

        val remaining = PersonaRepository.scanCustomPersonaDir(customDir)
        assertEquals(1, remaining.size)
        assertEquals(duplicateName.id, remaining.single().id)
    }
}
