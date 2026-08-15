package net.wastu.binderclip.webrtc

import java.util.Base64

/**
 * Compact binary QR payload for serverless WebRTC signaling (QWBP-style).
 * Mirrors the Swift WebRTCQRCodec on macOS so both platforms interoperate.
 *
 * Payload layout (all network byte order):
 *   magic  (1 byte, 0x51)
 *   version (1 byte, 0x00)
 *   fingerprint (32 bytes: SHA-256 of the DTLS certificate)
 *   ufrag  (4 bytes raw)
 *   pwd    (18 bytes raw)
 *   then 0..4 ICE candidates (see [Candidate])
 */
object WebRTCQRCodec {
    const val MAGIC: Int = 0x51
    const val FINGERPRINT_LENGTH = 32
    const val UFRAG_LENGTH = 4
    const val PWD_LENGTH = 18
    const val MAX_CANDIDATES = 4

    class CodecException(message: String) : Exception(message)

    data class Candidate(
        val addressFamily: Int, // 0 IPv4, 1 IPv6, 2 mDNS
        val protocol: Int,      // 0 UDP, 1 TCP
        val type: Int,          // 0 host, 1 srflx
        val tcpType: Int,       // 0 passive, 1 active, 2 so
        val address: ByteArray,
        val port: Int,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Candidate) return false
            return addressFamily == other.addressFamily &&
                protocol == other.protocol &&
                type == other.type &&
                tcpType == other.tcpType &&
                port == other.port &&
                address.contentEquals(other.address)
        }

        override fun hashCode(): Int = java.util.Arrays.hashCode(address) * 31 + port
    }

    data class Packet(
        val fingerprint: ByteArray,
        val ufrag: String,
        val pwd: String,
        val candidates: List<Candidate>,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Packet) return false
            return ufrag == other.ufrag &&
                pwd == other.pwd &&
                candidates == other.candidates &&
                fingerprint.contentEquals(other.fingerprint)
        }

        override fun hashCode(): Int = 31 * (java.util.Arrays.hashCode(fingerprint) * 31 + ufrag.hashCode()) + pwd.hashCode()
    }

    // MARK: - Encode

    fun encode(packet: Packet): ByteArray {
        require(packet.fingerprint.size == FINGERPRINT_LENGTH) { "Bad fingerprint length" }
        require(packet.candidates.size <= MAX_CANDIDATES) { "Too many candidates" }
        val ufragBytes = urlSafeBase64Decode(packet.ufrag)
        val pwdBytes = urlSafeBase64Decode(packet.pwd)
        require(ufragBytes.size <= 255 && pwdBytes.size <= 255) { "ICE credentials too long" }
        val out = java.io.ByteArrayOutputStream()
        out.write(MAGIC)
        out.write(0x00)
        out.write(packet.fingerprint)
        out.write(ufragBytes.size)
        out.write(ufragBytes)
        out.write(pwdBytes.size)
        out.write(pwdBytes)
        packet.candidates.forEach { appendCandidate(it, out) }
        return out.toByteArray()
    }

    private fun appendCandidate(candidate: Candidate, out: java.io.ByteArrayOutputStream) {
        val flag: Int = candidate.addressFamily or
            (candidate.protocol shl 2) or
            (candidate.type shl 3) or
            (candidate.tcpType shl 4)
        out.write(flag)
        val addressLength = when (candidate.addressFamily) {
            0 -> 4
            1, 2 -> 16
            else -> throw CodecException("Unknown address family")
        }
        require(candidate.address.size == addressLength) { "Bad candidate address length" }
        out.write(candidate.address)
        out.write(candidate.port ushr 8)
        out.write(candidate.port and 0xFF)
    }

    // MARK: - Decode

    fun decode(data: ByteArray): Packet {
        if (data.size < 2 + FINGERPRINT_LENGTH + 1 + 1) throw CodecException("Payload too short")
        if (data[0].toInt() != MAGIC) throw CodecException("Bad magic")
        val fingerprint = data.copyOfRange(2, 2 + FINGERPRINT_LENGTH)
        var offset = 2 + FINGERPRINT_LENGTH
        if (offset + 1 > data.size) throw CodecException("Payload too short")
        val ufragLength = data[offset].toInt() and 0xFF
        offset += 1
        if (offset + ufragLength + 1 > data.size) throw CodecException("Payload too short")
        val ufrag = urlSafeBase64Encode(data.copyOfRange(offset, offset + ufragLength))
        offset += ufragLength
        val pwdLength = data[offset].toInt() and 0xFF
        offset += 1
        if (offset + pwdLength > data.size) throw CodecException("Payload too short")
        val pwd = urlSafeBase64Encode(data.copyOfRange(offset, offset + pwdLength))
        offset += pwdLength
        val candidates = mutableListOf<Candidate>()
        while (offset < data.size) {
            if (candidates.size >= MAX_CANDIDATES) throw CodecException("Too many candidates")
            val (candidate, next) = decodeCandidate(data, offset)
            candidates.add(candidate)
            offset = next
        }
        return Packet(fingerprint, ufrag, pwd, candidates)
    }

    private fun decodeCandidate(data: ByteArray, start: Int): Pair<Candidate, Int> {
        var offset = start
        if (offset + 1 >= data.size) throw CodecException("Malformed candidate")
        val flag = data[offset].toInt()
        offset += 1
        val addressFamily = flag and 0b11
        val protocol = (flag shr 2) and 0b1
        val type = (flag shr 3) and 0b1
        val tcpType = (flag shr 4) and 0b11
        val addressLength = when (addressFamily) {
            0 -> 4
            1, 2 -> 16
            else -> throw CodecException("Unknown address family")
        }
        if (offset + addressLength + 2 > data.size) throw CodecException("Malformed candidate")
        val address = data.copyOfRange(offset, offset + addressLength)
        offset += addressLength
        val port = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
        offset += 2
        return Candidate(addressFamily, protocol, type, tcpType, address, port) to offset
    }

    // MARK: - ICE credential derivation

    /** Derive fixed-size ICE credentials from the DTLS fingerprint (QWBP-style). */
    fun deriveCredentials(fingerprint: ByteArray): Pair<String, String> {
        val prk = hmacSHA256(ByteArray(0), fingerprint)
        val ufragBytes = hmacSHA256(prk, "QWBP-ICE-UFRAG-v1".toByteArray()).copyOfRange(0, UFRAG_LENGTH)
        val pwdBytes = hmacSHA256(prk, "QWBP-ICE-PWD-v1".toByteArray()).copyOfRange(0, PWD_LENGTH)
        return urlSafeBase64Encode(ufragBytes) to urlSafeBase64Encode(pwdBytes)
    }

    private fun hmacSHA256(key: ByteArray, message: ByteArray): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }

    // MARK: - Base64url

    fun urlSafeBase64Encode(data: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(data)

    fun urlSafeBase64Decode(string: String): ByteArray = Base64.getUrlDecoder().decode(string)
}
