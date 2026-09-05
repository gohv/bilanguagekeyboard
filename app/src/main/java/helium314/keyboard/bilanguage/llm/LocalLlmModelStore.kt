// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.bilanguage.llm

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.UserManager
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Owns the one supported, user-imported GGUF without ever persisting typed text. */
object LocalLlmModelStore {
    const val DISPLAY_NAME = "keyboard-translator-en-fr-Q4_K_M.gguf"
    const val EXPECTED_BYTES = 397_807_424L
    const val EXPECTED_SHA256 = "74adde7ba961f85b0bbc31a7061bf2375f87015207fe7cf82273a54c13f148e9"

    fun isSupportedAbi(): Boolean = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "x86_64" }

    fun isUserUnlocked(context: Context): Boolean =
        context.getSystemService(UserManager::class.java)?.isUserUnlocked == true

    fun modelFile(context: Context): File {
        return File(File(context.applicationContext.filesDir, "translation-models"), DISPLAY_NAME)
    }

    fun isInstalled(context: Context): Boolean = modelFile(context).let {
        isUserUnlocked(context) && it.isFile && it.length() == EXPECTED_BYTES
    }

    fun delete(context: Context): Boolean {
        val file = modelFile(context)
        return !file.exists() || file.delete()
    }

    /** Copies and hashes before atomically replacing the installed model. */
    fun import(context: Context, source: Uri, onProgress: (Float) -> Unit = {}): Result<File> = runCatching {
        check(isSupportedAbi()) { "Local LLM requires an arm64-v8a phone (or x86-64 emulator)" }
        check(isUserUnlocked(context)) { "Unlock the phone before importing the model" }

        val target = modelFile(context)
        target.parentFile?.mkdirs()
        val partial = File(target.parentFile, "${target.name}.part")
        partial.delete()
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        try {
            context.contentResolver.openInputStream(source).use { input ->
                requireNotNull(input) { "Could not open the selected file" }
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        copied += count
                        onProgress((copied.toDouble() / EXPECTED_BYTES).coerceIn(0.0, 1.0).toFloat())
                    }
                    output.fd.sync()
                }
            }
            require(copied == EXPECTED_BYTES) {
                "Wrong file size: expected $EXPECTED_BYTES bytes, got $copied"
            }
            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            require(sha256 == EXPECTED_SHA256) {
                "Checksum mismatch. Select the project's Q4_K_M GGUF."
            }
            check(partial.renameTo(target)) { "Could not install the model in private storage" }
            onProgress(1f)
            target
        } catch (error: Throwable) {
            partial.delete()
            throw error
        }
    }
}
