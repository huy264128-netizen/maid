package top.aaa000111.maid.agent

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import top.aaa000111.maid.chat.ChatSession
import top.aaa000111.maid.chat.SessionAttachmentStorage
import top.aaa000111.maid.provider.CustomProviderProfile
import top.aaa000111.maid.provider.LlmKeyRepository
import top.aaa000111.maid.provider.LlmProvider
import top.aaa000111.maid.provider.LlmProviderRegistry
import top.aaa000111.maid.provider.ModelConfigRepository
import top.aaa000111.maid.provider.image.ImageGenProvider
import top.aaa000111.maid.provider.image.ImageGenRequest

class ImageGenAgentManager(
    private val session: ChatSession,
    private val cacheDirectory: File,
    private val isSessionActive: () -> Boolean,
) : AgentManager("image_generation", "ImageGenAgentManager") {
    @Tool
    @LLMDescription("List image generation providers with a saved API key, custom endpoint IDs, example model IDs, and the configured default image model when present. Does not contact providers.")
    suspend fun listImageGenTargets(): String {
        val defaultSelection = ModelConfigRepository.getImageGenModelSelection()
        val targets = buildList {
            for ((provider, example) in builtinModels) {
                if (!LlmKeyRepository.getApiKey(provider).isNullOrBlank()) {
                    add(buildJsonObject {
                        put("provider", provider.id)
                        put("exampleModelId", example)
                        if (defaultSelection.provider == provider && defaultSelection.isConfigured) {
                            put("defaultModelId", defaultSelection.normalizedModelId)
                        }
                    })
                }
            }
            for (profile in ModelConfigRepository.getCustomProfiles()) {
                if (profile.normalizedBaseUrl.isNotBlank() &&
                    !LlmKeyRepository.getCustomProfileApiKey(profile.normalizedId).isNullOrBlank()
                ) {
                    add(buildJsonObject {
                        put("provider", LlmProvider.CUSTOM.id)
                        put("customProviderId", profile.normalizedId)
                        put("name", profile.normalizedName)
                        if (defaultSelection.provider == LlmProvider.CUSTOM &&
                            defaultSelection.normalizedCustomProviderId == profile.normalizedId &&
                            defaultSelection.isConfigured
                        ) {
                            put("defaultModelId", defaultSelection.normalizedModelId)
                        }
                    })
                }
            }
        }
        return JsonArray(targets).toString()
    }

    @Tool
    @LLMDescription("Start generating one image into the current chat session. Returns a task ID immediately. Use waitImageGeneration to get the saved image attachment ID.")
    suspend fun generateImage(
        @LLMDescription("Optional provider ID: openai, gemini, xai, openrouter, or custom. Leave blank to use the default image model configured in Settings.")
        provider: String = "",
        @LLMDescription("Optional exact image generation model ID. Leave blank to use the configured default model.")
        modelId: String = "",
        @LLMDescription("Description of the image to generate.")
        prompt: String,
        @LLMDescription("Optional width:height aspect ratio, e.g. 1:1. OpenAI-compatible targets support 1:1, 3:2, or 2:3 here.")
        aspectRatio: String = "",
        @LLMDescription("Custom endpoint ID from listImageGenTargets. Only used for custom; blank uses its default profile.")
        customProviderId: String = "",
    ): String = submitTask(sessionId = session.id) {
        check(isSessionActive()) { "The originating chat session was deleted" }
        val configured = ModelConfigRepository.getImageGenModelSelection()
        val providerId = provider.trim().ifEmpty { configured.provider?.id.orEmpty() }
        val resolvedModelId = modelId.trim().ifEmpty {
            configured.normalizedModelId.takeIf { providerId == configured.provider?.id }.orEmpty()
        }
        val target = (builtinModels.keys + LlmProvider.CUSTOM).firstOrNull { it.id == providerId }
            ?: error("Configure a default image model in Settings or provide a supported provider")
        require(resolvedModelId.isNotEmpty()) {
            "Configure a default image model ID in Settings or provide modelId"
        }
        val profileId = customProviderId.trim().ifEmpty {
            if (target == configured.provider && target == LlmProvider.CUSTOM) {
                configured.normalizedCustomProviderId ?: CustomProviderProfile.DEFAULT_ID
            } else {
                CustomProviderProfile.DEFAULT_ID
            }
        }
        val apiKey = if (target == LlmProvider.CUSTOM) {
            LlmKeyRepository.getCustomProfileApiKey(profileId)
        } else LlmKeyRepository.getApiKey(target)
        check(!apiKey.isNullOrBlank()) { "Configure the API key for ${target.id} in Settings" }
        val endpoint = if (target == LlmProvider.CUSTOM) {
            val rawBaseUrl = ModelConfigRepository.resolveCustomBaseUrl(profileId)
                .trim().trimEnd('/').removeSuffix("/models")
            val parsed = runCatching { URI(rawBaseUrl) }.getOrNull()
            require(parsed?.host != null && parsed.scheme in listOf("https", "http") &&
                parsed.userInfo == null && parsed.rawQuery == null && parsed.rawFragment == null
            ) { "Configure a valid OpenAI-compatible base URL in Settings" }
            if (rawBaseUrl.endsWith("/v1")) rawBaseUrl else "$rawBaseUrl/v1"
        } else checkNotNull(LlmProviderRegistry.defaultProotEnvSpec(target)?.defaultBaseUrl)

        val image = try {
            ImageGenProvider(target, endpoint, apiKey, cacheDirectory).generate(
                ImageGenRequest(modelId = resolvedModelId, prompt = prompt, aspectRatio = aspectRatio),
            )
        } catch (_: IOException) {
            error("Image generation could not read the service response or write the image")
        }
        try {
            check(isSessionActive()) { "The originating chat session was deleted" }
            val attachment = session.moveFileToSession(
                uri = image.file.absolutePath,
                preferredId = "img_${UUID.randomUUID()}",
                fileName = "generated-image.${image.file.extension}",
                isImage = true,
            )
            if (!isSessionActive()) {
                SessionAttachmentStorage.resolveLocalFileOrNull(attachment.uri)?.delete()
                error("The originating chat session was deleted")
            }
            session.appendFileCard(attachmentIdOrUri = attachment.id)
            attachment.id
        } finally {
            image.file.delete()
        }
    }

    @Tool
    @LLMDescription("Wait for an image generation task. On success returns its image attachment ID in the originating session, already displayed in the chat.")
    suspend fun waitImageGeneration(
        @LLMDescription("Task ID returned by generateImage.") taskId: String,
    ): String = waitTask(taskId)

    private companion object {
        val builtinModels = mapOf(
            LlmProvider.OPENAI to "gpt-image-2",
            LlmProvider.GEMINI to "gemini-3.1-flash-image",
            LlmProvider.XAI to "grok-imagine-image-2.0",
            LlmProvider.OPENROUTER to "openai/gpt-image-2",
        )
    }
}
