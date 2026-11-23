# LlmProvider 接入指南与架构文档

`top.aaa000111.maid.provider` 负责统一管理 Maid 应用内所有大语言模型服务商（LLM Provider）的注册定义、鉴权凭据、多自定义端点档案、实时模型列表与能力元数据拉取、插件化适配器注册中心、Koog `PromptExecutor` 构建以及四角色能力分层配置。

自引入 **OpenAI「Sign in with ChatGPT」** 并完成插件化改造后，Maid 已实现：
- **「API Key 管理」与「模型选择」解耦**：前者仅管理带 `ApiKeySpec` 的静态密钥服务商（支持多自定义 OpenAI 兼容端点档案），后者面向更广泛的模型服务商超集（同时支持 API Key、OAuth 2.0 账号登录及未来本地/托管服务商）。
- **插件化 `LlmProviderAdapter` + `LlmProviderRegistry`**：上层运行态与 UI 不再散落 `when (provider.authType)` 分支。
- **统一 Ktor + OkHttp 网络栈**：模型拉取、OAuth 令牌交换、JWKS 公钥拉取与 Responses API 推理均共享统一网络层。

---

## 图片生成

聊天和 Planner 可调用独立的 `ImageGenProvider`。生图复用 API Key 管理中的凭据和自定义端点档案，默认图片服务商、端点和模型 ID 由模型选择页单独持久化；工具参数可覆盖默认选择。当前支持 OpenAI 和自定义兼容服务的 `/images/generations`、xAI 的同名接口、OpenRouter 的 `/images` 与 Gemini 原生的 `generateContent`。结果以 PNG、JPEG 或 WebP 会话附件交付。服务商返回的图片下载链接仅在后台读取，且不会附带 API Key。生图的等待与附件 ID 见 [Agent Manager 文档](agent_managers.md)。

## 1. 架构总览

```text
设置界面 (SettingsScreen / SettingsViewModel)
 ├── API Key 管理 ─────────► LlmProvider.apiKeyProviders ──► LlmKeyRepository ──► SecureKeyStorage (Tink AES-256-GCM)
 │                             └── CUSTOM 多端点档案 ────────► ModelConfigRepository (CustomProviderProfile)
 ├── Sign in with ChatGPT ──► LlmProvider.CHATGPT ──────────► ChatGptAuthRepository (Custom Tabs + OAuth 2.0 + PKCE + JWKS RS256)
 ├── 清除数据 ──────────────► 支持独立勾选/清理聊天记录、API Key、ChatGPT 登录会话、PRoot Rootfs
 └── 模型选择 (4 Role) ─────► configuredModelTargets (List<ModelProviderTarget>)
                                   │
                                   ├── 实时拉取模型与元数据 ──► LlmProviderRegistry.fetchModels ──► LlmProviderModelFetcher (Ktor)
                                   └── 持久化角色绑定与快照 ──► ModelConfigRepository (DataStore)
                                                                       │
                                                                       ▼
插件化适配与运行态调度 (LlmProviderRegistry / AppRuntime / ChatAgent / ChatBackgroundRuntime / ChatViewModel)
 ├── LlmProviderRegistry（聚合 ApiKeyProviderAdapter / ChatGptOAuthProviderAdapter）
 │    ├── observeAllAvailability() ──► AppRuntime 后台订阅：服务商失效时自动淘汰对应 CachedRoleExecutor
 │    ├── checkAvailability() / fetchModels() / createExecutor() / prootEnvSpec()
 │    └── resolveKoogModel(modelId, metadata) ──► 动态应用 contextLength / Vision / Tools 能力
 └── AppRuntime.resolveModelBinding(preferredRoles)（ChatAgent 与子 Agent 统一入口：就绪校验 + 失效淘汰 + 顺延回退）
      ├── API Key 服务商 ──► Koog OpenAI / Anthropic / Google / DeepSeek / OpenRouter Client
      │                       └── ProviderHttpClient.createReasoningAwareHttpClient() (ReasoningContentSupport)
      └── ChatGPT OAuth ───► ChatGptResponsesPromptExecutor (POST /v1/responses, store=false, stream=true, reasoning_summary)
```

