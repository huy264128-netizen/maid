package top.aaa000111.maid.agent

import top.aaa000111.maid.provider.CustomProviderProfile
import top.aaa000111.maid.provider.LlmKeyRepository
import top.aaa000111.maid.provider.LlmProvider
import top.aaa000111.maid.provider.LlmProviderEnvSpec
import top.aaa000111.maid.provider.LlmProviderRegistry
import top.aaa000111.maid.provider.ModelConfigRepository
import top.aaa000111.maid.provider.ModelRole
import top.aaa000111.maid.provider.RoleModelSelection

/**
 * 已解析并准备注入到 PRoot 终端的单个服务商摘要（不包含密钥明文）。
 */
data class ProotLlmApiProviderSummary(
    val provider: LlmProvider,
    val apiKeyEnvVar: String,
    val aliasApiKeyEnvVars: List<String> = emptyList(),
    val baseUrlEnvVar: String,
    val baseUrl: String?,
)

/**
 * 已解析并准备注入到 PRoot 终端的单个模型角色摘要（不包含密钥明文）。
 */
data class ProotLlmApiRoleSummary(
    val role: ModelRole,
    val provider: LlmProvider,
    val modelId: String,
    val providerEnvVar: String,
    val modelEnvVar: String,
    val apiKeyEnvVar: String,
    val baseUrlEnvVar: String,
    val baseUrl: String?,
)

/**
 * 单次带 LLM API 变量的 PRoot Linux 任务所对应的完整环境变量包：
 * - [envVars] 为实际要注入专用终端的环境变量键值（包含真实密钥，仅在任务期间 `export`，任务结束立即 `unset`）；
 * - [secretValues] 用于对终端返回给模型的 `stdout` / `stderr` 执行防泄露脱敏替换；
 * - [toSafePromptSection] 仅向模型公开变量名、服务商、Base URL 与模型 ID，**绝不包含任何 API Key 明文**。
 */
data class ProotLlmApiEnvBundle(
    val envVars: Map<String, String> = emptyMap(),
    val secretValues: Set<String> = emptySet(),
    val providerSummaries: List<ProotLlmApiProviderSummary> = emptyList(),
    val roleSummaries: List<ProotLlmApiRoleSummary> = emptyList(),
    val defaultProvider: LlmProvider? = null,
    val defaultModelId: String? = null,
    val defaultBaseUrl: String? = null,
    val missingRequestedFilters: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = envVars.isEmpty()

    val envVarNames: List<String>
        get() = envVars.keys.toList()

    /**
     * 生成供 `ProotLinuxAgent` 阅读的安全环境变量说明（仅变量名与非敏感元数据，绝不暴露 API Key 明文）。
     */
    fun toSafePromptSection(): String = buildString {
        appendLine("[已临时注入本任务的本机 LLM API 环境变量（任务结束后自动清理）]")
        appendLine("安全约束：")
        appendLine("- 这些环境变量已自动导出到本次任务的专用 PRoot 终端中，请在命令/脚本中直接通过 `\$VAR_NAME` 或 `os.environ[\"VAR_NAME\"]` 读取；")
        appendLine("- 严禁通过 `echo`、`env`、`printenv`、日志输出或文件写入的方式打印、回显或持久化任何 API Key 明文，也不要将密钥导出到会话附件。")

        if (defaultProvider != null) {
            appendLine()
            appendLine("通用默认入口变量（推荐脚本优先使用）：")
            appendLine("- `MAID_LLM_PROVIDER` = ${defaultProvider.id}")
            appendLine("- `MAID_LLM_API_KEY`（已注入，切勿打印明文）")
            if (!defaultBaseUrl.isNullOrBlank()) {
                appendLine("- `MAID_LLM_BASE_URL` = $defaultBaseUrl")
            }
            if (!defaultModelId.isNullOrBlank()) {
                appendLine("- `MAID_LLM_MODEL` = $defaultModelId")
            }
        }

        if (providerSummaries.isNotEmpty()) {
            appendLine()
            appendLine("按服务商注入的变量：")
            providerSummaries.forEach { summary ->
                val keyVars = (listOf(summary.apiKeyEnvVar) + summary.aliasApiKeyEnvVars)
                    .joinToString(" / ") { "`$it`" }
                val basePart = if (!summary.baseUrl.isNullOrBlank()) {
                    ", `${summary.baseUrlEnvVar}`=${summary.baseUrl}"
                } else {
                    ""
                }
                appendLine("- ${summary.provider.id}: $keyVars$basePart")
            }
        }

        if (roleSummaries.isNotEmpty()) {
            appendLine()
            appendLine("按应用模型角色注入的变量：")
            roleSummaries.forEach { role ->
                val basePart = if (!role.baseUrl.isNullOrBlank()) {
                    ", `${role.baseUrlEnvVar}`=${role.baseUrl}"
                } else {
                    ""
                }
                appendLine(
                    "- ${role.role.keyPrefix} (${role.provider.id} / ${role.modelId}): " +
                        "`${role.providerEnvVar}`, `${role.modelEnvVar}`, `${role.apiKeyEnvVar}`$basePart",
                )
            }
        }

        if (missingRequestedFilters.isNotEmpty()) {
            appendLine()
            append("未找到或尚未配置本机 API Key 的请求项：${missingRequestedFilters.joinToString(", ")}")
        }
    }.trimEnd()
}

