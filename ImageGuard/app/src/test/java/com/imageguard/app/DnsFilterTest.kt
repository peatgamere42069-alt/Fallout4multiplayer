package com.imageguard.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsFilterTest {

    private fun query(name: String, type: Int = 1, id: Int = 0x1234): ByteArray {
        val out = ArrayList<Byte>()
        fun u16(v: Int) { out += (v shr 8).toByte(); out += v.toByte() }
        u16(id); u16(0x0100); u16(1); u16(0); u16(0); u16(0)
        for (label in name.split('.')) {
            out += label.length.toByte()
            label.forEach { out += it.code.toByte() }
        }
        out += 0
        u16(type); u16(1)
        return out.toByteArray()
    }

    private val rules = FilterRules(
        FilterRules.parseList(
            sequenceOf(
                "# comment",
                "127.0.0.1 localhost",
                "0.0.0.0 doubleclick.net",
                "0.0.0.0 badsite.example   # trailing comment",
                "plain-domain.test",
            )
        )
    )

    @Test fun parsesQuestion() {
        val q = Dns.parseQuestion(query("WWW.Example.COM", 28))!!
        assertEquals("www.example.com", q.name)
        assertEquals(28, q.type)
        assertEquals(12 + 17 + 4, q.end)
    }

    @Test fun rejectsResponsesAndGarbage() {
        val resp = query("example.com").also { it[2] = 0x81.toByte() }
        assertNull(Dns.parseQuestion(resp))
        assertNull(Dns.parseQuestion(ByteArray(5)))
    }

    @Test fun blocksListedDomainsAndSubdomains() {
        assertEquals(FilterRules.Decision.Block, rules.decide("doubleclick.net"))
        assertEquals(FilterRules.Decision.Block, rules.decide("ad.g.doubleclick.net"))
        assertEquals(FilterRules.Decision.Block, rules.decide("badsite.example."))
        assertEquals(FilterRules.Decision.Block, rules.decide("plain-domain.test"))
        assertEquals(FilterRules.Decision.Forward, rules.decide("example.com"))
        assertEquals(FilterRules.Decision.Forward, rules.decide("net"))
        assertEquals(FilterRules.Decision.Forward, rules.decide("notdoubleclick.net"))
    }

    @Test fun blocksBypassDomains() {
        assertEquals(FilterRules.Decision.Block, rules.decide("use-application-dns.net"))
        assertEquals(FilterRules.Decision.Block, rules.decide("dns.google"))
        assertEquals(FilterRules.Decision.Block, rules.decide("mozilla.cloudflare-dns.com"))
    }

    @Test fun forcesSafeSearch() {
        val g = FilterRules.Decision.SafeSearch(FilterRules.GOOGLE_SAFE)
        assertEquals(g, rules.decide("www.google.com"))
        assertEquals(g, rules.decide("google.com"))
        assertEquals(g, rules.decide("www.google.co.uk"))
        assertEquals(g, rules.decide("www.google.com.au"))
        assertEquals(FilterRules.Decision.Forward, rules.decide("mail.google.com"))
        assertEquals(FilterRules.Decision.Forward, rules.decide("www.googleapis.com"))
        assertEquals(FilterRules.Decision.SafeSearch(FilterRules.BING_SAFE), rules.decide("www.bing.com"))
        assertEquals(
            FilterRules.Decision.SafeSearch(FilterRules.DUCKDUCKGO_SAFE), rules.decide("duckduckgo.com")
        )
        assertEquals(
            FilterRules.Decision.SafeSearch(FilterRules.YOUTUBE_SAFE), rules.decide("m.youtube.com")
        )
    }

    @Test fun buildsNxdomain() {
        val qb = query("doubleclick.net")
        val r = Dns.nxdomain(qb, Dns.parseQuestion(qb)!!)
        assertEquals(0x12, r[0].toInt()); assertEquals(0x34, r[1].toInt())
        assertEquals(0x81, r[2].toInt() and 0xFF) // QR + RD
        assertEquals(0x83, r[3].toInt() and 0xFF) // RA + NXDOMAIN
        assertEquals(0, Ipv4Udp.u16(r, 6))
        assertEquals(qb.size, r.size)
    }

    @Test fun buildsAAnswer() {
        val qb = query("www.google.com")
        val q = Dns.parseQuestion(qb)!!
        val ip = byteArrayOf(216.toByte(), 239.toByte(), 38, 120)
        val r = Dns.answerA(qb, q, listOf(ip), 300)
        assertEquals(1, Ipv4Udp.u16(r, 6))
        assertEquals(0xC00C, Ipv4Udp.u16(r, q.end))
        assertEquals(1, Ipv4Udp.u16(r, q.end + 2))
        assertEquals(300, Ipv4Udp.u16(r, q.end + 8))
        assertEquals(4, Ipv4Udp.u16(r, q.end + 10))
        assertArrayEquals(ip, r.copyOfRange(q.end + 12, q.end + 16))
    }

    @Test fun ipUdpRoundTripWithValidChecksums() {
        val src = byteArrayOf(10, 111, 222.toByte(), 1)
        val dst = byteArrayOf(10, 111, 222.toByte(), 2)
        val payload = query("example.com")
        val pkt = Ipv4Udp.build(src, dst, 40000, 53, payload)
        // A correct header checksums to zero.
        assertEquals(0, Ipv4Udp.checksum(pkt, 0, 20, 0))
        var pseudo = 0L
        for (i in 0 until 4 step 2) pseudo += Ipv4Udp.u16(src, i) + Ipv4Udp.u16(dst, i)
        pseudo += 17 + 8 + payload.size
        assertEquals(0, Ipv4Udp.checksum(pkt, 20, 8 + payload.size, pseudo))

        val parsed = Ipv4Udp.parse(pkt, pkt.size)
        assertNotNull(parsed)
        assertArrayEquals(src, parsed!!.srcIp)
        assertArrayEquals(dst, parsed.dstIp)
        assertEquals(40000, parsed.srcPort)
        assertEquals(53, parsed.dstPort)
        assertArrayEquals(payload, parsed.payload)
    }

    @Test fun ignoresNonUdpPackets() {
        val pkt = Ipv4Udp.build(ByteArray(4), ByteArray(4), 1, 53, ByteArray(20))
        pkt[9] = 6 // TCP
        assertNull(Ipv4Udp.parse(pkt, pkt.size))
        assertTrue(Ipv4Udp.parse(ByteArray(10), 10) == null)
    }
}
