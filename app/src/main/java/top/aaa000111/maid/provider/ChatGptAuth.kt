package top.aaa000111.maid.provider

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import top.aaa000111.maid.security.SecureKeyStorage
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.UUID

/**
 * OpenAI "Sign in with ChatGPT"（ChatGPT Plan Usage OAuth 2.0 + OIDC + PKCE）协议常量与配置。
 */
data class ChatGptOAuthConfig(
    val issuer: String = DEFAULT_ISSUER,
    val discoveryUrl: String = DEFAULT_DISCOVERY_URL,
    val authorizationEndpoint: String = DEFAULT_AUTHORIZATION_ENDPOINT,
    val tokenEndpoint: String = DEFAULT_TOKEN_ENDPOINT,
    val jwksUri: String = DEFAULT_JWKS_URI,
    val dynamicClientId: String = DYNAMIC_CLIENT_ID,
    val agentNameHint: String = DEFAULT_AGENT_NAME_HINT,
    val resourceIndicator: String = DEFAULT_RESOURCE_INDICATOR,
    val requiredPlanUsageScope: String = REQUIRED_PLAN_USAGE_SCOPE,
    val defaultScopes: List<String> = DEFAULT_SCOPES,
    val callbackPath: String = CALLBACK_PATH,
    val accessTokenRefreshSkewMillis: Long = ACCESS_TOKEN_REFRESH_SKEW_MILLIS,
    val signInTimeoutMillis: Long = SIGN_IN_TIMEOUT_MILLIS,
) {
    companion object {
        const val DEFAULT_ISSUER = "https://auth.openai.com"
        const val DEFAULT_DISCOVERY_URL = "https://auth.openai.com/.well-known/openid-configuration"
        const val DEFAULT_AUTHORIZATION_ENDPOINT = "https://auth.openai.com/api/accounts/authorize"
        const val DEFAULT_TOKEN_ENDPOINT = "https://auth.openai.com/api/accounts/oauth/token"
        const val DEFAULT_JWKS_URI = "https://auth.openai.com/.well-known/jwks.json"
        const val DYNAMIC_CLIENT_ID = "dynamic_agent_client"
        const val DEFAULT_AGENT_NAME_HINT = "Maid"
        const val DEFAULT_RESOURCE_INDICATOR = "https://api.openai.com/v1"
        const val REQUIRED_PLAN_USAGE_SCOPE = "chatgpt.tokens.use.direct"
        const val CALLBACK_PATH = "/auth/callback"
        const val ACCESS_TOKEN_REFRESH_SKEW_MILLIS = 60_000L
        const val SIGN_IN_TIMEOUT_MILLIS = 5 * 60_000L

        val DEFAULT_SCOPES: List<String> = listOf(
            "openid",
            "profile",
            "email",
            "offline_access",
            "resource.invoke",
            REQUIRED_PLAN_USAGE_SCOPE,
        )
    }
}

data class ChatGptUserProfile(
    val subject: String,
    val name: String? = null,
    val email: String? = null,
    val picture: String? = null,
) {
    val displayLabel: String
        get() = email?.takeIf { it.isNotBlank() }
            ?: name?.takeIf { it.isNotBlank() }
            ?: subject
}

data class ChatGptOAuthSession(
    val accessToken: String,
    val refreshToken: String? = null,
    val idToken: String? = null,
    val expiresAtMillis: Long,
    val grantedScopes: Set<String> = emptySet(),
    val profile: ChatGptUserProfile? = null,
) {
    val hasPlanUsageScope: Boolean
        get() = ChatGptOAuthConfig.REQUIRED_PLAN_USAGE_SCOPE in grantedScopes

    fun isAccessTokenExpired(
        nowMillis: Long = System.currentTimeMillis(),
        skewMillis: Long = ChatGptOAuthConfig.ACCESS_TOKEN_REFRESH_SKEW_MILLIS,
    ): Boolean = nowMillis + skewMillis >= expiresAtMillis
}

enum class ChatGptAuthStatus {
    SIGNED_OUT,
    SIGNING_IN,
    SIGNED_IN_READY,
    SIGNED_IN_WITHOUT_PLAN_SCOPE,
    SESSION_EXPIRED,
}

data class ChatGptAuthState(
    val status: ChatGptAuthStatus = ChatGptAuthStatus.SIGNED_OUT,
    val profile: ChatGptUserProfile? = null,
    val grantedScopes: Set<String> = emptySet(),
    val expiresAtMillis: Long? = null,
    val issuedClientId: String? = null,
    val isBusy: Boolean = false,
    val lastError: String? = null,
) {
    val isSignedIn: Boolean
        get() = status == ChatGptAuthStatus.SIGNED_IN_READY ||
            status == ChatGptAuthStatus.SIGNED_IN_WITHOUT_PLAN_SCOPE

    val isPlanUsageReady: Boolean
        get() = status == ChatGptAuthStatus.SIGNED_IN_READY

    val needsReconsent: Boolean
        get() = status == ChatGptAuthStatus.SIGNED_IN_WITHOUT_PLAN_SCOPE

    val needsReLogin: Boolean
        get() = status == ChatGptAuthStatus.SESSION_EXPIRED
}

