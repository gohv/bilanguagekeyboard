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
    private external fun nativeTranslate(
        prefixUtf8: ByteArray, restUtf8: ByteArray, maxTokens: Int, contextTokens: Int, chat: Boolean, requestId: Int,
    ): ByteArray?
    private external fun nativeCancel(requestId: Int)
    private external fun nativeChatTemplate(): String
    private external fun nativeUnload()
    private external fun nativeLastError(): String

    fun loadModel(path: String): String? =
        if (nativeLoadModel(path)) null else nativeLastError().ifBlank { "Model load failed" }

    /**
     * [prefix] is the part identical across calls (the instruction); its processed state is cached
     * natively and restored instead of recomputed. Empty disables the cache.
     */
    fun translate(prefix: String, rest: String, maxTokens: Int, contextTokens: Int, chat: Boolean, requestId: Int): Result<String> {
        val output = nativeTranslate(prefix.encodeToByteArray(), rest.encodeToByteArray(), maxTokens, contextTokens, chat, requestId)
            ?: return Result.failure(IllegalStateException(nativeLastError().ifBlank { "Inference failed" }))
        return Result.success(output.decodeToString().trim())
    }

    /** Thread-safe; returns at once. Aborts [requestId] only if it is still the running one. */
    fun cancel(requestId: Int) = nativeCancel(requestId)

    /** The loaded GGUF's chat template, or empty. */
    fun chatTemplate(): String = nativeChatTemplate()

    fun unload() = nativeUnload()

    fun lastError(): String = nativeLastError()
}
