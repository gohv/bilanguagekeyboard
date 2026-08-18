// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.bilanguage.engine

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import helium314.keyboard.bilanguage.BiLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Spec section 3, Option A: Google ML Kit on-device translation.
 *
 * Inference is local. The network is touched only by [preparePair], which downloads a model once per
 * language. That download is logged so the "no typed text leaves the device" claim stays auditable
 * from logcat rather than resting on trust.
 *
 * ## Things about ML Kit that shape this class
 *
 * - Models are **per language**, not per pair. Each one translates that language to and from English.
 *   A non-English pair such as FR to BG therefore pivots through English internally, which costs
 *   quality; that tradeoff was accepted in DECISIONS.md.
 * - [Translator] instances hold native resources and must be closed. They are cached per pair here
 *   and all released in [close], which the IME calls from `onTrimMemory` and `onDestroy`.
 * - Language tags are ML Kit's own two-letter set, obtained from [TranslateLanguage]. A BCP-47 tag
 *   with a region ("en-GB") has to be reduced to its language before ML Kit will accept it.
 */
class MlKitEngine(
    /** When false, a download may use mobile data. Spec section 7 wants unmetered-only by default. */
    private val requireWifi: Boolean = true,
) : TranslationEngine {

    override val id = "mlkit"

    private val translators = ConcurrentHashMap<String, Translator>()
    private val modelManager by lazy { RemoteModelManager.getInstance() }

    override suspend fun supportedLanguages(): List<String> = TranslateLanguage.getAllLanguages()

    override suspend fun isPairReady(source: String, target: String): Boolean {
        val needed = requiredModels(source, target)
        if (needed.isEmpty()) return false
        val downloaded = downloadedLanguageTags()
        val ready = needed.all { it in downloaded }
        if (!ready) BiLog.d("pair $source->$target not ready; have=$downloaded need=$needed")
        return ready
    }

    /**
     * Which language models must be present. English is included when it is one of the endpoints:
     * ML Kit ships a `TranslateRemoteModel` for English like any other language, so it is not assumed
     * to be built in. If that assumption is ever wrong the only symptom is one extra tiny download.
     */
    private fun requiredModels(source: String, target: String): Set<String> {
        val s = normalise(source) ?: return emptySet()
        val t = normalise(target) ?: return emptySet()
        return setOf(s, t)
    }

    /** Language tags of every model currently on disk. */
    suspend fun downloadedLanguageTags(): Set<String> = try {
        modelManager.getDownloadedModels(TranslateRemoteModel::class.java).awaitTask()
            .mapNotNull { it.language }
            .toSet()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        BiLog.e("could not list downloaded models", e)
        emptySet()
    }

    override fun preparePair(source: String, target: String): Flow<PrepareState> = callbackFlow {
        val s = normalise(source)
        val t = normalise(target)
        if (s == null || t == null) {
            trySend(PrepareState.Failed("Unsupported language pair", retryable = false))
            close()
            return@callbackFlow
        }

        trySend(PrepareState.Queued)
        val conditions = DownloadConditions.Builder().apply { if (requireWifi) requireWifi() }.build()
        val translator = translatorFor(s, t)

        // This is the one network call in the whole app. Logged deliberately and specifically.
        BiLog.i("MODEL DOWNLOAD starting for $s<->$t (requireWifi=$requireWifi) - this is the only network use")
        translator.downloadModelIfNeeded(conditions)
            .addOnSuccessListener {
                BiLog.i("MODEL DOWNLOAD complete for $s<->$t")
                trySend(PrepareState.Ready)
                close()
            }
            .addOnFailureListener { e ->
                BiLog.e("MODEL DOWNLOAD failed for $s<->$t", e)
                // Almost every failure here is transient: no network, wifi required but absent, or
                // storage full. Treat as retryable and let the user decide.
                trySend(PrepareState.Failed(e.message ?: "Download failed", retryable = true))
                close()
            }

        // ML Kit gives no progress callback for model downloads, so there is nothing honest to
        // report between Queued and Ready. The UI must therefore show an indeterminate state rather
        // than a fake percentage.
        awaitClose { }
    }

    override suspend fun translate(text: String, source: String, target: String): Result<String> {
        val s = normalise(source) ?: return Result.failure(IllegalArgumentException("bad source $source"))
        val t = normalise(target) ?: return Result.failure(IllegalArgumentException("bad target $target"))
        return try {
            val translator = translatorFor(s, t)
            val out = suspendCancellableCoroutine { cont ->
                val task = translator.translate(text)
                task.addOnSuccessListener { if (cont.isActive) cont.resume(Result.success(it)) }
                task.addOnFailureListener { e -> if (cont.isActive) cont.resume(Result.failure(e)) }
                // ML Kit tasks are not cancellable; on cancellation we simply stop caring about the
                // result. The controller has already guaranteed a stale result cannot be committed.
                cont.invokeOnCancellation { }
            }
            out
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun translatorFor(source: String, target: String): Translator =
        translators.getOrPut("$source|$target") {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(source)
                    .setTargetLanguage(target)
                    .build()
            )
        }

    /** Delete a downloaded model. Used by the model management screen. */
    suspend fun deleteModel(languageTag: String): Boolean {
        val tag = normalise(languageTag) ?: return false
        return try {
            modelManager.deleteDownloadedModel(TranslateRemoteModel.Builder(tag).build()).awaitTask()
            BiLog.i("deleted model $tag")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BiLog.e("could not delete model $tag", e)
            false
        }
    }

    override fun close() {
        translators.values.forEach { it.close() }
        translators.clear()
        BiLog.i("MlKitEngine closed, translators released")
    }

    companion object {
        /**
         * ML Kit wants its own bare language codes. Reduce a BCP-47 tag ("en-GB", "fr_FR") to
         * something it accepts, or null when it does not support the language at all.
         */
        fun normalise(tag: String): String? {
            val language = tag.substringBefore('-').substringBefore('_').lowercase(Locale.ROOT)
            return TranslateLanguage.fromLanguageTag(language)
        }

        fun displayName(tag: String): String =
            Locale.forLanguageTag(tag).getDisplayLanguage(Locale.getDefault())
                .ifEmpty { tag.uppercase(Locale.ROOT) }

        /**
         * ML Kit does not report model sizes, and mapping files on disk back to languages is not a
         * documented API. Google's own figure is roughly 30MB per language, so that is shown as an
         * estimate and labelled as one rather than presented as a measurement.
         */
        const val APPROX_MODEL_MB = 30

        /**
         * Await a Play Services [Task] without pulling in kotlinx-coroutines-play-services.
         * One small helper is cheaper than another dependency for two call sites.
         */
        suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
            addOnSuccessListener { if (cont.isActive) cont.resume(it) }
            addOnFailureListener { e -> if (cont.isActive) cont.resumeWith(Result.failure(e)) }
        }
    }
}
