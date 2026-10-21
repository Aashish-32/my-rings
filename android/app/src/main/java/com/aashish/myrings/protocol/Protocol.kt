package com.aashish.myrings.protocol

import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** GATT + framing + request builders. Ported from open_oura `oura-protocol`. All ints little-endian. */
object Gatt {
    val SERVICE: UUID = UUID.fromString("98ed0001-a541-11e4-b6a0-0002a5d5c51b")
    val WRITE: UUID = UUID.fromString("98ed0002-a541-11e4-b6a0-0002a5d5c51b")
    val NOTIFY: UUID = UUID.fromString("98ed0003-a541-11e4-b6a0-0002a5d5c51b")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

const val HISTORY_EVENT_PREFIX = 0x41

object Feature {
    const val DAYTIME_HR = 0x02
    const val SPO2 = 0x04
    const val RESTING_HR = 0x08
}

object FeatureMode {
    const val OFF = 0x00
    const val AUTOMATIC = 0x01
}

class Packet(val tag: Int, val payload: ByteArray) {
    fun encode(): ByteArray = byteArrayOf(tag.toByte(), payload.size.toByte()) + payload

    /** Extended op tag (first payload byte) for 0x2f frames. */
    val extTag: Int? get() = if (tag == 0x2f) payload.firstOrNull()?.toInt()?.and(0xff) else null

