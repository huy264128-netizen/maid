package top.aaa000111.maid.provider

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoleModelHistoryTest {
    private val a = RoleModelSelection(
        LlmProvider.OPENAI, "model-a",
        modelMetadata = LlmModelMetadata(contextLength = 200_000, supportsTools = true, supportsReasoning = true),
        reasoningEffort = ModelReasoningEffort.HIGH,
    )
    private val b = a.copy(modelId = "model-b", reasoningEffort = ModelReasoningEffort.LOW)
    private val role = ModelRole.FAST_CHEAP
    private val historyKey = stringPreferencesKey("fast_cheap_selection_history")

    @Test
    fun switchingModelsRestoresEffortAndReselectingCurrentModelKeepsIt() {
        val history = RoleModelHistory.remember(RoleModelHistory.remember(emptyList(), a), b)
        val restored = RoleModelHistory.selectModel(b, history, LlmModelOption(LlmProvider.OPENAI, "model-a"))
        assertEquals(a, restored)
        val edited = restored.copy(reasoningEffort = ModelReasoningEffort.XHIGH)
        assertEquals(ModelReasoningEffort.XHIGH, RoleModelHistory.selectModel(
            edited, history, LlmModelOption(LlmProvider.OPENAI, "model-a"),
        ).reasoningEffort)
        assertEquals(ModelReasoningEffort.DEFAULT, RoleModelHistory.selectModel(
            b, history, LlmModelOption(LlmProvider.OPENAI, "new-model"),
        ).reasoningEffort)
    }

    @Test
    fun persistenceSurvivesSwitchesWithoutOverwritingOtherRoles() {
        val prefs = mutablePreferencesOf()
        ModelConfigRepository.writeRoleConfig(prefs, ModelRole.COMPLEX_PLANNING, b)
        ModelConfigRepository.writeRoleConfig(prefs, role, a)
        ModelConfigRepository.writeRoleConfig(prefs, role, b)
        val history = RoleModelHistory.decode(prefs[historyKey])
        assertEquals(listOf(b, a), history)
        val restored = RoleModelHistory.selectModel(b, history, LlmModelOption(LlmProvider.OPENAI, "model-a"))
        ModelConfigRepository.writeRoleConfig(prefs, role, restored)
        val configs = ModelConfigRepository.readConfigsFromPreferences(prefs)
        assertEquals(a, configs[role])
        assertEquals(b, configs[ModelRole.COMPLEX_PLANNING])
        assertEquals(listOf(a, b), RoleModelHistory.decode(prefs[historyKey]))
    }

    @Test
    fun newProviderCanBePartiallySelectedAndPriorProviderIsRemembered() {
        val prefs = mutablePreferencesOf()
        ModelConfigRepository.writeRoleConfig(prefs, role, a)
        val partial = RoleModelSelection(LlmProvider.CHATGPT)
        ModelConfigRepository.writeRoleConfig(prefs, role, partial)
        val current = ModelConfigRepository.readConfigsFromPreferences(prefs).getValue(role)
        assertEquals(partial, current)
        assertFalse(current.isComplete)
        val restored = RoleModelHistory.selectProvider(
            current, RoleModelHistory.decode(prefs[historyKey]), ModelProviderTarget(LlmProvider.OPENAI),
        )
        assertEquals(a, restored)
    }

    @Test
    fun customEndpointsWithSameModelAreIndependentAndUseMostRecentModel() {
        val one = a.copy(provider = LlmProvider.CUSTOM, customProviderId = "one")
        val two = one.copy(customProviderId = "two", reasoningEffort = ModelReasoningEffort.LOW)
        val latest = one.copy(modelId = "model-new", reasoningEffort = ModelReasoningEffort.MEDIUM)
        val history = RoleModelHistory.decode(RoleModelHistory.encode(listOf(latest, two, one)))
        assertEquals(latest, RoleModelHistory.selectProvider(
            two, history, ModelProviderTarget(LlmProvider.CUSTOM, "one"),
        ))
        assertEquals(two, RoleModelHistory.selectProvider(
            one, history, ModelProviderTarget(LlmProvider.CUSTOM, "two"),
        ))
    }

    @Test
    fun draftHistoryOverridesOldSavedValueAndUnknownEntriesAreSkipped() {
        val prefs = mutablePreferencesOf()
        ModelConfigRepository.writeRoleConfig(prefs, role, a.copy(reasoningEffort = ModelReasoningEffort.MEDIUM))
        ModelConfigRepository.writeRoleConfig(prefs, role, b, listOf(a))
        assertEquals(ModelReasoningEffort.HIGH, RoleModelHistory.decode(prefs[historyKey])
            .first { it.modelId == a.modelId }.reasoningEffort)
        assertTrue(RoleModelHistory.decode("invalid-json").isEmpty())
        val json = """[{"provider":"removed","model":"ignored"},{"provider":"openai","model":"valid",
            "reasoning_effort":"unknown","future_field":1},null]"""
        assertEquals(listOf(RoleModelSelection(LlmProvider.OPENAI, "valid")), RoleModelHistory.decode(json))
    }

    @Test
    fun allFourRolesKeepIndependentSettingsForTheSameModel() {
        val prefs = mutablePreferencesOf()
        val efforts = listOf(
            ModelReasoningEffort.LOW, ModelReasoningEffort.HIGH,
            ModelReasoningEffort.MINIMAL, ModelReasoningEffort.XHIGH,
        )
        val expected = ModelRole.entries.mapIndexed { index, role ->
            role to a.copy(reasoningEffort = efforts[index])
        }.toMap()
        expected.forEach { (role, selection) -> ModelConfigRepository.writeRoleConfig(prefs, role, selection) }
        for ((role, original) in expected) {
            ModelConfigRepository.writeRoleConfig(prefs, role, b)
            val history = RoleModelHistory.decode(prefs[stringPreferencesKey("${role.keyPrefix}_selection_history")])
            val restored = RoleModelHistory.selectModel(b, history, LlmModelOption(LlmProvider.OPENAI, "model-a"))
            ModelConfigRepository.writeRoleConfig(prefs, role, restored)
            assertEquals(original, ModelConfigRepository.readConfigsFromPreferences(prefs)[role])
        }
        assertEquals(expected, ModelConfigRepository.readConfigsFromPreferences(prefs))
    }

    @Test
    fun explicitNonReasoningMetadataDoesNotRestoreUnsupportedEffort() {
        val restored = RoleModelHistory.selectModel(
            b, listOf(a),
            LlmModelOption(LlmProvider.OPENAI, "model-a", metadata = LlmModelMetadata(supportsReasoning = false)),
        )
        assertEquals(ModelReasoningEffort.DEFAULT, restored.reasoningEffort)
    }
}
