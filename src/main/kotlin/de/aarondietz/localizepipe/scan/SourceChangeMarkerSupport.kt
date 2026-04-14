package de.aarondietz.localizepipe.scan

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object SourceChangeMarkerSupport {
    const val METADATA_FILE_NAME = "localizepipe-source-hashes.json"
    private const val HASH_HEX_LENGTH = 16

    fun computeSourceHash(baseText: String, localizePipeContext: String? = null): String {
        val normalizedContext = localizePipeContext?.trim()?.takeIf { it.isNotEmpty() }
        val hashInput = if (normalizedContext == null) {
            baseText
        } else {
            "$baseText\n<localizePipeContext>\n$normalizedContext"
        }
        val bytes = hashInput.toByteArray(StandardCharsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = buildString(digest.size * 2) {
            for (byte in digest) {
                append("%02x".format(byte))
            }
        }
        return hex.take(HASH_HEX_LENGTH)
    }
}
