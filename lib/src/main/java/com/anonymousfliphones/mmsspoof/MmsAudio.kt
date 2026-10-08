package com.anonymousfliphones.mmsspoof

import android.content.Context
import android.net.Uri

/**
 * Normalises MMS audio attachment MIME types and provides byte-level sniffing.
 *
 * The Verizon MMSC delivers MP3 voice notes as `audio/x-mpeg3`. Android's
 * MediaPlayer handles the codec, but the content-type trips up file-open
 * intents and extension inference. [normalizeMimeType] collapses all known
 * aliases to their canonical RFC type.
 */
object MmsAudio {

    private val ALIASES = mapOf(
        // AMR
        "audio/x-amr"    to "audio/amr",
        "audio/amr-nb"   to "audio/amr",
        "audio/x-amr-wb" to "audio/amr-wb",
        "audio/awb"      to "audio/amr-wb",
        // 3GPP
        "audio/3gp"      to "audio/3gpp",
        "audio/x-3gpp"   to "audio/3gpp",
        // MP4/AAC
        "audio/m4a"      to "audio/mp4",
        "audio/x-m4a"    to "audio/mp4",
        "audio/mp4a-latm" to "audio/mp4",
        "audio/aacp"     to "audio/aac",
        "audio/x-aac"    to "audio/aac",
        // MP3 — Verizon MMSC delivers MP3 as audio/x-mpeg3
        "audio/mp3"      to "audio/mpeg",
        "audio/x-mp3"    to "audio/mpeg",
        "audio/mpeg3"    to "audio/mpeg",
        "audio/x-mpeg3"  to "audio/mpeg",
        // WAV / OGG
        "audio/x-wav"    to "audio/wav",
        "audio/wave"     to "audio/wav",
        "application/ogg" to "audio/ogg"
    )

    private val EXTENSION_TO_MIME = mapOf(
        "amr"  to "audio/amr",
        "awb"  to "audio/amr-wb",
        "3ga"  to "audio/3gpp",
        "m4a"  to "audio/mp4",
        "aac"  to "audio/aac",
        "mp3"  to "audio/mpeg",
        "wav"  to "audio/wav",
        "ogg"  to "audio/ogg",
        "oga"  to "audio/ogg",
        "opus" to "audio/ogg"
    )

    private val MIME_TO_EXTENSION = mapOf(
        "audio/amr"    to "amr",
        "audio/amr-wb" to "awb",
        "audio/3gpp"   to "3gp",
        "audio/3gpp2"  to "3g2",
        "audio/mp4"    to "m4a",
        "audio/aac"    to "aac",
        "audio/mpeg"   to "mp3",
        "audio/wav"    to "wav",
        "audio/ogg"    to "ogg"
    )

    private val GENERIC_TYPES = setOf(
        "", "application/octet-stream", "application/unknown", "*/*"
    )

    /**
     * Returns the canonical MIME type for a received MMS audio part.
     *
     * - Known aliases (e.g. `audio/x-mpeg3`) collapse to their RFC counterpart.
     * - Generic types (`application/octet-stream`) fall back to extension-based lookup.
     * - Unknown types pass through unchanged.
     *
     * @param declared  The content-type header from the MMSC (may include params).
     * @param fileName  File name from the part, used only for generic-type fallback.
     */
    fun normalizeMimeType(declared: String, fileName: String = ""): String {
        val ct = declared.substringBefore(';').trim().lowercase()
        ALIASES[ct]?.let { return it }
        if (ct in GENERIC_TYPES) {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            EXTENSION_TO_MIME[ext]?.let { return it }
        }
        return ct
    }

    /**
     * Identifies an audio container from its leading [header] bytes.
     * Returns a canonical MIME type, or null if unrecognised.
     */
    fun sniffMimeType(header: ByteArray): String? {
        fun startsWith(prefix: String, offset: Int = 0): Boolean =
            header.size >= offset + prefix.length &&
                prefix.indices.all { header[offset + it] == prefix[it].code.toByte() }

        return when {
            startsWith("#!AMR-WB\n")    -> "audio/amr-wb"
            startsWith("#!AMR\n")       -> "audio/amr"
            startsWith("ftyp", 4)       -> when {
                startsWith("3g2", 8)   -> "audio/3gpp2"
                startsWith("3g", 8)    -> "audio/3gpp"
                else                   -> "audio/mp4"
            }
            startsWith("OggS")         -> "audio/ogg"
            startsWith("RIFF") && startsWith("WAVE", 8) -> "audio/wav"
            startsWith("ID3")          -> "audio/mpeg"
            header.size >= 2 && header[0] == 0xFF.toByte() &&
                (header[1].toInt() and 0xF6) == 0xF0 -> "audio/aac"
            header.size >= 2 && header[0] == 0xFF.toByte() &&
                (header[1].toInt() and 0xE0) == 0xE0 -> "audio/mpeg"
            else                       -> null
        }
    }

    /** File extension for [mimeType], or null. */
    fun extensionFor(mimeType: String): String? = MIME_TO_EXTENSION[mimeType.lowercase()]

    /** True when [mimeType] is an audio type. */
    fun isAudio(mimeType: String): Boolean = mimeType.startsWith("audio/")

    /** Appends the appropriate extension to [fileName] when it has none. */
    fun withExtension(fileName: String, mimeType: String): String {
        if (fileName.contains('.')) return fileName
        return extensionFor(mimeType)?.let { "$fileName.$it" } ?: fileName
    }

    /**
     * Copies the MMS part at [contentUri] to a cache file, sniffing its true type
     * from the leading bytes. Returns the cached file, or null on failure.
     *
     * Must be called off the main thread.
     *
     * @param context      Used for [Context.getCacheDir] and ContentResolver.
     * @param contentUri   `content://mms/part/<id>` URI from the MMS database.
     * @param declaredMime Content-type stored in the MMS database part row.
     * @param fileName     File name stored in the MMS database part row.
     * @param cacheDir     Subdirectory name under [Context.getCacheDir]. Default: `mms_audio`.
     */
    fun cacheFile(
        context: Context,
        contentUri: Uri,
        declaredMime: String,
        fileName: String,
        cacheDir: String = "mms_audio"
    ): java.io.File? {
        val partId = contentUri.lastPathSegment?.toLongOrNull() ?: return null
        val dir = java.io.File(context.cacheDir, cacheDir)
        dir.listFiles { f -> f.name.startsWith("part_$partId.") && f.length() > 0 }
            ?.firstOrNull()?.let { return it }

        return runCatching {
            dir.mkdirs()
            val tmp = java.io.File(dir, "part_$partId.tmp")
            context.contentResolver.openInputStream(contentUri)?.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            } ?: return null

            val header = ByteArray(16)
            val read = tmp.inputStream().use { it.read(header) }.coerceAtLeast(0)
            val mime = sniffMimeType(header.copyOf(read))
                ?: normalizeMimeType(declaredMime, fileName)
            val target = java.io.File(dir, "part_$partId.${extensionFor(mime) ?: "bin"}")
            if (!tmp.renameTo(target)) { tmp.delete(); return null }
            target
        }.getOrNull()
    }
}
