// JNI surface of the read-in-language translator (decisions #162): one GGUF
// session per process, greedy decoding, chat template applied by the model's
// own template. The Kotlin side ([LlamaTranslator]) owns the message shape,
// the single-flight lock and the close policy; this file owns llama.cpp.
//
// Generated the exact measured text on the host for the 40-sentence FLORES
// gate through the same call sequence (prompt token counts matched the device
// llama-server run 44/68/55/82/60/53 token-for-token — decisions #162).

#include <jni.h>

#include <mutex>
#include <string>
#include <vector>

#include <android/log.h>

#include "ggml-backend.h"
#include "llama.h"

#define LOG_TAG "LlamaTranslator"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Passages measure <= 419 chars (~110 prompt tokens) and the measured gate ran
// the model with -c 8192 server-side; 2048 is cheap headroom over any passage.
constexpr int32_t N_CTX = 2048;

// One loaded session: model + context + the greedy sampler chain, guarded by a
// mutex (llama_decode on one context is not re-entrant; the Kotlin wrapper also
// serializes, this is the defensive layer).
struct Session {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    llama_sampler * smpl = nullptr;
    const llama_vocab * vocab = nullptr;
    std::mutex mutex;
};

void throw_illegal_state(JNIEnv * env, const std::string & message) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls != nullptr) {
        env->ThrowNew(cls, message.c_str());
    } else {
        LOGE("%s", message.c_str());
    }
}

std::string to_string(JNIEnv * env, jstring value) {
    if (value == nullptr) {
        return {};
    }
    const char * chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        return {};
    }
    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

// Rendered byte length of the prompt tail that is not a complete UTF-8
// sequence. The generation loop detokenizes token by token, so a multi-byte
// character split across the max-token boundary would otherwise surface as a
// replacement character in the middle of a word.
size_t partial_utf8_tail(const std::string & text) {
    size_t i = text.size();
    while (i > 0 && (static_cast<unsigned char>(text[i - 1]) & 0xC0) == 0x80) {
        --i;
    }
    if (i == 0) {
        return 0;
    }
    const unsigned char lead = static_cast<unsigned char>(text[i - 1]);
    size_t expected = 0;
    if ((lead & 0x80) == 0x00) {
        expected = 1;
    } else if ((lead & 0xE0) == 0xC0) {
        expected = 2;
    } else if ((lead & 0xF0) == 0xE0) {
        expected = 3;
    } else if ((lead & 0xF8) == 0xF0) {
        expected = 4;
    } else {
        return text.size() - (i - 1); // invalid lead byte: drop it and its tail
    }
    const size_t have = text.size() - (i - 1);
    return have < expected ? text.size() - (i - 1) : 0;
}

