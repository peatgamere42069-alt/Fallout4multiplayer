package com.imageguard.app

/**
 * Minimal IPv4/UDP and DNS packet handling for the local DNS filter.
 * Pure Kotlin (no Android APIs) so it can be unit-tested on the JVM.
 */
object Ipv4Udp {

    class Packet(
        val srcIp: ByteArray,
        val dstIp: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        val payload: ByteArray,
    )

    /** Parses an IPv4 UDP packet, or returns null for anything else. */
    fun parse(buf: ByteArray, len: Int): Packet? {
        if (len < 28) return null
        val version = (buf[0].toInt() shr 4) and 0xF
        if (version != 4) return null
        val ihl = (buf[0].toInt() and 0xF) * 4
        if (ihl < 20 || len < ihl + 8) return null
        if ((buf[9].toInt() and 0xFF) != 17) return null // UDP only
        // Ignore fragments (DNS queries are never fragmented in practice).
        val fragField = u16(buf, 6)
        if ((fragField and 0x3FFF) != 0) return null
        val totalLen = minOf(u16(buf, 2), len)
        val udpLen = u16(buf, ihl + 4)
        val payloadLen = minOf(udpLen - 8, totalLen - ihl - 8)
        if (payloadLen <= 0) return null
        return Packet(
            srcIp = buf.copyOfRange(12, 16),
            dstIp = buf.copyOfRange(16, 20),
            srcPort = u16(buf, ihl),
            dstPort = u16(buf, ihl + 2),
            payload = buf.copyOfRange(ihl + 8, ihl + 8 + payloadLen),
        )
    }

    /** Builds an IPv4 UDP packet with valid IP and UDP checksums. */
    fun build(srcIp: ByteArray, dstIp: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val udpLen = 8 + payload.size
        val total = 20 + udpLen
        val p = ByteArray(total)
        p[0] = 0x45
        put16(p, 2, total)
        put16(p, 6, 0x4000) // don't fragment
        p[8] = 64 // TTL
        p[9] = 17 // UDP
        System.arraycopy(srcIp, 0, p, 12, 4)
        System.arraycopy(dstIp, 0, p, 16, 4)
        put16(p, 10, checksum(p, 0, 20, 0))

        put16(p, 20, srcPort)
        put16(p, 22, dstPort)
        put16(p, 24, udpLen)
        System.arraycopy(payload, 0, p, 28, payload.size)
        // Pseudo-header: src, dst, zero, protocol, UDP length.
        var pseudo = 0L
        for (i in 0 until 4 step 2) {
            pseudo += u16(srcIp, i) + u16(dstIp, i)
        }
        pseudo += 17 + udpLen
        var udpSum = checksum(p, 20, udpLen, pseudo)
        if (udpSum == 0) udpSum = 0xFFFF
        put16(p, 26, udpSum)
        return p
    }

    internal fun checksum(b: ByteArray, off: Int, len: Int, initial: Long): Int {
        var sum = initial
        var i = off
        while (i + 1 < off + len) {
            sum += u16(b, i)
            i += 2
        }
        if (i < off + len) sum += (b[i].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    internal fun u16(b: ByteArray, off: Int) =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    internal fun put16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v shr 8).toByte()
        b[off + 1] = v.toByte()
    }
}

object Dns {
    const val TYPE_A = 1

    class Question(val name: String, val type: Int, val end: Int)

    /** Reads the first question of a DNS query. */
    fun parseQuestion(msg: ByteArray): Question? {
        if (msg.size < 12) return null
        val flags = Ipv4Udp.u16(msg, 2)
        if (flags and 0x8000 != 0) return null // a response, not a query
        if (Ipv4Udp.u16(msg, 4) < 1) return null
        val name = StringBuilder()
        var i = 12
        while (true) {
            if (i >= msg.size) return null
            val len = msg[i].toInt() and 0xFF
            if (len == 0) { i++; break }
            if (len and 0xC0 != 0 || i + 1 + len > msg.size) return null
            if (name.isNotEmpty()) name.append('.')
            for (k in 1..len) name.append((msg[i + k].toInt() and 0xFF).toChar())
            i += 1 + len
        }
        if (i + 4 > msg.size) return null
        return Question(name.toString().lowercase(), Ipv4Udp.u16(msg, i), i + 4)
    }

    fun nxdomain(query: ByteArray, q: Question) = reply(query, q, 3, emptyList(), 0)
    fun servfail(query: ByteArray, q: Question) = reply(query, q, 2, emptyList(), 0)
    fun noRecords(query: ByteArray, q: Question) = reply(query, q, 0, emptyList(), 300)
    fun answerA(query: ByteArray, q: Question, ips: List<ByteArray>, ttl: Int = 300) =
        reply(query, q, 0, ips, ttl)

    private fun reply(query: ByteArray, q: Question, rcode: Int, ips: List<ByteArray>, ttl: Int): ByteArray {
        val answers = ips.filter { it.size == 4 }
        val out = ByteArray(q.end + answers.size * 16)
        System.arraycopy(query, 0, out, 0, q.end) // header + question
        val rd = query[2].toInt() and 0x01
        val opcode = query[2].toInt() and 0x78
        out[2] = (0x80 or opcode or rd).toByte() // QR=1, AA=0, TC=0
        out[3] = (0x80 or (rcode and 0xF)).toByte() // RA=1
        Ipv4Udp.put16(out, 4, 1)
        Ipv4Udp.put16(out, 6, answers.size)
        Ipv4Udp.put16(out, 8, 0)
        Ipv4Udp.put16(out, 10, 0)
        var o = q.end
        for (ip in answers) {
            Ipv4Udp.put16(out, o, 0xC00C); o += 2 // pointer to the question name
            Ipv4Udp.put16(out, o, TYPE_A); o += 2
            Ipv4Udp.put16(out, o, 1); o += 2 // class IN
            Ipv4Udp.put16(out, o, ttl shr 16); o += 2
            Ipv4Udp.put16(out, o, ttl and 0xFFFF); o += 2
            Ipv4Udp.put16(out, o, 4); o += 2
            System.arraycopy(ip, 0, out, o, 4); o += 4
        }
        return out
    }
}
