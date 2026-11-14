package com.stocktracker.packs;

import java.util.List;

/**
 * Everything Ananke learned about a Hugging Face repo at one pinned commit,
 * plus its verdict on running it with vLLM. Stored as the Pack's profile JSON.
 *
 * @param compatibility "supported" (known architecture, text task, no blockers),
 *                      "unverified" (might work; Ananke has not checked this
 *                      architecture), or "unsupported" (a hard blocker).
 */
public record ModelProfile(
        String repo,
        String revision,
        String requestedRevision,
        String task,
        List<String> architectures,
        String modelType,
        String license,
        String gated,
        boolean isPrivate,
        Integer contextLength,
        String torchDtype,
        String quantization,
        Integer quantBits,
        Long parameters,
        Double estimatedWeightsGiB,
        boolean hasChatTemplate,
        boolean chatTemplateMentionsTools,
        boolean requiresRemoteCode,
        boolean hasSafetensors,
        String suggestedToolParser,
        String compatibility,
        List<String> notes,
        List<String> blockers) { }
