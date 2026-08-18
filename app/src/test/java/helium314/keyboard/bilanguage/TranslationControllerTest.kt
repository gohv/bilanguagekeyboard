// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.bilanguage

import helium314.keyboard.bilanguage.engine.StubEngine
import helium314.keyboard.bilanguage.engine.StubLanguageIdentifier
import helium314.keyboard.bilanguage.engine.PrepareState
import helium314.keyboard.bilanguage.engine.TranslationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Controller behaviour, with a fake [FieldIo] so the atomic-replace arithmetic and the undo/idempotence
 * rules are checked without a device.
 *
 * No kotlinx-coroutines-test dependency is available, so the scope is `Dispatchers.Unconfined` and the
 * stub engine has zero latency, which makes translateNow complete synchronously. The one timing test
 * uses runBlocking plus a real delay.
 */
class TranslationControllerTest {

    /** Records every replace so tests can assert exact delete counts, not just the final text. */
    private class FakeFieldIo(initial: String, cursor: Int = initial.length) : FieldIo {
        var text = initial
        var selStart = cursor
        var selEnd = cursor
        var finishComposingCalls = 0
        var replaceFailsNext = false
        val replaceLog = mutableListOf<Triple<Int, Int, String>>()

        override fun read() = FieldText(text, selStart, selEnd)
        override fun finishComposing() { finishComposingCalls++ }
        override fun collapseSelectionToEnd(): Boolean { selStart = selEnd; return true }
        override fun replace(deleteBefore: Int, deleteAfter: Int, newText: String): Boolean {
            if (replaceFailsNext) { replaceFailsNext = false; return false }
            replaceLog += Triple(deleteBefore, deleteAfter, newText)
            val keptStart = text.substring(0, (selEnd - deleteBefore).coerceIn(0, text.length))
            val keptEnd = text.substring((selEnd + deleteAfter).coerceIn(0, text.length))
            text = keptStart + newText + keptEnd
            selEnd = keptStart.length + newText.length
            selStart = selEnd
            return true
        }
        override fun setCursor(position: Int) { selStart = position; selEnd = position }
    }

    private fun controller(
        engine: TranslationEngine = StubEngine(latencyMs = 0),
        timeoutMs: Long = 3_000L,
    ) = TranslationController(
        engineFactory = { engine },
        identifierFactory = { StubLanguageIdentifier() },
        mainScope = CoroutineScope(Dispatchers.Unconfined),
        ioDispatcher = Dispatchers.Unconfined,
        timeoutMs = timeoutMs,
    )

    private fun allow(c: TranslationController) = c.onFieldChanged(FieldGate.Verdict.Allowed)

    // ---- the phase 2 acceptance criteria ----

    @Test fun `translate replaces the whole field and reports Committed`() {
        val io = FakeFieldIo("hello")
        val c = controller().also { allow(it) }
        c.translateNow(io, "en", "fr")
        assertEquals("[FR]HELLO", io.text)
        assertTrue(c.state.value is TranslateUiState.Committed)
    }

    @Test fun `undo restores the original text exactly`() {
        val io = FakeFieldIo("hello")
        val c = controller().also { allow(it) }
        c.translateNow(io, "en", "fr")
        assertEquals("[FR]HELLO", io.text)
        c.undoLast(io)
        assertEquals("hello", io.text)
        assertEquals(TranslateUiState.Idle, c.state.value)
    }

    @Test fun `undo is one step only`() {
        val io = FakeFieldIo("hello")
        val c = controller().also { allow(it) }
        c.translateNow(io, "en", "fr")
        c.undoLast(io)
        val afterFirstUndo = io.text
        c.undoLast(io) // must be a no-op, not a re-apply
        assertEquals(afterFirstUndo, io.text)
    }

    @Test fun `re-translating an already translated string is a no-op`() {
        val io = FakeFieldIo("hello")
        val c = controller().also { allow(it) }
        c.translateNow(io, "en", "fr")
        val once = io.text
        c.translateNow(io, "en", "fr")
        assertEquals("must not become [FR][FR]HELLO", once, io.text)
        assertTrue(c.state.value is TranslateUiState.AlreadyInTarget)
    }

    @Test fun `a blocked field refuses to translate even if asked directly`() {
        val io = FakeFieldIo("hello")
        val c = controller()
        c.onFieldChanged(FieldGate.Verdict.Blocked("TEXT_VARIATION_WEB_PASSWORD"))
        c.translateNow(io, "en", "fr")
        assertEquals("hello", io.text)
        assertTrue(io.replaceLog.isEmpty())
    }

    // ---- the replace arithmetic, which is where silent corruption would come from ----

    @Test fun `replace deletes exactly the whole field around the cursor`() {
        val io = FakeFieldIo("hello world", cursor = 5) // cursor mid-text
        val c = controller().also { allow(it) }
        c.translateNow(io, "en", "fr")
        val (before, after, _) = io.replaceLog.single()
        assertEquals(5, before)
        assertEquals("hello world".length - 5, after)
        assertEquals("[FR]HELLO WORLD", io.text)
    }

