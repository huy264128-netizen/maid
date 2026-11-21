package top.aaa000111.maid.provider

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.milliseconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

class ChatGptAuthTest {

    private class InMemoryChatGptAuthStore : ChatGptAuthStore {
        var hostId: String? = null
        var clientId: String? = null
        var session: ChatGptOAuthSession? = null

        override suspend fun getExtAgentHostId(): String? = hostId
        override suspend fun saveExtAgentHostId(hostId: String) {
            this.hostId = hostId
        }

        override suspend fun getIssuedClientId(): String? = clientId
        override suspend fun saveIssuedClientId(clientId: String) {
            this.clientId = clientId
        }

        override suspend fun getSession(): ChatGptOAuthSession? = session
        override suspend fun saveSession(session: ChatGptOAuthSession) {
            this.session = session
        }

        override suspend fun clearSession() {
            this.session = null
        }
    }

    private class FakeOAuthHttpClient(
        private val onGet: suspend (String) -> ChatGptHttpResponse = {
            ChatGptHttpResponse(404, "")
        },
        private val onPost: suspend (String, Map<String, String>) -> ChatGptHttpResponse = { _, _ ->
            ChatGptHttpResponse(404, "")
        },
    ) : ChatGptOAuthHttpClient {
        val getUrls = mutableListOf<String>()
        val postCalls = mutableListOf<Pair<String, Map<String, String>>>()

        override suspend fun get(url: String): ChatGptHttpResponse {
            getUrls += url
            return onGet(url)
        }

        override suspend fun postForm(
            url: String,
            formParams: Map<String, String>,
        ): ChatGptHttpResponse {
            postCalls += (url to LinkedHashMap(formParams))
            return onPost(url, formParams)
        }
    }

