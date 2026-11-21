package top.aaa000111.maid.search

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.searchConfigDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "maid_search_configs",
)

/**
 * SearXNG 元搜索引擎运行模式（全部为可选配置，默认关闭以保持零常驻内存开销）：
 * - [DISABLED]：关闭 SearXNG，直接使用内置的 10 个轻量直连/开放接口引擎；
 * - [EXTERNAL]：连接用户指定的外部/自建 SearXNG 实例地址；
 * - [PROOT_LOCAL]：使用内置 PRoot Linux 容器按需托管的本机 SearXNG（`http://127.0.0.1:<port>`）。
 */
enum class SearxngMode(val storageKey: String) {
    DISABLED("disabled"),
    EXTERNAL("external"),
    PROOT_LOCAL("proot_local"),
    ;

    companion object {
        fun fromStorageKey(raw: String?): SearxngMode {
            val normalized = raw?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull {
                it.storageKey.equals(normalized, ignoreCase = true) ||
                    it.name.equals(normalized, ignoreCase = true)
            } ?: DISABLED
        }
    }
}

/**
 * 联网搜索持久化配置。
 */
data class SearchSettingsConfig(
    val searxngMode: SearxngMode = SearxngMode.DISABLED,
    val searxngBaseUrl: String = "",
    val localProotPort: Int = DEFAULT_LOCAL_PROOT_PORT,
    val autoStartLocalProot: Boolean = false,
) {
    val normalizedExternalBaseUrl: String?
        get() = normalizeSearxngUrl(searxngBaseUrl)

    val safeLocalProotPort: Int
        get() = localProotPort.coerceIn(MIN_PORT, MAX_PORT)

    val localProotBaseUrl: String
        get() = "http://127.0.0.1:$safeLocalProotPort"

    /** 当前模式下实际生效的 SearXNG Base URL；关闭或外部模式未填有效地址时返回 `null`。 */
    val effectiveSearxngBaseUrl: String?
        get() = when (searxngMode) {
            SearxngMode.DISABLED -> null
            SearxngMode.EXTERNAL -> normalizedExternalBaseUrl
            SearxngMode.PROOT_LOCAL -> localProotBaseUrl
        }

    /** 本机 PRoot 托管配置默认开启 JSON 格式，外部实例默认走通用 HTML（如返回 JSON 也会自动识别）。 */
    val preferJsonFormat: Boolean
        get() = searxngMode == SearxngMode.PROOT_LOCAL

    /** 向后兼容旧字段名：等同于 [effectiveSearxngBaseUrl]。 */
    val normalizedSearxngBaseUrl: String?
        get() = effectiveSearxngBaseUrl

    companion object {
        const val DEFAULT_LOCAL_PROOT_PORT = 8888
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535

        fun normalizeSearxngUrl(raw: String?): String? {
            val trimmed = raw?.trim().orEmpty().trimEnd('/')
            if (trimmed.isEmpty()) return null
            val candidate = if (trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true)
            ) {
                trimmed
            } else {
                "https://$trimmed"
            }
            return if (SearchEngine.isValidHttpUrl(candidate)) candidate else null
        }
    }
}

/**
 * 持久化并缓存联网搜索设置（SearXNG 模式、外部实例 URL、本机 PRoot 托管端口与按需自启开关）。
 */
object SearchConfigRepository {

    private val SEARXNG_MODE_KEY = stringPreferencesKey("searxng_mode")
    private val SEARXNG_BASE_URL_KEY = stringPreferencesKey("searxng_base_url")
    private val SEARXNG_LOCAL_PORT_KEY = intPreferencesKey("searxng_local_port")
    private val SEARXNG_LOCAL_AUTO_START_KEY = booleanPreferencesKey("searxng_local_auto_start")

    @Volatile
    private var appContext: Context? = null

    private val _cachedConfig = MutableStateFlow(SearchSettingsConfig())
    val cachedConfig: StateFlow<SearchSettingsConfig> = _cachedConfig.asStateFlow()

