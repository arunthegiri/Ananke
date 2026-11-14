package com.stocktracker.packs;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What Ananke is willing to claim about running a Hugging Face model on vLLM.
 *
 * <p>This is deliberately a short, curated list of decoder-only text
 * architectures vLLM has supported for a long time — not vLLM's full registry.
 * Anything outside it is reported as "unverified" (the user may still try),
 * never as supported. Tool-calling parsers are only suggested for families
 * whose chat templates and vLLM parsers are known to pair up, and are still
 * verified with a live probe after deployment before the copilot relies on them.
 */
public final class VllmCompatibility {

    private VllmCompatibility() { }

    public static final Set<String> KNOWN_ARCHITECTURES = Set.of(
            "LlamaForCausalLM", "MistralForCausalLM", "MixtralForCausalLM",
            "Qwen2ForCausalLM", "Qwen2MoeForCausalLM", "Qwen3ForCausalLM", "Qwen3MoeForCausalLM",
            "GemmaForCausalLM", "Gemma2ForCausalLM", "Phi3ForCausalLM",
            "GraniteForCausalLM", "Olmo2ForCausalLM", "DeepseekV2ForCausalLM", "DeepseekV3ForCausalLM",
            "InternLM2ForCausalLM", "CohereForCausalLM", "Starcoder2ForCausalLM");

    /** Text-generation pipeline tags we accept. Everything else (embeddings, vision-only, audio) is refused. */
    public static final Set<String> TEXT_TASKS = Set.of("text-generation", "conversational", "text2text-generation");

    /** Architecture → vLLM {@code --tool-call-parser}. Only used when the chat template mentions tools. */
    private static final Map<String, String> TOOL_PARSERS = Map.of(
            "LlamaForCausalLM", "llama3_json",
            "Qwen2ForCausalLM", "hermes",
            "Qwen2MoeForCausalLM", "hermes",
            "Qwen3ForCausalLM", "hermes",
            "Qwen3MoeForCausalLM", "hermes",
            "MistralForCausalLM", "mistral",
            "MixtralForCausalLM", "mistral",
            "GraniteForCausalLM", "granite");

    /** Quantization methods vLLM can load from a checkpoint's {@code quantization_config}. */
    public static final Set<String> KNOWN_QUANT = Set.of("awq", "gptq", "fp8", "compressed-tensors", "bitsandbytes");

    public static String toolParserFor(String architecture, boolean templateMentionsTools) {
        if (!templateMentionsTools || architecture == null) return null;
        return TOOL_PARSERS.get(architecture);
    }

    /**
     * Rough weights-only VRAM need in GiB, plus 20% for activations/CUDA graphs.
     * KV cache comes out of whatever is left, so this is a floor, not a promise.
     */
    public static Double estimateWeightsGiB(Long params, String quantMethod, Integer quantBits, String dtype) {
        if (params == null || params <= 0) return null;
        double bytesPerParam;
        if (quantBits != null && quantBits > 0) bytesPerParam = quantBits / 8.0;
        else if ("fp8".equals(quantMethod)) bytesPerParam = 1.0;
        else if (quantMethod != null && List.of("awq", "gptq").contains(quantMethod)) bytesPerParam = 0.5;
        else if ("float32".equals(dtype)) bytesPerParam = 2.0; // vLLM serves fp32 checkpoints as bf16/fp16
        else bytesPerParam = 2.0;
        return Math.round(params * bytesPerParam * 1.2 / (1L << 30) * 10) / 10.0;
    }
}
