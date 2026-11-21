package top.aaa000111.maid.search

import top.aaa000111.maid.prompt.PromptResources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import top.aaa000111.maid.runtime.AppRuntime
import top.aaa000111.maid.tinymodel.TinyModel
import top.aaa000111.maid.tinymodel.TinyModelSamplerConfig
import java.net.URI

/**
 * 过滤阶段输出结果。
 */
data class FilteredSearchResults(
    val results: List<SearchResult>,
    val stage: SearchFilterStage,
    val warning: String? = null,
)

/**
 * 搜索结果二次筛选器：
 * 接收经 Ksoup 解析和确定性规则清洗后的候选列表，输出最终采纳的结果。
 */
interface SearchResultFilter {
    suspend fun filter(
        query: SearchQuery,
        candidates: List<RawSearchResult>,
    ): FilteredSearchResults
}

/**
 * 仅保留 Ksoup 规则清洗结果，不调用端侧模型。
 */
object RuleOnlySearchResultFilter : SearchResultFilter {
    override suspend fun filter(
        query: SearchQuery,
        candidates: List<RawSearchResult>,
    ): FilteredSearchResults {
        val limited = candidates.take(query.maxResults).mapIndexed { index, raw ->
            raw.toSearchResult(rank = index + 1, acceptedByTinyModel = null)
        }
        return FilteredSearchResults(
            results = limited,
            stage = SearchFilterStage.RULE_ONLY,
        )
    }
}

/**
 * 基于端侧 [TinyModel]（LiteRT-LM）的批量搜索结果采纳过滤器：
 * - 将经 Ksoup 预筛后的候选压缩为紧凑编号列表，**仅调用 1 次** `TinyModel.generate()`，
 *   避免逐条推理触发多次引擎重建与串行等待；
 * - 低温、短输出仅返回采纳编号（如 `1, 3, 5`）；
 * - 若 TinyModel 未就绪、超时、解析失败或异常，自动回退为 Ksoup 规则预筛 Top-K，保证搜索链路高可用。
 */
