// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.bilanguage.cloud

/** The two wire protocols the cloud engine speaks. Nearly every hosted LLM accepts one of them. */
enum class CloudProtocol { OPENAI_CHAT, ANTHROPIC_MESSAGES }

/**
 * Presets only fill in defaults. Base URL and model stay editable, so a proxy or a different model
 * needs no new preset.
 */
enum class CloudProvider(
    val id: String,
    val label: String,
    val protocol: CloudProtocol,
    val defaultBaseUrl: String,
    val defaultModel: String,
) {
    MISTRAL("mistral", "Mistral", CloudProtocol.OPENAI_CHAT, "https://api.mistral.ai/v1", "mistral-small-latest"),
    ANTHROPIC("anthropic", "Anthropic", CloudProtocol.ANTHROPIC_MESSAGES, "https://api.anthropic.com", "claude-haiku-5-5"),
    OPENAI("openai", "OpenAI", CloudProtocol.OPENAI_CHAT, "https://api.openai.com/v1", ""),
    OPENROUTER("openrouter", "OpenRouter", CloudProtocol.OPENAI_CHAT, "https://openrouter.ai/api/v1", ""),
    GEMINI("gemini", "Google Gemini", CloudProtocol.OPENAI_CHAT, "https://generativelanguage.googleapis.com/v1beta/openai", ""),
    CUSTOM("custom", "Other OpenAI-compatible", CloudProtocol.OPENAI_CHAT, "", "");

    companion object {
        val DEFAULT = MISTRAL

        fun fromId(id: String?): CloudProvider = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * Register for French targets: tu or vous. Only an instruction-following engine can honour it, so it
 * is offered only with the cloud engine.
 */
enum class Register(val id: String, val label: String) {
    CASUAL("casual", "Casual"),
    POLITE("polite", "Polite");

    fun toggled() = if (this == CASUAL) POLITE else CASUAL

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: CASUAL

        /** Languages the switch is offered for. */
        fun appliesTo(target: String) = target.substringBefore('-').substringBefore('_').equals("fr", ignoreCase = true)
    }
}

/** Everything the engine needs apart from the key, which lives in [ApiKeyStore]. */
data class CloudConfig(val provider: CloudProvider, val baseUrl: String, val model: String) {
    val normalizedBaseUrl get() = baseUrl.trim().trimEnd('/')

    /** Null when usable, otherwise what the user has to fix. */
    fun problem(hasKey: Boolean): String? = when {
        !hasKey -> "Add an API key in Translation settings"
        model.isBlank() -> "Choose a model in Translation settings"
        !normalizedBaseUrl.startsWith("https://") -> "The provider URL must start with https://"
        else -> null
    }
}
