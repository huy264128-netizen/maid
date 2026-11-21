package top.aaa000111.maid.security

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "secure_key_store")

/**
 * 结合 Google Tink 与 Jetpack DataStore 的安全密钥存储全局单例。
 *
 * 数据在写入 DataStore 前经过 Tink (AES256_GCM，主密钥由 Android KeyStore 保护) 加密，
 * 从 DataStore 读取后解密输出明文。
 */
object SecureKeyStorage {

    private const val PREF_FILE_NAME = "maid_tink_keyset_prefs"
    private const val KEYSET_NAME = "maid_master_keyset"
    private const val MASTER_KEY_URI = "android-keystore://_maid_master_key_"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cachedAead: Aead? = null

    fun init(context: Context) {
        if (appContext != null) return
        synchronized(this) {
            if (appContext == null) {
                appContext = context.applicationContext
            }
        }
    }

    private fun requireContext(): Context {
        return appContext ?: error("SecureKeyStorage must be initialized via init(context) first")
    }

    @Suppress("DEPRECATION")
    private fun getAead(): Aead {
        cachedAead?.let { return it }
        return synchronized(this) {
            cachedAead ?: run {
                AeadConfig.register()
                AndroidKeysetManager.Builder()
                    .withSharedPref(requireContext(), KEYSET_NAME, PREF_FILE_NAME)
                    .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                    .withMasterKeyUri(MASTER_KEY_URI)
                    .build()
                    .keysetHandle
                    .getPrimitive(Aead::class.java)
                    .also { cachedAead = it }
            }
        }
    }

    /**
     * 加密字符串
     */
    fun encrypt(plainText: String, associatedData: String = ""): String {
        if (plainText.isEmpty()) return ""
        val encryptedBytes = getAead().encrypt(
            plainText.toByteArray(Charsets.UTF_8),
            associatedData.toByteArray(Charsets.UTF_8),
        )
        return Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
    }

    /**
     * 解密字符串
     */
    fun decrypt(cipherText: String, associatedData: String = ""): String {
        if (cipherText.isEmpty()) return ""
        val encryptedBytes = Base64.decode(cipherText, Base64.NO_WRAP)
        val decryptedBytes = getAead().decrypt(
            encryptedBytes,
            associatedData.toByteArray(Charsets.UTF_8),
        )
        return String(decryptedBytes, Charsets.UTF_8)
    }

    /**
     * 安全保存 Key-Value 映射
     *
     * @param key 存储键名
     * @param value 敏感密钥明文
     */
    suspend fun putKey(key: String, value: String) {
        val prefKey = stringPreferencesKey(key)
        val encryptedValue = encrypt(value, associatedData = key)
        requireContext().dataStore.edit { preferences ->
            preferences[prefKey] = encryptedValue
        }
    }

    /**
     * 读取指定 Key 的 Flow 响应式数据
     */
    fun getKeyFlow(key: String): Flow<String?> {
        val prefKey = stringPreferencesKey(key)
        return requireContext().dataStore.data.map { preferences ->
            val encryptedValue = preferences[prefKey] ?: return@map null
            try {
                decrypt(encryptedValue, associatedData = key)
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * 单次挂起获取指定 Key 的明文值
     */
    suspend fun getKey(key: String): String? {
        return getKeyFlow(key).firstOrNull()
    }

    /**
     * 删除指定的 Key
     */
    suspend fun removeKey(key: String) {
        val prefKey = stringPreferencesKey(key)
        requireContext().dataStore.edit { preferences ->
            preferences.remove(prefKey)
        }
    }

    /**
     * 清空所有保存的 Key
     */
    suspend fun clearAll() {
        requireContext().dataStore.edit { preferences ->
            preferences.clear()
        }
    }
}
