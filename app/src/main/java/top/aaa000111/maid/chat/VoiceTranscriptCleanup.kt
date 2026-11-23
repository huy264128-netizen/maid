package top.aaa000111.maid.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import top.aaa000111.maid.prompt.PromptResources
import top.aaa000111.maid.runtime.AppRuntime
import top.aaa000111.maid.tinymodel.TinyModelConfigRepository

internal suspend fun cleanVoiceTranscriptIfEnabled(transcript: String): String {
    return try {
        if (!TinyModelConfigRepository.observeVoiceCleanupEnabled().first()) return transcript
        val tinyModel = AppRuntime.tinyModel?.takeIf { it.isModelAvailable() } ?: return transcript
        withTimeoutOrNull(20_000L) {
            tinyModel.generate(
                prompt = transcript,
                systemPrompt = PromptResources.read("chat/voice_transcript_cleanup.md"),
                maxOutputTokens = 512,
            ).text.trim().takeIf { it.isNotEmpty() }
        } ?: transcript
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        transcript
    }
}
