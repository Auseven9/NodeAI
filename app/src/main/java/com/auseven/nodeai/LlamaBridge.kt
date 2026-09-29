package com.auseven.nodeai

/**
 * Thin JNI wrapper around the llama.cpp native library (libllama-android.so).
 * All pointers are opaque [Long] handles into native memory.
 */
class LlamaBridge {

    /** Called once per generated token (a decoded text piece). */
    fun interface TokenCallback {
        fun onToken(token: String)
    }

    external fun backendInit()
    external fun backendFree()

    /** Returns a model handle, or 0 on failure. */
    external fun loadModel(path: String): Long
    external fun freeModel(modelPtr: Long)

    /** Returns a context handle, or 0 on failure. */
    external fun newContext(modelPtr: Long, nCtx: Int, nThreads: Int): Long
    external fun freeContext(ctxPtr: Long)

    /**
     * Formats a conversation with the model's embedded chat template.
     * Returns null if the model has no template (caller applies a fallback).
     */
    external fun applyChatTemplate(
        modelPtr: Long,
        roles: Array<String>,
        contents: Array<String>,
        addAssistant: Boolean,
    ): String?

    /** Runs generation synchronously, streaming each token to [callback]. */
    external fun generate(
        modelPtr: Long,
        ctxPtr: Long,
        prompt: String,
        nPredict: Int,
        callback: TokenCallback,
    )

    companion object {
        init {
            System.loadLibrary("llama-android")
        }
    }
}