    companion object {
        /** Lenient single-packet parse (rings sometimes pad frames). */
        fun parse(frame: ByteArray): Packet? {
            if (frame.size < 2) return null
            val tag = frame[0].toInt() and 0xff
            val len = frame[1].toInt() and 0xff
            val payload = if (2 + len <= frame.size) frame.copyOfRange(2, 2 + len) else frame.copyOfRange(2, frame.size)
            return Packet(tag, payload)
        }

        /** Every complete tag|len|payload packet concatenated into one notification. */
        fun parseMany(frame: ByteArray): List<Packet> {
            val out = ArrayList<Packet>()
            var i = 0
            while (i + 2 <= frame.size) {
                val tag = frame[i].toInt() and 0xff
                val len = frame[i + 1].toInt() and 0xff
                val start = i + 2
                val end = start + len
                if (end > frame.size) break
                out.add(Packet(tag, frame.copyOfRange(start, end)))
                i = end
            }
            if (out.isEmpty()) parse(frame)?.let { out.add(it) }
            return out
        }
    }
}

private fun u16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
private fun u32(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
private fun u64(v: Long) = ByteArray(8) { (v shr (8 * it)).toByte() }
private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

object Req {
    fun firmware() = bytes(0x08, 0x03, 0x00, 0x00, 0x00)
    fun battery() = bytes(0x0c, 0x00)
    fun authNonce() = bytes(0x2f, 0x01, 0x2b)
    fun authenticate(encrypted: ByteArray): ByteArray {
        require(encrypted.size == 16)
        return Packet(0x2f, byteArrayOf(0x2d) + encrypted).encode()
    }
    fun setAuthKey(key: ByteArray): ByteArray {
        require(key.size == 16)
        return Packet(0x24, key).encode()
    }
    fun syncTime(unixSecs: Long, tzHalfHours: Int = 0) = Packet(0x12, u64(unixSecs) + byteArrayOf(tzHalfHours.toByte())).encode()

    /**
     * The official app's `12 09 ... f6` time sync. It was once read as `<token> <unix/256> 00000000 f6` with a
     * scrambled token, but the ring takes all eight leading bytes as a plain u64 unix time (the token became the
     * clock's low byte, up to 255 s off) and the `f6` was just the capturing phone's UTC-5 offset. So it is exactly
     * [syncTime]: exact unix seconds plus the local UTC offset in signed half-hours. Mirrors open_oura's fix.
     */
    fun syncTimeCounter(unixSecs: Long, tzHalfHours: Int) = syncTime(unixSecs, tzHalfHours)

    /** This phone's current UTC offset in signed half-hours, the unit the ring's time sync carries (UTC-4 = -8). */
    fun localTzHalfHours(): Int = (java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1_800_000).coerceIn(-24, 28)
    fun capabilities(page: Int) = bytes(0x2f, 0x02, 0x01, page)
    val SERIAL = bytes(0x18, 0x03, 0x08, 0x00, 0x10)
    val HARDWARE = bytes(0x18, 0x03, 0x18, 0x00, 0x10)
    fun getEvent(startDeciseconds: Long, maxEvents: Int, flags: Int): ByteArray =
        Packet(0x10, u32(startDeciseconds) + byteArrayOf(maxEvents.toByte()) + u32(flags.toLong() and 0xffffffffL)).encode()
    fun extGetEvent(startMs: Long, maxEvents: Int, bufferId: Int): ByteArray =
        Packet(0x2f, byteArrayOf(0x41, bufferId.toByte()) + u64(startMs) + u16(maxEvents)).encode()
    fun dataFlush() = bytes(0x28, 0x01, 0x00)
    fun featureStatus(feature: Int) = bytes(0x2f, 0x02, 0x20, feature)
    fun setFeatureMode(feature: Int, mode: Int) = bytes(0x2f, 0x03, 0x22, feature, mode)
    fun checkSleepAnalysis(force: Boolean) = bytes(0x28, 0x01, if (force) 1 else 0)
}

object Auth {
    sealed class Result(val code: Int) {
        object Success : Result(0)
        object AuthenticationError : Result(1)
        object InFactoryReset : Result(2)
        object NotOriginalOnboardedDevice : Result(3)
        class Unknown(code: Int) : Result(code)

        val isSuccess get() = code == 0
        override fun toString() = when (this) {
            Success -> "Success"
            AuthenticationError -> "AuthenticationError"
            InFactoryReset -> "InFactoryReset"
            NotOriginalOnboardedDevice -> "NotOriginalOnboardedDevice"
            is Unknown -> "Unknown(0x%02x)".format(code)
        }

        companion object {
            fun from(b: Int) = when (b) {
                0 -> Success
                1 -> AuthenticationError
                2 -> InFactoryReset
                3 -> NotOriginalOnboardedDevice
                else -> Unknown(b)
            }
        }
    }

    /** AES-128/ECB/PKCS5 of the (typically 15-byte) nonce -> 16-byte block. */
    fun encryptNonce(key: ByteArray, nonce: ByteArray): ByteArray {
        require(key.size == 16)
        val c = Cipher.getInstance("AES/ECB/PKCS5Padding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return c.doFinal(nonce.copyOf(minOf(nonce.size, 16)))
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
fun String.hexToBytes(): ByteArray {
    val s = trim().replace(" ", "")
    require(s.length % 2 == 0) { "odd hex length" }
    return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

// ---- device info ----

data class DeviceInfo(val api: String, val firmware: String, val bootloader: String, val btStack: String, val mac: String) {
    companion object {
        fun parse(p: Packet): DeviceInfo? {
            if (p.tag != 0x09 || p.payload.size < 18) return null
            val b = p.payload
            fun v3(from: Int) = (from until from + 3).joinToString(".") { (b[it].toInt() and 0xff).toString() }
            val mac = (17 downTo 12).joinToString(":") { "%02x".format(b[it]) }
            return DeviceInfo(v3(0), v3(3), v3(6), v3(9), mac)
        }
    }
}

data class Battery(val percent: Int, val chargingProgress: Int, val chargingRecommended: Int) {
    companion object {
        fun parse(p: Packet): Battery? {
            if (p.tag != 0x0d || p.payload.size < 3) return null
            val b = p.payload
            return Battery(b[0].toInt() and 0xff, b[1].toInt() and 0xff, b[2].toInt() and 0xff)
        }
    }
}

fun parseProductAscii(p: Packet): String? {
    if (p.tag != 0x19 || p.payload.isEmpty() || p.payload[0].toInt() != 0) return null
    val t = String(p.payload, 1, p.payload.size - 1, Charsets.UTF_8).trimEnd('\u0000').trim()
    return t.ifEmpty { null }
}