/**
 * 从本机安全存储的 [LlmKeyRepository] 与 [ModelConfigRepository] 解析 PRoot Linux 任务可用的 LLM API 环境变量，
 * 并提供 POSIX Shell 导出/清理脚本构造与密钥脱敏工具。
 */
object ProotLlmApiEnvResolver {

    const val REDACTED_SECRET_PLACEHOLDER = "[REDACTED_API_KEY]"
    private const val MIN_REDACT_SECRET_LENGTH = 6
    private val VALID_ENV_VAR_NAME_REGEX = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    private val ROLE_DEFAULT_PRIORITY: List<ModelRole> = listOf(
        ModelRole.COMPLEX_PLANNING,
        ModelRole.FAST_CHEAP,
        ModelRole.COMPLEX_VISION,
        ModelRole.FAST_VISION,
    )

    internal fun providerSpec(provider: LlmProvider): LlmProviderEnvSpec? =
        LlmProviderRegistry.prootEnvSpec(provider)

    internal fun normalizeCustomBaseUrl(rawUrl: String?): String? {
        val trimmed = rawUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            trimmed.endsWith("/models") -> trimmed.removeSuffix("/models").trimEnd('/')
            trimmed.endsWith("/chat/completions") -> trimmed.removeSuffix("/chat/completions").trimEnd('/')
            else -> trimmed
        }.takeIf { it.isNotEmpty() }
    }

    /**
     * 解析当前本机已配置的 LLM API Key、Base URL 与模型角色环境变量包。
     *
     * @param requestedFilters 可选过滤列表：支持服务商 ID/枚举名（如 `openai`、`deepseek`）或角色名（如 `complex_planning`、`fast_cheap`）。
     *                         留空表示注入全部本机已配置的服务商与角色。
     */
    suspend fun resolve(
        requestedFilters: List<String> = emptyList(),
        apiKeyLoader: suspend (LlmProvider) -> String? = { provider ->
            runCatching { LlmKeyRepository.getApiKey(provider) }.getOrNull()
        },
        modelConfigsLoader: suspend () -> Map<ModelRole, RoleModelSelection> = {
            if (ModelConfigRepository.isReady) {
                runCatching { ModelConfigRepository.getAllConfigs() }.getOrDefault(emptyMap())
            } else {
                emptyMap()
            }
        },
        customBaseUrlLoader: suspend () -> String = {
            if (ModelConfigRepository.isReady) {
                runCatching { ModelConfigRepository.getCustomBaseUrl() }.getOrDefault("")
            } else {
                ""
            }
        },
    ): ProotLlmApiEnvBundle {
        val allConfigs = modelConfigsLoader()
        val normalizedCustomBaseUrl = normalizeCustomBaseUrl(customBaseUrlLoader())
        val normalizedFilters = requestedFilters
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        val selectedProviders = LinkedHashSet<LlmProvider>()
        val selectedRoles = LinkedHashSet<ModelRole>()
        val missingFilters = mutableListOf<String>()

        if (normalizedFilters.isEmpty()) {
            selectedProviders.addAll(LlmProvider.apiKeyProviders)
            selectedRoles.addAll(ROLE_DEFAULT_PRIORITY)
        } else {
            for (rawFilter in normalizedFilters) {
                val matchedProvider = matchProvider(rawFilter)
                if (matchedProvider != null) {
                    selectedProviders.add(matchedProvider)
                    continue
                }
                val matchedRole = matchRole(rawFilter)
                if (matchedRole != null) {
                    selectedRoles.add(matchedRole)
                    val roleProvider = allConfigs[matchedRole]?.provider
                    if (roleProvider != null && roleProvider.usesApiKey) {
                        selectedProviders.add(roleProvider)
                    } else {
                        missingFilters += rawFilter
                    }
                    continue
                }
                missingFilters += rawFilter
            }
            // 若用户只指定了服务商，也自动包含绑定到这些服务商的模型角色，便于脚本读取模型 ID
            if (selectedRoles.isEmpty() && selectedProviders.isNotEmpty()) {
                ROLE_DEFAULT_PRIORITY.forEach { role ->
                    val roleProvider = allConfigs[role]?.provider
                    if (roleProvider != null && roleProvider in selectedProviders) {
                        selectedRoles.add(role)
                    }
                }
            }
        }

        val loadedKeys = LinkedHashMap<LlmProvider, String>()
        for (provider in selectedProviders) {
            if (!provider.usesApiKey) continue
            val key = apiKeyLoader(provider)?.trim()?.takeIf { it.isNotEmpty() }
            if (key != null) {
                loadedKeys[provider] = key
            } else if (normalizedFilters.isNotEmpty()) {
                val explicitMatch = normalizedFilters.any { matchProvider(it) == provider }
                if (explicitMatch && provider.id !in missingFilters) {
                    missingFilters += provider.id
                }
            }
        }

        val envVars = LinkedHashMap<String, String>()
        val secretValues = LinkedHashSet<String>()
        val providerSummaries = mutableListOf<ProotLlmApiProviderSummary>()

        for ((provider, apiKey) in loadedKeys) {
            val spec = providerSpec(provider) ?: continue
            val baseUrl = if (provider == LlmProvider.CUSTOM) normalizedCustomBaseUrl else spec.defaultBaseUrl

            envVars[spec.apiKeyEnvVar] = apiKey
            spec.aliasApiKeyEnvVars.forEach { alias -> envVars[alias] = apiKey }
            if (!baseUrl.isNullOrBlank()) {
                envVars[spec.baseUrlEnvVar] = baseUrl
            }
            secretValues += apiKey
            providerSummaries += ProotLlmApiProviderSummary(
                provider = provider,
                apiKeyEnvVar = spec.apiKeyEnvVar,
                aliasApiKeyEnvVars = spec.aliasApiKeyEnvVars,
                baseUrlEnvVar = spec.baseUrlEnvVar,
                baseUrl = baseUrl,
            )
        }

        val roleSummaries = mutableListOf<ProotLlmApiRoleSummary>()
        for (role in selectedRoles) {
            val selection = allConfigs[role] ?: continue
            val provider = selection.provider ?: continue
            val modelId = selection.normalizedModelId ?: continue
            val customProfileId = selection.normalizedCustomProviderId
            val isNonDefaultCustom = provider == LlmProvider.CUSTOM &&
                customProfileId != null &&
                customProfileId != CustomProviderProfile.DEFAULT_ID
            val apiKey = if (isNonDefaultCustom) {
                runCatching { LlmKeyRepository.getCustomProfileApiKey(customProfileId) }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: loadedKeys[provider]
            } else {
                loadedKeys[provider]
            }
            if (apiKey == null) {
                val explicitRole = normalizedFilters.any { matchRole(it) == role }
                if (explicitRole && role.keyPrefix !in missingFilters) {
                    missingFilters += role.keyPrefix
                }
                continue
            }
            secretValues += apiKey
            val spec = providerSpec(provider) ?: continue
            val baseUrl = when {
                isNonDefaultCustom && ModelConfigRepository.isReady -> normalizeCustomBaseUrl(
                    runCatching { ModelConfigRepository.resolveCustomBaseUrl(customProfileId) }.getOrNull(),
                ) ?: normalizedCustomBaseUrl
                provider == LlmProvider.CUSTOM -> normalizedCustomBaseUrl
                else -> spec.defaultBaseUrl
            }
            val prefix = "MAID_LLM_${role.keyPrefix.uppercase()}"
            val providerVar = "${prefix}_PROVIDER"
            val modelVar = "${prefix}_MODEL"
            val apiKeyVar = "${prefix}_API_KEY"
            val baseUrlVar = "${prefix}_BASE_URL"

            envVars[providerVar] = provider.id
            envVars[modelVar] = modelId
            envVars[apiKeyVar] = apiKey
            if (!baseUrl.isNullOrBlank()) {
                envVars[baseUrlVar] = baseUrl
            }
            roleSummaries += ProotLlmApiRoleSummary(
                role = role,
                provider = provider,
                modelId = modelId,
                providerEnvVar = providerVar,
                modelEnvVar = modelVar,
                apiKeyEnvVar = apiKeyVar,
                baseUrlEnvVar = baseUrlVar,
                baseUrl = baseUrl,
            )
        }

        val primaryRole = roleSummaries.firstOrNull()
        val primaryProviderSummary = if (primaryRole != null) {
            providerSummaries.firstOrNull { it.provider == primaryRole.provider }
        } else {
            providerSummaries.firstOrNull()
        }

        val defaultProvider = primaryRole?.provider ?: primaryProviderSummary?.provider
        val defaultApiKey = defaultProvider?.let { loadedKeys[it] }
        val defaultModelId = primaryRole?.modelId
        val defaultBaseUrl = primaryRole?.baseUrl ?: primaryProviderSummary?.baseUrl

        if (defaultProvider != null && !defaultApiKey.isNullOrBlank()) {
            envVars["MAID_LLM_PROVIDER"] = defaultProvider.id
            envVars["MAID_LLM_API_KEY"] = defaultApiKey
            if (!defaultBaseUrl.isNullOrBlank()) {
                envVars["MAID_LLM_BASE_URL"] = defaultBaseUrl
            }
            if (!defaultModelId.isNullOrBlank()) {
                envVars["MAID_LLM_MODEL"] = defaultModelId
            }
        }

        return ProotLlmApiEnvBundle(
            envVars = envVars,
            secretValues = secretValues,
            providerSummaries = providerSummaries,
            roleSummaries = roleSummaries,
            defaultProvider = defaultProvider,
            defaultModelId = defaultModelId,
            defaultBaseUrl = defaultBaseUrl,
            missingRequestedFilters = missingFilters.distinct(),
        )
    }

    /**
     * 构造 POSIX Shell 的 `export KEY='VALUE' ...` 命令（严格校验变量名并做单引号安全转义）。
     */
    fun buildShellExportCommand(envVars: Map<String, String>): String {
        if (envVars.isEmpty()) return ""
        val assignments = envVars.entries.mapNotNull { (key, value) ->
            if (!VALID_ENV_VAR_NAME_REGEX.matches(key)) return@mapNotNull null
            "$key=${posixSingleQuote(value)}"
        }
        if (assignments.isEmpty()) return ""
        return "export " + assignments.joinToString(" ")
    }

    /**
     * 构造 POSIX Shell 的 `unset KEY1 KEY2 ...` 命令，用于在任务结束后立即清理敏感环境变量。
     */
    fun buildShellUnsetCommand(varNames: Collection<String>): String {
        val valid = varNames.filter { VALID_ENV_VAR_NAME_REGEX.matches(it) }.distinct()
        if (valid.isEmpty()) return ""
        return "unset " + valid.joinToString(" ")
    }

    /**
     * 将命令输出中意外回显的 API Key 明文脱敏为 [REDACTED_SECRET_PLACEHOLDER]。
     */
    fun redactSecrets(
        text: String,
        secretValues: Collection<String>,
    ): String {
        if (text.isEmpty() || secretValues.isEmpty()) return text
        var result = text
        // 长密钥优先替换，避免子串残留
        secretValues
            .asSequence()
            .map { it.trim() }
            .filter { it.length >= MIN_REDACT_SECRET_LENGTH }
            .distinct()
            .sortedByDescending { it.length }
            .forEach { secret ->
                if (result.contains(secret)) {
                    result = result.replace(secret, REDACTED_SECRET_PLACEHOLDER)
                }
            }
        return result
    }

    private fun posixSingleQuote(value: String): String {
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }

    private fun matchProvider(raw: String): LlmProvider? {
        val normalized = raw.trim().lowercase().replace("-", "_")
        return LlmProvider.apiKeyProviders.firstOrNull {
            it.id.equals(normalized, ignoreCase = true) ||
                it.name.equals(normalized, ignoreCase = true)
        }
    }

    private fun matchRole(raw: String): ModelRole? {
        val normalized = raw.trim().lowercase().replace("-", "_")
        return ModelRole.entries.firstOrNull {
            it.keyPrefix.equals(normalized, ignoreCase = true) ||
                it.name.equals(normalized, ignoreCase = true)
        }
    }
}
