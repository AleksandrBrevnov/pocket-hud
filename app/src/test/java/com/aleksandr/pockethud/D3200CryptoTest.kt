package com.aleksandr.pockethud

import org.junit.Assert.*
import org.junit.Test
import java.security.interfaces.ECPublicKey

class D3200CryptoTest {
    @Test fun agreesOnP256SecretAndRejectsMismatch() {
        val first = D3200Crypto.generate()
        val second = D3200Crypto.generate()
        val alice = D3200Crypto.encode(first.public as ECPublicKey)
        val bob = D3200Crypto.encode(second.public as ECPublicKey)
        assertEquals(65, alice.size)
        val secret = D3200Crypto.shared(first, bob)
        assertArrayEquals(secret, D3200Crypto.shared(second, alice))
        assertEquals(32, D3200Crypto.sessionKey(first, bob + secret).size)
        secret[15] = (secret[15].toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) { D3200Crypto.sessionKey(first, bob + secret) }
    }

    @Test fun hkdfMatchesRfc5869CaseOne() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = bytes("000102030405060708090a0b0c")
        val info = bytes("f0f1f2f3f4f5f6f7f8f9")
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf",
            D3200Crypto.hkdf(ikm, salt, info).hex())
    }

    @Test fun aesCtrMatchesNistAndFileKeyRequiresMagic() {
        val key = bytes("603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4")
        val iv = bytes("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff")
        val cipher = bytes("601ec313775789a5b7a7f504bbf3d228")
        assertEquals("6bc1bee22e409f96e93d7e117393172a", D3200Crypto.ctr(key, iv, cipher).hex())
        val fileKey = ByteArray(32) { it.toByte() }
        val wrapped = D3200Crypto.ctr(key, iv, "soundcored3200".toByteArray() + fileKey)
        assertArrayEquals(fileKey, D3200Crypto.unwrap(key, iv, wrapped))
        wrapped[0] = (wrapped[0].toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) { D3200Crypto.unwrap(key, iv, wrapped) }
    }

    @Test fun buildsBigEndianCounterFromSequence() {
        val iv = bytes("000102030405060708090a0b0c0d0e0f")
        assertEquals("000102030405060708090a0b01020304", D3200Crypto.counter(iv, 0x0019cd1aL).hex())
        assertEquals("000102030405060708090a0bfffffff6", D3200Crypto.counter(iv, 0xffffffffL).hex())
    }

    private fun bytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
