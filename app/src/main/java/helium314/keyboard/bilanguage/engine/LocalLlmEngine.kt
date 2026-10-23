// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.bilanguage.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.google.mlkit.nl.translate.TranslateLanguage
import helium314.keyboard.bilanguage.BiLog
import helium314.keyboard.bilanguage.BiPrefs
import helium314.keyboard.bilanguage.cloud.Register
import helium314.keyboard.bilanguage.llm.ILocalLlmService
import helium314.keyboard.bilanguage.llm.LocalLlmModelStore
import helium314.keyboard.bilanguage.llm.LocalLlmService
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * llama.cpp in a separate app process. Two prompt styles, chosen by what was imported: the
 * fine-tuned EN↔FR model gets its raw training prompt; any other GGUF is an instruction model and
 * gets the same translation instruction as the cloud engine, wrapped in its own chat template.
 */
class LocalLlmEngine(context: Context) : TranslationEngine {
    override val id = "local-llm"

    private val appContext = context.applicationContext
    @Volatile private var service: ILocalLlmService? = null
    @Volatile private var connection: ServiceConnection? = null
    @Volatile private var closed = false
    @Volatile private var chatTemplate: String? = null
    @Volatile private var loadedPath: String? = null
    private val nextRequestId = java.util.concurrent.atomic.AtomicInteger()
    private val connectMutex = Mutex()

    private val isChat get() = LocalLlmModelStore.isActiveChat(appContext)

    override suspend fun supportedLanguages(): List<String> = supportedLanguages(appContext)

    private fun supportsPair(source: String, target: String) =
        if (isChat) !sameLanguage(source, target) else supports(source, target)

    override suspend fun isPairReady(source: String, target: String): Boolean {
        // Rescan the folder (cheap unless a file is new): picks up models pushed with adb.
        withContext(Dispatchers.IO) { LocalLlmModelStore.models(appContext) }
        return supportsPair(source, target) && LocalLlmModelStore.isInstalled(appContext)
    }

    override fun preparePair(source: String, target: String): Flow<PrepareState> = flow {
        emit(PrepareState.Queued)
        when {
            !supportsPair(source, target) -> emit(
                PrepareState.Failed("The local model supports only English and French", false)
            )
            !LocalLlmModelStore.isSupportedAbi() -> emit(
                PrepareState.Failed("Local LLM is unavailable on this device architecture", false)
            )
            LocalLlmModelStore.isInstalled(appContext) -> emit(PrepareState.Ready)
            else -> emit(PrepareState.Failed("Add a GGUF model in Translation settings", true))
        }
    }

    override suspend fun translate(
        text: String,
        source: String,
        target: String,
    ): Result<String> = runCatching {
        check(supportsPair(source, target)) { "This model has no chat template, so only English ↔ French works" }
        check(LocalLlmModelStore.isInstalled(appContext)) { "Add a GGUF model in Translation settings" }
        val model = LocalLlmModelStore.active(appContext)!!

        val remote = loadActive(model)
        val requestId = nextRequestId.incrementAndGet()
        val output = if (isChat) {
            val template = chatTemplate ?: (remote.chatTemplate() as String?).orEmpty().also { chatTemplate = it }
            val register = BiPrefs.register(appContext).takeIf { Register.appliesTo(target) }
            val system = CloudLlmEngine.systemPrompt(source, target, register)
            val (prefix, rest) = chatPromptParts(template, system, text.trim())
            generateCancellable(remote, requestId) {
                remote.generate(prefix, rest, CHAT_MAX_OUTPUT_TOKENS, CHAT_CONTEXT_TOKENS, true, requestId)
            }?.let(::cleanChatOutput)
        } else {
            generateCancellable(remote, requestId) {
                remote.generate("", promptFor(target, text), MAX_OUTPUT_TOKENS, 0, false, requestId)
            }
        } ?: throw IllegalStateException((remote.lastError() as String?).orEmpty().ifBlank { "Local inference failed" })
        output.trim().also { check(it.isNotEmpty()) { "Local model produced no translation" } }
    }

