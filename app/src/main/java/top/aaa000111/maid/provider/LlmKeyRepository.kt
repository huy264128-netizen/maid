package top.aaa000111.maid.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import top.aaa000111.maid.security.SecureKeyStorage

/**
 * 负责各 API Key 类 LLM 服务商密钥与多自定义端点档案密钥的加密读写与脱敏展示（全局单例）。
 */
object LlmKeyRepository {

    fun observeApiKey(provider: LlmProvider): Flow<String?> {
        val storageKey = provider.apiKeySpec?.storageKey?.takeIf { it.isNotBlank() }
            ?: return flowOf(null)
        return SecureKeyStorage.getKeyFlow(storageKey)
            .flowOn(Dispatchers.IO)
    }

    fun observeAllApiKeys(): Flow<Map<LlmProvider, String?>> {
        val apiKeyProviders = LlmProvider.apiKeyProviders
        val flows = apiKeyProviders.map { provider ->
            observeApiKey(provider)
        }
        return combine(flows) { values ->
            apiKeyProviders.mapIndexed { index, provider ->
                provider to values[index]?.takeIf { it.isNotBlank() }
            }.toMap()
        }.flowOn(Dispatchers.IO)
    }

    suspend fun getApiKey(provider: LlmProvider): String? = withContext(Dispatchers.IO) {
        val storageKey = provider.apiKeySpec?.storageKey?.takeIf { it.isNotBlank() }
            ?: return@withContext null
        SecureKeyStorage.getKey(storageKey)?.takeIf { it.isNotBlank() }
    }

    suspend fun saveApiKey(provider: LlmProvider, rawApiKey: String) {
        val storageKey = provider.apiKeySpec?.storageKey?.takeIf { it.isNotBlank() }
        require(provider.usesApiKey && storageKey != null) {
            "Provider ${provider.id} does not use API Key authentication"
        }
        val normalized = rawApiKey.trim()
        require(normalized.isNotEmpty()) { "API Key cannot be blank" }
        withContext(Dispatchers.IO) {
            SecureKeyStorage.putKey(storageKey, normalized)
        }
    }

    suspend fun deleteApiKey(provider: LlmProvider) = withContext(Dispatchers.IO) {
        val storageKey = provider.apiKeySpec?.storageKey?.takeIf { it.isNotBlank() }
            ?: return@withContext
        SecureKeyStorage.removeKey(storageKey)
    }

    fun observeCustomProfileApiKey(profileId: String): Flow<String?> {
        val storageKey = CustomProviderProfile.storageKeyFor(profileId)
        return SecureKeyStorage.getKeyFlow(storageKey)
            .flowOn(Dispatchers.IO)
    }

    fun observeCustomProfileApiKeys(profiles: List<CustomProviderProfile>): Flow<Map<String, String?>> {
        if (profiles.isEmpty()) return flowOf(emptyMap())
        val flows = profiles.map { profile ->
            observeCustomProfileApiKey(profile.normalizedId)
        }
        return combine(flows) { values ->
            profiles.mapIndexed { index, profile ->
                profile.normalizedId to values[index]?.takeIf { it.isNotBlank() }
            }.toMap()
        }.flowOn(Dispatchers.IO)
    }

    suspend fun getCustomProfileApiKey(profileId: String?): String? = withContext(Dispatchers.IO) {
        val storageKey = CustomProviderProfile.storageKeyFor(profileId)
        SecureKeyStorage.getKey(storageKey)?.takeIf { it.isNotBlank() }
    }

    suspend fun saveCustomProfileApiKey(profileId: String, rawApiKey: String) {
        val normalized = rawApiKey.trim()
        require(normalized.isNotEmpty()) { "API Key cannot be blank" }
        val storageKey = CustomProviderProfile.storageKeyFor(profileId)
        withContext(Dispatchers.IO) {
            SecureKeyStorage.putKey(storageKey, normalized)
        }
    }

    suspend fun deleteCustomProfileApiKey(profileId: String) = withContext(Dispatchers.IO) {
        val storageKey = CustomProviderProfile.storageKeyFor(profileId)
        SecureKeyStorage.removeKey(storageKey)
    }

    suspend fun clearAllApiKeys() = withContext(Dispatchers.IO) {
        // 仅清理 API Key 存储键（含多自定义端点），保留其他安全存储项（例如 Sign in with ChatGPT 的设备标识与会话）
        LlmProvider.apiKeyProviders.forEach { provider ->
            val storageKey = provider.apiKeySpec?.storageKey?.takeIf { it.isNotBlank() }
            if (storageKey != null) {
                SecureKeyStorage.removeKey(storageKey)
            }
        }
        if (ModelConfigRepository.isReady) {
            val profiles = runCatching { ModelConfigRepository.getCustomProfiles() }.getOrDefault(emptyList())
            profiles.forEach { profile ->
                SecureKeyStorage.removeKey(profile.storageKey)
            }
        }
    }

    fun maskKey(apiKey: String?): String {
        if (apiKey.isNullOrBlank()) return ""
        val trimmed = apiKey.trim()
        return if (trimmed.length <= 8) {
            "••••"
        } else {
            "${trimmed.take(4)}••••${trimmed.takeLast(4)}"
        }
    }
}
