// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.polyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 6.1. Most cases use values actually captured on the device (docs/field-types-observed.md), so
 * this is a regression fixture and not just a restatement of the implementation.
 *
 * "Getting this wrong once destroys user trust permanently", so the blocked cases are enumerated
 * explicitly rather than tested by sampling.
 */
class FieldGateTest {

    private fun info(inputType: Int, imeOptions: Int = 0) = EditorInfo().also {
        it.inputType = inputType
        it.imeOptions = imeOptions
        it.packageName = "test.pkg"
    }

    // ---- allowed ----

    @Test fun `whatsapp message box is translatable`() {
        assertTrue(FieldGate.isAllowed(info(0x0002C001, 0x40000006)))
    }

    @Test fun `web text input is translatable`() {
        assertTrue(FieldGate.isAllowed(info(0x0000C0A1, 0x12000005)))
    }

    @Test fun `plain text with no flags is translatable`() {
        assertTrue(FieldGate.isAllowed(info(InputType.TYPE_CLASS_TEXT)))
    }

    @Test fun `short message and long message variations are translatable`() {
        assertTrue(FieldGate.isAllowed(info(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE)))
        assertTrue(FieldGate.isAllowed(info(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE)))
    }

    // ---- blocked: passwords ----

    @Test fun `all three password variations are blocked`() {
        assertBlocked(info(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD), "TEXT_VARIATION_PASSWORD")
        assertBlocked(info(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD), "TEXT_VARIATION_VISIBLE_PASSWORD")
        // real captured value from a web password field
        assertBlocked(info(0x000000E1, 0x12000005), "TEXT_VARIATION_WEB_PASSWORD")
    }

    @Test fun `numeric password, the PIN case, is blocked`() {
        // nothing on the test device produced this naturally (Revolut uses a biometric prompt), so it
        // only exists as a synthetic case. That is exactly why it needs a test.
        assertBlocked(
            info(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD),
            "TYPE_CLASS_NUMBER"
        )
    }

    // ---- blocked: identifiers ----

    @Test fun `email variations are blocked`() {
        assertBlocked(info(0x00000021, 0x02000005), "TEXT_VARIATION_EMAIL_ADDRESS")
        assertBlocked(info(0x000000D1, 0x12000005), "TEXT_VARIATION_WEB_EMAIL_ADDRESS")
    }

    @Test fun `uri is blocked, including the browser omnibox`() {
        assertBlocked(info(0x00000011, 0x12000005), "TEXT_VARIATION_URI")
        // Brave omnibox, real captured value
        assertBlocked(info(0x00080011, 0x12000002), "TEXT_VARIATION_URI")
    }

    // ---- blocked: non-text classes ----

    @Test fun `number phone and datetime classes are blocked`() {
        assertBlocked(info(0x00006002, 0x12000005), "TYPE_CLASS_NUMBER")
        assertBlocked(info(0x00004003, 0x12000005), "TYPE_CLASS_PHONE")
        assertBlocked(info(InputType.TYPE_CLASS_DATETIME), "TYPE_CLASS_DATETIME")
    }

    @Test fun `TYPE_NULL is blocked because there is no text field to write back into`() {
        assertBlocked(info(InputType.TYPE_NULL), "TYPE_NULL")
    }

    // ---- blocked: explicit app request ----

    @Test fun `NO_PERSONALIZED_LEARNING blocks an otherwise fine text field`() {
        val ei = info(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE,
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        )
        assertBlocked(ei, "IME_FLAG_NO_PERSONALIZED_LEARNING")
    }

    // ---- deny by default ----

    @Test fun `an unknown input class is blocked, not allowed`() {
        // class 7 is not a real InputType class; the gate must not fall through to allowed
        assertFalse(FieldGate.isAllowed(info(0x00000007)))
    }

    @Test fun `null editorInfo is blocked`() {
        assertBlocked(null, "no editorInfo")
    }

    private fun assertBlocked(ei: EditorInfo?, expectedReason: String) {
        val v = FieldGate.verdict(ei)
        assertTrue("expected Blocked for reason $expectedReason but got $v", v is FieldGate.Verdict.Blocked)
        assertEquals(expectedReason, (v as FieldGate.Verdict.Blocked).reason)
    }
}
