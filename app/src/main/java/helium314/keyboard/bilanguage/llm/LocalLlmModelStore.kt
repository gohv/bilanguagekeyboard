// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.bilanguage.llm

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.core.content.edit
import helium314.keyboard.bilanguage.BiLog
import helium314.keyboard.latin.utils.prefs
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * The GGUF models the keyboard can use: every `.gguf` in [modelsDir], the app's own folder on shared
 * storage (`Android/data/<package>/files/models`). Drop a file there, e.g. with `adb push`, and it is
 * listed; one is active. Files picked elsewhere on the phone are copied in once, because the model
 * process needs a real path it may open: Android refuses llama.cpp re-opening a picker-granted file
 * through `/proc/self/fd`.
 *
 * Nothing about a model is hardcoded. What the keyboard needs is read from the GGUF header and
 * cached per file: name, architecture, and whether it carries a chat template. A model with one is
 * prompted as an instruction model (all languages); a model without one gets the raw `FR:` / `EN:`
 * completion prompt of the project's fine-tuned English ↔ French model.
 */
object LocalLlmModelStore {

    @Serializable
    data class Model(
        val path: String,
        val fileName: String,
        val modelName: String? = null,
        val architecture: String? = null,
        val bytes: Long = -1,
        val modified: Long = 0,
        val chat: Boolean = true,
    ) {
        val label get() = modelName?.takeIf { it.isNotBlank() } ?: fileName
    }

    private const val PREF_CACHE = "blk_local_models"
    private const val PREF_ACTIVE = "blk_local_model_active"
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 64-bit, and on ARM the instructions the native library is compiled for (dotprod, i8mm, fp16):
     * without them the model process would die with SIGILL. Roughly every phone from 2022 on.
     */
    fun isSupportedAbi(): Boolean = supported

