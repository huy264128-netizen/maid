package top.aaa000111.maid.provider

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import top.aaa000111.maid.runtime.AppRuntime
import top.aaa000111.maid.runtime.CachedRoleExecutor

class LlmProviderRegistryAndCacheTest {

    private class DummyPromptExecutor(private val label: String) : PromptExecutor {
        override suspend fun execute(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>,
        ): List<Message.Response> = emptyList()

        override fun executeStreaming(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>,
        ): Flow<StreamFrame> = emptyFlow()

        override suspend fun moderate(
            prompt: Prompt,
            model: LLModel,
        ): ModerationResult {
            throw UnsupportedOperationException("Not used in test: $label")
        }
    }

    @After
    fun tearDown() {
        LlmProviderRegistry.resetForTest()
        AppRuntime.resetForTest()
    }

    @Test
    fun registry_dispatchesToTypedAdaptersAndSupportsCustomTestAdapter() = runBlocking {
        assertTrue(LlmProviderRegistry.adapterFor(LlmProvider.OPENAI) is ApiKeyProviderAdapter)
        assertTrue(LlmProviderRegistry.adapterFor(LlmProvider.CHATGPT) is ChatGptOAuthProviderAdapter)
        assertNull(LlmProviderRegistry.prootEnvSpec(LlmProvider.CHATGPT))
        assertNotNull(LlmProviderRegistry.prootEnvSpec(LlmProvider.OPENAI))

        val availabilityFlow = MutableStateFlow<ProviderAvailability>(ProviderAvailability.Ready)
        val fakeExecutor = DummyPromptExecutor("fake-chatgpt")
        LlmProviderRegistry.registerAdapterForTest(
            object : LlmProviderAdapter {
                override val provider: LlmProvider = LlmProvider.CHATGPT
                override fun observeAvailability(customProviderId: String?): Flow<ProviderAvailability> =
                    availabilityFlow

                override suspend fun checkAvailability(customProviderId: String?): ProviderAvailability =
                    availabilityFlow.value

                override suspend fun createExecutor(customProviderId: String?): PromptExecutor = fakeExecutor

                override suspend fun fetchModels(customProviderId: String?): Result<List<LlmModelOption>> =
                    Result.success(listOf(LlmModelOption(LlmProvider.CHATGPT, "gpt-5.4")))
            },
        )

        assertTrue(LlmProviderRegistry.checkAvailability(LlmProvider.CHATGPT).isReady)
        assertTrue(LlmProviderRegistry.observeAvailability(LlmProvider.CHATGPT).first().isReady)
        val created = LlmProviderRegistry.createExecutor(LlmProvider.CHATGPT)
        assertSame(fakeExecutor, created)
        assertEquals(
            listOf("gpt-5.4"),
            LlmProviderRegistry.fetchModels(LlmProvider.CHATGPT).getOrThrow().map { it.modelId },
        )
    }

    @Test
    fun appRuntime_resolveModelBinding_evictsExpiredPrimaryCacheAndFallsBackToSecondaryRole() = runBlocking {
        val chatGptExec = DummyPromptExecutor("chatgpt-cached")
        val openAiExec = DummyPromptExecutor("openai-fallback")
        var chatGptReady = true

        val configs = mapOf(
            ModelRole.COMPLEX_PLANNING to RoleModelSelection(
                provider = LlmProvider.CHATGPT,
                modelId = "gpt-5.4",
                modelMetadata = LlmModelMetadata(contextLength = 272_000L, supportsVision = true),
            ),
            ModelRole.FAST_CHEAP to RoleModelSelection(
                provider = LlmProvider.OPENAI,
                modelId = "gpt-4o-mini",
            ),
        )

        // 1. 首次解析命中 COMPLEX_PLANNING (ChatGPT) 并缓存
        AppRuntime.cacheRoleExecutor(
            ModelRole.COMPLEX_PLANNING,
            CachedRoleExecutor(
                provider = LlmProvider.CHATGPT,
                customProviderId = null,
                modelId = "gpt-5.4",
                executor = chatGptExec,
            ),
        )
        val initialBinding = AppRuntime.resolveModelBinding(
            preferredRoles = listOf(ModelRole.COMPLEX_PLANNING, ModelRole.FAST_CHEAP),
            configsLoader = { configs },
            availabilityChecker = { provider, _ ->
                if (provider == LlmProvider.CHATGPT && !chatGptReady) {
                    ProviderAvailability.Unavailable("ChatGPT session expired")
                } else {
                    ProviderAvailability.Ready
                }
            },
            executorCreator = { provider, _ ->
                if (provider == LlmProvider.OPENAI) openAiExec else chatGptExec
            },
        )
        assertEquals(LlmProvider.CHATGPT, initialBinding.provider)
        assertEquals("gpt-5.4", initialBinding.model.id)
        assertSame(chatGptExec, initialBinding.executor)
        assertEquals(272_000L, initialBinding.model.contextLength)

        // 2. ChatGPT 会话失效后，resolveModelBinding 自动淘汰 COMPLEX_PLANNING 缓存并回退至 FAST_CHEAP
        chatGptReady = false
        val fallbackBinding = AppRuntime.resolveModelBinding(
            preferredRoles = listOf(ModelRole.COMPLEX_PLANNING, ModelRole.FAST_CHEAP),
            configsLoader = { configs },
            availabilityChecker = { provider, _ ->
                if (provider == LlmProvider.CHATGPT && !chatGptReady) {
                    ProviderAvailability.Unavailable("ChatGPT session expired")
                } else {
                    ProviderAvailability.Ready
                }
            },
            executorCreator = { provider, _ ->
                if (provider == LlmProvider.OPENAI) openAiExec else chatGptExec
            },
        )
        assertNull(AppRuntime.getCachedRoleExecutor(ModelRole.COMPLEX_PLANNING))
        assertEquals(LlmProvider.OPENAI, fallbackBinding.provider)
        assertEquals("gpt-4o-mini", fallbackBinding.model.id)
        assertSame(openAiExec, fallbackBinding.executor)
    }

    @Test
    fun appRuntime_invalidateExecutorsForProvider_evictsTargetedCustomProfileOnly() {
        val execDefault = DummyPromptExecutor("custom-default")
        val execOllama = DummyPromptExecutor("custom-ollama")

        AppRuntime.cacheRoleExecutor(
            ModelRole.FAST_CHEAP,
            CachedRoleExecutor(LlmProvider.CUSTOM, CustomProviderProfile.DEFAULT_ID, "m1", execDefault),
        )
        AppRuntime.cacheRoleExecutor(
            ModelRole.COMPLEX_PLANNING,
            CachedRoleExecutor(LlmProvider.CUSTOM, "ollama", "qwen3", execOllama),
        )

        AppRuntime.invalidateExecutorsForProvider(LlmProvider.CUSTOM, customProviderId = "ollama")
        assertNotNull(AppRuntime.getCachedRoleExecutor(ModelRole.FAST_CHEAP))
        assertNull(AppRuntime.getCachedRoleExecutor(ModelRole.COMPLEX_PLANNING))

        AppRuntime.invalidateExecutorsForProvider(LlmProvider.CUSTOM)
        assertNull(AppRuntime.getCachedRoleExecutor(ModelRole.FAST_CHEAP))
        assertFalse(AppRuntime.fastCheapExecutor != null)
    }
}
