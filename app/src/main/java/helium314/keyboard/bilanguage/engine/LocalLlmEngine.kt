// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.bilanguage.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import helium314.keyboard.bilanguage.BiLog
import helium314.keyboard.bilanguage.llm.ILocalLlmService
import helium314.keyboard.bilanguage.llm.LocalLlmModelStore
import helium314.keyboard.bilanguage.llm.LocalLlmService
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Raw, deterministic EN↔FR completion through llama.cpp in a separate app process. */
class LocalLlmEngine(context: Context) : TranslationEngine {
    override val id = "local-llm"

    private val appContext = context.applicationContext
    @Volatile private var service: ILocalLlmService? = null
    @Volatile private var connection: ServiceConnection? = null
    @Volatile private var closed = false
    private val connectMutex = Mutex()

    override suspend fun supportedLanguages(): List<String> = SUPPORTED_LANGUAGES

    override suspend fun isPairReady(source: String, target: String): Boolean =
        supports(source, target) && LocalLlmModelStore.isInstalled(appContext)

    override fun preparePair(source: String, target: String): Flow<PrepareState> = flow {
        emit(PrepareState.Queued)
        when {
            !supports(source, target) -> emit(
                PrepareState.Failed("The local model supports only English and French", false)
            )
            !LocalLlmModelStore.isSupportedAbi() -> emit(
                PrepareState.Failed("Local LLM is unavailable on this device architecture", false)
            )
            LocalLlmModelStore.isInstalled(appContext) -> emit(PrepareState.Ready)
            else -> emit(PrepareState.Failed("Import the Q4_K_M GGUF in Translation settings", true))
        }
    }

    override suspend fun translate(
        text: String,
        source: String,
        target: String,
    ): Result<String> = runCatching {
        check(supports(source, target)) { "Local LLM supports only English ↔ French" }
        val model = LocalLlmModelStore.modelFile(appContext)
        check(LocalLlmModelStore.isInstalled(appContext)) { "Local GGUF model is not installed" }

        val remote = connect()
        remote.loadModel(model.absolutePath)?.let { throw IllegalStateException(it) }
        val output = remote.translate(promptFor(target, text), MAX_OUTPUT_TOKENS)
            ?: throw IllegalStateException(remote.lastError().ifBlank { "Local inference failed" })
        output.trim().also { check(it.isNotEmpty()) { "Local model produced no translation" } }
    }

    private suspend fun connect(): ILocalLlmService = connectMutex.withLock {
        check(!closed) { "Local LLM engine is closed" }
        service?.let { return@withLock it }
        suspendCancellableCoroutine { continuation ->
            val newConnection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    val connected = ILocalLlmService.Stub.asInterface(binder)
                    service = connected
                    if (continuation.isActive) {
                        continuation.resume(connected)
                    } else {
                        runCatching { appContext.unbindService(this) }
                        if (connection === this) connection = null
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    service = null
                    BiLog.w("local LLM service disconnected")
                }

                override fun onBindingDied(name: ComponentName?) {
                    service = null
                    BiLog.w("local LLM service binding died")
                }
            }
            connection = newConnection
            val bound = appContext.bindService(
                Intent(appContext, LocalLlmService::class.java),
                newConnection,
                Context.BIND_AUTO_CREATE,
            )
            if (!bound && continuation.isActive) {
                connection = null
                continuation.resumeWithException(IllegalStateException("Could not start local LLM service"))
            }
            continuation.invokeOnCancellation {
                if (service == null && connection === newConnection) {
                    runCatching { appContext.unbindService(newConnection) }
                    connection = null
                }
            }
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        service = null
        connection?.let {
            runCatching { appContext.unbindService(it) }
            connection = null
        }
    }

    companion object {
        val SUPPORTED_LANGUAGES = listOf("en", "fr")
        const val MAX_OUTPUT_TOKENS = 64

        fun promptFor(target: String, text: String): String {
            val language = target.substringBefore('-').substringBefore('_').lowercase(Locale.ROOT)
            val marker = when (language) {
                "fr" -> "FR"
                "en" -> "EN"
                else -> throw IllegalArgumentException("Unsupported local LLM target: $target")
            }
            return "$marker:\n${text.trim()}\n"
        }

        fun supports(source: String, target: String): Boolean {
            val from = source.substringBefore('-').substringBefore('_').lowercase(Locale.ROOT)
            val to = target.substringBefore('-').substringBefore('_').lowercase(Locale.ROOT)
            return from != to && from in SUPPORTED_LANGUAGES && to in SUPPORTED_LANGUAGES
        }
    }
}