    private val supported: Boolean by lazy {
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "x86_64" } ?: return@lazy false
        if (abi == "x86_64") return@lazy true
        val features = runCatching {
            File("/proc/cpuinfo").readLines().firstOrNull { it.startsWith("Features") }?.substringAfter(':')
                ?.trim()?.split(' ')?.toSet()
        }.getOrNull() ?: return@lazy true // unreadable: do not block, the model process is isolated anyway
        listOf("asimddp", "i8mm", "asimdhp").all { it in features }
    }

    fun modelsDir(context: Context): File? =
        context.applicationContext.getExternalFilesDir("models")?.also { it.mkdirs() }

    /** Every GGUF in the folder, headers read once per file and cached by size and mtime. */
    @Synchronized
    fun models(context: Context): List<Model> {
        val files = modelsDir(context)?.listFiles { f -> f.isFile && f.name.endsWith(".gguf", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase() } ?: return emptyList()
        val cached = cache(context).associateBy { it.path }
        var changed = cached.size != files.size
        val result = files.mapNotNull { file ->
            cached[file.path]?.takeIf { it.bytes == file.length() && it.modified == file.lastModified() }
                ?: runCatching { describe(file) }.onFailure {
                    BiLog.w("skipping ${file.name}: ${it.message}")
                }.getOrNull()?.also { changed = true }
        }
        if (changed) context.prefs().edit { putString(PREF_CACHE, json.encodeToString(result)) }
        return result
    }

    /** Cheap: the cached list only, for calls on the keyboard's hot path. */
    private fun cache(context: Context): List<Model> =
        context.prefs().getString(PREF_CACHE, null)
            ?.let { runCatching { json.decodeFromString<List<Model>>(it) }.getOrNull() } ?: emptyList()

    fun active(context: Context): Model? {
        val all = cache(context).filter { File(it.path).isFile }
        val id = context.prefs().getString(PREF_ACTIVE, null)
        return all.firstOrNull { it.path == id } ?: all.firstOrNull()
    }

    fun setActive(context: Context, model: Model) =
        context.prefs().edit { putString(PREF_ACTIVE, model.path) }

    fun isInstalled(context: Context): Boolean = active(context) != null

    fun isActiveChat(context: Context): Boolean = active(context)?.chat ?: true

    private fun describe(file: File): Model {
        val header = file.inputStream().use { readHeader(it) }
        require(header.architecture?.contains("embedding") != true && header.architecture?.contains("bert") != true) {
            "${header.architecture} is an embedding model and cannot translate"
        }
        return Model(file.path, file.name, header.name, header.architecture, file.length(), file.lastModified(), header.hasChatTemplate)
    }

    /** Copy a GGUF picked elsewhere into the folder and make it active. */
    fun add(context: Context, uri: Uri, onProgress: (Float) -> Unit = {}): Result<Model> = runCatching {
        check(isSupportedAbi()) { "Local LLM needs a 64-bit phone with dot-product and int8 matrix instructions (most phones from 2022 on)" }
        val dir = checkNotNull(modelsDir(context)) { "Shared storage is not available" }
        val resolver = context.contentResolver
        var fileName = "model.gguf"
        var total = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { fileName = it.substringAfterLast('/') }
                if (!c.isNull(1)) total = c.getLong(1)
            }
        }
        if (!fileName.endsWith(".gguf", ignoreCase = true)) fileName += ".gguf"
        val target = File(dir, fileName)
        val partial = File(dir, "$fileName.part")
        try {
            requireNotNull(resolver.openInputStream(uri)) { "Could not open the selected file" }.use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    var copied = 0L
                    var lastPercent = -1
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (copied == 0L) require(count >= 4 && String(buffer, 0, 4, Charsets.US_ASCII) == "GGUF") { "Not a GGUF file" }
                        output.write(buffer, 0, count)
                        copied += count
                        if (total > 0 && (copied * 100 / total).toInt() != lastPercent) {
                            lastPercent = (copied * 100 / total).toInt()
                            onProgress(copied.toFloat() / total)
                        }
                    }
                    output.fd.sync()
                }
            }
            val model = describe(partial).let { partial }
            target.delete()
            check(model.renameTo(target)) { "Could not move the model into place" }
        } catch (e: Throwable) {
            partial.delete()
            throw e
        }
        val added = models(context).first { it.path == target.path }
        setActive(context, added)
        BiLog.i("local model added: arch=${added.architecture} chat=${added.chat} bytes=${added.bytes}")
        added
    }

    /** Delete a model file from the folder. */
    fun remove(context: Context, model: Model): Boolean {
        val ok = File(model.path).delete()
        if (context.prefs().getString(PREF_ACTIVE, null) == model.path) context.prefs().edit { remove(PREF_ACTIVE) }
        models(context)
        return ok
    }

    data class Header(val name: String?, val architecture: String?, val hasChatTemplate: Boolean)

    /**
     * Minimal GGUF metadata reader: magic, version, counts, then key/value pairs. Values that are not
     * needed are skipped without being kept, so a chat template of any size costs nothing.
     */
    fun readHeader(input: InputStream): Header {
        val data = DataInputStream(input.buffered(64 * 1024))
        val magic = ByteArray(4).also { data.readFully(it) }
        require(String(magic, Charsets.US_ASCII) == "GGUF") { "Not a GGUF file" }
        val version = data.leInt()
        require(version >= 2) { "GGUF version $version is too old" }
        data.leLong() // tensor count
        val kvCount = data.leLong()
        var name: String? = null
        var arch: String? = null
        var chat = false
        for (i in 0 until kvCount) {
            val key = data.ggufString()
            val type = data.leInt()
            when {
                key == "general.name" && type == TYPE_STRING -> name = data.ggufString()
                key == "general.architecture" && type == TYPE_STRING -> arch = data.ggufString()
                key == "tokenizer.chat_template" && type == TYPE_STRING -> {
                    chat = data.skipString() > 0
                }
                else -> data.skipValue(type)
            }
            // Everything needed sits in the metadata; stop before the tensor info.
            if (name != null && arch != null && chat) break
        }
        return Header(name, arch, chat)
    }

    private const val TYPE_STRING = 8
    private const val TYPE_ARRAY = 9
    private val FIXED_SIZES = mapOf(0 to 1, 1 to 1, 2 to 2, 3 to 2, 4 to 4, 5 to 4, 6 to 4, 7 to 1, 10 to 8, 11 to 8, 12 to 8)

    private fun DataInputStream.leInt(): Int = Integer.reverseBytes(readInt())
    private fun DataInputStream.leLong(): Long = java.lang.Long.reverseBytes(readLong())

    private fun DataInputStream.ggufString(): String {
        val length = leLong()
        require(length in 0..1_000_000) { "Corrupt GGUF metadata" }
        return ByteArray(length.toInt()).also { readFully(it) }.toString(Charsets.UTF_8)
    }

    private fun DataInputStream.skipString(): Long = leLong().also { skipFully(it) }

    private fun DataInputStream.skipValue(type: Int) {
        when (type) {
            TYPE_STRING -> skipString()
            TYPE_ARRAY -> {
                val elementType = leInt()
                val count = leLong()
                val fixed = FIXED_SIZES[elementType]
                if (fixed != null) skipFully(count * fixed) else for (j in 0 until count) skipValue(elementType)
            }
            else -> skipFully((FIXED_SIZES[type] ?: error("Unknown GGUF value type $type")).toLong())
        }
    }

    private fun DataInputStream.skipFully(n: Long) {
        var left = n
        while (left > 0) {
            val skipped = skip(left)
            if (skipped <= 0) { readByte(); left-- } else left -= skipped
        }
    }
}
