package top.aaa000111.maid.runtime

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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.aaa000111.maid.memory.MaidMemoryManager
import top.aaa000111.maid.provider.LlmProvider
import top.aaa000111.maid.shizuku.ShizukuAvailability
import top.aaa000111.maid.shizuku.ShizukuStatus

class AppRuntimeTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @After
    fun tearDown() {
        AppRuntime.resetForTest()
    }

    private class DummyPromptExecutor : PromptExecutor {
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
        ): ModerationResult = throw UnsupportedOperationException()
    }

    @Test
    fun appRuntime_holdsRuntimeInfoAndFourSeparateExecutorVariables() {
        val buildInfo = AppBuildInfo(
            packageName = "top.aaa000111.maid",
            versionName = "1.0",
            versionCode = 1L,
            isDebuggable = true,
            deviceManufacturer = "Test",
            deviceModel = "TestDevice",
            androidRelease = "15",
            androidSdkInt = 35,
            appStartedAtMillis = 1_000L,
        )
        val shizukuFlow = MutableStateFlow(ShizukuStatus())
        AppRuntime.initForTest(
            buildInfo = buildInfo,
            shizukuStatus = shizukuFlow,
            memoryRootPath = tempFolder.root.toPath(),
        )

        assertEquals("top.aaa000111.maid", AppRuntime.buildInfo.packageName)
        assertEquals(ShizukuAvailability.NOT_INSTALLED, AppRuntime.shizukuStatus.value.availability)
        assertEquals(LlmProvider.OPENAI, AppRuntime.preferredProvider.value)
        assertSame(MaidMemoryManager.memoryProvider, AppRuntime.memoryProvider)

        // 四个执行器变量默认均为 null
        assertNull(AppRuntime.fastCheapExecutor)
        assertNull(AppRuntime.complexPlanningExecutor)
        assertNull(AppRuntime.fastVisionExecutor)
        assertNull(AppRuntime.complexVisionExecutor)

        // 外部可分别直接赋值
        val fastCheap = DummyPromptExecutor()
        val complexPlanning = DummyPromptExecutor()
        val fastVision = DummyPromptExecutor()
        val complexVision = DummyPromptExecutor()

        AppRuntime.fastCheapExecutor = fastCheap
        AppRuntime.complexPlanningExecutor = complexPlanning
        AppRuntime.fastVisionExecutor = fastVision
        AppRuntime.complexVisionExecutor = complexVision

        assertSame(fastCheap, AppRuntime.fastCheapExecutor)
        assertSame(complexPlanning, AppRuntime.complexPlanningExecutor)
        assertSame(fastVision, AppRuntime.fastVisionExecutor)
        assertSame(complexVision, AppRuntime.complexVisionExecutor)

        AppRuntime.setPreferredProvider(LlmProvider.GEMINI)
        assertEquals(LlmProvider.GEMINI, AppRuntime.preferredProvider.value)

        AppRuntime.release()
    }
}
