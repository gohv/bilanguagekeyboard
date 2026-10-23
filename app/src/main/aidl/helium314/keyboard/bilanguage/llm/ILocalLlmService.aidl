package helium314.keyboard.bilanguage.llm;

interface ILocalLlmService {
    String loadModel(String path);
    /** Prompt split into a cached prefix and the rest; null on failure, see lastError. */
    String generate(String prefix, String rest, int maxTokens, int contextTokens, boolean chat, int requestId);
    oneway void cancel(int requestId);
    String chatTemplate();
    String lastError();
    void unload();
}
