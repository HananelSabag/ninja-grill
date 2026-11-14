package com.example.mygrill.core

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** One AES slot: a 32-byte key and a 16-byte nonce. */
class Slot(val key: ByteArray, val iv: ByteArray) {
    init {
        require(key.size == 32 && iv.size == 16) { "slot must be 32-byte key + 16-byte nonce" }
    }
}

/**
 * The local key bundle extracted once from the owner's copy of the vendor app: the app->grill
 * session slot, the grill->app session slot and the beacon slot, 144 bytes in that order.
 * The session slots are derived from the grill's Bluetooth address, so a bundle fits one grill.
 */
class KeyBundle(val tx: Slot, val rx: Slot, val beacon: Slot) {
    companion object {
        const val SIZE = 144

        /** Base64, or whitespace-separated hex tokens (both forms the extractor can print). */
        fun parse(text: String): KeyBundle {
            val trimmed = text.trim()
            val tokens = trimmed.split(Regex("\\s+"))
            val raw = if (tokens.size > 1) {
                tokens.joinToString("").hexToBytes()
            } else {
                runCatching { Base64.getDecoder().decode(trimmed) }
                    .getOrElse { throw IllegalArgumentException("key bundle must be base64 or hex") }
            }
            require(raw.size == SIZE) { "key bundle must be $SIZE bytes, got ${raw.size}" }
            fun slot(off: Int) = Slot(raw.copyOfRange(off, off + 32), raw.copyOfRange(off + 32, off + 48))
            return KeyBundle(slot(0), slot(48), slot(96))
        }
    }
}

internal object Aes {
    fun cbcDecrypt(slot: Slot, data: ByteArray): ByteArray = cbc(Cipher.DECRYPT_MODE, slot, data)
    fun cbcEncrypt(slot: Slot, data: ByteArray): ByteArray = cbc(Cipher.ENCRYPT_MODE, slot, data)

    fun ecbDecrypt(key: ByteArray, block: ByteArray): ByteArray =
        Cipher.getInstance("AES/ECB/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
            doFinal(block)
        }

    private fun cbc(mode: Int, slot: Slot, data: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/NoPadding").run {
            init(mode, SecretKeySpec(slot.key, "AES"), IvParameterSpec(slot.iv))
            doFinal(data)
        }
}

internal fun String.hexToBytes(): ByteArray {
    val clean = filterNot { it.isWhitespace() }
    require(clean.length % 2 == 0) { "odd hex length" }
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