### 1.1 核心文件清单

| 文件 | 职责 |
| :--- | :--- |
| [`app/src/main/java/top/aaa000111/maid/provider/LlmProvider.kt`](../app/src/main/java/top/aaa000111/maid/provider/LlmProvider.kt) | 定义 `LlmProviderAuthType`、`ApiKeySpec?`、`CustomProviderProfile`、`ModelProviderTarget` 与 `LlmProvider` 枚举，暴露 `apiKeyProviders` 与 `modelSelectionProviders` |
| [`app/src/main/java/top/aaa000111/maid/provider/LlmProviderRegistry.kt`](../app/src/main/java/top/aaa000111/maid/provider/LlmProviderRegistry.kt) | 插件化适配层：`ProviderAvailability`、`LlmProviderAdapter`、`ApiKeyProviderAdapter`、`ChatGptOAuthProviderAdapter` 与 `LlmProviderRegistry` 注册中心 |
| [`app/src/main/java/top/aaa000111/maid/provider/ProviderHttpClient.kt`](../app/src/main/java/top/aaa000111/maid/provider/ProviderHttpClient.kt) | 统一 Ktor + OkHttp 客户端工厂（`sharedHttpClient`、`sharedStreamingHttpClient`、`createReasoningAwareHttpClient`）以及 `KtorModelHttpTransport`、`KtorChatGptOAuthClient` |
| [`app/src/main/java/top/aaa000111/maid/provider/LlmKeyRepository.kt`](../app/src/main/java/top/aaa000111/maid/provider/LlmKeyRepository.kt) | 管理内置与多自定义端点档案的 API Key Tink 加密读写、脱敏与精准清理（不误清 ChatGPT OAuth 存储键） |
| [`app/src/main/java/top/aaa000111/maid/provider/ChatGptAuth.kt`](../app/src/main/java/top/aaa000111/maid/provider/ChatGptAuth.kt) | OpenAI「Sign in with ChatGPT」完整 OAuth 2.0 + OIDC + PKCE + `127.0.0.1` 回环回调 + 动态注册 + 串行刷新轮换 + `ChatGptJwksVerifier`（JDK RS256 本地验签） |
| [`app/src/main/java/top/aaa000111/maid/provider/ChatGptResponsesPromptExecutor.kt`](../app/src/main/java/top/aaa000111/maid/provider/ChatGptResponsesPromptExecutor.kt) | 遵循 ChatGPT Plan Usage 约束的 OpenAI Responses API Koog `PromptExecutor`（SSE 流式文本、工具调用、图片多模态、401 刷新重试与 `reasoning_summary_text` 回调） |
| [`app/src/main/java/top/aaa000111/maid/provider/ModelConfig.kt`](../app/src/main/java/top/aaa000111/maid/provider/ModelConfig.kt) | 定义 `ModelRole`、`LlmModelMetadata`、`RoleModelSelection`、`LlmModelOption` 与 `LlmModelCatalog`（支持按动态元数据构造 `LLModel`） |
| [`app/src/main/java/top/aaa000111/maid/provider/ModelConfigRepository.kt`](../app/src/main/java/top/aaa000111/maid/provider/ModelConfigRepository.kt) | 持久化四角色模型绑定（含 `customProviderId` 与 `LlmModelMetadata` 快照）及多自定义端点档案 `CustomProviderProfile`（透明兼容旧 `custom_provider_base_url`） |
| [`app/src/main/java/top/aaa000111/maid/provider/LlmProviderModelFetcher.kt`](../app/src/main/java/top/aaa000111/maid/provider/LlmProviderModelFetcher.kt) | 经 `KtorModelHttpTransport` 实时向服务商请求模型列表，并解析 `contextLength`、`supportsVision`、`supportsTools`、`supportsReasoning` 动态元数据 |
| [`app/src/main/java/top/aaa000111/maid/provider/ReasoningContentSupport.kt`](../app/src/main/java/top/aaa000111/maid/provider/ReasoningContentSupport.kt) | 面向 OpenAI 兼容思考模式接口的 Ktor 插件，按 `tool_call_id` 缓存并回填 `reasoning_content` |
| [`app/src/main/java/top/aaa000111/maid/agent/ProotLlmApiEnv.kt`](../app/src/main/java/top/aaa000111/maid/agent/ProotLlmApiEnv.kt) | 通过 `LlmProviderRegistry.prootEnvSpec` 将已配置的 API Key（含自定义端点档案）按需注入 PRoot Linux 环境，并自动跳过非 API Key 服务商 |

