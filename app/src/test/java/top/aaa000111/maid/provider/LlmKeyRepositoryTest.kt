package top.aaa000111.maid.provider

import org.junit.Assert.assertEquals
import org.junit.Test

class LlmKeyRepositoryTest {

    @Test
    fun maskKey_handlesNullAndBlank() {
        assertEquals("", LlmKeyRepository.maskKey(null))
        assertEquals("", LlmKeyRepository.maskKey(""))
        assertEquals("", LlmKeyRepository.maskKey("   "))
    }

    @Test
    fun maskKey_masksShortKeysCompletely() {
        assertEquals("••••", LlmKeyRepository.maskKey("1234"))
        assertEquals("••••", LlmKeyRepository.maskKey("12345678"))
    }

    @Test
    fun maskKey_preservesPrefixAndSuffixForLongKeys() {
        assertEquals("sk-a••••wxyz", LlmKeyRepository.maskKey("sk-abcdefghijklmnopwxyz"))
        assertEquals("sk-a••••wxyz", LlmKeyRepository.maskKey("  sk-abcdefghijklmnopwxyz  "))
    }

    @Test
    fun fromId_returnsMatchingProviderOrDefault() {
        assertEquals(LlmProvider.ANTHROPIC, LlmProvider.fromId("anthropic"))
        assertEquals(LlmProvider.GEMINI, LlmProvider.fromId("gemini"))
        assertEquals(LlmProvider.OPENAI, LlmProvider.fromId("unknown"))
        assertEquals(LlmProvider.OPENAI, LlmProvider.fromId(null))
    }
}
