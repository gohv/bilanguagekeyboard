#include <android/log.h>
#include <jni.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

namespace {

constexpr char LOG_TAG[] = "BiLangKbLLM";
constexpr int RAW_CONTEXT_TOKENS = 256;

/** End-of-turn markers of common chat templates, in case a GGUF does not flag them as EOG. */
const char *const CHAT_STOPS[] = {"<turn|>", "<end_of_turn>", "<|im_end|>", "<|eot_id|>", "</s>"};

std::mutex g_mutex;
llama_model *g_model = nullptr;
std::string g_loaded_path;
std::string g_last_error;
bool g_backend_initialized = false;

// Kept across calls: building a context per translation costs allocation every time, and the
// instruction prefix is identical from one call to the next.
llama_context *g_ctx = nullptr;
int g_ctx_size = 0;
bool g_ctx_chat = false;
// Snapshot of sequence 0 right after the prompt prefix (the fixed instruction). Restoring it skips
// re-processing the prefix. A snapshot rather than KV trimming, because hybrid/recurrent models
// (Qwen3.5, Gemma 3n…) cannot roll back part of a sequence.
std::vector<llama_token> g_prefix_tokens;
std::vector<uint8_t> g_prefix_state;

// Cancellation must not take g_mutex: it is held by the generation being cancelled.
std::atomic<int> g_active_request{0};
std::atomic<bool> g_abort{false};

bool abort_callback(void *) { return g_abort.load(std::memory_order_relaxed); }

int cpu_count() { return std::max(1, static_cast<int>(sysconf(_SC_NPROCESSORS_ONLN))); }

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

void free_context_locked() {
    if (g_ctx != nullptr) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    g_ctx_size = 0;
    g_prefix_tokens.clear();
    g_prefix_state.clear();
}

std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &text, bool add_bos) {
    if (text.empty()) return {};
    int n = -llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()), nullptr, 0, add_bos, true);
    if (n <= 0) return {};
    std::vector<llama_token> tokens(static_cast<size_t>(n));
    n = llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()), tokens.data(), n, add_bos, true);
    if (n < 0) return {};
    tokens.resize(static_cast<size_t>(n));
    return tokens;
}

/** Decode in n_batch-sized chunks. Returns 0 on success, non-zero on failure or abort. */
int decode_all(llama_context *ctx, std::vector<llama_token> &tokens) {
    const int batch = static_cast<int>(llama_n_batch(ctx));
    for (size_t at = 0; at < tokens.size(); at += static_cast<size_t>(batch)) {
        const int n = static_cast<int>(std::min(tokens.size() - at, static_cast<size_t>(batch)));
        const int rc = llama_decode(ctx, llama_batch_get_one(tokens.data() + at, n));
        if (rc != 0) return rc;
    }
    return 0;
}