### 1.2 服务商集合解耦规则

| 维度 | API Key 管理页面 | 模型选择页面 | PRoot 环境变量注入 |
| :--- | :--- | :--- | :--- |
| 数据源 | `LlmProvider.apiKeyProviders` | `SettingsUiState.configuredModelTargets`（基于 `LlmProvider.modelSelectionProviders` 展开多自定义档案） | `LlmProviderRegistry.prootEnvSpec(provider)` 非空的服务商 |
| 就绪条件 | 对应密钥非空（`CUSTOM` 任一档案密钥非空） | 由 `LlmProviderRegistry.observeAllAvailability()` 与自定义档案密钥统一判定 | 对应密钥（或目标自定义档案密钥）非空 |
| `SettingsUiState` 属性 | `configuredApiKeyProviders` | `configuredModelTargets` / `configuredModelProviders` | 不依赖 UI 状态 |
| 包含 `CHATGPT`？ | 否（`apiKeySpec == null`） | 是（需完成登录并授予 `chatgpt.tokens.use.direct`） | 否（避免泄露短期 OAuth 令牌） |

---

## 2. 如何接入新的 LlmProvider

### 场景 A：接入新的 API Key 类服务商（最常见）

例如新增 `COHERE`、`PERPLEXITY` 或 `TOGETHER`，只需修改以下 **5 处核心代码 + 1 处文案 + 单元测试**：

1. **[`LlmProvider.kt`](../app/src/main/java/top/aaa000111/maid/provider/LlmProvider.kt)**：新增枚举项并传入 `ApiKeySpec(storageKey, keyHint)`（发布后 `id` 与 `storageKey` 不可变更）：
   ```kotlin
   TOGETHER(
       id = "together",
       displayNameRes = R.string.llm_provider_together,
       apiKeySpec = ApiKeySpec(
           storageKey = "llm_api_key_together",
           keyHint = "tgp_v1_...",
       ),
   ),
   ```
2. **[`strings.xml`](../app/src/main/res/values/strings.xml)**：补充 `<string name="llm_provider_together">Together AI</string>`。
3. **[`ModelConfig.kt`](../app/src/main/java/top/aaa000111/maid/provider/ModelConfig.kt)**：在 `LlmModelCatalog.resolveKoogModel` 与 `createExecutor` 中补充映射（OpenAI 兼容服务商直接复用 `openAiCompatibleExecutor(...)`，自动启用 `ReasoningContentSupport` 与共享 OkHttp 引擎）。
4. **[`LlmProviderModelFetcher.kt`](../app/src/main/java/top/aaa000111/maid/provider/LlmProviderModelFetcher.kt)**：在 `buildApiKeyRequestSpec` 配置拉取 URL 与鉴权 Header，并在 `parseModels` 中归入 `parseOpenAiStyleModels` 或专属解析器。
5. **[`LlmProviderRegistry.kt`](../app/src/main/java/top/aaa000111/maid/provider/LlmProviderRegistry.kt)**：在 `defaultProotEnvSpec(provider)` 中补充对应的 `LlmProviderEnvSpec`。
6. **单元测试**：在 [`ModelConfigTest.kt`](../app/src/test/java/top/aaa000111/maid/provider/ModelConfigTest.kt) 覆盖执行器构造与模型列表/元数据解析。

