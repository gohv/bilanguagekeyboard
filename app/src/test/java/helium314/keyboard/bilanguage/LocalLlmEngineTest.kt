// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.bilanguage

import helium314.keyboard.bilanguage.engine.LocalLlmEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLlmEngineTest {

    @Test fun `prompt exactly matches the fine tuning format`() {
        assertEquals("FR:\nwhere r u 😂\n", LocalLlmEngine.promptFor("fr-FR", "  where r u 😂  "))
        assertEquals("EN:\ntkt j'arrive dans 5\n", LocalLlmEngine.promptFor("en_US", "tkt j'arrive dans 5"))
    }

    @Test fun `only cross direction English and French pairs are supported`() {
        assertTrue(LocalLlmEngine.supports("en-US", "fr-FR"))
        assertTrue(LocalLlmEngine.supports("fr", "en"))
        assertFalse(LocalLlmEngine.supports("en", "en"))
        assertFalse(LocalLlmEngine.supports("de", "fr"))
    }
}
