package top.aaa000111.maid.prompt

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PromptResourcesTest {
    @Test fun bundledPromptsLoadFromTheMainClasspathWithoutAndroidContext() {
        val paths = listOf(
            "chat/system.md",
            "chat/session_title.md",
            "planner/system.md",
            "device/system.md",
            "display/system.md",
            "linux/system.md",
            "research/system.md",
            "vision/system.md",
            "search/relevance_filter.md",
            "skill/usage.md",
            "planner/delegate_device.md",
            "planner/delegate_linux.md",
            "planner/cloud_api_allowed.md",
            "planner/cloud_api_disabled.md",
            "planner/delegate_research.md",
            "planner/async_tasks.md",
            "planner/direct_images.md",
            "planner/vision_tool.md",
            "planner/step_budget.md",
            "device/direct_images.md",
            "device/vision_tool.md",
            "device/screenshot_fallback.md",
            "device/step_budget.md",
            "linux/direct_images.md",
            "linux/vision_tool.md",
            "linux/step_budget.md",
            "linux/isolation.md",
            "research/direct_images.md",
            "research/step_budget.md",
            "research/depth_quick.md",
            "research/depth_deep.md",
            "research/depth_standard.md",
            "chat/step_budget.md",
            "chat/memory_enabled.md",
            "chat/memory_disabled.md",
            "chat/context_sources.md",
            "linux/offline_model.md",
            "vision/default_question.md",
            "chat/direct_images.md",
            "chat/user_images_header.md",
            "chat/user_images_direct.md",
            "chat/user_images_tool.md",
            "chat/user_files_header.md",
            "chat/attachment_pool.md",
            "chat/persona_intro.md",
        )
        paths.forEach { path ->
            assertTrue("Bundled prompt must not be blank: $path", PromptResources.read(path).isNotBlank())
        }
    }

    @Test fun missingPromptFailsWithItsResourcePath() {
        try {
            PromptResources.read("missing/not_a_prompt.md")
            fail("A missing prompt must not silently become an empty system prompt")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains("/prompts/missing/not_a_prompt.md"))
        }
    }
}
