// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.bilanguage.llm

import androidx.annotation.Keep

/** Thin UTF-8-safe JNI boundary around the pinned llama.cpp library. */
@Keep
internal class LocalLlmNative {
    init {
        System.loadLibrary("keyboard_llm")
        nativeInit()
    }

    private external fun nativeInit()
    private external fun nativeLoadModel(path: String): Boolean
    private external fun nativeTranslate(promptUtf8: ByteArray, maxTokens: Int): ByteArray?
    private external fun nativeUnload()
    private external fun nativeLastError(): String

    fun loadModel(path: String): String? =
        if (nativeLoadModel(path)) null else nativeLastError().ifBlank { "Model load failed" }

    fun translate(prompt: String, maxTokens: Int): Result<String> {
        val output = nativeTranslate(prompt.encodeToByteArray(), maxTokens)
            ?: return Result.failure(IllegalStateException(nativeLastError().ifBlank { "Inference failed" }))
        return Result.success(output.decodeToString().trim())
    }

    fun unload() = nativeUnload()

    fun lastError(): String = nativeLastError()
}
