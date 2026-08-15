package net.wastu.binderclip

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object DirectProtocol {
    const val MAXIMUM_TEXT_BYTES = 1_048_576
    private const val MAX_FRAME = MAXIMUM_TEXT_BYTES + 65_536

    fun read(input: DataInputStream): JSONObject {
        val length = input.readInt()
        require(length in 1..MAX_FRAME) { "Invalid frame" }
        return JSONObject(input.readNBytes(length).toString(Charsets.UTF_8))
    }
    fun write(output: DataOutputStream, message: JSONObject) {
        val bytes = message.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_FRAME) { "Frame too large" }
        synchronized(output) { output.writeInt(bytes.size); output.write(bytes); output.flush() }
    }
    fun hmac(key: ByteArray, value: String): String = Base64.getEncoder().encodeToString(hmacBytes(key, value.toByteArray()))
    fun constantTimeEquals(a: String, b: String): Boolean {
        val left = runCatching { Base64.getDecoder().decode(a) }.getOrNull() ?: return false
        val right = runCatching { Base64.getDecoder().decode(b) }.getOrNull() ?: return false
        if (left.size != right.size) return false
        var different = 0; left.indices.forEach { different = different or (left[it].toInt() xor right[it].toInt()) }
        return different == 0
    }
    fun pairSessionKey(inviteKey: ByteArray, clientNonce: String, serverNonce: String): ByteArray {
        val salt = "$clientNonce|$serverNonce".toByteArray()
        val prk = hmacBytes(salt, inviteKey)
        return hmacBytes(prk, "binderclip-pairing".toByteArray() + byteArrayOf(1))
    }
    fun seal(message: JSONObject, key: ByteArray): JSONObject {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return JSONObject().put("type", "encrypted").put("payload", Base64.getEncoder().encodeToString(nonce + cipher.doFinal(message.toString().toByteArray())))
    }
    fun open(frame: JSONObject, key: ByteArray): JSONObject {
        require(frame.optString("type") == "encrypted") { "Expected encrypted frame" }
        val combined = Base64.getDecoder().decode(frame.getString("payload"))
        require(combined.size > 12) { "Invalid encrypted frame" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, combined.copyOfRange(0, 12)))
        return JSONObject(cipher.doFinal(combined.copyOfRange(12, combined.size)).toString(Charsets.UTF_8))
    }
    private fun hmacBytes(key: ByteArray, value: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(value) }
}
