package com.imageguard.app

/** Decides what to do with each website lookup. Pure Kotlin, unit-tested. */
class FilterRules(private val blocked: Set<String>) {

    sealed class Decision {
        object Block : Decision()
        /** Answer with the address of the search engine's forced-SafeSearch host. */
        data class SafeSearch(val host: String) : Decision()
        object Forward : Decision()
    }

    fun decide(rawName: String): Decision {
        val name = rawName.lowercase().trimEnd('.')
        safeSearchHost(name)?.let { return Decision.SafeSearch(it) }
        if (isBlocked(name)) return Decision.Block
        return Decision.Forward
    }

    /** True if the name or any parent domain is on a blocklist. */
    fun isBlocked(name: String): Boolean {
        var n = name
        while (true) {
            if (n in ALWAYS_BLOCKED || n in blocked) return true
            val dot = n.indexOf('.')
            if (dot < 0 || dot == n.length - 1) return false
            n = n.substring(dot + 1)
            if (!n.contains('.')) return false // don't match bare TLDs like "com"
        }
    }

    companion object {
        private val GOOGLE = Regex("^(www\\.)?google(\\.[a-z]{2,3}){1,2}$")
        private val BING = setOf("bing.com", "www.bing.com")
        private val DUCKDUCKGO = setOf("duckduckgo.com", "www.duckduckgo.com", "start.duckduckgo.com")
        private val YOUTUBE = setOf(
            "www.youtube.com", "m.youtube.com", "youtube.com",
            "youtubei.googleapis.com", "youtube.googleapis.com", "www.youtube-nocookie.com",
        )

        const val GOOGLE_SAFE = "forcesafesearch.google.com"
        const val BING_SAFE = "strict.bing.com"
        const val DUCKDUCKGO_SAFE = "safe.duckduckgo.com"
        const val YOUTUBE_SAFE = "restrictmoderate.youtube.com"

        /**
         * Blocked regardless of the blocklist:
         *  - Firefox's canary domain, which makes Firefox turn off its own encrypted DNS;
         *  - well-known encrypted-DNS (DoH) servers that apps could use to get around the filter;
         *  - search engines that can't be forced into SafeSearch.
         */
        val ALWAYS_BLOCKED = setOf(
            "use-application-dns.net",
            "dns.google", "dns.google.com", "cloudflare-dns.com", "one.one.one.one",
            "dns.quad9.net", "dns9.quad9.net", "dns10.quad9.net", "dns11.quad9.net",
            "doh.opendns.com", "dns.nextdns.io", "doh.cleanbrowsing.org", "dns.adguard.com",
            "dns.adguard-dns.com", "doh.dns.sb", "dns.mullvad.net", "doh.mullvad.net",
            "dns.controld.com", "freedns.controld.com",
        )

        fun safeSearchHost(name: String): String? = when {
            GOOGLE.matches(name) -> GOOGLE_SAFE
            name in BING -> BING_SAFE
            name in DUCKDUCKGO -> DUCKDUCKGO_SAFE
            name in YOUTUBE -> YOUTUBE_SAFE
            else -> null
        }

        /** Parses a hosts file or plain domain list into a set of domains. */
        fun parseList(lines: Sequence<String>): HashSet<String> {
            val out = HashSet<String>(200_000)
            for (raw in lines) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val parts = line.split(' ', '\t').filter { it.isNotEmpty() }
                val domain = (if (parts.size >= 2) parts[1] else parts[0]).lowercase()
                if (domain.contains('.') && domain != "localhost" && !domain.endsWith(".localdomain")) {
                    out += domain
                }
            }
            return out
        }
    }
}