    private fun generateRsaKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        return generator.generateKeyPair()
    }

    private fun BigInteger.toUnsignedBase64Url(): String {
        val raw = toByteArray()
        val unsigned = if (raw.size > 1 && raw[0] == 0.toByte()) {
            raw.copyOfRange(1, raw.size)
        } else {
            raw
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(unsigned)
    }

    private fun buildJwksJson(vararg entries: Pair<String, KeyPair>): String {
        val keysJson = entries.joinToString(",") { (kid, pair) ->
            val pub = pair.public as RSAPublicKey
            val n = pub.modulus.toUnsignedBase64Url()
            val e = pub.publicExponent.toUnsignedBase64Url()
            """{"kty":"RSA","use":"sig","alg":"RS256","kid":"$kid","n":"$n","e":"$e"}"""
        }
        return """{"keys":[$keysJson]}"""
    }

    private fun buildSignedJwt(
        payloadJson: String,
        keyPair: KeyPair,
        kid: String = "kid-1",
    ): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString(
            """{"alg":"RS256","typ":"JWT","kid":"$kid"}""".toByteArray(Charsets.UTF_8),
        )
        val payload = encoder.encodeToString(payloadJson.toByteArray(Charsets.UTF_8))
        val signingInput = "$header.$payload"
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(keyPair.private)
        signer.update(signingInput.toByteArray(Charsets.US_ASCII))
        val signature = encoder.encodeToString(signer.sign())
        return "$signingInput.$signature"
    }

    private val defaultKeyPair: KeyPair by lazy { generateRsaKeyPair() }
    private val defaultJwksJson: String by lazy { buildJwksJson("kid-1" to defaultKeyPair) }

    @Test
    fun providerDecoupling_separatesApiKeyProvidersFromModelSelectionProviders() {
        assertFalse(LlmProvider.CHATGPT in LlmProvider.apiKeyProviders)
        assertTrue(LlmProvider.CHATGPT in LlmProvider.modelSelectionProviders)
        assertFalse(LlmProvider.CHATGPT.usesApiKey)
        assertNull(LlmProvider.CHATGPT.apiKeySpec)
        assertEquals(LlmProviderAuthType.CHATGPT_OAUTH, LlmProvider.CHATGPT.authType)

        assertTrue(LlmProvider.OPENAI in LlmProvider.apiKeyProviders)
        assertTrue(LlmProvider.OPENAI in LlmProvider.modelSelectionProviders)
        assertTrue(LlmProvider.OPENAI.usesApiKey)
        assertNotNull(LlmProvider.OPENAI.apiKeySpec)

        runBlocking {
            assertNull(LlmKeyRepository.getApiKey(LlmProvider.CHATGPT))
            try {
                LlmKeyRepository.saveApiKey(LlmProvider.CHATGPT, "sk-should-reject")
                fail("Expected IllegalArgumentException when saving API key for CHATGPT provider")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun pkceS256_matchesRfc7636Vector() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val expectedChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        assertEquals(expectedChallenge, ChatGptOAuthCrypto.computeCodeChallengeS256(verifier))
    }

    @Test
    fun signInFlow_loopbackDynamicRegistrationPersistsClientIdAndReusesOnNextLogin() = runBlocking {
        val store = InMemoryChatGptAuthStore()
        val nowMillis = 1_780_000_000_000L
        val expSeconds = (nowMillis / 1000L) + 3600L

        lateinit var expectedNonce: String
        val http = FakeOAuthHttpClient(
            onGet = { url ->
                when (url) {
                    ChatGptOAuthConfig.DEFAULT_DISCOVERY_URL -> ChatGptHttpResponse(
                        statusCode = 200,
                        body = """{"jwks_uri":"${ChatGptOAuthConfig.DEFAULT_JWKS_URI}"}""",
                    )
                    ChatGptOAuthConfig.DEFAULT_JWKS_URI -> ChatGptHttpResponse(
                        statusCode = 200,
                        body = defaultJwksJson,
                    )
                    else -> ChatGptHttpResponse(404, "")
                }
            },
            onPost = { _, form ->
                assertEquals("authorization_code", form["grant_type"])
                assertEquals("oaiapp_issued_001", form["client_id"])
                assertEquals("https://api.openai.com/v1", form["resource"])
                val idToken = buildSignedJwt(
                    payloadJson = """{"iss":"https://auth.openai.com","aud":"oaiapp_issued_001","sub":"user_42","name":"Alice","email":"alice@example.com","nonce":"$expectedNonce","exp":$expSeconds}""",
                    keyPair = defaultKeyPair,
                )
                ChatGptHttpResponse(
                    statusCode = 200,
                    body = """
                        {
                          "access_token": "at_initial_1",
                          "refresh_token": "rt_initial_1",
                          "id_token": "$idToken",
                          "expires_in": 3600,
                          "scope": "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
                        }
                    """.trimIndent(),
                )
            },
        )
        val manager = ChatGptAuthManager(
            store = store,
            httpClient = http,
            nowMillisProvider = { nowMillis },
        )

        // 首次登录：使用 dynamic_agent_client + agent_name_hint，且 redirect_uri 仅监听 127.0.0.1
        val pending = manager.prepareSignIn(forceConsent = false)
        expectedNonce = pending.nonce
        assertTrue(pending.usedDynamicRegistration)
        assertTrue(pending.redirectUri.startsWith("http://127.0.0.1:"))
        assertTrue(pending.redirectUri.endsWith("/auth/callback"))
        assertTrue(pending.authorizeUrl.contains("client_id=dynamic_agent_client"))
        assertTrue(pending.authorizeUrl.contains("agent_name_hint=Maid"))
        assertTrue(pending.authorizeUrl.contains("resource=https%3A%2F%2Fapi.openai.com%2Fv1"))
        assertTrue(pending.authorizeUrl.contains("code_challenge_method=S256"))
        assertFalse(pending.authorizeUrl.contains("prompt=consent"))
        assertNotNull(store.hostId)
        assertTrue(store.hostId!!.startsWith("urn:uuid:"))

        val completion = async(Dispatchers.IO) { manager.awaitAndCompleteSignIn(pending) }

        // 先模拟浏览器请求 /favicon.ico（应返回 404 且不中断回调监听）
        val faviconConn = (URL("http://127.0.0.1:${pending.callbackServer.boundPort}/favicon.ico").openConnection() as HttpURLConnection)
        assertEquals(404, faviconConn.responseCode)
        faviconConn.disconnect()

        // 再模拟 OAuth 授权回调
        val callbackUrl = "${pending.redirectUri}?code=auth_code_123&state=${pending.state}&client_id=oaiapp_issued_001"
        val cbConn = (URL(callbackUrl).openConnection() as HttpURLConnection)
        assertEquals(200, cbConn.responseCode)
        cbConn.disconnect()

        val state = completion.await()
        assertEquals(ChatGptAuthStatus.SIGNED_IN_READY, state.status)
        assertTrue(state.isPlanUsageReady)
        assertEquals("oaiapp_issued_001", store.clientId)
        assertEquals("alice@example.com", state.profile?.email)
        assertEquals("at_initial_1", manager.getValidAccessToken())
        assertTrue(ChatGptOAuthConfig.DEFAULT_JWKS_URI in http.getUrls)

        // 第二次发起登录：必须复用已签发的 client_id 且不再发送 agent_name_hint；开启 forceConsent 时附带 prompt=consent
        val pending2 = manager.prepareSignIn(forceConsent = true)
        try {
            assertFalse(pending2.usedDynamicRegistration)
            assertTrue(pending2.authorizeUrl.contains("client_id=oaiapp_issued_001"))
            assertFalse(pending2.authorizeUrl.contains("agent_name_hint"))
            assertTrue(pending2.authorizeUrl.contains("prompt=consent"))
        } finally {
            manager.cancelActiveSignIn(resetToCurrentSession = true)
        }
    }

    @Test
    fun signInWithoutPlanUsageScope_disablesPlanUsageAndRequiresReconsent() = runBlocking {
        val store = InMemoryChatGptAuthStore()
        val nowMillis = 1_780_000_000_000L
        val expSeconds = (nowMillis / 1000L) + 3600L

        lateinit var expectedNonce: String
        val http = FakeOAuthHttpClient(
            onGet = { url ->
                when (url) {
                    ChatGptOAuthConfig.DEFAULT_JWKS_URI -> ChatGptHttpResponse(200, defaultJwksJson)
                    else -> ChatGptHttpResponse(404, "")
                }
            },
            onPost = { _, _ ->
                val idToken = buildSignedJwt(
                    payloadJson = """{"iss":"https://auth.openai.com","aud":"oaiapp_002","sub":"user_2","email":"bob@example.com","nonce":"$expectedNonce","exp":$expSeconds}""",
                    keyPair = defaultKeyPair,
                )
                ChatGptHttpResponse(
                    statusCode = 200,
                    body = """
                        {
                          "access_token": "at_identity_only",
                          "refresh_token": "rt_identity_only",
                          "id_token": "$idToken",
                          "expires_in": 3600,
                          "scope": "openid profile email offline_access"
                        }
                    """.trimIndent(),
                )
            },
        )
        val manager = ChatGptAuthManager(store = store, httpClient = http, nowMillisProvider = { nowMillis })
        val pending = manager.prepareSignIn()
        expectedNonce = pending.nonce
        try {
            val state = manager.completeSignInWithCallback(
                pending = pending,
                callback = ChatGptCallbackPayload(
                    code = "code_xyz",
                    state = pending.state,
                    clientId = "oaiapp_002",
                    error = null,
                    errorDescription = null,
                ),
            )
            assertEquals(ChatGptAuthStatus.SIGNED_IN_WITHOUT_PLAN_SCOPE, state.status)
            assertFalse(state.isPlanUsageReady)
            assertTrue(state.needsReconsent)

            try {
                manager.getValidAccessToken()
                fail("Expected ChatGptAuthException when plan usage scope is missing")
            } catch (e: ChatGptAuthException) {
                assertTrue(e.requiresReconsent)
            }
        } finally {
            pending.close()
        }
    }

    @Test
    fun jwksVerifier_rejectsTamperedIdTokenSignatureAndHandlesKeyRotation() = runBlocking {
        val keyPair1 = generateRsaKeyPair()
        val keyPair2 = generateRsaKeyPair()
        val jwksFetchCount = AtomicInteger(0)

        val http = FakeOAuthHttpClient(
            onGet = { url ->
                assertEquals(ChatGptOAuthConfig.DEFAULT_JWKS_URI, url)
                val count = jwksFetchCount.incrementAndGet()
                val body = if (count == 1) {
                    buildJwksJson("kid-v1" to keyPair1)
                } else {
                    buildJwksJson("kid-v1" to keyPair1, "kid-v2" to keyPair2)
                }
                ChatGptHttpResponse(200, body)
            },
        )
        val verifier = ChatGptJwksVerifier(
            httpClient = http,
            jwksUriProvider = { ChatGptOAuthConfig.DEFAULT_JWKS_URI },
        )

        // 1. 合法签名通过并命中缓存
        val validToken1 = buildSignedJwt("""{"sub":"u1"}""", keyPair1, kid = "kid-v1")
        verifier.verifyRs256(validToken1)
        verifier.verifyRs256(validToken1)
        assertEquals(1, jwksFetchCount.get())

        // 2. 篡改 payload 后验签必须失败
        val parts = validToken1.split('.')
        val tamperedPayload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"sub":"attacker"}""".toByteArray(Charsets.UTF_8))
        val tamperedToken = "${parts[0]}.$tamperedPayload.${parts[2]}"
        try {
            verifier.verifyRs256(tamperedToken)
            fail("Expected ChatGptAuthException for tampered JWT")
        } catch (e: ChatGptAuthException) {
            assertTrue(e.message.orEmpty().contains("签名校验失败"))
        }

        // 3. 遇到新 kid 时自动刷新 JWKS 一次并验签成功
        val rotatedToken = buildSignedJwt("""{"sub":"u2"}""", keyPair2, kid = "kid-v2")
        verifier.verifyRs256(rotatedToken)
        assertEquals(2, jwksFetchCount.get())
    }

    @Test
    fun ktorChatGptOAuthClient_executesGetAndFormPostViaKtor() = runBlocking {
        var capturedFormBody: String? = null
        val mockClient = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    if (request.url.encodedPath.endsWith("/jwks.json")) {
                        respond(
                            content = """{"keys":[]}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    } else {
                        capturedFormBody = (request.body as? TextContent)?.text
                        respond(
                            content = """{"access_token":"ok"}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    }
                }
            }
        }
        val oauthClient = KtorChatGptOAuthClient(mockClient)

        val getResp = oauthClient.get("https://auth.openai.com/.well-known/jwks.json")
        assertEquals(200, getResp.statusCode)
        assertEquals("""{"keys":[]}""", getResp.body)

        val postResp = oauthClient.postForm(
            url = "https://auth.openai.com/oauth/token",
            formParams = mapOf("grant_type" to "refresh_token", "client_id" to "oaiapp_1"),
        )
        assertEquals(200, postResp.statusCode)
        assertTrue(capturedFormBody.orEmpty().contains("grant_type=refresh_token"))
        assertTrue(capturedFormBody.orEmpty().contains("client_id=oaiapp_1"))
    }

    @Test
    fun concurrentRefresh_isSerializedAndPersistsRotatedRefreshToken() = runBlocking {
        val store = InMemoryChatGptAuthStore().apply {
            hostId = "urn:uuid:host-1"
            clientId = "oaiapp_refresh_1"
            session = ChatGptOAuthSession(
                accessToken = "at_expired",
                refreshToken = "rt_v1",
                expiresAtMillis = 1_000L, // 已过期
                grantedScopes = setOf("openid", "offline_access", "chatgpt.tokens.use.direct"),
                profile = ChatGptUserProfile(subject = "u1", email = "u1@example.com"),
            )
        }
        val refreshCount = AtomicInteger(0)
        val nowMillis = 1_780_000_000_000L
        val http = FakeOAuthHttpClient(
            onPost = { _, form ->
                val count = refreshCount.incrementAndGet()
                delay(40.milliseconds)
                assertEquals("refresh_token", form["grant_type"])
                assertEquals("oaiapp_refresh_1", form["client_id"])
                assertEquals("rt_v1", form["refresh_token"])
                assertEquals("https://api.openai.com/v1", form["resource"])
                assertNull(form["scope"])
                ChatGptHttpResponse(
                    statusCode = 200,
                    body = """
                        {
                          "access_token": "at_refreshed_$count",
                          "refresh_token": "rt_v2_rotated",
                          "expires_in": 3600
                        }
                    """.trimIndent(),
                )
            },
        )
        val manager = ChatGptAuthManager(store = store, httpClient = http, nowMillisProvider = { nowMillis })

        val tokens = (1..5).map {
            async { manager.getValidAccessToken() }
        }.awaitAll()

        assertEquals(1, refreshCount.get())
        assertTrue(tokens.all { it == "at_refreshed_1" })
        assertEquals("rt_v2_rotated", store.session?.refreshToken)
        assertEquals("at_refreshed_1", store.session?.accessToken)
    }

    @Test
    fun refreshInvalidGrant_marksExpiredAndRetainsHostAndClientId() = runBlocking {
        val store = InMemoryChatGptAuthStore().apply {
            hostId = "urn:uuid:host-keep"
            clientId = "oaiapp_keep"
            session = ChatGptOAuthSession(
                accessToken = "at_expired",
                refreshToken = "rt_revoked",
                expiresAtMillis = 1_000L,
                grantedScopes = setOf("chatgpt.tokens.use.direct"),
            )
        }
        val http = FakeOAuthHttpClient(
            onPost = { _, _ ->
                ChatGptHttpResponse(
                    statusCode = 400,
                    body = """{"error":"invalid_grant","error_description":"Refresh token revoked"}""",
                )
            },
        )
        val manager = ChatGptAuthManager(store = store, httpClient = http)

        try {
            manager.getValidAccessToken()
            fail("Expected ChatGptAuthException")
        } catch (e: ChatGptAuthException) {
            assertTrue(e.requiresReLogin)
        }

        assertEquals(ChatGptAuthStatus.SESSION_EXPIRED, manager.state.value.status)
        assertNull(store.session)
        assertEquals("urn:uuid:host-keep", store.hostId)
        assertEquals("oaiapp_keep", store.clientId)
    }

    @Test
    fun signOut_revokesRefreshTokenClearsSessionAndRetainsHostAndClientId() = runBlocking {
        val store = InMemoryChatGptAuthStore().apply {
            hostId = "urn:uuid:host-persistent"
            clientId = "oaiapp_persistent"
            session = ChatGptOAuthSession(
                accessToken = "at_live",
                refreshToken = "rt_live",
                expiresAtMillis = System.currentTimeMillis() + 3_600_000L,
                grantedScopes = setOf("openid", "chatgpt.tokens.use.direct"),
            )
        }
        val http = FakeOAuthHttpClient(
            onGet = { url ->
                assertEquals(ChatGptOAuthConfig.DEFAULT_DISCOVERY_URL, url)
                ChatGptHttpResponse(
                    statusCode = 200,
                    body = """{"revocation_endpoint":"https://auth.openai.com/api/accounts/oauth/revoke"}""",
                )
            },
            onPost = { url, form ->
                assertEquals("https://auth.openai.com/api/accounts/oauth/revoke", url)
                assertEquals("rt_live", form["token"])
                assertEquals("refresh_token", form["token_type_hint"])
                assertEquals("oaiapp_persistent", form["client_id"])
                ChatGptHttpResponse(statusCode = 200, body = "")
            },
        )
        val manager = ChatGptAuthManager(store = store, httpClient = http)
        manager.ensureLoaded()
        assertTrue(manager.isPlanUsageReady())

        manager.signOut()

        assertEquals(ChatGptAuthStatus.SIGNED_OUT, manager.state.value.status)
        assertFalse(manager.isPlanUsageReady())
        assertNull(store.session)
        assertEquals("urn:uuid:host-persistent", store.hostId)
        assertEquals("oaiapp_persistent", store.clientId)
        assertEquals(1, http.postCalls.size)
    }
}
