package com.aleksandr.pockethud

import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object D3200Crypto {
    private val hkdfContext = byteArrayOf(1, 2, 3)
    private val magic = "soundcored3200".toByteArray(Charsets.US_ASCII)

    fun generate(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    fun encode(key: ECPublicKey): ByteArray = byteArrayOf(4) + coordinate(key.w.affineX) +
        coordinate(key.w.affineY)

    private fun coordinate(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return if (bytes.size > 32) bytes.copyOfRange(bytes.size - 32, bytes.size)
        else ByteArray(32 - bytes.size) + bytes
    }

    fun shared(pair: KeyPair, peer: ByteArray): ByteArray {
        require(peer.size == 65 && peer[0] == 4.toByte()) { "Формат ключа D3200" }
        val point = ECPoint(BigInteger(1, peer.copyOfRange(1, 33)), BigInteger(1, peer.copyOfRange(33, 65)))
        val parameters = (pair.public as ECPublicKey).params
        val key = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, parameters))
        return KeyAgreement.getInstance("ECDH").run {
            init(pair.private)
            doPhase(key, true)
            generateSecret().also { require(it.size == 32) }
        }
    }

    fun sessionKey(pair: KeyPair, payload: ByteArray): ByteArray {
        require(payload.size >= 97) { "Недостаточно данных ECDH" }
        val secret = shared(pair, payload.copyOfRange(0, 65))
        val reported = payload.copyOfRange(65, 97)
        try {
            require(MessageDigest.isEqual(secret, reported)) { "Проверка ECDH не пройдена" }
            return hkdf(secret, hkdfContext, hkdfContext)
        } finally {
            secret.fill(0)
            reported.fill(0)
        }
    }

    fun hkdf(secret: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val extracted = mac.doFinal(secret)
        try {
            mac.init(SecretKeySpec(extracted, "HmacSHA256"))
            return mac.doFinal(info + byteArrayOf(1))
        } finally {
            extracted.fill(0)
        }
    }

    fun unwrap(sessionKey: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray {
        require(ciphertext.size == 46)
        val plain = ctr(sessionKey, nonce, ciphertext)
        try {
            require(plain.copyOfRange(0, magic.size).contentEquals(magic)) { "Проверка ключа файла не пройдена" }
            return plain.copyOfRange(magic.size, plain.size)
        } finally {
            plain.fill(0)
        }
    }

    fun counter(nonce: ByteArray, sequence: Long): ByteArray {
        require(nonce.size == 16 && sequence in 0..0xffffffffL)
        return ByteBuffer.allocate(16).put(nonce, 0, 12).putInt((sequence * 10).toInt()).array()
    }

    fun ctr(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray {
        require(key.size == 32 && iv.size == 16)
        return Cipher.getInstance("AES/CTR/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(ciphertext)
        }
    }
}
