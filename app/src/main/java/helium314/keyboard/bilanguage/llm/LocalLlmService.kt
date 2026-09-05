// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.bilanguage.llm

import android.app.Service
import android.content.Intent
import android.os.IBinder
import helium314.keyboard.bilanguage.BiLog

/**
 * Hosts the 398 MB model outside the latency-sensitive IME process. All Binder methods are called
 * from the engine's IO dispatcher and serialized again by the native runtime.
 */
class LocalLlmService : Service() {
    private lateinit var runtime: LocalLlmNative

    override fun onCreate() {
        super.onCreate()
        runtime = LocalLlmNative()
        BiLog.i("local LLM service created in separate app process")
    }

    private val binder = object : ILocalLlmService.Stub() {
        override fun loadModel(path: String): String? = runtime.loadModel(path)

        override fun translate(prompt: String, maxTokens: Int): String? =
            runtime.translate(prompt, maxTokens).getOrNull()

        override fun lastError(): String = runtime.lastError()

        override fun unload() = runtime.unload()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_UNLOAD) {
            runtime.unload()
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runtime.unload()
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