void unload_locked() {
    free_context_locked();
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
        // A file in shared storage arrives as /proc/self/fd/N; if it cannot be mapped, read it.
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "mmap load failed, retrying with plain reads");
        params.load_mode = LLAMA_LOAD_MODE_NONE;
        g_model = llama_model_load_from_file(path.c_str(), params);
    }
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
    jbyteArray prefix_bytes,
    jbyteArray rest_bytes,
    jint max_tokens,
    jint context_tokens_arg,
    jboolean chat_mode,
    jint request_id
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_last_error.clear();
    g_active_request.store(request_id);
    g_abort.store(false);
    if (g_model == nullptr) {
        set_error("No local model is loaded");
        return nullptr;
    }

    // Raw mode is the fine-tuned model: tiny context, one line out. Chat mode is any instruction
    // model: room for the instruction, output until the end-of-turn marker.
    const bool chat = chat_mode == JNI_TRUE;
    const int context_tokens = chat ? std::max(512, static_cast<int>(context_tokens_arg)) : RAW_CONTEXT_TOKENS;
    const llama_vocab *vocab = llama_model_get_vocab(g_model);
    const std::string prefix = bytes_from_java(env, prefix_bytes);
    const std::string rest = bytes_from_java(env, rest_bytes);
    std::vector<llama_token> prefix_tokens = tokenize(vocab, prefix, true);
    std::vector<llama_token> rest_tokens = tokenize(vocab, rest, prefix_tokens.empty());
    if (rest_tokens.empty()) {
        set_error("Failed to tokenize translation prompt");
        return nullptr;
    }
    if (static_cast<int>(prefix_tokens.size() + rest_tokens.size()) + max_tokens > context_tokens) {
        set_error("Text is too long for the local model's context");
        return nullptr;
    }

    if (g_ctx == nullptr || g_ctx_size != context_tokens || g_ctx_chat != chat) {
        free_context_locked();
        // Prompt processing is compute-bound and scales to every core; generation is
        // memory-bound and slows down once the two fastest cores are also busy. Measured on a
        // Snapdragon 8 Elite: prefill 106 t/s at 6 threads vs 191 at 8; generation 39 vs 17.
        const int cpus = cpu_count();
        llama_context_params params = llama_context_default_params();
        params.n_ctx = context_tokens;
        params.n_batch = std::min(context_tokens, 512);
        params.n_ubatch = params.n_batch;
        params.n_threads = std::clamp(cpus - 2, 2, 6);
        params.n_threads_batch = std::clamp(cpus, 2, 8);
        params.no_perf = true;
        g_ctx = llama_init_from_model(g_model, params);
        if (g_ctx == nullptr) {
            set_error("Failed to create llama.cpp inference context");
            return nullptr;
        }
        llama_set_abort_callback(g_ctx, abort_callback, nullptr);
        g_ctx_size = context_tokens;
        g_ctx_chat = chat;
    }

    llama_memory_t memory = llama_get_memory(g_ctx);
    llama_memory_clear(memory, true);
    bool restored = false;
    if (!prefix_tokens.empty() && prefix_tokens == g_prefix_tokens && !g_prefix_state.empty()) {
        restored = llama_state_seq_set_data(g_ctx, g_prefix_state.data(), g_prefix_state.size(), 0) > 0;
        if (!restored) llama_memory_clear(memory, true);
    }
    if (!restored && !prefix_tokens.empty()) {
        g_prefix_tokens.clear();
        g_prefix_state.clear();
        if (decode_all(g_ctx, prefix_tokens) != 0) {
            set_error(g_abort.load() ? "Cancelled" : "llama.cpp failed while decoding the prompt");
            llama_memory_clear(memory, true);
            return nullptr;
        }
        const size_t size = llama_state_seq_get_size(g_ctx, 0);
        g_prefix_state.resize(size);
        if (size > 0 && llama_state_seq_get_data(g_ctx, g_prefix_state.data(), size, 0) == size) {
            g_prefix_tokens = prefix_tokens;
        } else {
            g_prefix_state.clear();
        }
    }

    if (decode_all(g_ctx, rest_tokens) != 0) {
        set_error(g_abort.load() ? "Cancelled" : "llama.cpp failed while decoding the prompt");
        return nullptr;
    }

    llama_sampler *sampler = llama_sampler_init_greedy();
    std::string output;
    bool failed = false;
    llama_token next_token = 0;
    for (int generated = 0; generated < max_tokens; ++generated) {
        if (generated > 0 && llama_decode(g_ctx, llama_batch_get_one(&next_token, 1)) != 0) {
            set_error(g_abort.load() ? "Cancelled" : "llama.cpp failed while decoding");
            failed = true;
            break;
        }
        if (g_abort.load()) {
            set_error("Cancelled");
            failed = true;
            break;
        }
        next_token = llama_sampler_sample(sampler, g_ctx, -1);
        if (llama_vocab_is_eog(vocab, next_token)) break;

        const std::string piece = token_piece(vocab, next_token);
        if (chat) {
            output.append(piece);
            bool stop = false;
            for (const char *marker : CHAT_STOPS) {
                const size_t at = output.find(marker);
                if (at != std::string::npos) {
                    output.erase(at);
                    stop = true;
                    break;
                }
            }
            if (stop) break;
        } else {
            const size_t newline = piece.find('\n');
            output.append(piece, 0, newline);
            if (newline != std::string::npos) break;
        }
    }

    llama_sampler_free(sampler);
    if (failed || output.empty()) {
        if (!failed) set_error("The local model produced no translation");
        return nullptr;
    }
    return bytes_to_java(env, output);
}

/** Abort the generation for [request_id] if it is the one running. Never blocks. */
extern "C" JNIEXPORT void JNICALL
Java_helium314_keyboard_bilanguage_llm_LocalLlmNative_nativeCancel(JNIEnv *, jobject, jint request_id) {
    if (g_active_request.load() == request_id) g_abort.store(true);
}

extern "C" JNIEXPORT jstring JNICALL
Java_helium314_keyboard_bilanguage_llm_LocalLlmNative_nativeChatTemplate(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    const char *tmpl = g_model == nullptr ? nullptr : llama_model_chat_template(g_model, nullptr);
    return env->NewStringUTF(tmpl == nullptr ? "" : tmpl);
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