class TinyModelSearchResultFilter(
    private val tinyModelProvider: () -> TinyModel? = { AppRuntime.tinyModel },
    private val maxCandidatesForModel: Int = DEFAULT_MAX_CANDIDATES,
    private val inferenceTimeoutMillis: Long = DEFAULT_INFERENCE_TIMEOUT_MS,
) : SearchResultFilter {

    override suspend fun filter(
        query: SearchQuery,
        candidates: List<RawSearchResult>,
    ): FilteredSearchResults {
        if (candidates.isEmpty()) {
            return FilteredSearchResults(
                results = emptyList(),
                stage = SearchFilterStage.RULE_ONLY,
            )
        }

        val pool = candidates.take(maxCandidatesForModel.coerceAtLeast(query.maxResults))
        val tinyModel = tinyModelProvider()
        if (tinyModel == null || !tinyModel.isModelAvailable()) {
            return fallback(
                query = query,
                candidates = pool,
                warning = "TinyModel 未就绪，已自动回退为 Ksoup 规则筛选结果。",
            )
        }

        val prompt = buildBatchSelectionPrompt(
            query = query.normalizedQuery,
            candidates = pool,
            targetCount = query.maxResults,
        )

        val generationResult = try {
            withTimeoutOrNull(inferenceTimeoutMillis) {
                tinyModel.generate(
                    prompt = prompt,
                    systemPrompt = SYSTEM_PROMPT,
                    maxOutputTokens = MAX_OUTPUT_TOKENS,
                    sampler = FILTER_SAMPLER,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return fallback(
                query = query,
                candidates = pool,
                warning = "TinyModel 筛选异常（${error.message ?: error::class.simpleName}），已回退为规则筛选结果。",
            )
        }

        if (generationResult == null) {
            return fallback(
                query = query,
                candidates = pool,
                warning = "TinyModel 筛选超时（${inferenceTimeoutMillis}ms），已回退为规则筛选结果。",
            )
        }

        val rawOutput = generationResult.text

        val selectedIndices = parseAcceptedIndices(
            modelOutput = rawOutput,
            candidateCount = pool.size,
            maxResults = query.maxResults,
        )

        if (selectedIndices.isEmpty()) {
            return fallback(
                query = query,
                candidates = pool,
                warning = "TinyModel 未给出有效采纳编号（输出：${rawOutput.trim().take(60)}），已回退为规则筛选结果。",
            )
        }

        val accepted = selectedIndices.mapIndexed { order, oneBasedIndex ->
            pool[oneBasedIndex - 1].toSearchResult(
                rank = order + 1,
                acceptedByTinyModel = true,
            )
        }
        return FilteredSearchResults(
            results = accepted,
            stage = SearchFilterStage.TINY_MODEL_ACCEPTED,
        )
    }

    private fun fallback(
        query: SearchQuery,
        candidates: List<RawSearchResult>,
        warning: String,
    ): FilteredSearchResults {
        val limited = candidates.take(query.maxResults).mapIndexed { index, raw ->
            raw.toSearchResult(rank = index + 1, acceptedByTinyModel = null)
        }
        return FilteredSearchResults(
            results = limited,
            stage = SearchFilterStage.TINY_MODEL_FALLBACK,
            warning = warning,
        )
    }

    companion object {
        const val DEFAULT_MAX_CANDIDATES = 12
        const val DEFAULT_INFERENCE_TIMEOUT_MS = 18_000L
        private const val MAX_OUTPUT_TOKENS = 48
        private const val MAX_TITLE_CHARS = 90
        private const val MAX_SNIPPET_CHARS = 130

        private val FILTER_SAMPLER = TinyModelSamplerConfig(
            topK = 20,
            topP = 0.85,
            temperature = 0.05,
        )

        internal val SYSTEM_PROMPT: String
            get() = PromptResources.read("search/relevance_filter.md")

        internal fun buildBatchSelectionPrompt(
            query: String,
            candidates: List<RawSearchResult>,
            targetCount: Int,
        ): String = buildString {
            appendLine("Query: $query")
            appendLine("Select up to $targetCount relevant result numbers from the list below:")
            candidates.forEachIndexed { index, item ->
                val host = extractHost(item.url).ifEmpty { item.displayUrl }
                val compactTitle = item.title.replace(Regex("\\s+"), " ").trim().take(MAX_TITLE_CHARS)
                val compactSnippet = item.snippet.replace(Regex("\\s+"), " ").trim().take(MAX_SNIPPET_CHARS)
                append("[${index + 1}] $compactTitle")
                if (host.isNotBlank()) {
                    append(" | $host")
                }
                if (compactSnippet.isNotBlank()) {
                    append(" | $compactSnippet")
                }
                appendLine()
            }
            append("Accepted numbers (comma-separated only):")
        }

        /**
         * 宽容解析小模型输出的编号序列（支持 `1, 3, 5`、`[1, 3, 5]`、`1 3 5` 等格式），
         * 去重并过滤越界编号，同时保留模型排序。
         */
        internal fun parseAcceptedIndices(
            modelOutput: String,
            candidateCount: Int,
            maxResults: Int,
        ): List<Int> {
            val cleaned = modelOutput.trim()
            if (cleaned.isEmpty() || candidateCount <= 0 || maxResults <= 0) {
                return emptyList()
            }
            if (cleaned.equals("NONE", ignoreCase = true) || cleaned == "[]") {
                return emptyList()
            }
            val matches = Regex("\\b\\d{1,2}\\b").findAll(cleaned)
            val result = LinkedHashSet<Int>()
            for (match in matches) {
                val number = match.value.toIntOrNull() ?: continue
                if (number in 1..candidateCount) {
                    result += number
                    if (result.size >= maxResults) break
                }
            }
            return result.toList()
        }

        private fun extractHost(url: String): String {
            return runCatching {
                URI(url).host.orEmpty().removePrefix("www.")
            }.getOrDefault("")
        }
    }
}

private fun RawSearchResult.toSearchResult(
    rank: Int,
    acceptedByTinyModel: Boolean?,
): SearchResult = SearchResult(
    title = title,
    url = url,
    snippet = snippet,
    engineId = engineId,
    displayUrl = displayUrl,
    rank = rank,
    acceptedByTinyModel = acceptedByTinyModel,
)
