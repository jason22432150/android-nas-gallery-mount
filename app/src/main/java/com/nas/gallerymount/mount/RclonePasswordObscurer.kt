package com.nas.gallerymount.mount

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 實作 rclone 同款密碼 obscure（AES-CTR + Base64 Raw URL），
 * 避免呼叫 `rclone obscure` 時 stderr 雜訊被寫進 `pass =`。
 *
 * 對齊：https://github.com/rclone/rclone/blob/v1.74.3/fs/config/obscure/obscure.go
 */
class RclonePasswordObscurer(
    @Suppress("UNUSED_PARAMETER") toolLocator: ToolLocator? = null,
) {
    private val random = SecureRandom()

    /**
     * @return obscure 後字串；空密碼回傳空字串
     */
    fun obscure(plainPassword: String): String {
        if (plainPassword.isEmpty()) return ""
        val plaintext = plainPassword.toByteArray(Charsets.UTF_8)
        val iv = ByteArray(AES_BLOCK_SIZE).also { random.nextBytes(it) }
        val key = SecretKeySpec(CRYPT_KEY, "AES")
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(iv))
        val encrypted = cipher.doFinal(plaintext)
        val payload = iv + encrypted
        // rclone 使用 encoding/base64.RawURLEncoding（無 padding、URL-safe）
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
    }

    companion object {
        private const val AES_BLOCK_SIZE = 16

        /** rclone obscure.go 的固定 cryptKey（僅用於設定檔混淆，非高強度保密）。 */
        private val CRYPT_KEY = byteArrayOf(
            0x9c.toByte(), 0x93.toByte(), 0x5b, 0x48, 0x73, 0x0a, 0x55, 0x4d,
            0x6b, 0xfd.toByte(), 0x7c, 0x63, 0xc8.toByte(), 0x86.toByte(), 0xa9.toByte(), 0x2b,
            0xd3.toByte(), 0x90.toByte(), 0x19, 0x8e.toByte(), 0xb8.toByte(), 0x12, 0x8a.toByte(), 0xfb.toByte(),
            0xf4.toByte(), 0xde.toByte(), 0x16, 0x2b, 0x8b.toByte(), 0x95.toByte(), 0xf6.toByte(), 0x38,
        )

        /**
         * Reveal（測試用）：還原 obscure 字串。
         */
        fun revealForTest(obscured: String): String {
            val ciphertext = Base64.getUrlDecoder().decode(obscured)
            require(ciphertext.size >= AES_BLOCK_SIZE) { "obscured 過短" }
            val iv = ciphertext.copyOfRange(0, AES_BLOCK_SIZE)
            val data = ciphertext.copyOfRange(AES_BLOCK_SIZE, ciphertext.size)
            val key = SecretKeySpec(CRYPT_KEY, "AES")
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))
            return cipher.doFinal(data).toString(Charsets.UTF_8)
        }
    }
}
