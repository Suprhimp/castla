package com.castla.mirror.diagnostics

/**
 * Redacts URLs, intent extras, and shell command bodies from log messages
 * before they are persisted to disk. Pure object; safe to call concurrently.
 */
object DiagnosticSanitizer {

    private const val MAX_LEN = 500
    private val URL_REGEX = Regex("https?://[^\\s\"]+")
    private val EXTRA_REGEX = Regex("--es\\s+(\\w+)\\s+\\S+")
    private val EXECUTING_PREFIX = Regex("(Executing:\\s*).+", RegexOption.DOT_MATCHES_ALL)

    /** Returns scheme + host only (no path/query/fragment). Sentinel on parse failure. */
    fun redactUrl(url: String): String {
        if (url.isBlank()) return "<invalid-url>"
        return try {
            val u = java.net.URI(url)
            val scheme = u.scheme ?: return "<invalid-url>"
            val host = u.host ?: return "<invalid-url>"
            "$scheme://$host"
        } catch (_: Throwable) {
            "<invalid-url>"
        }
    }

    /** Strips PII-prone fragments and caps at [MAX_LEN] chars. Safe inputs pass through. */
    fun safeMessage(msg: String): String {
        if (msg.isEmpty()) return msg
        var s = msg
        // Order matters: handle "Executing:" first so its trailing command becomes <command-redacted>
        // BEFORE we attempt URL/extra redaction inside the now-removed body.
        s = EXECUTING_PREFIX.replace(s) { it.groupValues[1] + "<command-redacted>" }
        s = EXTRA_REGEX.replace(s) { "--es ${it.groupValues[1]} <redacted>" }
        s = URL_REGEX.replace(s) { redactUrl(it.value) }
        if (s.length > MAX_LEN) s = s.substring(0, MAX_LEN) + "…"
        return s
    }
}
