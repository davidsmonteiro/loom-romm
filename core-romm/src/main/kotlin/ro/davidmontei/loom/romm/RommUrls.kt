package ro.davidmontei.loom.romm

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** URL rules for talking to a RomM server. */
object RommUrls {

    /**
     * Turns what the user typed into base URLs to try, in order. A scheme the user typed is kept.
     * Without one, IP literals, `localhost`, single-label names and `.local`/`.lan`/`.ts.net`
     * hosts try `http://` first (LAN / Tailnet); anything else tries `https://` first.
     * Every result ends with "/" and keeps any sub-path (RomM behind a path prefix).
     */
    fun candidates(input: String): List<HttpUrl> {
        val text = input.trim().trimEnd('/')
        if (text.isEmpty()) return emptyList()
        if (text.contains("://")) return listOfNotNull(normalize(text))
        val host = text.substringBefore('/').substringBefore(':')
        val schemes = if (prefersHttp(host)) listOf("http", "https") else listOf("https", "http")
        return schemes.mapNotNull { normalize("$it://$text") }
    }

    /** Parses [raw] as an http(s) base URL ending in "/", dropping query and fragment. */
    fun normalize(raw: String): HttpUrl? {
        val url = raw.trim().toHttpUrlOrNull() ?: return null
        val path = url.encodedPath.trimEnd('/') + "/"
        return url.newBuilder().encodedPath(path).query(null).fragment(null).build()
    }

    /** Host part of the hosts Loom will send its token to. Comparison is case-insensitive. */
    fun sameServer(a: HttpUrl, b: HttpUrl): Boolean =
        a.host.equals(b.host, ignoreCase = true) && a.port == b.port

    /** True for addresses where plain HTTP is expected: private ranges, CGNAT (Tailscale), loopback. */
    fun isPrivateHost(host: String): Boolean {
        val h = host.lowercase()
        if (h == "localhost" || !h.contains('.') || h.endsWith(".local") || h.endsWith(".lan") ||
            h.endsWith(".ts.net") || h.endsWith(".home.arpa")
        ) return true
        val octets = h.split('.').map { it.toIntOrNull() ?: return false }
        if (octets.size != 4) return false
        val (a, b) = octets
        return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b in 16..31) ||
            (a == 100 && b in 64..127) || (a == 169 && b == 254)
    }

    private fun prefersHttp(host: String): Boolean =
        isPrivateHost(host) || host.split('.').let { p -> p.size == 4 && p.all { it.toIntOrNull() != null } }

    /**
     * Download URL for one ROM file. RomM ignores the trailing file-name segment (it derives the
     * name and type from its database), so an ASCII placeholder avoids encoding problems with
     * `#`, `%`, `?` and non-ASCII names. Stored relative so a job can switch server addresses.
     */
    fun romFileContentPath(romFileId: Int): String = "api/roms/$romFileId/files/content/file"

    /** Resolves a relative API path (as stored in a download job) against the active base URL. */
    fun resolve(base: HttpUrl, relativePath: String): HttpUrl =
        base.resolve(relativePath) ?: error("Cannot resolve $relativePath against $base")

    /**
     * Absolute cover URL from a ROM's `path_cover_small`/`path_cover_large`, which RomM returns
     * origin-relative with a cache-busting `?ts=` holding an unencoded timestamp
     * ("2026-10-10 15:16:10.123+00:00"). The query is re-encoded properly; null when there is no
     * cover (RomM sends an empty string).
     */
    fun coverUrl(base: HttpUrl, path: String?): HttpUrl? {
        if (path.isNullOrBlank()) return null
        if (path.startsWith("http://") || path.startsWith("https://")) return path.toHttpUrlOrNull()
        val rawPath = path.substringBefore('?')
        val ts = path.substringAfter('?', "").split('&')
            .firstOrNull { it.startsWith("ts=") }?.removePrefix("ts=")
        val origin = base.newBuilder().encodedPath("/").query(null).build()
        val builder = origin.newBuilder().encodedPath(if (rawPath.startsWith("/")) rawPath else "/$rawPath")
        if (!ts.isNullOrEmpty()) builder.addQueryParameter("ts", ts)
        return builder.build()
    }

    /**
     * RomM's Socket.IO endpoint for devices, over a WebSocket with Engine.IO v4, the only version RomM
     * takes. It is resolved against the base URL like the API, as RomM's own Android client does, so a
     * RomM behind a sub-path keeps it; for a RomM at the root it is `/ws/socket.io/`. The token is never
     * put in it.
     */
    fun deviceSocketUrl(base: HttpUrl): HttpUrl = resolve(base, "ws/socket.io/").newBuilder()
        .addQueryParameter("EIO", "4")
        .addQueryParameter("transport", "websocket")
        .build()

    /** Absolute URL of a device-auth verification path, for the QR code and the on-screen link. */
    fun verificationUrl(base: HttpUrl, path: String): HttpUrl =
        base.resolve(path.removePrefix("/")) ?: base
}