    @Test fun `a long field is not truncated`() {
        val long = "a".repeat(2600) // over both the 1024 cache cap and the 2000 in the phase 6 spec
        val io = FakeFieldIo(long)
        val c = controller().also { allow(it) }
        c.translateNow(io, "en", "fr")
        assertEquals("[FR]" + "A".repeat(2600), io.text)
    }

    @Test fun `a selection is collapsed before replacing so no text survives`() {
        val io = FakeFieldIo("hello", cursor = 5).also { it.selStart = 1 } // "ello" selected
        val c = controller().also { allow(it) }
        c.translateNow(io, "en", "fr")
        assertEquals("[FR]HELLO", io.text)
    }

    @Test fun `composing text is settled before reading`() {
        val io = FakeFieldIo("hello")
        val c = controller().also { allow(it) }
        c.translateNow(io, "en", "fr")
        assertTrue(io.finishComposingCalls >= 1)
    }

    // ---- failure paths must leave the field alone and be visible ----

    @Test fun `blank field fails visibly without touching the text`() {
        val io = FakeFieldIo("   ")
        val c = controller().also { allow(it) }
        c.translateNow(io, "en", "fr")
        assertEquals("   ", io.text)
        assertTrue(c.state.value is TranslateUiState.Failed)
    }

    @Test fun `an engine failure is surfaced and the field is untouched`() {
        val failing = object : TranslationEngine {
            override val id = "failing"
            override suspend fun supportedLanguages() = listOf("en", "fr")
            override suspend fun isPairReady(source: String, target: String) = true
            override fun preparePair(source: String, target: String): Flow<PrepareState> = flow { emit(PrepareState.Ready) }
            override suspend fun translate(text: String, source: String, target: String) =
                Result.failure<String>(IllegalStateException("model exploded"))
            override fun close() = Unit
        }
        val io = FakeFieldIo("hello")
        val c = controller(engine = failing).also { allow(it) }
        c.translateNow(io, "en", "fr")
        assertEquals("hello", io.text)
        assertTrue(io.replaceLog.isEmpty())
        assertTrue(c.state.value is TranslateUiState.Failed)
    }

    @Test fun `a failed InputConnection write is reported rather than silently dropped`() {
        val io = FakeFieldIo("hello").also { it.replaceFailsNext = true }
        val c = controller().also { allow(it) }
        c.translateNow(io, "en", "fr")
        assertEquals("hello", io.text)
        assertTrue(c.state.value is TranslateUiState.Failed)
    }

    @Test fun `same source and target is a no-op`() {
        val io = FakeFieldIo("hello")
        val c = controller().also { allow(it) }
        c.translateNow(io, "fr", "fr")
        assertEquals("hello", io.text)
        assertTrue(c.state.value is TranslateUiState.AlreadyInTarget)
    }

