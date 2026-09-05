#include <android/log.h>
#include <jni.h>
#include <unistd.h>

#include <algorithm>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

namespace {

constexpr char LOG_TAG[] = "BiLangKbLLM";
constexpr int CONTEXT_TOKENS = 256;

std::mutex g_mutex;
llama_model *g_model = nullptr;
std::string g_loaded_path;
std::string g_last_error;
bool g_backend_initialized = false;

void set_error(const std::string &message) {
    g_last_error = message;
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "%s", message.c_str());
}

void llama_log_callback(ggml_log_level level, const char *text, void *) {
    if (level == GGML_LOG_LEVEL_ERROR || level == GGML_LOG_LEVEL_WARN) {
        __android_log_print(
            level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN,
            LOG_TAG,
            "%s",
            text
        );
    }
}

std::string bytes_from_java(JNIEnv *env, jbyteArray input) {
    const jsize size = env->GetArrayLength(input);
    std::string value(static_cast<size_t>(size), '\0');
    if (size > 0) {
        env->GetByteArrayRegion(input, 0, size, reinterpret_cast<jbyte *>(value.data()));
    }
    return value;
}

jbyteArray bytes_to_java(JNIEnv *env, const std::string &value) {
    auto result = env->NewByteArray(static_cast<jsize>(value.size()));
    if (result != nullptr && !value.empty()) {
        env->SetByteArrayRegion(
            result,
            0,
            static_cast<jsize>(value.size()),
            reinterpret_cast<const jbyte *>(value.data())
        );
    }
    return result;
}

std::string token_piece(const llama_vocab *vocab, llama_token token) {
    std::vector<char> buffer(128);
    int length = llama_token_to_piece(vocab, token, buffer.data(), buffer.size(), 0, true);
    if (length < 0) {
        buffer.resize(static_cast<size_t>(-length));
        length = llama_token_to_piece(vocab, token, buffer.data(), buffer.size(), 0, true);
    }
    return length > 0 ? std::string(buffer.data(), static_cast<size_t>(length)) : std::string();
}

void unload_locked() {
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    g_loaded_path.clear();
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_helium314_keyboard_bilanguage_llm_LocalLlmNative_nativeInit(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_backend_initialized) return;
    llama_log_set(llama_log_callback, nullptr);
    llama_backend_init();
    g_backend_initialized = true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_helium314_keyboard_bilanguage_llm_LocalLlmNative_nativeLoadModel(
    JNIEnv *env,
    jobject,
    jstring model_path
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    const char *chars = env->GetStringUTFChars(model_path, nullptr);
    if (chars == nullptr) {
        set_error("Could not read model path");
        return JNI_FALSE;
    }
    const std::string path(chars);
    env->ReleaseStringUTFChars(model_path, chars);

    if (g_model != nullptr && g_loaded_path == path) return JNI_TRUE;
    unload_locked();
    g_last_error.clear();

    llama_model_params params = llama_model_default_params();
    params.n_gpu_layers = 0;
    params.load_mode = LLAMA_LOAD_MODE_MMAP;
    g_model = llama_model_load_from_file(path.c_str(), params);
    if (g_model == nullptr) {
        set_error("llama.cpp could not load the GGUF model");
        return JNI_FALSE;
    }
    g_loaded_path = path;
    return JNI_TRUE;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_helium314_keyboard_bilanguage_llm_LocalLlmNative_nativeTranslate(
    JNIEnv *env,
    jobject,
    jbyteArray prompt_bytes,
    jint max_tokens
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_last_error.clear();
    if (g_model == nullptr) {
        set_error("No local model is loaded");
        return nullptr;
    }

    const std::string prompt = bytes_from_java(env, prompt_bytes);
    const llama_vocab *vocab = llama_model_get_vocab(g_model);
    int token_count = llama_tokenize(
        vocab, prompt.data(), static_cast<int32_t>(prompt.size()), nullptr, 0, true, true
    );
    if (token_count >= 0) {
        set_error("Unexpected tokenizer sizing result");
        return nullptr;
    }
    token_count = -token_count;
    if (token_count <= 0 || token_count + max_tokens > CONTEXT_TOKENS) {
        set_error("Text is too long for the local model's 256-token context");
        return nullptr;
    }

    std::vector<llama_token> prompt_tokens(static_cast<size_t>(token_count));
    const int encoded = llama_tokenize(
        vocab,
        prompt.data(),
        static_cast<int32_t>(prompt.size()),
        prompt_tokens.data(),
        token_count,
        true,
        true
    );
    if (encoded < 0) {
        set_error("Failed to tokenize translation prompt");
        return nullptr;
    }
    prompt_tokens.resize(static_cast<size_t>(encoded));

    const int available_cpus = static_cast<int>(sysconf(_SC_NPROCESSORS_ONLN));
    const int threads = std::clamp(available_cpus - 2, 2, 6);
    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = CONTEXT_TOKENS;
    context_params.n_batch = CONTEXT_TOKENS;
    context_params.n_ubatch = CONTEXT_TOKENS;
    context_params.n_threads = threads;
    context_params.n_threads_batch = threads;
    context_params.no_perf = true;
    llama_context *context = llama_init_from_model(g_model, context_params);
    if (context == nullptr) {
        set_error("Failed to create llama.cpp inference context");
        return nullptr;
    }

    llama_sampler *sampler = llama_sampler_init_greedy();
    llama_batch batch = llama_batch_get_one(prompt_tokens.data(), static_cast<int32_t>(prompt_tokens.size()));
    std::string output;
    bool failed = false;
    llama_token next_token = 0;
    for (int generated = 0; generated < max_tokens; ++generated) {
        if (llama_decode(context, batch) != 0) {
            set_error("llama.cpp failed while decoding");
            failed = true;
            break;
        }
        next_token = llama_sampler_sample(sampler, context, -1);
        if (llama_vocab_is_eog(vocab, next_token)) break;

        const std::string piece = token_piece(vocab, next_token);
        const size_t newline = piece.find('\n');
        output.append(piece, 0, newline);
        if (newline != std::string::npos) break;
        batch = llama_batch_get_one(&next_token, 1);
    }

    llama_sampler_free(sampler);
    llama_free(context);
    if (failed || output.empty()) {
        if (!failed) set_error("The local model produced no translation");
        return nullptr;
    }
    return bytes_to_java(env, output);
}

extern "C" JNIEXPORT void JNICALL
Java_helium314_keyboard_bilanguage_llm_LocalLlmNative_nativeUnload(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    unload_locked();
}

extern "C" JNIEXPORT jstring JNICALL
Java_helium314_keyboard_bilanguage_llm_LocalLlmNative_nativeLastError(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return env->NewStringUTF(g_last_error.c_str());
}
