package dev.idoodler.labimporter.ai.gemma

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/** The few LiteRT-LM calls [dev.idoodler.labimporter.ai.GemmaAi] makes — a seam, so its states and errors are testable without the native runtime. */
fun interface GemmaRuntime {
    /**
     * Loads the model and runs one step with it (blocking, seconds); throws
     * when [gpu] — or the CPU — can't run it.
     */
    fun load(modelPath: String, gpu: Boolean): GemmaSession
}

/** A loaded model; [close] frees its memory. */
interface GemmaSession : AutoCloseable {
    /** The reply as it streams: one piece (a token) per emission. */
    fun generate(instructions: String, prompt: String, responseSchema: String?, maxOutputTokens: Int): Flow<String>
}

/**
 * LiteRT-LM, as checked on the Mac (`evaluation/`): greedy decoding, no
 * thinking, and with a schema the output is constrained to it.
 *
 * @param cacheDir where the GPU backend keeps its compiled kernels between launches.
 */
class LiteRtGemmaRuntime(private val cacheDir: File) : GemmaRuntime {
    override fun load(modelPath: String, gpu: Boolean): GemmaSession {
        cacheDir.mkdirs()
        val engine = Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = if (gpu) Backend.GPU() else Backend.CPU(),
                maxNumTokens = MAX_TOKENS,
                cacheDir = cacheDir.absolutePath,
            ),
        )
        try {
            engine.initialize()
            // The GPU backend initializes without a GPU driver and only fails at the first step
            // ("Can not find OpenCL library on this device", seen on the emulator): take that step
            // now, so a phone without one falls back to the CPU instead of failing every report.
            engine.createConversation(ConversationConfig(maxOutputToken = 1)).use { it.sendMessage("OK") }
        } catch (e: Throwable) {
            engine.close()
            // What the GPU backend converted before failing (1.5 GB of weights on the Mac) is of no use to the CPU.
            if (gpu) cacheDir.deleteRecursively()
            throw e
        }
        return LiteRtSession(engine)
    }

    private class LiteRtSession(private val engine: Engine) : GemmaSession {
        override fun generate(instructions: String, prompt: String, responseSchema: String?, maxOutputTokens: Int): Flow<String> = flow {
            val conversation = engine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(instructions),
                    samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 0),
                    thinkingConfig = ThinkingConfig(enableThinking = false),
                    enableResponseFormat = responseSchema != null,
                    maxOutputToken = maxOutputTokens,
                ),
            )
            try {
                conversation.sendMessageAsync(prompt, responseFormat = responseSchema?.let(ResponseFormat::json)).collect { message ->
                    emit(message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text })
                }
            } catch (e: CancellationException) {
                conversation.cancelProcess()
                throw e
            } finally {
                conversation.close()
            }
        }

        override fun close() = engine.close()
    }

    companion object {
        /**
         * Prompt plus reply, the size the quality check ran with — about what
         * Gemini Nano takes, so the same reports fit.
         */
        const val MAX_TOKENS = 4096
    }
}