    private suspend fun loadActive(model: LocalLlmModelStore.Model): ILocalLlmService {
        val remote = connect()
        if (model.path != loadedPath) chatTemplate = null
        remote.loadModel(model.path)?.let { throw IllegalStateException(it) }
        loadedPath = model.path
        return remote
    }

    /**
     * Run a blocking generation so that cancelling the coroutine (a newer keystroke, the timeout)
     * actually stops the model: otherwise the native run continues and the next request queues
     * behind it. The cancel is sent only on real cancellation, and carries the request id so it can
     * never abort a later request.
     */
    private suspend fun generateCancellable(remote: ILocalLlmService, requestId: Int, block: () -> String?): String? =
        coroutineScope {
            val outer = coroutineContext[Job]
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    if (outer?.isCancelled == true) runCatching { remote.cancel(requestId) }
                }
            }
            try {
                runInterruptible(Dispatchers.IO) { block() }
            } finally {
                watcher.cancel()
            }
        }

    /** Load the model ahead of the first translation, e.g. when the translate box opens. */
    override suspend fun warmUp() {
        if (!LocalLlmModelStore.isInstalled(appContext)) return
        val model = LocalLlmModelStore.active(appContext) ?: return
        runCatching { withContext(Dispatchers.IO) { loadActive(model) } }
            .onFailure { BiLog.w("local model warm-up failed: ${it.message}") }
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
                    chatTemplate = null
                    loadedPath = null
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
        const val CHAT_MAX_OUTPUT_TOKENS = 384
        const val CHAT_CONTEXT_TOKENS = 1536

        /** Fine-tuned model: EN and FR only. A chat model: everything the pair picker offers. */
        fun supportedLanguages(context: Context): List<String> =
            if (LocalLlmModelStore.isActiveChat(context)) TranslateLanguage.getAllLanguages()
            else SUPPORTED_LANGUAGES

        /**
         * Wrap the instruction and text in the model's own turn format, recognised from its embedded
         * template. Thinking is never enabled. Unknown templates get ChatML, which most fine-tunes
         * of recent bases understand.
         */
        /**
         * The chat prompt split where the user's text begins: the first part is identical on every
         * call with the same instruction, so its processed state is cached by the native runtime.
         */
        fun chatPromptParts(template: String, system: String, user: String): Pair<String, String> = when {
            "<|turn>" in template -> // Gemma 4
                "<|turn>system\n$system<turn|>\n<|turn>user\n" to "$user<turn|>\n<|turn>model\n"
            "<start_of_turn>" in template -> // Gemma 2/3: no system role, merged into the user turn
                "<start_of_turn>user\n$system\n\n" to "$user<end_of_turn>\n<start_of_turn>model\n"
            "<|start_header_id|>" in template -> // Llama 3
                "<|start_header_id|>system<|end_header_id|>\n\n$system<|eot_id|>" +
                    "<|start_header_id|>user<|end_header_id|>\n\n" to
                    "$user<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n"
            else -> // ChatML (Qwen, many others). A template that knows <think> gets an empty one, which
                // is how Qwen-family models are told "no reasoning, answer now".
                "<|im_start|>system\n$system<|im_end|>\n<|im_start|>user\n" to
                    "$user<|im_end|>\n<|im_start|>assistant\n" + if ("<think>" in template) "<think>\n\n</think>\n\n" else ""
        }

        fun chatPrompt(template: String, system: String, user: String): String =
            chatPromptParts(template, system, user).let { it.first + it.second }

        private val THOUGHTS = Regex("""<think>[\s\S]*?</think>|<\|channel>[\s\S]*?<channel\|>""")

        /** Drop any reasoning block a model emits despite thinking being off. */
        fun cleanChatOutput(output: String): String = output.replace(THOUGHTS, "").trim()

        private fun sameLanguage(a: String, b: String) =
            a.substringBefore('-').substringBefore('_').equals(b.substringBefore('-').substringBefore('_'), ignoreCase = true)

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
