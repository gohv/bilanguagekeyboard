// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.bilanguage.llm

import android.app.Service
import android.content.Intent
import android.os.IBinder
import helium314.keyboard.bilanguage.BiLog

/**
 * Hosts the model outside the latency-sensitive IME process. All Binder methods are called
 * from the engine's IO dispatcher and serialized again by the native runtime.
 */
class LocalLlmService : Service() {
    private lateinit var runtime: LocalLlmNative
    private var loadedPath: String? = null

    override fun onCreate() {
        super.onCreate()
        runtime = LocalLlmNative()
        BiLog.i("local LLM service created in separate app process")
    }

    /** Null on success, otherwise the reason. A different path unloads the previous model first. */
    @Synchronized
    private fun load(path: String): String? {
        if (path == loadedPath) return null
        release()
        if (!java.io.File(path).canRead()) return "Cannot read the model file. Was it deleted?"
        val error = runtime.loadModel(path)
        if (error == null) {
            loadedPath = path
            BiLog.i("local model loaded")
        }
        return error
    }

    @Synchronized
    private fun release() {
        runtime.unload()
        loadedPath = null
    }

    private val binder = object : ILocalLlmService.Stub() {
        override fun loadModel(path: String): String? = load(path)

        override fun generate(
            prefix: String, rest: String, maxTokens: Int, contextTokens: Int, chat: Boolean, requestId: Int,
        ): String? = runtime.translate(prefix, rest, maxTokens, contextTokens, chat, requestId).getOrNull()

        override fun cancel(requestId: Int) = runtime.cancel(requestId)

        override fun chatTemplate(): String = runtime.chatTemplate()

        override fun lastError(): String = runtime.lastError()

        override fun unload() = release()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_UNLOAD) {
            release()
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        release()
        BiLog.i("local LLM service destroyed and model unloaded")
        super.onDestroy()
    }

    companion object {
        private const val ACTION_UNLOAD =
            "helium314.keyboard.bilanguage.llm.action.UNLOAD"

        fun requestUnload(context: android.content.Context) {
            runCatching {
                context.startService(
                    Intent(context, LocalLlmService::class.java).setAction(ACTION_UNLOAD)
                )
            }.onFailure { BiLog.w("could not request local LLM unload: ${it.javaClass.simpleName}") }
        }
    }
}
