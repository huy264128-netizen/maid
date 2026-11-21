package top.aaa000111.maid.prompt

import java.util.concurrent.ConcurrentHashMap

/** APK 与 JVM 测试共用的 UTF-8 提示词；固定文本按需读取并在进程内缓存。 */
internal object PromptResources {
    private val cache = ConcurrentHashMap<String, String>()

    fun read(path: String): String = cache.computeIfAbsent(path) {
        val resourcePath = "/prompts/$path"
        val stream = checkNotNull(PromptResources::class.java.getResourceAsStream(resourcePath)) {
            "Missing bundled prompt: $resourcePath"
        }
        stream.bufferedReader(Charsets.UTF_8).use { reader ->
            reader.readText().trimEnd().also { text ->
                check(text.isNotBlank()) { "Empty bundled prompt: $resourcePath" }
            }
        }
    }
}
