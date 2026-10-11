package app.nullpointr.foxnav.voice

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.nullpointr.foxnav.shared.voice.Phrases
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** A voice pack: recorded phrases ([app.nullpointr.foxnav.shared.voice.Phrases]), one WAV file each. */
class VoicePack(val id: String, val name: String, val directory: File) {
    fun clip(key: String): File? = File(directory, "$key.wav").takeIf { it.isFile && it.length() > WavClip.HEADER_BYTES }

    fun has(keys: List<String>): Boolean = keys.isNotEmpty() && keys.all { clip(it) != null }

    val recorded: Int get() = directory.listFiles { file -> file.extension == "wav" && file.length() > WavClip.HEADER_BYTES }?.size ?: 0
}

/** How an import went: the new pack (none if nothing in the file was usable) and how many phrases it got. */
data class PackImport(val pack: VoicePack?, val phrases: Int, val unreadable: Int)

/**
 * Voice packs on this phone, in `files/voices/<id>/` with a `voice.json`.
 *
 * Packs travel as zip files (docs/voice-packs.md): a clip per phrase named after it, like
 * `turn_left.mp3`, in any format the phone plays, and optionally a `voice.json` with the name.
 */
class VoicePacks(private val context: Context) {
    private val root = File(context.filesDir, "voices")
    private val _packs = MutableStateFlow(load())

    val packs: StateFlow<List<VoicePack>> = _packs.asStateFlow()

    fun find(id: String): VoicePack? = _packs.value.firstOrNull { it.id == id }

    fun create(name: String): VoicePack {
        val id = UUID.randomUUID().toString()
        val directory = File(root, id).apply { mkdirs() }
        writeManifest(directory, id, name.take(MAX_NAME))
        refresh()
        return find(id)!!
    }

    fun delete(pack: VoicePack) {
        pack.directory.deleteRecursively()
        refresh()
    }

    /** Where a phrase of [pack] is recorded to. */
    fun clipTarget(pack: VoicePack, key: String): File = File(pack.directory, "$key.wav")

    /** After recording or deleting clips: counts and completeness change. */
    fun refresh() {
        _packs.value = load()
    }

    /** Sets one phrase of [pack] from an audio file; false when the phone can't read it as sound. */
    suspend fun importClip(pack: VoicePack, key: String, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val clip = AudioDecoder.decode(context, uri, MAX_CLIP_SECONDS)?.let(WavClip::prepare)
        if (clip == null || clip.isEmpty()) return@withContext false
        WavClip.write(clipTarget(pack, key), clip)
        refresh()
        true
    }

    /** Fills phrases of [pack] from files named after them, like `turn_left.mp3`. Returns how many it took. */
    suspend fun importFiles(pack: VoicePack, uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        var count = 0
        for (uri in uris) {
            val key = displayName(uri)?.let(::phraseKeyOf) ?: continue
            val clip = AudioDecoder.decode(context, uri, MAX_CLIP_SECONDS)?.let(WavClip::prepare) ?: continue
            if (clip.isEmpty()) continue
            WavClip.write(clipTarget(pack, key), clip)
            count++
        }
        refresh()
        count
    }

