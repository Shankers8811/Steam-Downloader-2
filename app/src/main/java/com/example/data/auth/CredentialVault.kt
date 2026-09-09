package com.example.data.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Persistent, hardware-backed store for a remembered Steam login.
 *
 * The refresh token (the secret that keeps the user logged in) is encrypted
 * with an AES-256/GCM key that lives inside the AndroidKeyStore, then the
 * ciphertext is kept in SharedPreferences. This is the same protection model
 * the Steam mobile/desktop apps use ("remember my password").
 */
class CredentialVault(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** True when a remembered session has been persisted on this device. */
    fun isRemembered(): Boolean = prefs.contains(KEY_SESSION)

    fun saveSession(session: SteamSession) {
        val json = JSONObject()
            .put("steamId", session.steamId)
            .put("accountName", session.accountName)
            .put("refreshToken", session.refreshToken)
            .put("accessToken", session.accessToken)
            .put("obtainedAtMs", session.obtainedAtMs)
            .toString()
        val sealed = encrypt(json.toByteArray(Charsets.UTF_8)) ?: return
        prefs.edit().putString(KEY_SESSION, sealed).apply()
    }

    fun loadSession(): SteamSession? {
        val sealed = prefs.getString(KEY_SESSION, null) ?: return null
        return try {
            val plain = decrypt(sealed) ?: return null
            val json = JSONObject(String(plain, Charsets.UTF_8))
            SteamSession(
                steamId = json.optString("steamId"),
                accountName = json.optString("accountName"),
                refreshToken = json.optString("refreshToken"),
                accessToken = json.optString("accessToken"),
                obtainedAtMs = json.optLong("obtainedAtMs", System.currentTimeMillis())
            )
        } catch (e: Exception) {
            // Keystore rotated / corrupt payload -> forget the session cleanly.
            clear()
            null
        }
    }

    fun clear() {
        prefs.edit().remove(KEY_SESSION).apply()
    }

    // ------------------------------------------------------------------
    // AES/GCM helpers backed by AndroidKeyStore.
    // ------------------------------------------------------------------

    private fun encrypt(plain: ByteArray): String? = try {
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plain)
        val packed = ByteArray(iv.size + cipherText.size)
        System.arraycopy(iv, 0, packed, 0, iv.size)
        System.arraycopy(cipherText, 0, packed, iv.size, cipherText.size)
        Base64.encodeToString(packed, Base64.NO_WRAP)
    } catch (e: Exception) {
        null
    }

    private fun decrypt(sealed: String): ByteArray? = try {
        val packed = Base64.decode(sealed, Base64.NO_WRAP)
        val iv = packed.copyOfRange(0, GCM_IV_LENGTH)
        val cipherText = packed.copyOfRange(GCM_IV_LENGTH, packed.size)
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.doFinal(cipherText)
    } catch (e: Exception) {
        null
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val PREFS_NAME = "depot_secure_vault"
        private const val KEY_SESSION = "remembered_steam_session"
        private const val KEY_ALIAS = "depot_session_aes"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_BITS = 128
    }
}