std::string render_prompt(JNIEnv * env, Session * s, const std::string & user_message) {
    const char * tmpl = llama_model_chat_template(s->model, nullptr);
    llama_chat_message chat[1] = {{"user", user_message.c_str()}};
    // Recommended sizing: 2x the total message characters.
    std::vector<char> buf(2 * user_message.size() + 1024);
    int32_t n = llama_chat_apply_template(tmpl, chat, 1, /* add_ass */ true, buf.data(), (int32_t) buf.size());
    if (n < 0) {
        throw_illegal_state(env, "llama_chat_apply_template failed (n=" + std::to_string(n) + ")");
        return {};
    }
    if (n > (int32_t) buf.size()) {
        buf.resize(static_cast<size_t>(n) + 1);
        n = llama_chat_apply_template(tmpl, chat, 1, true, buf.data(), (int32_t) buf.size());
        if (n < 0) {
            throw_illegal_state(env, "llama_chat_apply_template failed on retry (n=" + std::to_string(n) + ")");
            return {};
        }
    }
    return std::string(buf.data(), static_cast<size_t>(n));
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_moronigranja_localttsreader_llm_LlamaTranslator_nativeLoadModel(
    JNIEnv * env,
    jclass,
    jstring path,
    jint n_threads,
    jstring backend_dir) {
    const std::string model_path = to_string(env, path);
    const std::string backend = to_string(env, backend_dir);

    llama_backend_init();
    // GGML_BACKEND_DL builds the CPU backend as dl-loaded modules; the default
    // search paths (executable dir, cwd) are useless inside an Android app, so
    // the caller passes the app's native library dir where AGP packed them.
    if (!backend.empty()) {
        ggml_backend_load_all_from_path(backend.c_str());
    }

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    llama_model * model = llama_model_load_from_file(model_path.c_str(), mparams);
    if (model == nullptr) {
        throw_illegal_state(env, "unable to load model: " + model_path);
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = N_CTX;
    cparams.n_batch = N_CTX;
    cparams.n_threads = n_threads > 0 ? n_threads : 4;
    cparams.n_threads_batch = cparams.n_threads;
    cparams.no_perf = true;

    llama_context * ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        llama_model_free(model);
        throw_illegal_state(env, "unable to create llama context (n_ctx=" + std::to_string(N_CTX) + ")");
        return 0;
    }

    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    sparams.no_perf = true;
    llama_sampler * smpl = llama_sampler_chain_init(sparams);
    // Greedy: the measured gate ran temperature 0 (docs/prints/beam-spike/lfm12_gate.py).
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    auto * session = new Session{model, ctx, smpl, llama_model_get_vocab(model)};
    LOGI("loaded %s (n_threads=%d)", model_path.c_str(), static_cast<int>(cparams.n_threads));
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT jbyteArray JNICALL
Java_com_moronigranja_localttsreader_llm_LlamaTranslator_nativeCompleteBytes(
    JNIEnv * env,
    jclass,
    jlong handle,
    jstring user_message,
    jint max_tokens) {
    auto * s = reinterpret_cast<Session *>(handle);
    if (s == nullptr) {
        throw_illegal_state(env, "translate called on a closed session");
        return nullptr;
    }

    std::lock_guard<std::mutex> lock(s->mutex);
    const std::string message = to_string(env, user_message);

    const std::string prompt = render_prompt(env, s, message);
    if (env->ExceptionCheck()) {
        return nullptr;
    }

    // add_special=true, parse_special=true: identical to llama-server's chat
    // path (server-context.cpp tokenize_input_prompts(vocab, mctx, prompt, true, true)),
    // so the token stream matches the measured gate.
    const int32_t n_prompt = -llama_tokenize(s->vocab, prompt.c_str(), prompt.size(), nullptr, 0, true, true);
    if (n_prompt <= 0 || n_prompt > N_CTX) {
        throw_illegal_state(env, "prompt does not fit the context (tokens=" + std::to_string(n_prompt) + ")");
        return nullptr;
    }
    std::vector<llama_token> tokens(static_cast<size_t>(n_prompt));
    if (llama_tokenize(s->vocab, prompt.c_str(), prompt.size(), tokens.data(), n_prompt, true, true) < 0) {
        throw_illegal_state(env, "failed to tokenize the rendered prompt");
        return nullptr;
    }

    // One session serves passage after passage: reset the KV cache between runs.
    llama_memory_clear(llama_get_memory(s->ctx), true);

    llama_batch batch = llama_batch_get_one(tokens.data(), n_prompt);
    std::string out;
    int n_pos = 0;
    int remaining = max_tokens;
    while (n_pos + batch.n_tokens <= N_CTX && remaining > 0) {
        if (llama_decode(s->ctx, batch) != 0) {
            throw_illegal_state(env, "llama_decode failed");
            return nullptr;
        }
        n_pos += batch.n_tokens;

        llama_token id = llama_sampler_sample(s->smpl, s->ctx, -1);
        if (llama_vocab_is_eog(s->vocab, id)) {
            break;
        }
        char piece[256];
        int32_t n_piece = llama_token_to_piece(s->vocab, id, piece, sizeof(piece), 0, /* special */ false);
        if (n_piece < 0) {
            std::vector<char> big(static_cast<size_t>(-n_piece));
            n_piece = llama_token_to_piece(s->vocab, id, big.data(), (int32_t) big.size(), 0, false);
            if (n_piece < 0) {
                throw_illegal_state(env, "failed to detokenize a sampled token");
                return nullptr;
            }
            out.append(big.data(), static_cast<size_t>(n_piece));
        } else {
            out.append(piece, static_cast<size_t>(n_piece));
        }

        --remaining;
        batch = llama_batch_get_one(&id, 1);
    }

    if (const size_t tail = partial_utf8_tail(out); tail > 0) {
        out.resize(out.size() - tail);
    }

    jbyteArray result = env->NewByteArray(static_cast<jsize>(out.size()));
    if (result == nullptr) {
        return nullptr;
    }
    env->SetByteArrayRegion(
        result, 0, static_cast<jsize>(out.size()), reinterpret_cast<const jbyte *>(out.data()));
    return result;
}

JNIEXPORT void JNICALL
Java_com_moronigranja_localttsreader_llm_LlamaTranslator_nativeClose(JNIEnv *, jclass, jlong handle) {
    auto * s = reinterpret_cast<Session *>(handle);
    if (s == nullptr) {
        return;
    }
    {
        std::lock_guard<std::mutex> lock(s->mutex);
        llama_sampler_free(s->smpl);
        llama_free(s->ctx);
        llama_model_free(s->model);
        s->smpl = nullptr;
        s->ctx = nullptr;
        s->model = nullptr;
    }
    delete s;
    LOGI("session closed");
}

} // extern "C"