    /** A new pack from a voice pack zip. */
    suspend fun importPack(uri: Uri): PackImport = withContext(Dispatchers.IO) {
        val fallbackName = displayName(uri)?.substringBeforeLast('.')?.ifBlank { null } ?: "Imported voice"
        val pack = create(fallbackName)
        val scratch = File(context.cacheDir, "voice-import").apply { mkdirs() }
        var phrases = 0
        var unreadable = 0
        var name: String? = null
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zip ->
                    var entries = 0
                    while (entries++ < MAX_ENTRIES) {
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory) continue
                        // Only the file name counts; folders in the zip don't, and never become paths here.
                        val fileName = entry.name.substringAfterLast('/').substringAfterLast('\\')
                        if (fileName == MANIFEST) {
                            name = runCatching { JSONObject(String(zip.readAtMost(MAX_MANIFEST_BYTES))).optString("name").trim().ifBlank { null } }.getOrNull()
                            continue
                        }
                        val key = phraseKeyOf(fileName) ?: continue
                        val temporary = File(scratch, "clip-$entries")
                        try {
                            if (!zip.copyAtMost(temporary, MAX_CLIP_BYTES)) {
                                unreadable++
                                continue
                            }
                            val clip = AudioDecoder.decode(temporary, MAX_CLIP_SECONDS)?.let(WavClip::prepare)
                            if (clip == null || clip.isEmpty()) {
                                unreadable++
                            } else {
                                WavClip.write(clipTarget(pack, key), clip)
                                phrases++
                            }
                        } finally {
                            temporary.delete()
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Not a zip, or a broken one: whatever was read so far stays.
        }
        if (phrases == 0) {
            delete(pack)
            return@withContext PackImport(null, 0, unreadable)
        }
        name?.let { writeManifest(pack.directory, pack.id, it.take(MAX_NAME)) }
        refresh()
        PackImport(find(pack.id), phrases, unreadable)
    }

    /** Writes [pack] as a zip that [importPack] reads back, on this phone or another. */
    suspend fun exportPack(pack: VoicePack, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(uri, "wt")!!.use { output ->
                ZipOutputStream(output).use { zip ->
                    zip.putNextEntry(ZipEntry(MANIFEST))
                    zip.write(JSONObject().put("format", 1).put("name", pack.name).toString(2).toByteArray())
                    zip.closeEntry()
                    Phrases.ALL.forEach { phrase ->
                        val clip = pack.clip(phrase.key) ?: return@forEach
                        zip.putNextEntry(ZipEntry("${phrase.key}.wav"))
                        clip.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
        }.isSuccess
    }

    private fun displayName(uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment

    private fun writeManifest(directory: File, id: String, name: String) {
        val created = runCatching { JSONObject(File(directory, MANIFEST).readText()).optLong("created") }.getOrNull()
        File(directory, MANIFEST).writeText(
            JSONObject().put("format", 1).put("id", id).put("name", name).put("created", created?.takeIf { it > 0 } ?: System.currentTimeMillis()).toString(),
        )
    }

    private fun load(): List<VoicePack> =
        root.listFiles().orEmpty().mapNotNull { directory ->
            runCatching {
                val manifest = JSONObject(File(directory, MANIFEST).readText())
                VoicePack(manifest.getString("id"), manifest.getString("name"), directory) to manifest.optLong("created")
            }.getOrNull()
        }.sortedBy { it.second }.map { it.first }

    private companion object {
        const val MANIFEST = "voice.json"
        const val MAX_NAME = 40
        const val MAX_CLIP_SECONDS = 10
        const val MAX_CLIP_BYTES = 8L shl 20
        const val MAX_MANIFEST_BYTES = 64 shl 10
        const val MAX_ENTRIES = 500
    }
}

private val phraseKeys = Phrases.ALL.map { it.key }.toSet()

/** The phrase a file is for, from its name: `turn_left.mp3`, `Turn left.ogg` and `turn-left.wav` are all "turn_left". */
fun phraseKeyOf(fileName: String): String? {
    val stem = fileName.substringBeforeLast('.').trim().lowercase().replace(Regex("[\\s-]+"), "_")
    return stem.takeIf { it in phraseKeys }
}

/** At most [limit] bytes of the current entry; more than that is a broken or hostile file. */
private fun InputStream.readAtMost(limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    require(copyLimited(out, limit.toLong())) { "Too large" }
    return out.toByteArray()
}

/** Copies the current entry to [target]; false when it's over [limit] bytes (a zip bomb, or not a clip). */
private fun InputStream.copyAtMost(target: File, limit: Long): Boolean = target.outputStream().use { copyLimited(it, limit) }

private fun InputStream.copyLimited(out: OutputStream, limit: Long): Boolean {
    var total = 0L
    val buffer = ByteArray(64 shl 10)
    while (true) {
        val read = read(buffer)
        if (read < 0) return true
        total += read
        if (total > limit) return false
        out.write(buffer, 0, read)
    }
}
