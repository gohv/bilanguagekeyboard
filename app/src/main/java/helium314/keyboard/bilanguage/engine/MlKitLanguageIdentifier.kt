// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.bilanguage.engine

import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier as MlKitIdentifier
import helium314.keyboard.bilanguage.BiLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * ML Kit language identification, for the spec 6.2 idempotence check.
 *
 * Unlike the translation models this one is **bundled in the library**, so it needs no download and
 * works on a device that has never had network. That matters: it means the "don't translate French
 * into French" guard is available immediately rather than only after a model is fetched.
 *
 * ML Kit returns the string `"und"` when it cannot tell. That is mapped to null, which the controller
 * treats as "proceed with the configured source language" per spec 6.2.
 */
class MlKitLanguageIdentifier : LanguageIdentifier {

    override val id = "mlkit"

    private val client: MlKitIdentifier by lazy { LanguageIdentification.getClient() }

    override suspend fun identify(text: String): String? {
        if (text.isBlank()) return null
        return try {
            val tag = suspendCancellableCoroutine { cont ->
                val task = client.identifyLanguage(text)
                task.addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                task.addOnFailureListener { e ->
                    BiLog.e("language identification failed", e)
                    if (cont.isActive) cont.resume(UNDETERMINED)
                }
            }
            if (tag == UNDETERMINED) null else tag
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BiLog.e("language identification threw", e)
            null
        }
    }

    override fun close() {
        try {
            client.close()
        } catch (e: Exception) {
            BiLog.e("closing language identifier failed", e)
        }
    }

    private companion object {
        const val UNDETERMINED = "und"
    }
}
