package io.github.mangi.eta.data.provider

/**
 * 本地/局域网端点判定。
 *
 * Ollama、LM Studio、llama.cpp、one-api 这类本地服务通常不校验 Key，
 * 因此对回环、RFC1918、.local 与 Tailscale 网段放行空 Key；公网地址仍强制要求。
 */
internal fun String.isLocalEndpoint(): Boolean {
    val host = runCatching { java.net.URI(this).host }
        .getOrNull()
        ?.lowercase()
        ?: return false
    if (host == "localhost" || host == "127.0.0.1" || host == "0.0.0.0" || host == "::1") return true
    if (host.endsWith(".local")) return true
    val parts = host.split('.')
    if (parts.size == 4) {
        val a = parts[0].toIntOrNull() ?: return false
        val b = parts[1].toIntOrNull() ?: return false
        if (a == 10) return true
        if (a == 192 && b == 168) return true
        if (a == 172 && b in 16..31) return true
        if (a == 100 && b in 64..127) return true
    }
    return false
}
