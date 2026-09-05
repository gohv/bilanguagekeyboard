package helium314.keyboard.bilanguage.llm;

interface ILocalLlmService {
    String loadModel(String path);
    String translate(String prompt, int maxTokens);
    String lastError();
    void unload();
}
