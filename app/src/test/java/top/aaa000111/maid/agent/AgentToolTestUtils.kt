package top.aaa000111.maid.agent

import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 模拟模型读取启动工具返回的 ID，再显式调用对应的等待工具。 */
internal fun waitForAgentTask(prompt: Prompt, toolName: String): List<Message.Response> {
    val content = prompt.messages.filterIsInstance<Message.Tool.Result>().last().content
    val taskId = runCatching { Json.parseToJsonElement(content).jsonPrimitive.content }.getOrDefault(content)
    java.util.UUID.fromString(taskId)
    return listOf(Message.Tool.Call(
        id = "wait-$taskId",
        tool = toolName,
        content = buildJsonObject { put("taskId", taskId) }.toString(),
        metaInfo = ResponseMetaInfo.Empty,
    ))
}