> 完成以上步骤后，设置页、模型选择页、`AppRuntime`、`ChatAgent`、`ChatBackgroundRuntime`、`ChatViewModel` 与 PRoot 环境注入会自动生效，无需修改任何 UI 或调度代码。

---

### 场景 B：接入新的非 API Key 服务商（如 OAuth / 设备码 / 本地服务）

依托插件化 `LlmProviderAdapter` 与 `LlmProviderRegistry`，接入全新鉴权类型已大幅简化：

1. **[`LlmProvider.kt`](../app/src/main/java/top/aaa000111/maid/provider/LlmProvider.kt)**：
   - 在 `LlmProviderAuthType` 新增鉴权类型；
   - 新增 `LlmProvider` 枚举项，设置 `apiKeySpec = null` 与对应 `authType`。
2. **实现独立的鉴权 / 连接仓库**（参考 [`ChatGptAuth.kt`](../app/src/main/java/top/aaa000111/maid/provider/ChatGptAuth.kt)）：
   - 敏感令牌经 [`SecureKeyStorage`](../app/src/main/java/top/aaa000111/maid/security/SecureKeyStorage.kt) 加密存储，网络请求复用 [`ProviderHttpClient`](../app/src/main/java/top/aaa000111/maid/provider/ProviderHttpClient.kt)；
   - 暴露响应式 `StateFlow` 与 `init` / `initForTest` / `resetForTest` 钩子。
3. **实现对应的 `PromptExecutor` 与 `LlmProviderAdapter`**：
   - 新增适配器实现 `observeAvailability`、`checkAvailability`、`fetchModels`、`createExecutor` 与 `prootEnvSpec`（默认 `null`）；
   - 在 [`LlmProviderRegistry.buildDefaultAdapters()`](../app/src/main/java/top/aaa000111/maid/provider/LlmProviderRegistry.kt) 中注册。
4. **运行态初始化与设置入口**：
   - 在 [`AppRuntime.init`](../app/src/main/java/top/aaa000111/maid/runtime/AppRuntime.kt) / `resetForTest` 中初始化/重置该仓库；
   - 在 [`SettingsScreen.kt`](../app/src/main/java/top/aaa000111/maid/settings/SettingsScreen.kt) 与 [`SettingsViewModel.kt`](../app/src/main/java/top/aaa000111/maid/settings/SettingsViewModel.kt) 中补充专属登录/配置页面（运行态调度、缓存自动淘汰、聊天页可用性校验均由 `LlmProviderRegistry` 自动接管，无需改动 `ChatAgent` / `ChatBackgroundRuntime` / `ChatViewModel`）。

---

## 3. Sign in with ChatGPT 协议与实现参考

`LlmProvider.CHATGPT` 遵循 OpenAI 官方公开协议：

- **OAuth / OIDC 端点**：
  - Discovery：`https://auth.openai.com/.well-known/openid-configuration`
  - JWKS：`https://auth.openai.com/.well-known/jwks.json`
  - Authorization：`https://auth.openai.com/api/accounts/authorize`
  - Token：`https://auth.openai.com/api/accounts/oauth/token`
- **动态注册与持久标识**：
  - 首次登录发送 `client_id=dynamic_agent_client` + `agent_name_hint=Maid`，回调返回永久 `client_id` 后加密保存，后续登录复用该 `client_id` 且不再发送 `agent_name_hint`；
  - 永久保留 `ext_agent_host_id = urn:uuid:<uuid>` 与已签发 `client_id`（退出登录或「清除数据」清理 ChatGPT 会话时仅清除令牌并尝试调用远端吊销端点）。
