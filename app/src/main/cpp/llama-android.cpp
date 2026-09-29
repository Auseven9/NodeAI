#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>

#include "llama.h"

#define TAG "llama-android"
#define LOGe(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGi(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

static std::vector<llama_token> tokenize(const llama_model *model,
                                         const std::string &text,
                                         bool add_special) {
    // First call with a null buffer to learn the token count.
    int n = -llama_tokenize(model, text.c_str(), (int) text.size(),
                            nullptr, 0, add_special, true);
    if (n <= 0) {
        return {};
    }
    std::vector<llama_token> tokens(n);
    int check = llama_tokenize(model, text.c_str(), (int) text.size(),
                               tokens.data(), n, add_special, true);
    if (check < 0) {
        return {};
    }
    tokens.resize(check);
    return tokens;
}

static std::string token_to_piece(const llama_model *model, llama_token token) {
    char buf[256];
    int n = llama_token_to_piece(model, token, buf, sizeof(buf), 0, true);
    if (n < 0) {
        return {};
    }
    return std::string(buf, n);
}

extern "C" {

JNIEXPORT void JNICALL
Java_com_auseven_nodeai_LlamaBridge_backendInit(JNIEnv *, jobject) {
    llama_backend_init();
}

JNIEXPORT void JNICALL
Java_com_auseven_nodeai_LlamaBridge_backendFree(JNIEnv *, jobject) {
    llama_backend_free();
}

JNIEXPORT jlong JNICALL
Java_com_auseven_nodeai_LlamaBridge_loadModel(JNIEnv *env, jobject, jstring pathJ) {
    const char *path = env->GetStringUTFChars(pathJ, nullptr);
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0; // CPU only
    llama_model *model = llama_load_model_from_file(path, mparams);
    env->ReleaseStringUTFChars(pathJ, path);
    if (model == nullptr) {
        LOGe("failed to load model");
        return 0;
    }
    return reinterpret_cast<jlong>(model);
}

JNIEXPORT void JNICALL
Java_com_auseven_nodeai_LlamaBridge_freeModel(JNIEnv *, jobject, jlong modelPtr) {
    if (modelPtr != 0) {
        llama_free_model(reinterpret_cast<llama_model *>(modelPtr));
    }
}

JNIEXPORT jlong JNICALL
Java_com_auseven_nodeai_LlamaBridge_newContext(JNIEnv *, jobject, jlong modelPtr,
                                               jint nCtx, jint nThreads) {
    auto *model = reinterpret_cast<llama_model *>(modelPtr);
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = (uint32_t) nCtx;
    cparams.n_batch = 512;
    cparams.n_threads = nThreads;
    cparams.n_threads_batch = nThreads;
    llama_context *ctx = llama_new_context_with_model(model, cparams);
    if (ctx == nullptr) {
        LOGe("failed to create context");
        return 0;
    }
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_auseven_nodeai_LlamaBridge_freeContext(JNIEnv *, jobject, jlong ctxPtr) {
    if (ctxPtr != 0) {
        llama_free(reinterpret_cast<llama_context *>(ctxPtr));
    }
}

JNIEXPORT void JNICALL
Java_com_auseven_nodeai_LlamaBridge_generate(JNIEnv *env, jobject, jlong modelPtr,
                                             jlong ctxPtr, jstring promptJ,
                                             jint nPredict, jobject callback) {
    auto *model = reinterpret_cast<llama_model *>(modelPtr);
    auto *ctx = reinterpret_cast<llama_context *>(ctxPtr);

    const char *promptC = env->GetStringUTFChars(promptJ, nullptr);
    std::string prompt(promptC);
    env->ReleaseStringUTFChars(promptJ, promptC);

    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");
    if (onToken == nullptr) {
        LOGe("callback method onToken not found");
        return;
    }

    // Fresh decode each turn: clear the KV cache and re-feed the full prompt.
    llama_kv_cache_clear(ctx);

    std::vector<llama_token> tokens = tokenize(model, prompt, true);
    if (tokens.empty()) {
        LOGe("tokenize produced no tokens");
        return;
    }

    const int n_ctx = llama_n_ctx(ctx);
    if ((int) tokens.size() >= n_ctx) {
        LOGe("prompt too long: %d >= %d", (int) tokens.size(), n_ctx);
        return;
    }

    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    llama_batch batch = llama_batch_init((int) tokens.size(), 0, 1);
    for (int i = 0; i < (int) tokens.size(); i++) {
        batch.token[i] = tokens[i];
        batch.pos[i] = i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] = false;
    }
    batch.n_tokens = (int) tokens.size();
    batch.logits[batch.n_tokens - 1] = true;

    if (llama_decode(ctx, batch) != 0) {
        LOGe("llama_decode (prompt) failed");
        llama_batch_free(batch);
        llama_sampler_free(smpl);
        return;
    }

    int n_cur = batch.n_tokens;
    int n_decoded = 0;

    while (n_decoded < nPredict) {
        llama_token new_token = llama_sampler_sample(smpl, ctx, -1);
        if (llama_token_is_eog(model, new_token)) {
            break;
        }

        std::string piece = token_to_piece(model, new_token);
        if (!piece.empty()) {
            jstring js = env->NewStringUTF(piece.c_str());
            env->CallVoidMethod(callback, onToken, js);
            env->DeleteLocalRef(js);
        }

        // Feed the sampled token back in as the next single-token batch.
        batch.n_tokens = 1;
        batch.token[0] = new_token;
        batch.pos[0] = n_cur;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = true;

        n_cur++;
        n_decoded++;

        if (llama_decode(ctx, batch) != 0) {
            LOGe("llama_decode (generation) failed");
            break;
        }
    }

    llama_batch_free(batch);
    llama_sampler_free(smpl);
}

} // extern "C"
