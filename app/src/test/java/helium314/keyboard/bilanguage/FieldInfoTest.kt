// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.bilanguage

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decoder tests. These are pure Int -> String, no Robolectric needed.
 *
 * The WhatsApp cases are real values captured from a Galaxy Z Fold 7, not invented, so they double
 * as a regression fixture for the phase 1 acceptance criteria.
 */
class FieldInfoTest {

    @Test fun `whatsapp compose box decodes as a plain multiline text field`() {
        // captured on device: com.whatsapp/.Conversation message box
        assertEquals(
            "TEXT/NORMAL +CAP_SENTENCES +AUTO_CORRECT +MULTI_LINE",
            FieldInfo.describeInputType(0x0002C001)
        )
        assertEquals("DONE +NO_ENTER_ACTION", FieldInfo.describeImeOptions(0x40000006))
    }

    @Test fun `password variations are named, because 6_1 depends on spotting them`() {
        assertEquals(
            "TEXT/PASSWORD",
            FieldInfo.describeInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        )
        assertEquals(
            "TEXT/VISIBLE_PASSWORD",
            FieldInfo.describeInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        )
        assertEquals(
            "TEXT/WEB_PASSWORD",
            FieldInfo.describeInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
        )
    }

    @Test fun `email and uri variations are named`() {
        assertEquals(
            "TEXT/EMAIL_ADDRESS",
            FieldInfo.describeInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        )
        assertEquals(
            "TEXT/WEB_EMAIL_ADDRESS",
            FieldInfo.describeInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)
        )
        assertEquals(
            "TEXT/URI",
            FieldInfo.describeInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        )
    }

    @Test fun `numeric pin field decodes as NUMBER PASSWORD`() {
        assertEquals(
            "NUMBER/PASSWORD",
            FieldInfo.describeInputType(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        )
        assertEquals(
            "NUMBER/NORMAL +SIGNED +DECIMAL",
            FieldInfo.describeInputType(
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL
            )
        )
    }

    @Test fun `phone class has no variation suffix`() {
        assertEquals("PHONE", FieldInfo.describeInputType(InputType.TYPE_CLASS_PHONE))
    }

    @Test fun `datetime variations are named`() {
        assertEquals(
            "DATETIME/DATE",
            FieldInfo.describeInputType(InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE)
        )
        assertEquals(
            "DATETIME/TIME",
            FieldInfo.describeInputType(InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME)
        )
    }

    @Test fun `TYPE_NULL short-circuits`() {
        assertEquals("NULL", FieldInfo.describeInputType(InputType.TYPE_NULL))
    }

    @Test fun `no personalized learning is surfaced first among ime flags`() {
        val decoded = FieldInfo.describeImeOptions(
            EditorInfo.IME_ACTION_SEND or
                EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or
                EditorInfo.IME_FLAG_NO_FULLSCREEN
        )
        assertEquals("SEND +NO_PERSONALIZED_LEARNING +NO_FULLSCREEN", decoded)
    }

    @Test fun `unknown variation falls back to hex rather than lying`() {
        // 0x00000f0 is not a defined text variation
        assertTrue(
            FieldInfo.describeInputType(InputType.TYPE_CLASS_TEXT or 0x000000f0)
                .startsWith("TEXT/VARIATION(")
        )
    }

    @Test fun `hex formatting is fixed width and uppercase`() {
        assertEquals("0x0002C001", FieldInfo.hex(0x0002C001))
        assertEquals("0x00000000", FieldInfo.hex(0))
        // must not blow up or sign-extend oddly on the top bit
        assertEquals("0x80000000", FieldInfo.hex(Int.MIN_VALUE))
    }

    @Test fun `redact never reveals the text`() {
        val secret = "my password is hunter2"
        val out = BiLog.redact(secret)
        assertTrue(out.contains("len=${secret.length}"))
        assertTrue(!out.contains("hunter2"))
        assertEquals("null", BiLog.redact(null))
        assertEquals("len=0", BiLog.redact(""))
    }
}
