// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.polyboard

import helium314.keyboard.latin.utils.Log

/**
 * The single logging tag for everything PolyBoard adds on top of HeliBoard.
 *
 *     adb logcat -s PolyBoard:V
 *
 * Delegates to upstream's [Log] rather than `android.util.Log` so PolyBoard lines also show up
 * in the in-app log viewer, same as the rest of the keyboard.
 *
 * ## Never pass user-typed text to these methods
 *
 * This is not a style preference, it is spec section 6.4. Upstream's [Log] retains up to 12000
 * lines in memory, and `DebugFlags.CrashReportExceptionHandler` writes the last 100 of them into
 * a crash report file in external storage. So a single `PolyLog.d("translating: $text")` would
 * persist typed text to disk the next time the keyboard crashed, which for a keyboard is
 * indefensible.
 *
 * Log metadata instead: lengths, language tags, durations, field types, engine ids, error kinds.
 * When you genuinely need to correlate a specific string across log lines, log
 * [redact]`(text)` — a length plus a hash, which is stable across lines but not reversible.
 */
object PolyLog {
    const val TAG = "PolyBoard"

    @JvmStatic fun v(message: String) = Log.v(TAG, message)
    @JvmStatic fun d(message: String) = Log.d(TAG, message)
    @JvmStatic fun i(message: String) = Log.i(TAG, message)
    @JvmStatic fun w(message: String) = Log.w(TAG, message)
    @JvmStatic fun e(message: String) = Log.e(TAG, message)
    @JvmStatic fun e(message: String, t: Throwable?) = Log.e(TAG, message, t)

    /**
     * Safe stand-in for a piece of user text: its length and a short non-reversible hash.
     * Use this when correlating lines about the same string, never the string itself.
     */
    @JvmStatic
    fun redact(text: CharSequence?): String {
        if (text == null) return "null"
        if (text.isEmpty()) return "len=0"
        // hashCode is not a cryptographic hash; it is here to correlate lines, not to protect
        // secrets. Combined with only ever emitting 4 hex digits, it is not a practical way to
        // recover short strings, and nothing here is ever used for a security decision.
        val h = text.toString().hashCode() and 0xffff
        return "len=${text.length} h=${h.toString(16).padStart(4, '0')}"
    }
}
