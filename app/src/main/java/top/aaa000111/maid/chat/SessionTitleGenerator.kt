package top.aaa000111.maid.chat

import top.aaa000111.maid.prompt.PromptResources
import top.aaa000111.maid.runtime.AppRuntime

internal suspend fun generateSessionTitle(firstMessage: String): String? {
    val tinyModel = AppRuntime.tinyModel?.takeIf { it.isModelAvailable() } ?: return null
    return tinyModel.generate(
        prompt = firstMessage.take(1_000),
        systemPrompt = PromptResources.read("chat/session_title.md"),
        maxOutputTokens = 64,
    ).text
}
