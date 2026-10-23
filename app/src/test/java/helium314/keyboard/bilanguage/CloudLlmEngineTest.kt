// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.bilanguage

import helium314.keyboard.bilanguage.cloud.CloudConfig
import helium314.keyboard.bilanguage.cloud.CloudProtocol
import helium314.keyboard.bilanguage.cloud.CloudProvider
import helium314.keyboard.bilanguage.engine.CloudLlmEngine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudLlmEngineTest {

    @Test fun `anthropic request uses messages endpoint, key header and system prompt`() {
        val config = CloudConfig(CloudProvider.ANTHROPIC, "https://api.anthropic.com/", "claude-haiku-5-5")
        val request = CloudLlmEngine.buildRequest(config, "sk-test", "on se voit demain ?", "fr", "en")

        assertEquals("https://api.anthropic.com/v1/messages", request.url)
        assertEquals("sk-test", request.headers["x-api-key"])
        assertEquals(CloudLlmEngine.ANTHROPIC_VERSION, request.headers["anthropic-version"])
        assertFalse(request.headers.containsKey("Authorization"))

        val body = Json.parseToJsonElement(request.body).jsonObject
        assertEquals("claude-haiku-5-5", body["model"]!!.jsonPrimitive.content)
        assertTrue(body["system"]!!.jsonPrimitive.content.contains("from French to English"))
        val message = body["messages"]!!.jsonArray.single().jsonObject
        assertEquals("user", message["role"]!!.jsonPrimitive.content)
        assertEquals("on se voit demain ?", message["content"]!!.jsonPrimitive.content)
    }

    @Test fun `openai compatible request uses bearer auth and chat completions`() {
        val config = CloudConfig(CloudProvider.OPENROUTER, "https://openrouter.ai/api/v1", "some/model")
        val request = CloudLlmEngine.buildRequest(config, "sk-or", "see you tomorrow", "en", "fr")

        assertEquals("https://openrouter.ai/api/v1/chat/completions", request.url)
        assertEquals("Bearer sk-or", request.headers["Authorization"])
        val messages = Json.parseToJsonElement(request.body).jsonObject["messages"]!!.jsonArray
        assertEquals(listOf("system", "user"), messages.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        assertEquals("see you tomorrow", messages[1].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test fun `text with quotes and newlines is escaped, not spliced`() {
        val config = CloudConfig(CloudProvider.OPENAI, "https://api.openai.com/v1", "m")
        val text = "he said \"hi\"\nthen left }"
        val request = CloudLlmEngine.buildRequest(config, "k", text, "en", "fr")
        val messages = Json.parseToJsonElement(request.body).jsonObject["messages"]!!.jsonArray
        assertEquals(text, messages[1].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test fun `parses both response shapes and trims`() {
        assertEquals(
            "See you tomorrow?",
            CloudLlmEngine.parseTranslation(
                CloudProtocol.ANTHROPIC_MESSAGES,
                """{"content":[{"type":"text","text":" See you tomorrow? "}],"stop_reason":"end_turn"}""",
            ),
        )
        assertEquals(
            "À demain",
            CloudLlmEngine.parseTranslation(
                CloudProtocol.OPENAI_CHAT,
                """{"choices":[{"message":{"role":"assistant","content":"À demain\n"}}]}""",
            ),
        )
    }

    @Test(expected = IllegalStateException::class)
    fun `empty translation is a failure, not an empty replace`() {
        CloudLlmEngine.parseTranslation(CloudProtocol.OPENAI_CHAT, """{"choices":[{"message":{"content":"  "}}]}""")
    }

    @Test fun `provider error message is extracted from either error shape`() {
        assertEquals("invalid x-api-key", CloudLlmEngine.errorMessage("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""))
        assertEquals("bad", CloudLlmEngine.errorMessage("""{"error":"bad"}"""))
        assertNull(CloudLlmEngine.errorMessage("<html>502</html>"))
    }

    @Test fun `config problems block use until key, model and https url are present`() {
        val ok = CloudConfig(CloudProvider.OPENAI, "https://api.openai.com/v1", "m")
        assertNull(ok.problem(hasKey = true))
        assertNotNull(ok.problem(hasKey = false))
        assertNotNull(ok.copy(model = " ").problem(hasKey = true))
        assertNotNull(ok.copy(baseUrl = "http://192.168.1.5:1234/v1").problem(hasKey = true))
    }
}