    val isReady: Boolean
        get() = appContext != null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun observeConfig(): Flow<SearchSettingsConfig> {
        val ctx = appContext ?: return flowOf(_cachedConfig.value)
        return ctx.searchConfigDataStore.data
            .map { prefs -> prefs.toSearchSettingsConfig().also { _cachedConfig.value = it } }
            .flowOn(Dispatchers.IO)
    }

    suspend fun getConfig(): SearchSettingsConfig = withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext _cachedConfig.value
        val prefs = ctx.searchConfigDataStore.data.first()
        prefs.toSearchSettingsConfig().also { _cachedConfig.value = it }
    }

    suspend fun saveConfig(config: SearchSettingsConfig): SearchSettingsConfig = withContext(Dispatchers.IO) {
        val normalizedUrl = SearchSettingsConfig.normalizeSearxngUrl(config.searxngBaseUrl).orEmpty()
        val sanitized = SearchSettingsConfig(
            searxngMode = config.searxngMode,
            searxngBaseUrl = normalizedUrl,
            localProotPort = config.safeLocalProotPort,
            autoStartLocalProot = config.autoStartLocalProot,
        )
        _cachedConfig.value = sanitized
        val ctx = appContext
        if (ctx != null) {
            ctx.searchConfigDataStore.edit { prefs ->
                prefs[SEARXNG_MODE_KEY] = sanitized.searxngMode.storageKey
                if (sanitized.searxngBaseUrl.isEmpty()) {
                    prefs.remove(SEARXNG_BASE_URL_KEY)
                } else {
                    prefs[SEARXNG_BASE_URL_KEY] = sanitized.searxngBaseUrl
                }
                prefs[SEARXNG_LOCAL_PORT_KEY] = sanitized.localProotPort
                prefs[SEARXNG_LOCAL_AUTO_START_KEY] = sanitized.autoStartLocalProot
            }
        }
        sanitized
    }

    suspend fun saveSearxngMode(mode: SearxngMode): SearchSettingsConfig {
        val current = getConfig()
        return saveConfig(current.copy(searxngMode = mode))
    }

    suspend fun saveSearxngBaseUrl(rawUrl: String): SearchSettingsConfig {
        val current = getConfig()
        val normalized = SearchSettingsConfig.normalizeSearxngUrl(rawUrl).orEmpty()
        val nextMode = when {
            normalized.isNotEmpty() -> SearxngMode.EXTERNAL
            current.searxngMode == SearxngMode.EXTERNAL -> SearxngMode.DISABLED
            else -> current.searxngMode
        }
        return saveConfig(
            current.copy(
                searxngMode = nextMode,
                searxngBaseUrl = normalized,
            ),
        )
    }

    suspend fun saveLocalProotOptions(
        port: Int,
        autoStart: Boolean,
    ): SearchSettingsConfig {
        val current = getConfig()
        return saveConfig(
            current.copy(
                localProotPort = port,
                autoStartLocalProot = autoStart,
            ),
        )
    }

    private fun Preferences.toSearchSettingsConfig(): SearchSettingsConfig {
        val savedUrl = this[SEARXNG_BASE_URL_KEY].orEmpty()
        val rawMode = this[SEARXNG_MODE_KEY]
        val resolvedMode = if (rawMode != null) {
            SearxngMode.fromStorageKey(rawMode)
        } else if (savedUrl.isNotBlank()) {
            // 兼容旧版本仅存了外部 URL 的配置
            SearxngMode.EXTERNAL
        } else {
            SearxngMode.DISABLED
        }
        return SearchSettingsConfig(
            searxngMode = resolvedMode,
            searxngBaseUrl = savedUrl,
            localProotPort = (this[SEARXNG_LOCAL_PORT_KEY] ?: SearchSettingsConfig.DEFAULT_LOCAL_PROOT_PORT)
                .coerceIn(SearchSettingsConfig.MIN_PORT, SearchSettingsConfig.MAX_PORT),
            autoStartLocalProot = this[SEARXNG_LOCAL_AUTO_START_KEY] ?: false,
        )
    }

    internal fun setCachedConfigForTest(config: SearchSettingsConfig) {
        _cachedConfig.value = config
    }

    internal fun resetForTest() {
        appContext = null
        _cachedConfig.value = SearchSettingsConfig()
    }
}