- **安全与体验**：
  - 强制 PKCE `S256`、`state`、`nonce`、`resource=https://api.openai.com/v1`；
  - 回调监听严格绑定 `127.0.0.1` 随机端口（`/auth/callback`）；
  - 客户端优先通过 **Chrome Custom Tabs**（`androidx.browser:browser`）拉起授权页，不可用时自动降级为 `ACTION_VIEW`；
  - 对 `id_token` 同时校验 `iss`、`aud`、`exp`、`nonce` 并通过 `ChatGptJwksVerifier` 使用 JDK `KeyFactory("RSA")` / `Signature("SHA256withRSA")` 校验 **JWKS RS256** 签名（支持 `kid` 缓存与密钥轮换自动刷新）；
  - 通过 `Mutex` 串行化并发令牌刷新并持久化轮换后的新 `refresh_token`。
- **推理与模型列表接口约束**：
  - 仅调用官方 `https://api.openai.com/v1/models` 与 `https://api.openai.com/v1/responses`；
  - 强制 `"store": false`、`"stream": true`，系统提示词使用顶层 `"instructions"`，不发送 `role: "system"`、`previous_response_id`、`temperature`、`top_p`、`max_output_tokens`；
  - 支持消费 `response.reasoning_summary_text.delta` / `done` 与 `reasoning` 完成项回调。

---

## 4. 已落地的架构演进记录

以下 6 项 P0–P2 架构改进均已完成并覆盖对应单元测试：

| 优先级 | 演进项 | 落地位置与关键能力 |
| :--- | :--- | :--- |
| **P0-1** | 插件化 `LlmProviderAdapter` + `LlmProviderRegistry` | [`LlmProviderRegistry.kt`](../app/src/main/java/top/aaa000111/maid/provider/LlmProviderRegistry.kt)，消除上层 6 处散落的 `when (provider.authType)` 分支 |
| **P0-2** | `ApiKeySpec?` 结构化凭据 + 多自定义端点档案 | [`LlmProvider.kt`](../app/src/main/java/top/aaa000111/maid/provider/LlmProvider.kt)、[`LlmKeyRepository.kt`](../app/src/main/java/top/aaa000111/maid/provider/LlmKeyRepository.kt)、[`ModelConfigRepository.kt`](../app/src/main/java/top/aaa000111/maid/provider/ModelConfigRepository.kt)、[`SettingsScreen.kt`](../app/src/main/java/top/aaa000111/maid/settings/SettingsScreen.kt)，支持添加/切换/删除多个 `CustomProviderProfile` 并透明兼容旧 `CUSTOM` 配置 |
| **P1-1** | 执行器缓存自动失效与统一多角色回退 | [`AppRuntime.kt`](../app/src/main/java/top/aaa000111/maid/runtime/AppRuntime.kt)、[`ChatAgent.kt`](../app/src/main/java/top/aaa000111/maid/agent/ChatAgent.kt)，后台监听服务商可用性自动淘汰 `CachedRoleExecutor`，`resolveModelBinding` 在主角色失效时平滑回退至候选角色 |
| **P1-2** | 动态模型能力元数据感知 | [`ModelConfig.kt`](../app/src/main/java/top/aaa000111/maid/provider/ModelConfig.kt)、[`LlmProviderModelFetcher.kt`](../app/src/main/java/top/aaa000111/maid/provider/LlmProviderModelFetcher.kt)、[`ModelConfigRepository.kt`](../app/src/main/java/top/aaa000111/maid/provider/ModelConfigRepository.kt)，从 `/models` 提取上下文长度与视觉/工具/推理能力并在选择页与 Koog `LLModel` 生效 |
| **P1-3** | 统一 Ktor + OkHttp 网络栈 & Responses API 思考摘要 | [`ProviderHttpClient.kt`](../app/src/main/java/top/aaa000111/maid/provider/ProviderHttpClient.kt)、[`ChatGptResponsesPromptExecutor.kt`](../app/src/main/java/top/aaa000111/maid/provider/ChatGptResponsesPromptExecutor.kt)，淘汰 `HttpURLConnection` 并支持 `reasoning_summary_text` 事件 |
| **P2** | ChatGPT 登录体验与安全打磨 | Chrome Custom Tabs（[`SettingsScreen.kt`](../app/src/main/java/top/aaa000111/maid/settings/SettingsScreen.kt)）、JWKS RS256 本地验签（[`ChatGptAuth.kt`](../app/src/main/java/top/aaa000111/maid/provider/ChatGptAuth.kt)）与「清除数据」独立 ChatGPT 会话卡片 |