    @Test fun `a timeout leaves the field untouched and reports failure`() = runBlocking {
        val slow = StubEngine(latencyMs = 400)
        val io = FakeFieldIo("hello")
        val c = TranslationController(
            engineFactory = { slow },
            identifierFactory = { StubLanguageIdentifier() },
            mainScope = CoroutineScope(Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Default,
            timeoutMs = 30,
        ).also { allow(it) }
        c.translateNow(io, "en", "fr")
        delay(600) // let the timeout fire
        assertEquals("hello", io.text)
        assertTrue(io.replaceLog.isEmpty())
        assertTrue("state was ${c.state.value}", c.state.value is TranslateUiState.Failed)
    }

    // ---- lifecycle ----

    @Test fun `close drops the engine and the engine is recreated on next use`() {
        var built = 0
        val io = FakeFieldIo("hello")
        val c = TranslationController(
            engineFactory = { built++; StubEngine(latencyMs = 0) },
            identifierFactory = { StubLanguageIdentifier() },
            mainScope = CoroutineScope(Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
        ).also { allow(it) }
        c.translateNow(io, "en", "fr")
        assertEquals(1, built)
        c.close()
        allow(c)
        c.translateNow(FakeFieldIo("bonjour"), "en", "fr")
        assertEquals("engine should be rebuilt after close()", 2, built)
    }

    @Test fun `close clears the undo snapshot`() {
        val io = FakeFieldIo("hello")
        val c = controller().also { allow(it) }
        c.translateNow(io, "en", "fr")
        assertTrue(c.canUndo)
        c.close()
        assertTrue(!c.canUndo)
    }

    @Test fun `opening a new field clears undo`() {
        val io = FakeFieldIo("hello")
        val c = controller().also { allow(it) }
        c.translateNow(io, "en", "fr")
        assertTrue(c.canUndo)
        allow(c) // new field
        assertTrue(!c.canUndo)
    }

    // ---- Mode 2 live preview ----

    @Test fun `preview never writes to the field`() = runBlocking {
        val io = FakeFieldIo("hello")
        val c = controller().also { allow(it) }
        c.onTextChanged(io, "en", "fr", immediate = true)
        delay(200)
        assertEquals("spec rule 5: partial translations must never reach the field", "hello", io.text)
        assertTrue(io.replaceLog.isEmpty())
        assertTrue("state was ${c.state.value}", c.state.value is TranslateUiState.Preview)
        assertEquals("[FR]HELLO", (c.state.value as TranslateUiState.Preview).translated)
    }

    @Test fun `tapping the preview commits it via the same atomic replace`() = runBlocking {
        val io = FakeFieldIo("hello")
        val c = controller().also { allow(it) }
        c.onTextChanged(io, "en", "fr", immediate = true)
        delay(200)
        c.commitPreview(io)
        assertEquals("[FR]HELLO", io.text)
        val (before, after, _) = io.replaceLog.single()
        assertEquals(5, before)
        assertEquals(0, after)
        assertTrue(c.state.value is TranslateUiState.Committed)
    }

    @Test fun `a committed preview is undoable`() = runBlocking {
        val io = FakeFieldIo("hello")
        val c = controller().also { allow(it) }
        c.onTextChanged(io, "en", "fr", immediate = true)
        delay(200)
        c.commitPreview(io)
        assertTrue(c.canUndo)
        c.undoLast(io)
        assertEquals("hello", io.text)
    }

    @Test fun `a stale preview is refused rather than overwriting newer text`() = runBlocking {
        val io = FakeFieldIo("hello")
        val c = controller().also { allow(it) }
        c.onTextChanged(io, "en", "fr", immediate = true)
        delay(200)
        // user keeps typing after the preview was computed
        io.text = "hello there"
        io.selStart = io.text.length
        io.selEnd = io.text.length
        c.commitPreview(io)
        assertEquals("hello there", io.text)
        assertTrue(io.replaceLog.isEmpty())
        assertTrue(c.state.value is TranslateUiState.Failed)
    }

    @Test fun `preview does not run in a blocked field`() = runBlocking {
        val io = FakeFieldIo("hello")
        val c = controller()
        c.onFieldChanged(FieldGate.Verdict.Blocked("TEXT_VARIATION_WEB_PASSWORD"))
        c.onTextChanged(io, "en", "fr", immediate = true)
        delay(200)
        assertTrue(io.replaceLog.isEmpty())
        assertTrue(c.state.value is TranslateUiState.Disabled)
    }

    @Test fun `a new keystroke cancels the previous preview so no stale result lands`() = runBlocking {
        val io = FakeFieldIo("hello")
        val c = controller(engine = StubEngine(latencyMs = 300)).also { allow(it) }
        c.onTextChanged(io, "en", "fr", immediate = true)
        delay(50)
        io.text = "hello world"
        io.selEnd = io.text.length
        io.selStart = io.selEnd
        c.onTextChanged(io, "en", "fr", immediate = true) // cancels the first
        delay(600)
        val preview = c.state.value
        assertTrue("state was $preview", preview is TranslateUiState.Preview)
        assertEquals("[FR]HELLO WORLD", (preview as TranslateUiState.Preview).translated)
    }

    @Test fun `opening a new field discards a pending preview`() = runBlocking {
        val io = FakeFieldIo("hello")
        val c = controller().also { allow(it) }
        c.onTextChanged(io, "en", "fr", immediate = true)
        delay(200)
        assertTrue(c.state.value is TranslateUiState.Preview)
        allow(c) // new field
        c.commitPreview(io)
        assertEquals("hello", io.text)
        assertTrue(io.replaceLog.isEmpty())
    }

    @Test fun `sentence terminal punctuation is recognised`() {
        assertTrue(TranslationController.isSentenceTerminal('.'.code))
        assertTrue(TranslationController.isSentenceTerminal('!'.code))
        assertTrue(TranslationController.isSentenceTerminal('?'.code))
        assertTrue(TranslationController.isSentenceTerminal('\u3002'.code)) // CJK full stop
        assertTrue(!TranslationController.isSentenceTerminal('a'.code))
        assertTrue(!TranslationController.isSentenceTerminal(','.code))
    }

    @Test fun `very short text produces no preview`() = runBlocking {
        val io = FakeFieldIo("h")
        val c = controller().also { allow(it) }
        c.onTextChanged(io, "en", "fr", immediate = true)
        delay(200)
        assertTrue(c.state.value !is TranslateUiState.Preview)
    }

    // ---- the stub identifier's contract ----

    @Test fun `stub identifier reports undetermined for ordinary text`() = runBlocking {
        assertNull(StubLanguageIdentifier().identify("bonjour tout le monde"))
        // a human-written string that merely starts with a bracketed tag must not be mistaken
        // for already-translated output
        assertNull(StubLanguageIdentifier().identify("[FR] bonjour tout le monde"))
    }

    @Test fun `stub identifier recognises its own output`() = runBlocking {
        assertEquals("fr", StubLanguageIdentifier().identify("[FR]HELLO"))
    }
}
