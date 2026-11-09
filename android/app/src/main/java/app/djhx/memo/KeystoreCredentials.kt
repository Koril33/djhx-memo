package app.djhx.memo

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import uniffi.djhx_memo_core.CredentialStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** token 只以 Keystore AES-GCM 密文落盘；不可用时回调 false，由 core 保留进程内凭据。 */
@Suppress("TooGenericExceptionCaught")
class KeystoreCredentials(context: Context) : CredentialStore {
    private val prefs = context.getSharedPreferences("credential_ciphertext", Context.MODE_PRIVATE)

    private fun alias(profileId: String): String = KEY_ALIAS + "." +
        java.security.MessageDigest.getInstance("SHA-256").digest(profileId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun key(profileId: String): SecretKey {
        val keyAlias = alias(profileId)
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    @Synchronized
    override fun load(profileId: String): String? = try {
        prefs.getString(profileId, null)?.let { encoded ->
            val parts = encoded.split(":", limit = 2)
            if (parts.size != 2) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(profileId),
                GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP))
            )
            cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
        }
    } catch (_: Exception) {
        // 回调不能让 Android 平台异常穿过 UniFFI；不记录凭据或异常消息。
        null
    }

    // core 只有确认密文已持久落盘后才能报告 credential_persistent=true；回调已在 IO 线程。
    @android.annotation.SuppressLint("ApplySharedPref")
    @Synchronized
    override fun save(profileId: String, token: String): Boolean = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(profileId))
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val encrypted = Base64.encodeToString(cipher.doFinal(token.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        prefs.edit().putString(profileId, "$iv:$encrypted").commit()
    } catch (_: Exception) {
        false
    }

    @Synchronized
    override fun remove(profileId: String) {
        // 双路撤销：即使偏好文件写入失败，删除账号专属 Keystore key 后旧密文也无法解密。
        runCatching { prefs.edit().remove(profileId).commit() }
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias(profileId))
        }
    }

    companion object { private const val KEY_ALIAS = "djhx.memo.credentials.v1" }
}