---

## 5. 按角色控制思考强度

- `RoleModelSelection.reasoningEffort` 随角色配置存入 DataStore；缺失或未知值按 `DEFAULT` 读取。`RoleModelHistory` 按角色、具体端点和模型保存历史；切回服务商恢复最近使用的模型，切回模型恢复其强度，首次选择使用默认。
- 设置页使用四角色表单保存具名配置组。旧版角色配置作为默认组读取。聊天输入区只有一个配置组选单，`activateModelGroup` 在同一次 DataStore 事务中保存当前组并切换四个角色及活动组 ID。配置组保存服务商引用、模型、强度与能力快照，凭据仍由原服务商和端点管理。
- `AppRuntime.resolveModelBinding` 使用实际命中角色的配置包装原始缓存执行器，`ReasoningPromptExecutor` 在同步、流式、多候选调用前更新请求参数。缓存保留原始执行器，避免角色之间污染。
- OpenAI 兼容端点和 DeepSeek 使用 `reasoning_effort`，ChatGPT Responses 和 OpenRouter 使用 `reasoning.effort`，Anthropic 使用 `output_config.effort`，Gemini 使用 `generationConfig.thinkingConfig.thinkingLevel`。ChatGPT 执行器从额外属性中提取白名单内的强度。
- 未设置强度时省略保存字段，运行时直接使用原始执行器。旧配置中的 `default` 仍按未设置读取。显式选择等级时会省略 temperature。
- Koog 的部分客户端把额外属性包装在 `additional_properties` 或 `additionalProperties` 中。`ReasoningContentPlugin` 在发送前调用 `normalizeReasoningRequest`，将有效的档位字段移到各 API 要求的位置，同时保留 `reasoning_content` 回填。
- 选单在模型选定后提供对应协议的手动档位，实际可用值由服务商校验。
- 协议依据：[OpenAI 推理指南](https://developers.openai.com/api/docs/guides/reasoning)、[OpenRouter 推理参数](https://openrouter.ai/docs/guides/best-practices/reasoning-tokens)。

## 6. 接入自检清单

每次新增或修改 `LlmProvider` 后，建议按以下清单逐项验证：

1. **枚举与持久化**：`id` 唯一且小写下划线风格；不破坏既有 `ModelConfigRepository` 与 `ChatSessionRepository` 历史数据反序列化。
2. **集合与适配器归属**：确认 `apiKeySpec` 是否正确配置、`LlmProviderRegistry` 已注册适配器，且 `LlmKeyRepository.clearAllApiKeys()` 不误删非 API Key 存储项。
3. **模型与元数据拉取**：[`ModelConfigTest.kt`](../app/src/test/java/top/aaa000111/maid/provider/ModelConfigTest.kt) 覆盖请求 URL、Header、响应与动态元数据解析及异常状态码处理。
4. **缓存失效与回退**：[`LlmProviderRegistryAndCacheTest.kt`](../app/src/test/java/top/aaa000111/maid/provider/LlmProviderRegistryAndCacheTest.kt) 与 [`ChatGptAuthTest.kt`](../app/src/test/java/top/aaa000111/maid/provider/ChatGptAuthTest.kt) 覆盖可用性切换、缓存淘汰与验签逻辑。
5. **构建与测试**：执行 `./gradlew :app:testDebugUnitTest :app:assembleDebug` 确保全量单测与构建通过。
