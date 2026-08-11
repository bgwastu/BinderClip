package net.wastu.clipboard.crypto

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class E2ECryptoTest {
    @Test
    fun sealAndOpen_roundTrip() {
        val token = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        val key = E2ECrypto.deriveKey(token)
        val plaintext = "hello from android test".toByteArray(Charsets.UTF_8)

        val blob = E2ECrypto.seal(plaintext, key)
        val reopened = E2ECrypto.open(blob, key)

        assertArrayEquals(plaintext, reopened)
    }

    @Test
    fun deriveKeyAndDeviceTag_matchKnownVector() {
        val token = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        val key = E2ECrypto.deriveKey(token)
        val tag = E2ECrypto.deviceTag(token)

        assertEquals(
            "582cc5651a3aa4f9c66964c1893a9111217127c5f1c11612808c9c225b526885",
            key.encoded.toHex()
        )
        assertEquals("79acf879b1bd4b71", tag.toHex())
    }

    @Test(expected = Exception::class)
    fun open_rejectsTamperedBlob() {
        val token = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        val key = E2ECrypto.deriveKey(token)
        val blob = E2ECrypto.seal("payload".toByteArray(Charsets.UTF_8), key)
        blob[blob.lastIndex] = (blob.last().toInt() xor 0x01).toByte()

        E2ECrypto.open(blob, key)
    }

    @Test(expected = IllegalArgumentException::class)
    fun open_rejectsShortBlob() {
        val token = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        val key = E2ECrypto.deriveKey(token)
        val tooShort = ByteArray(16)

        E2ECrypto.open(tooShort, key)
    }

    @Test
    fun seal_usesRandomNonce() {
        val token = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        val key = E2ECrypto.deriveKey(token)
        val plaintext = "same payload".toByteArray(Charsets.UTF_8)

        val first = E2ECrypto.seal(plaintext, key)
        val second = E2ECrypto.seal(plaintext, key)

        assertNotEquals(first.toHex(), second.toHex())
    }

    @Test
    fun ecdhSharedSecretSymmetry() {
        val keyPair1 = E2ECrypto.generateX25519KeyPair()
        val keyPair2 = E2ECrypto.generateX25519KeyPair()

        val pub1Raw = E2ECrypto.x25519PublicKeyToRaw(keyPair1.public)
        val pub2Raw = E2ECrypto.x25519PublicKeyToRaw(keyPair2.public)

        val secret1 = E2ECrypto.ecdhSharedSecret(keyPair1.private, pub2Raw)
        val secret2 = E2ECrypto.ecdhSharedSecret(keyPair2.private, pub1Raw)

        assertArrayEquals(secret1, secret2)
        assertEquals(32, secret1.size)
    }

    @Test
    fun deriveKeyFromSecretBytes() {
        val secretBytes = ByteArray(32) { 0x42 }
        val key = E2ECrypto.deriveKey(secretBytes)
        assertNotNull(key)

        val tag = E2ECrypto.deviceTag(secretBytes)
        assertEquals(8, tag.size)
    }

    @Test
    fun deriveKeyFromSecretBytesMatchesHexVersion() {
        val hex = "fcd54dacce529f89bb4a3d48a3f68da3093ec379b84e16d0d03cdf80a8cb6238"
        val bytes = E2ECrypto.hexToBytes(hex)

        val keyFromHex = E2ECrypto.deriveKey(hex)
        val keyFromBytes = E2ECrypto.deriveKey(bytes)

        // Both should produce the same key - verify by encrypting and cross-decrypting
        val plaintext = "test".toByteArray()
        val encrypted = E2ECrypto.seal(plaintext, keyFromHex)
        val decrypted = E2ECrypto.open(encrypted, keyFromBytes)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun x25519PublicKeyRoundTrip() {
        val keyPair = E2ECrypto.generateX25519KeyPair()
        val raw = E2ECrypto.x25519PublicKeyToRaw(keyPair.public)
        assertEquals(32, raw.size)

        val reconstructed = E2ECrypto.x25519PublicKeyFromRaw(raw)
        val rawAgain = E2ECrypto.x25519PublicKeyToRaw(reconstructed)
        assertArrayEquals(raw, rawAgain)
    }

    // --- v2 session key derivation (cross-platform fixture: v2_session_fixture.json) ---

    private fun loadV2SessionFixture(): JSONObject {
        val path = "macos/test-fixtures/protocol/l2cap/v2_session_fixture.json"
        val file = findUpwards(path)
            ?: error("Could not locate fixture file: $path from ${System.getProperty("user.dir")}")
        return JSONObject(file.readText())
    }

    private fun findUpwards(relativePath: String): File? {
        var current = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (true) {
            val candidate = File(current, relativePath)
            if (candidate.exists()) return candidate
            val parent = current.parentFile ?: return null
            if (parent == current) return null
            current = parent
        }
    }

    @Test
    fun rawX25519MatchesFixture() {
        val fixture = loadV2SessionFixture()
        val keyPairs = fixture.getJSONObject("key_pairs")
        val alicePriv = E2ECrypto.hexToBytes(keyPairs.getJSONObject("mac_ephemeral").getString("private_hex"))
        val bobPub = E2ECrypto.hexToBytes(keyPairs.getJSONObject("android_ephemeral").getString("public_hex"))
        val expectedRawEcdh = fixture.getJSONObject("derivation").getString("raw_ecdh")

        val rawEcdh = E2ECrypto.rawX25519(alicePriv, bobPub)
        assertEquals(expectedRawEcdh, rawEcdh.toHex())
    }

    @Test
    fun deriveAuthKeyMatchesFixture() {
        val fixture = loadV2SessionFixture()
        val sharedSecret = E2ECrypto.hexToBytes(fixture.getString("shared_secret"))
        val expectedAuthKey = fixture.getJSONObject("derivation").getString("auth_key")

        val authKey = E2ECrypto.deriveAuthKey(sharedSecret)
        assertEquals(expectedAuthKey, authKey.encoded.toHex())
    }

    @Test
    fun hmacAuthMatchesFixture() {
        val fixture = loadV2SessionFixture()
        val sharedSecret = E2ECrypto.hexToBytes(fixture.getString("shared_secret"))
        val macPubKey = E2ECrypto.hexToBytes(
            fixture.getJSONObject("key_pairs").getJSONObject("mac_ephemeral").getString("public_hex")
        )
        val expectedAuthMac = fixture.getJSONObject("derivation").getString("auth_mac")

        val authKey = E2ECrypto.deriveAuthKey(sharedSecret)
        val authMac = E2ECrypto.hmacAuth(macPubKey, authKey)
        assertEquals(expectedAuthMac, authMac.toHex())
    }

    @Test
    fun verifyAuthAcceptsCorrectHmac() {
        val fixture = loadV2SessionFixture()
        val sharedSecret = E2ECrypto.hexToBytes(fixture.getString("shared_secret"))
        val macPubKey = E2ECrypto.hexToBytes(
            fixture.getJSONObject("key_pairs").getJSONObject("mac_ephemeral").getString("public_hex")
        )
        val expectedAuthMac = E2ECrypto.hexToBytes(fixture.getJSONObject("derivation").getString("auth_mac"))

        val authKey = E2ECrypto.deriveAuthKey(sharedSecret)
        assertTrue(E2ECrypto.verifyAuth(macPubKey, authKey, expectedAuthMac))
    }

    @Test
    fun verifyAuthRejectsWrongHmac() {
        val fixture = loadV2SessionFixture()
        val sharedSecret = E2ECrypto.hexToBytes(fixture.getString("shared_secret"))
        val macPubKey = E2ECrypto.hexToBytes(
            fixture.getJSONObject("key_pairs").getJSONObject("mac_ephemeral").getString("public_hex")
        )
        val wrongMac = ByteArray(32) { 0xFF.toByte() }

        val authKey = E2ECrypto.deriveAuthKey(sharedSecret)
        assertFalse(E2ECrypto.verifyAuth(macPubKey, authKey, wrongMac))
    }

    @Test
    fun deriveSessionKeyMatchesFixture() {
        val fixture = loadV2SessionFixture()
        val sharedSecret = E2ECrypto.hexToBytes(fixture.getString("shared_secret"))
        val rawEcdh = E2ECrypto.hexToBytes(fixture.getJSONObject("derivation").getString("raw_ecdh"))
        val expectedSessionKey = fixture.getJSONObject("derivation").getString("session_key")

        val sessionKey = E2ECrypto.deriveSessionKey(sharedSecret, rawEcdh)
        assertEquals(expectedSessionKey, sessionKey.encoded.toHex())
    }

    // --- Cross-platform ECDH interop (must match macOS E2ECryptoKeyDerivationTests.swift) ---

    // Golden fixture values from macos/test-fixtures/protocol/l2cap/ecdh_fixture.json
    private val rawEcdhSecretHex = "fcd54dacce529f89bb4a3d48a3f68da3093ec379b84e16d0d03cdf80a8cb6238"
    private val expectedRootSecretHex = "753c3067d7ed56384ace75c07da61e6d547c4fc82e0ca0b1082c9cbda42fc853"
    private val expectedEncryptionKeyHex = "3edc21d3c7f029b51fcf5163d7a23dee1b9926e20fee20ab49f43376442488f2"
    private val expectedDeviceTagHex = "020e73f5b1906642"
    private val expectedPairingTagHex = "82dab32a9aedffb4"
    private val macPublicKeyHex = "132c442be010fbd57e72603328aa76e71fccc1503aae219327d14d9c9993f472"

    @Test
    fun ecdhFixtureRootSecret() {
        // root_secret = HKDF-SHA256(ikm=raw_ecdh_secret, salt=zeros(32), info="clipboard-ecdh-v1", len=32)
        val rawSecret = E2ECrypto.hexToBytes(rawEcdhSecretHex)
        val rootSecret = E2ECrypto.hkdf(rawSecret, "clipboard-ecdh-v1", 32)
        assertEquals(expectedRootSecretHex, rootSecret.toHex())
    }

    @Test
    fun ecdhFixtureEncryptionKey() {
        // encryption_key = deriveKey(root_secret)
        val rootBytes = E2ECrypto.hexToBytes(expectedRootSecretHex)
        val encKey = E2ECrypto.deriveKey(rootBytes)
        assertEquals(expectedEncryptionKeyHex, encKey.encoded.toHex())
    }

    @Test
    fun ecdhFixtureDeviceTag() {
        // device_tag = deviceTag(root_secret)
        val rootBytes = E2ECrypto.hexToBytes(expectedRootSecretHex)
        val tag = E2ECrypto.deviceTag(rootBytes)
        assertEquals(expectedDeviceTagHex, tag.toHex())
    }

    @Test
    fun ecdhFixturePairingTag() {
        // pairing_tag = SHA256(mac_public_key)[0:8]
        val macPubBytes = E2ECrypto.hexToBytes(macPublicKeyHex)
        val hash = MessageDigest.getInstance("SHA-256").digest(macPubBytes)
        val pairingTag = hash.copyOfRange(0, 8)
        assertEquals(expectedPairingTagHex, pairingTag.toHex())
    }

    @Test
    fun ecdhFixtureFullDerivationChain() {
        // Verify the full chain: raw_ecdh_secret -> root_secret -> encryption_key + device_tag
        val rawSecret = E2ECrypto.hexToBytes(rawEcdhSecretHex)

        // Step 1: Derive root_secret from raw ECDH secret
        val rootSecret = E2ECrypto.hkdf(rawSecret, "clipboard-ecdh-v1", 32)

        // Step 2: Derive encryption_key from root_secret
        val encKey = E2ECrypto.deriveKey(rootSecret)

        // Step 3: Derive device_tag from root_secret
        val tag = E2ECrypto.deviceTag(rootSecret)

        // All values must match the fixture
        assertEquals(expectedRootSecretHex, rootSecret.toHex())
        assertEquals(expectedEncryptionKeyHex, encKey.encoded.toHex())
        assertEquals(expectedDeviceTagHex, tag.toHex())
    }
}

private fun ByteArray.toHex(): String = joinToString(separator = "") { "%02x".format(it) }