class ChatGptAuthException(
    message: String,
    val errorCode: String? = null,
    val requiresReLogin: Boolean = false,
    val requiresReconsent: Boolean = false,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

data class ChatGptHttpResponse(
    val statusCode: Int,
    val body: String,
)

interface ChatGptOAuthHttpClient {
    suspend fun get(url: String): ChatGptHttpResponse
    suspend fun postForm(url: String, formParams: Map<String, String>): ChatGptHttpResponse
}

/**
 * 基于 JDK 标准库（[KeyFactory] + [Signature] `SHA256withRSA`）的 OIDC ID Token 本地 JWKS RS256 验签器。
 *
 * - 零额外第三方依赖；
 * - 按 `kid` 在内存中缓存已解析的 RSA 公钥，遇到未知 `kid` 时自动强制刷新一次 JWKS 以兼容服务端密钥轮换。
 */
internal class ChatGptJwksVerifier(
    private val httpClient: ChatGptOAuthHttpClient,
    private val jwksUriProvider: suspend () -> String,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cacheMutex = Mutex()

    @Volatile
    private var cachedKeysByKid: Map<String, PublicKey> = emptyMap()

    @Volatile
    private var cachedDefaultKey: PublicKey? = null

    suspend fun verifyRs256(idToken: String) {
        val segments = idToken.split('.')
        if (segments.size != 3 || segments.any { it.isBlank() }) {
            throw ChatGptAuthException("ID Token 格式无效，缺少 JWS 签名段")
        }
        val headerJson = runCatching {
            String(ChatGptOAuthCrypto.base64UrlDecode(segments[0]), Charsets.UTF_8)
        }.getOrElse { e ->
            throw ChatGptAuthException("无法解码 ID Token 头部", cause = e)
        }
        val headerObj = runCatching {
            json.parseToJsonElement(headerJson).jsonObject
        }.getOrElse { e ->
            throw ChatGptAuthException("ID Token 头部不是合法 JSON", cause = e)
        }

        val alg = headerObj["alg"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (!alg.equals("RS256", ignoreCase = true)) {
            throw ChatGptAuthException("不支持的 ID Token 签名算法：$alg")
        }
        val kid = headerObj["kid"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

        val publicKey = resolvePublicKey(kid, forceRefresh = false)
            ?: resolvePublicKey(kid, forceRefresh = true)
            ?: throw ChatGptAuthException("JWKS 中未找到匹配的 RSA 验签公钥${kid?.let { " (kid=$it)" }.orEmpty()}")

        val signingInput = "${segments[0]}.${segments[1]}".toByteArray(Charsets.US_ASCII)
        val signatureBytes = runCatching {
            ChatGptOAuthCrypto.base64UrlDecode(segments[2])
        }.getOrElse { e ->
            throw ChatGptAuthException("ID Token 签名段 Base64URL 解码失败", cause = e)
        }

        val verified = runCatching {
            val verifier = Signature.getInstance("SHA256withRSA")
            verifier.initVerify(publicKey)
            verifier.update(signingInput)
            verifier.verify(signatureBytes)
        }.getOrDefault(false)

        if (!verified) {
            throw ChatGptAuthException("ID Token RS256 签名校验失败")
        }
    }

    private suspend fun resolvePublicKey(kid: String?, forceRefresh: Boolean): PublicKey? {
        if (!forceRefresh) {
            val hit = if (kid != null) cachedKeysByKid[kid] else cachedDefaultKey
            if (hit != null) return hit
        }
        return cacheMutex.withLock {
            if (!forceRefresh) {
                val hit = if (kid != null) cachedKeysByKid[kid] else cachedDefaultKey
                if (hit != null) return@withLock hit
            }
            val jwksUri = jwksUriProvider()
            val response = httpClient.get(jwksUri)
            if (response.statusCode !in 200..299) {
                throw ChatGptAuthException("获取 OpenAI JWKS 验签公钥失败 (HTTP ${response.statusCode})")
            }
            val parsed = parseRsaKeysFromJwks(response.body)
            cachedKeysByKid = parsed.first
            cachedDefaultKey = parsed.second
            if (kid != null) parsed.first[kid] else parsed.second
        }
    }

    internal fun parseRsaKeysFromJwks(rawJson: String): Pair<Map<String, PublicKey>, PublicKey?> {
        val root = runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrNull()
            ?: return emptyMap<String, PublicKey>() to null
        val keysArray = root["keys"] as? JsonArray ?: return emptyMap<String, PublicKey>() to null
        val byKid = LinkedHashMap<String, PublicKey>()
        var firstKey: PublicKey? = null
        val keyFactory = KeyFactory.getInstance("RSA")

        for (element in keysArray) {
            val obj = element as? JsonObject ?: continue
            val kty = obj["kty"]?.jsonPrimitive?.contentOrNull
            if (!kty.equals("RSA", ignoreCase = true)) continue
            val use = obj["use"]?.jsonPrimitive?.contentOrNull
            if (!use.isNullOrBlank() && !use.equals("sig", ignoreCase = true)) continue
            val nRaw = obj["n"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
            val eRaw = obj["e"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: continue

            val publicKey = runCatching {
                val modulus = BigInteger(1, ChatGptOAuthCrypto.base64UrlDecode(nRaw))
                val exponent = BigInteger(1, ChatGptOAuthCrypto.base64UrlDecode(eRaw))
                keyFactory.generatePublic(RSAPublicKeySpec(modulus, exponent))
            }.getOrNull() ?: continue

            if (firstKey == null) firstKey = publicKey
            val kid = obj["kid"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            if (kid != null) {
                byKid[kid] = publicKey
            }
        }
        return byKid to firstKey
    }
}

/**
 * 持久化存储抽象：分离永久设备/客户端标识（退出登录时保留）与会话令牌（退出登录时清除）。
 */
interface ChatGptAuthStore {
    suspend fun getExtAgentHostId(): String?
    suspend fun saveExtAgentHostId(hostId: String)
    suspend fun getIssuedClientId(): String?
    suspend fun saveIssuedClientId(clientId: String)
    suspend fun getSession(): ChatGptOAuthSession?
    suspend fun saveSession(session: ChatGptOAuthSession)
    suspend fun clearSession()
}

internal object ChatGptSessionSerializer {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(session: ChatGptOAuthSession): String {
        val obj = buildJsonObject {
            put("access_token", JsonPrimitive(session.accessToken))
            session.refreshToken?.let { put("refresh_token", JsonPrimitive(it)) }
            session.idToken?.let { put("id_token", JsonPrimitive(it)) }
            put("expires_at_millis", JsonPrimitive(session.expiresAtMillis))
            put(
                "granted_scopes",
                buildJsonArray {
                    session.grantedScopes.forEach { add(JsonPrimitive(it)) }
                },
            )
            session.profile?.let { profile ->
                put(
                    "profile",
                    buildJsonObject {
                        put("sub", JsonPrimitive(profile.subject))
                        profile.name?.let { put("name", JsonPrimitive(it)) }
                        profile.email?.let { put("email", JsonPrimitive(it)) }
                        profile.picture?.let { put("picture", JsonPrimitive(it)) }
                    },
                )
            }
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    fun decode(raw: String): ChatGptOAuthSession? {
        if (raw.isBlank()) return null
        return runCatching {
            val root = json.parseToJsonElement(raw).jsonObject
            val accessToken = root["access_token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return null
            val refreshToken = root["refresh_token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val idToken = root["id_token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val expiresAtMillis = root["expires_at_millis"]?.jsonPrimitive?.longOrNull ?: 0L
            val grantedScopes = root["granted_scopes"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }
                ?.toSet()
                .orEmpty()
            val profileObj = root["profile"] as? JsonObject
            val profile = profileObj?.get("sub")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { sub ->
                ChatGptUserProfile(
                    subject = sub,
                    name = profileObj["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                    email = profileObj["email"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                    picture = profileObj["picture"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                )
            }
            ChatGptOAuthSession(
                accessToken = accessToken,
                refreshToken = refreshToken,
                idToken = idToken,
                expiresAtMillis = expiresAtMillis,
                grantedScopes = grantedScopes,
                profile = profile,
            )
        }.getOrNull()
    }
}

/**
 * 基于 [SecureKeyStorage]（Tink AES-256-GCM + Android KeyStore）的加密存储实现。
 */
class SecureChatGptAuthStore : ChatGptAuthStore {

    override suspend fun getExtAgentHostId(): String? = withContext(Dispatchers.IO) {
        SecureKeyStorage.getKey(KEY_EXT_AGENT_HOST_ID)?.takeIf { it.isNotBlank() }
    }

    override suspend fun saveExtAgentHostId(hostId: String) = withContext(Dispatchers.IO) {
        SecureKeyStorage.putKey(KEY_EXT_AGENT_HOST_ID, hostId)
    }

    override suspend fun getIssuedClientId(): String? = withContext(Dispatchers.IO) {
        SecureKeyStorage.getKey(KEY_ISSUED_CLIENT_ID)?.takeIf { it.isNotBlank() }
    }

    override suspend fun saveIssuedClientId(clientId: String) = withContext(Dispatchers.IO) {
        SecureKeyStorage.putKey(KEY_ISSUED_CLIENT_ID, clientId)
    }

    override suspend fun getSession(): ChatGptOAuthSession? = withContext(Dispatchers.IO) {
        val raw = SecureKeyStorage.getKey(KEY_SESSION_JSON) ?: return@withContext null
        ChatGptSessionSerializer.decode(raw)
    }

    override suspend fun saveSession(session: ChatGptOAuthSession) = withContext(Dispatchers.IO) {
        SecureKeyStorage.putKey(KEY_SESSION_JSON, ChatGptSessionSerializer.encode(session))
    }

    override suspend fun clearSession() = withContext(Dispatchers.IO) {
        SecureKeyStorage.removeKey(KEY_SESSION_JSON)
    }

    companion object {
        internal const val KEY_EXT_AGENT_HOST_ID = "chatgpt_oauth_ext_agent_host_id"
        internal const val KEY_ISSUED_CLIENT_ID = "chatgpt_oauth_issued_client_id"
        internal const val KEY_SESSION_JSON = "chatgpt_oauth_session_json"
    }
}

internal object ChatGptOAuthCrypto {
    private val secureRandom = SecureRandom()
    private val json = Json { ignoreUnknownKeys = true }

    fun generateCodeVerifier(byteCount: Int = 32): String {
        val bytes = ByteArray(byteCount)
        secureRandom.nextBytes(bytes)
        return base64UrlEncode(bytes)
    }

    fun computeCodeChallengeS256(codeVerifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(codeVerifier.toByteArray(Charsets.US_ASCII))
        return base64UrlEncode(digest)
    }

    fun generateRandomToken(byteCount: Int = 24): String {
        val bytes = ByteArray(byteCount)
        secureRandom.nextBytes(bytes)
        return base64UrlEncode(bytes)
    }

    fun base64UrlEncode(bytes: ByteArray): String {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun base64UrlDecode(encoded: String): ByteArray {
        val normalized = encoded.trim()
        val remainder = normalized.length % 4
        val padded = if (remainder == 0) normalized else normalized + "=".repeat(4 - remainder)
        return Base64.getUrlDecoder().decode(padded)
    }

    /**
     * 解析并校验 OIDC `id_token` 的核心声明（`iss`、`aud`、`exp`、`nonce`）并提取用户信息。
     */
    fun parseAndValidateIdToken(
        idToken: String,
        expectedIssuer: String,
        expectedClientId: String,
        expectedNonce: String? = null,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000L,
        clockSkewSeconds: Long = 120L,
    ): ChatGptUserProfile {
        val segments = idToken.split('.')
        if (segments.size < 2) {
            throw ChatGptAuthException("返回的 ID Token 格式无效")
        }
        val payloadJson = runCatching {
            String(base64UrlDecode(segments[1]), Charsets.UTF_8)
        }.getOrElse { e ->
            throw ChatGptAuthException("无法解码 ID Token 载荷", cause = e)
        }
        val claims = runCatching {
            json.parseToJsonElement(payloadJson).jsonObject
        }.getOrElse { e ->
            throw ChatGptAuthException("ID Token 载荷不是合法 JSON", cause = e)
        }

        val iss = claims["iss"]?.jsonPrimitive?.contentOrNull?.trim()?.trimEnd('/').orEmpty()
        val expectedIssNormalized = expectedIssuer.trim().trimEnd('/')
        if (iss != expectedIssNormalized) {
            throw ChatGptAuthException("ID Token 签发方 (iss) 不匹配：$iss")
        }

        val audMatches = when (val audElement = claims["aud"]) {
            is JsonPrimitive -> audElement.contentOrNull == expectedClientId
            is JsonArray -> audElement.any { (it as? JsonPrimitive)?.contentOrNull == expectedClientId }
            else -> false
        }
        if (!audMatches) {
            throw ChatGptAuthException("ID Token 受众 (aud) 与当前 client_id 不匹配")
        }

        val exp = claims["exp"]?.jsonPrimitive?.longOrNull
            ?: throw ChatGptAuthException("ID Token 缺少过期时间 (exp)")
        if (nowEpochSeconds - clockSkewSeconds >= exp) {
            throw ChatGptAuthException("ID Token 已过期")
        }

        if (!expectedNonce.isNullOrBlank()) {
            val tokenNonce = claims["nonce"]?.jsonPrimitive?.contentOrNull
            if (tokenNonce != expectedNonce) {
                throw ChatGptAuthException("ID Token nonce 校验失败")
            }
        }

        val sub = claims["sub"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw ChatGptAuthException("ID Token 缺少用户标识 (sub)")

        return ChatGptUserProfile(
            subject = sub,
            name = claims["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            email = claims["email"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            picture = claims["picture"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
        )
    }
}

data class ChatGptCallbackPayload(
    val code: String?,
    val state: String?,
    val clientId: String?,
    val error: String?,
    val errorDescription: String?,
)

/**
 * 仅监听 IPv4 回环地址 `127.0.0.1` 的一次性 OAuth 回调接收器（绝不使用 `localhost` 或 `0.0.0.0`）。
 */
class ChatGptLoopbackCallbackServer(
    private val callbackPath: String = ChatGptOAuthConfig.CALLBACK_PATH,
    port: Int = 0,
) : Closeable {

    private val serverSocket = ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"))

    val boundPort: Int
        get() = serverSocket.localPort

    val redirectUri: String
        get() = "http://127.0.0.1:$boundPort$callbackPath"

    suspend fun awaitCallback(timeoutMillis: Long = ChatGptOAuthConfig.SIGN_IN_TIMEOUT_MILLIS): ChatGptCallbackPayload {
        return withTimeout(timeoutMillis) {
            withContext(Dispatchers.IO) {
                serverSocket.soTimeout = 1_500
                try {
                    while (!serverSocket.isClosed) {
                        val socket = try {
                            serverSocket.accept()
                        } catch (_: SocketTimeoutException) {
                            continue
                        } catch (e: SocketException) {
                            if (serverSocket.isClosed) {
                                throw CancellationException("登录已取消", e)
                            }
                            throw e
                        }

                        socket.use { client ->
                            client.soTimeout = 5_000
                            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
                            val requestLine = reader.readLine().orEmpty()
                            // 消费 HTTP 请求头直至空行
                            while (true) {
                                val headerLine = reader.readLine() ?: break
                                if (headerLine.isEmpty()) break
                            }

                            val rawTarget = requestLine.split(' ').getOrNull(1).orEmpty()
                            val path = rawTarget.substringBefore('?')
                            if (path != callbackPath) {
                                writeHttpResponse(
                                    output = client.getOutputStream(),
                                    statusLine = "HTTP/1.1 404 Not Found",
                                    htmlBody = "<html><body>Not Found</body></html>",
                                )
                                continue
                            }

                            val query = rawTarget.substringAfter('?', "")
                            val params = parseQueryParams(query)
                            val payload = ChatGptCallbackPayload(
                                code = params["code"]?.takeIf { it.isNotBlank() },
                                state = params["state"]?.takeIf { it.isNotBlank() },
                                clientId = params["client_id"]?.takeIf { it.isNotBlank() },
                                error = params["error"]?.takeIf { it.isNotBlank() },
                                errorDescription = params["error_description"]?.takeIf { it.isNotBlank() },
                            )

                            val hasError = !payload.error.isNullOrBlank() || payload.code.isNullOrBlank()
                            val html = buildCallbackHtml(isError = hasError, errorText = payload.errorDescription ?: payload.error)
                            writeHttpResponse(
                                output = client.getOutputStream(),
                                statusLine = if (hasError) "HTTP/1.1 400 Bad Request" else "HTTP/1.1 200 OK",
                                htmlBody = html,
                            )
                            return@withContext payload
                        }
                    }
                    throw CancellationException("登录回调监听已关闭")
                } finally {
                    close()
                }
            }
        }
    }

    override fun close() {
        runCatching { serverSocket.close() }
    }

    private fun writeHttpResponse(
        output: java.io.OutputStream,
        statusLine: String,
        htmlBody: String,
    ) {
        val bodyBytes = htmlBody.toByteArray(Charsets.UTF_8)
        val headers = buildString {
            append(statusLine).append("\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ").append(bodyBytes.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(headers.toByteArray(Charsets.UTF_8))
        output.write(bodyBytes)
        output.flush()
    }

    internal companion object {
        internal fun parseQueryParams(rawQuery: String): Map<String, String> {
            if (rawQuery.isBlank()) return emptyMap()
            val result = LinkedHashMap<String, String>()
            rawQuery.split('&').forEach { pair ->
                if (pair.isBlank()) return@forEach
                val idx = pair.indexOf('=')
                val key = if (idx >= 0) pair.substring(0, idx) else pair
                val value = if (idx >= 0) pair.substring(idx + 1) else ""
                val decodedKey = URLDecoder.decode(key, "UTF-8")
                val decodedValue = URLDecoder.decode(value, "UTF-8")
                if (decodedKey.isNotEmpty()) {
                    result[decodedKey] = decodedValue
                }
            }
            return result
        }

        private fun buildCallbackHtml(isError: Boolean, errorText: String?): String {
            val title = if (isError) "Sign in with ChatGPT 未完成" else "Sign in with ChatGPT 登录成功"
            val subtitle = if (isError) {
                val safeError = errorText.orEmpty()
                    .replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                "授权未完成${if (safeError.isNotBlank()) "：$safeError" else ""}。请返回 Maid 应用重试。"
            } else {
                "已完成账号授权，现在可以关闭此浏览器标签页并返回 Maid 应用。"
            }
            return """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                  <meta charset="utf-8" />
                  <meta name="viewport" content="width=device-width, initial-scale=1" />
                  <title>$title</title>
                  <style>
                    body { font-family: system-ui, -apple-system, sans-serif; background: #111827; color: #f9fafb; display: flex; align-items: center; justify-content: center; min-height: 100vh; margin: 0; padding: 24px; }
                    .card { max-width: 420px; background: #1f2937; border-radius: 16px; padding: 28px; text-align: center; box-shadow: 0 12px 32px rgba(0,0,0,0.35); }
                    h1 { font-size: 20px; margin: 0 0 12px; }
                    p { font-size: 14px; line-height: 1.6; color: #d1d5db; margin: 0; }
                  </style>
                </head>
                <body>
                  <div class="card">
                    <h1>$title</h1>
                    <p>$subtitle</p>
                  </div>
                </body>
                </html>
            """.trimIndent()
        }
    }
}

data class PendingChatGptSignIn(
    val authorizeUrl: String,
    val redirectUri: String,
    val state: String,
    val nonce: String,
    val codeVerifier: String,
    val usedDynamicRegistration: Boolean,
    val requestedClientId: String,
    val callbackServer: ChatGptLoopbackCallbackServer,
) : Closeable {
    override fun close() {
        callbackServer.close()
    }
}

/**
 * OpenAI "Sign in with ChatGPT" 完整状态机与 OAuth/OIDC 管理器。
 *
 * 严格遵循：
 * - 首次登录使用 `client_id=dynamic_agent_client` + `agent_name_hint`，并持久化回调下发的正式 `client_id`；
 * - 生成并持久化 `ext_agent_host_id`（退出登录时保留）；
 * - 强制使用 PKCE `S256`、`state`、`nonce`、`resource=https://api.openai.com/v1` 及 `127.0.0.1` 本地回环回调；
 * - 校验 `chatgpt.tokens.use.direct` 授权范围；
 * - 使用 [Mutex] 串行化刷新令牌并在轮换后立即持久化新的 `refresh_token`。
 */
class ChatGptAuthManager(
    private val store: ChatGptAuthStore,
    private val httpClient: ChatGptOAuthHttpClient = KtorChatGptOAuthClient(ProviderHttpClient.sharedHttpClient),
    private val config: ChatGptOAuthConfig = ChatGptOAuthConfig(),
    private val nowMillisProvider: () -> Long = { System.currentTimeMillis() },
    private val validateIdToken: Boolean = true,
    private val verifyIdTokenSignature: Boolean = true,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val refreshMutex = Mutex()
    private val loadMutex = Mutex()
    private val jwksVerifier = ChatGptJwksVerifier(
        httpClient = httpClient,
        jwksUriProvider = { discoverJwksUri() ?: config.jwksUri },
    )

    @Volatile
    private var loadedFromStore = false

    @Volatile
    private var cachedSession: ChatGptOAuthSession? = null

    @Volatile
    private var cachedIssuedClientId: String? = null

    @Volatile
    private var activePendingSignIn: PendingChatGptSignIn? = null

    private val _state = MutableStateFlow(ChatGptAuthState())
    val state: StateFlow<ChatGptAuthState> = _state.asStateFlow()

    suspend fun ensureLoaded() {
        if (loadedFromStore) return
        loadMutex.withLock {
            if (loadedFromStore) return
            val issuedClientId = runCatching { store.getIssuedClientId() }.getOrNull()
            val session = runCatching { store.getSession() }.getOrNull()
            cachedIssuedClientId = issuedClientId
            cachedSession = session
            loadedFromStore = true
            publishStateLocked(session = session, issuedClientId = issuedClientId, lastError = null)
        }
    }

    suspend fun isPlanUsageReady(): Boolean {
        ensureLoaded()
        return _state.value.isPlanUsageReady
    }

    /**
     * 准备一次浏览器登录会话：启动 `127.0.0.1` 回环监听并生成完整的 OAuth 2.0 授权 URL。
     */
    suspend fun prepareSignIn(
        forceConsent: Boolean = false,
        callbackPort: Int = 0,
    ): PendingChatGptSignIn {
        ensureLoaded()
        cancelActiveSignIn(resetToCurrentSession = false)

        val hostId = getOrCreateExtAgentHostId()
        val existingClientId = cachedIssuedClientId
            ?: store.getIssuedClientId()?.also { cachedIssuedClientId = it }
        val isDynamic = existingClientId.isNullOrBlank()
        val requestedClientId = if (isDynamic) config.dynamicClientId else existingClientId

        val server = ChatGptLoopbackCallbackServer(
            callbackPath = config.callbackPath,
            port = callbackPort,
        )
        val codeVerifier = ChatGptOAuthCrypto.generateCodeVerifier()
        val codeChallenge = ChatGptOAuthCrypto.computeCodeChallengeS256(codeVerifier)
        val stateToken = ChatGptOAuthCrypto.generateRandomToken()
        val nonce = ChatGptOAuthCrypto.generateRandomToken()

        val authorizeUrl = buildAuthorizeUrl(
            redirectUri = server.redirectUri,
            clientId = requestedClientId,
            includeAgentNameHint = isDynamic,
            extAgentHostId = hostId,
            codeChallenge = codeChallenge,
            state = stateToken,
            nonce = nonce,
            forceConsent = forceConsent,
        )

        val pending = PendingChatGptSignIn(
            authorizeUrl = authorizeUrl,
            redirectUri = server.redirectUri,
            state = stateToken,
            nonce = nonce,
            codeVerifier = codeVerifier,
            usedDynamicRegistration = isDynamic,
            requestedClientId = requestedClientId,
            callbackServer = server,
        )
        activePendingSignIn = pending
        _state.value = _state.value.copy(
            status = ChatGptAuthStatus.SIGNING_IN,
            isBusy = true,
            lastError = null,
        )
        return pending
    }

    /**
     * 等待浏览器回调，校验 `state`、保存动态签发的 `client_id`，并换取访问令牌与刷新令牌。
     */
    suspend fun awaitAndCompleteSignIn(pending: PendingChatGptSignIn): ChatGptAuthState {
        return try {
            val callback = pending.callbackServer.awaitCallback(config.signInTimeoutMillis)
            completeSignInWithCallback(pending, callback)
        } catch (e: CancellationException) {
            cancelActiveSignIn(resetToCurrentSession = true)
            throw e
        } catch (e: Exception) {
            val message = e.message ?: "Sign in with ChatGPT 登录失败"
            publishStateLocked(
                session = cachedSession,
                issuedClientId = cachedIssuedClientId,
                lastError = message,
            )
            throw e
        } finally {
            pending.close()
            if (activePendingSignIn === pending) {
                activePendingSignIn = null
            }
        }
    }

    internal suspend fun completeSignInWithCallback(
        pending: PendingChatGptSignIn,
        callback: ChatGptCallbackPayload,
    ): ChatGptAuthState {
        if (!callback.error.isNullOrBlank()) {
            val detail = callback.errorDescription?.takeIf { it.isNotBlank() } ?: callback.error
            throw ChatGptAuthException(
                message = "ChatGPT 授权未通过：$detail",
                errorCode = callback.error,
            )
        }
        if (callback.state != pending.state) {
            throw ChatGptAuthException("OAuth state 校验失败，请重新发起登录")
        }
        val code = callback.code?.takeIf { it.isNotBlank() }
            ?: throw ChatGptAuthException("回调中缺少授权码 (code)")

        val effectiveClientId = resolveEffectiveClientId(pending, callback.clientId)
        if (effectiveClientId != cachedIssuedClientId) {
            store.saveIssuedClientId(effectiveClientId)
            cachedIssuedClientId = effectiveClientId
        }

        val tokenResponse = httpClient.postForm(
            url = config.tokenEndpoint,
            formParams = linkedMapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to pending.redirectUri,
                "client_id" to effectiveClientId,
                "code_verifier" to pending.codeVerifier,
                "resource" to config.resourceIndicator,
            ),
        )
        if (tokenResponse.statusCode !in 200..299) {
            val (codeErr, descErr) = extractOAuthError(tokenResponse.body)
            val detail = descErr ?: codeErr ?: "HTTP ${tokenResponse.statusCode}"
            throw ChatGptAuthException(
                message = "换取 ChatGPT 令牌失败：$detail",
                errorCode = codeErr,
            )
        }

        val session = parseTokenResponse(
            rawBody = tokenResponse.body,
            expectedClientId = effectiveClientId,
            expectedNonce = pending.nonce,
            previousSession = null,
        )
        store.saveSession(session)
        cachedSession = session
        loadedFromStore = true
        publishStateLocked(
            session = session,
            issuedClientId = effectiveClientId,
            lastError = null,
        )
        return _state.value
    }

    fun cancelActiveSignIn(resetToCurrentSession: Boolean = true) {
        val pending = activePendingSignIn
        activePendingSignIn = null
        pending?.close()
        if (resetToCurrentSession) {
            publishStateLocked(
                session = cachedSession,
                issuedClientId = cachedIssuedClientId,
                lastError = null,
            )
        }
    }

    /**
     * 获取当前有效的 Bearer `access_token`。若即将过期或 [forceRefresh] 为 true，则通过 [Mutex] 串行刷新并落盘轮换后的 `refresh_token`。
     */
    suspend fun getValidAccessToken(forceRefresh: Boolean = false): String {
        ensureLoaded()
        val current = cachedSession
            ?: throw ChatGptAuthException(
                message = "尚未登录 ChatGPT，请先在设置中完成「Sign in with ChatGPT」",
                requiresReLogin = true,
            )
        if (!current.hasPlanUsageScope) {
            throw ChatGptAuthException(
                message = "当前 ChatGPT 登录未授予订阅额度调用权限（${config.requiredPlanUsageScope}），请前往设置重新授权。",
                requiresReconsent = true,
            )
        }
        val now = nowMillisProvider()
        if (!forceRefresh && !current.isAccessTokenExpired(now, config.accessTokenRefreshSkewMillis)) {
            return current.accessToken
        }

        return refreshMutex.withLock {
            val latest = cachedSession
                ?: throw ChatGptAuthException(
                    message = "ChatGPT 登录会话已失效，请重新登录。",
                    requiresReLogin = true,
                )
            if (!latest.hasPlanUsageScope) {
                throw ChatGptAuthException(
                    message = "当前 ChatGPT 登录未授予订阅额度调用权限（${config.requiredPlanUsageScope}），请重新授权。",
                    requiresReconsent = true,
                )
            }
            val nowLocked = nowMillisProvider()
            if (!forceRefresh && !latest.isAccessTokenExpired(nowLocked, config.accessTokenRefreshSkewMillis)) {
                return@withLock latest.accessToken
            }
            // 若调用方要求强制刷新，但其他协程刚刚完成刷新（accessToken 已变化），直接复用新 token
            if (forceRefresh && latest.accessToken != current.accessToken &&
                !latest.isAccessTokenExpired(nowLocked, config.accessTokenRefreshSkewMillis)
            ) {
                return@withLock latest.accessToken
            }

            val refreshToken = latest.refreshToken?.takeIf { it.isNotBlank() }
                ?: run {
                    markSessionExpiredLocked("ChatGPT 刷新令牌缺失，请重新登录。")
                    throw ChatGptAuthException(
                        message = "ChatGPT 登录已过期，请重新登录。",
                        requiresReLogin = true,
                    )
                }
            val clientId = (cachedIssuedClientId ?: store.getIssuedClientId())
                ?.takeIf { it.isNotBlank() && it != config.dynamicClientId }
                ?: run {
                    markSessionExpiredLocked("缺少已签发的 ChatGPT client_id，请重新登录。")
                    throw ChatGptAuthException(
                        message = "ChatGPT 客户端标识缺失，请重新登录。",
                        requiresReLogin = true,
                    )
                }

            val response = httpClient.postForm(
                url = config.tokenEndpoint,
                formParams = linkedMapOf(
                    "grant_type" to "refresh_token",
                    "client_id" to clientId,
                    "refresh_token" to refreshToken,
                    "resource" to config.resourceIndicator,
                ),
            )
            if (response.statusCode !in 200..299) {
                val (errCode, errDesc) = extractOAuthError(response.body)
                val isPermanentAuthError = errCode.equals("invalid_grant", ignoreCase = true) ||
                    errCode.equals("invalid_client", ignoreCase = true) ||
                    errCode.equals("unauthorized_client", ignoreCase = true) ||
                    response.statusCode == 400 ||
                    response.statusCode == 401
                val detail = errDesc ?: errCode ?: "HTTP ${response.statusCode}"
                if (isPermanentAuthError) {
                    markSessionExpiredLocked("ChatGPT 登录已过期或被撤销（$detail），请重新登录。")
                }
                throw ChatGptAuthException(
                    message = if (isPermanentAuthError) {
                        "ChatGPT 登录已过期（$detail），请前往设置重新登录。"
                    } else {
                        "刷新 ChatGPT 令牌失败：$detail"
                    },
                    errorCode = errCode,
                    requiresReLogin = isPermanentAuthError,
                )
            }

            val refreshed = parseTokenResponse(
                rawBody = response.body,
                expectedClientId = clientId,
                expectedNonce = null,
                previousSession = latest,
            )
            // 刷新令牌每次都会轮换，必须立即持久化
            store.saveSession(refreshed)
            cachedSession = refreshed
            publishStateLocked(
                session = refreshed,
                issuedClientId = clientId,
                lastError = null,
            )
            if (!refreshed.hasPlanUsageScope) {
                throw ChatGptAuthException(
                    message = "ChatGPT 订阅额度权限已被撤销，请重新授权。",
                    requiresReconsent = true,
                )
            }
            refreshed.accessToken
        }
    }

    /**
     * 退出登录：尽力通过 OIDC Discovery 中的 `revocation_endpoint` 吊销刷新令牌，
     * 清理本地令牌，但严格保留 `ext_agent_host_id` 与已签发的 `issued_client_id`。
     */
    suspend fun signOut() {
        ensureLoaded()
        cancelActiveSignIn(resetToCurrentSession = false)
        refreshMutex.withLock {
            val session = cachedSession
            val clientId = cachedIssuedClientId ?: runCatching { store.getIssuedClientId() }.getOrNull()
            val refreshToken = session?.refreshToken?.takeIf { it.isNotBlank() }
            if (refreshToken != null && !clientId.isNullOrBlank() && clientId != config.dynamicClientId) {
                runCatching {
                    val revocationEndpoint = discoverRevocationEndpoint()
                    if (!revocationEndpoint.isNullOrBlank()) {
                        httpClient.postForm(
                            url = revocationEndpoint,
                            formParams = linkedMapOf(
                                "token" to refreshToken,
                                "token_type_hint" to "refresh_token",
                                "client_id" to clientId,
                            ),
                        )
                    }
                }
            }
            store.clearSession()
            cachedSession = null
            publishStateLocked(
                session = null,
                issuedClientId = clientId,
                lastError = null,
            )
        }
    }

    /**
     * 标记当前会话需要重新登录（例如 API 返回 `subscription_sharing_invalid_user`）。
     */
    suspend fun invalidateSession(reason: String) {
        refreshMutex.withLock {
            markSessionExpiredLocked(reason)
        }
    }

    internal fun buildAuthorizeUrl(
        redirectUri: String,
        clientId: String,
        includeAgentNameHint: Boolean,
        extAgentHostId: String,
        codeChallenge: String,
        state: String,
        nonce: String,
        forceConsent: Boolean,
    ): String {
        val params = LinkedHashMap<String, String>()
        params["response_type"] = "code"
        params["client_id"] = clientId
        params["redirect_uri"] = redirectUri
        params["scope"] = config.defaultScopes.joinToString(" ")
        params["resource"] = config.resourceIndicator
        params["ext_agent_host_id"] = extAgentHostId
        params["code_challenge"] = codeChallenge
        params["code_challenge_method"] = "S256"
        params["state"] = state
        params["nonce"] = nonce
        if (includeAgentNameHint) {
            params["agent_name_hint"] = config.agentNameHint
        }
        if (forceConsent) {
            params["prompt"] = "consent"
        }
        val query = params.entries.joinToString("&") { (key, value) ->
            "${encodeUrlQueryComponent(key)}=${encodeUrlQueryComponent(value)}"
        }
        return "${config.authorizationEndpoint}?$query"
    }

    private suspend fun getOrCreateExtAgentHostId(): String {
        val existing = store.getExtAgentHostId()
        if (!existing.isNullOrBlank()) return existing
        val created = "urn:uuid:${UUID.randomUUID()}"
        store.saveExtAgentHostId(created)
        return created
    }

    private fun resolveEffectiveClientId(
        pending: PendingChatGptSignIn,
        callbackClientId: String?,
    ): String {
        if (pending.usedDynamicRegistration) {
            val issued = callbackClientId?.trim().orEmpty()
            if (issued.isEmpty() || issued == config.dynamicClientId) {
                throw ChatGptAuthException("动态客户端注册未返回有效的永久 client_id")
            }
            return issued
        }
        val callbackValid = callbackClientId?.trim()?.takeIf {
            it.isNotEmpty() && it != config.dynamicClientId
        }
        return callbackValid ?: pending.requestedClientId
    }

    private suspend fun parseTokenResponse(
        rawBody: String,
        expectedClientId: String,
        expectedNonce: String?,
        previousSession: ChatGptOAuthSession?,
    ): ChatGptOAuthSession {
        val root = runCatching {
            json.parseToJsonElement(rawBody).jsonObject
        }.getOrElse { e ->
            throw ChatGptAuthException("无法解析 OAuth 令牌响应", cause = e)
        }
        val accessToken = root["access_token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw ChatGptAuthException("令牌响应缺少 access_token")
        val refreshToken = root["refresh_token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: previousSession?.refreshToken
        val idToken = root["id_token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: previousSession?.idToken
        val expiresInSeconds = root["expires_in"]?.jsonPrimitive?.longOrNull?.coerceAtLeast(60L) ?: 3600L
        val expiresAtMillis = nowMillisProvider() + expiresInSeconds * 1000L

        val rawScope = root["scope"]?.jsonPrimitive?.contentOrNull
        val grantedScopes = if (!rawScope.isNullOrBlank()) {
            rawScope.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()
        } else {
            previousSession?.grantedScopes.orEmpty()
        }

        val profile = if (!idToken.isNullOrBlank() && validateIdToken && root["id_token"] != null) {
            if (verifyIdTokenSignature) {
                jwksVerifier.verifyRs256(idToken)
            }
            ChatGptOAuthCrypto.parseAndValidateIdToken(
                idToken = idToken,
                expectedIssuer = config.issuer,
                expectedClientId = expectedClientId,
                expectedNonce = expectedNonce,
                nowEpochSeconds = nowMillisProvider() / 1000L,
            )
        } else {
            previousSession?.profile
        }

        return ChatGptOAuthSession(
            accessToken = accessToken,
            refreshToken = refreshToken,
            idToken = idToken,
            expiresAtMillis = expiresAtMillis,
            grantedScopes = grantedScopes,
            profile = profile,
        )
    }

    private suspend fun discoverJwksUri(): String? {
        val response = runCatching { httpClient.get(config.discoveryUrl) }.getOrNull() ?: return null
        if (response.statusCode !in 200..299) return null
        val root = runCatching { json.parseToJsonElement(response.body).jsonObject }.getOrNull()
            ?: return null
        return root["jwks_uri"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    private suspend fun discoverRevocationEndpoint(): String? {
        val response = httpClient.get(config.discoveryUrl)
        if (response.statusCode !in 200..299) return null
        val root = runCatching { json.parseToJsonElement(response.body).jsonObject }.getOrNull()
            ?: return null
        return root["revocation_endpoint"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    private suspend fun markSessionExpiredLocked(reason: String) {
        runCatching { store.clearSession() }
        cachedSession = null
        _state.value = ChatGptAuthState(
            status = ChatGptAuthStatus.SESSION_EXPIRED,
            profile = null,
            grantedScopes = emptySet(),
            expiresAtMillis = null,
            issuedClientId = cachedIssuedClientId,
            isBusy = false,
            lastError = reason,
        )
    }

    private fun publishStateLocked(
        session: ChatGptOAuthSession?,
        issuedClientId: String?,
        lastError: String?,
    ) {
        val status = when {
            session == null -> ChatGptAuthStatus.SIGNED_OUT
            session.hasPlanUsageScope -> ChatGptAuthStatus.SIGNED_IN_READY
            else -> ChatGptAuthStatus.SIGNED_IN_WITHOUT_PLAN_SCOPE
        }
        _state.value = ChatGptAuthState(
            status = status,
            profile = session?.profile,
            grantedScopes = session?.grantedScopes.orEmpty(),
            expiresAtMillis = session?.expiresAtMillis,
            issuedClientId = issuedClientId,
            isBusy = false,
            lastError = lastError,
        )
    }

    private fun extractOAuthError(body: String): Pair<String?, String?> {
        if (body.isBlank()) return null to null
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return null to body.trim().take(160)
        val error = root["error"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: (root["error"] as? JsonObject)?.get("code")?.jsonPrimitive?.contentOrNull
        val desc = root["error_description"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: (root["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
            ?: root["message"]?.jsonPrimitive?.contentOrNull
        return error to desc
    }

    private fun encodeUrlQueryComponent(value: String): String {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}

/**
 * 全局应用级 Sign in with ChatGPT 鉴权仓库单例。
 */
object ChatGptAuthRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var initialized = false

    @Volatile
    private var managerInstance: ChatGptAuthManager? = null

    private val fallbackState = MutableStateFlow(ChatGptAuthState())

    val isReady: Boolean
        get() = initialized && managerInstance != null

    val state: StateFlow<ChatGptAuthState>
        get() = managerInstance?.state ?: fallbackState.asStateFlow()

    fun init(context: Context) {
        if (initialized && managerInstance != null) return
        synchronized(this) {
            if (initialized && managerInstance != null) return
            SecureKeyStorage.init(context.applicationContext)
            val manager = ChatGptAuthManager(store = SecureChatGptAuthStore())
            managerInstance = manager
            initialized = true
            scope.launch {
                runCatching { manager.ensureLoaded() }
            }
        }
    }

    internal fun initForTest(manager: ChatGptAuthManager) {
        synchronized(this) {
            managerInstance?.cancelActiveSignIn(resetToCurrentSession = false)
            managerInstance = manager
            initialized = true
        }
    }

    internal fun resetForTest() {
        synchronized(this) {
            managerInstance?.cancelActiveSignIn(resetToCurrentSession = false)
            managerInstance = null
            initialized = false
            fallbackState.value = ChatGptAuthState()
        }
    }

    private fun requireManager(): ChatGptAuthManager {
        return managerInstance
            ?: error("ChatGptAuthRepository must be initialized via init(context) first")
    }

    suspend fun ensureLoaded() {
        managerInstance?.ensureLoaded()
    }

    suspend fun isPlanUsageReady(): Boolean {
        val mgr = managerInstance ?: return false
        return mgr.isPlanUsageReady()
    }

    suspend fun prepareSignIn(forceConsent: Boolean = false): PendingChatGptSignIn {
        return requireManager().prepareSignIn(forceConsent = forceConsent)
    }

    suspend fun awaitAndCompleteSignIn(pending: PendingChatGptSignIn): ChatGptAuthState {
        return requireManager().awaitAndCompleteSignIn(pending)
    }

    fun cancelActiveSignIn() {
        managerInstance?.cancelActiveSignIn(resetToCurrentSession = true)
    }

    suspend fun getValidAccessToken(forceRefresh: Boolean = false): String {
        return requireManager().getValidAccessToken(forceRefresh = forceRefresh)
    }

    suspend fun signOut() {
        managerInstance?.signOut()
    }

    suspend fun invalidateSession(reason: String) {
        managerInstance?.invalidateSession(reason)
    }
}
