package com.stocktracker.packs;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The only knobs a user can turn on a vLLM launch. Each is validated here and
 * again inside vllm-bootstrap.sh; there is no free-form "extra args" field.
 */
public record LaunchConfig(String dtype, int maxModelLen, double gpuMemoryUtilization,
                           int tensorParallelSize, int remotePort, boolean enableToolCalling) {

    static final Set<String> DTYPES = Set.of("auto", "bfloat16", "float16");

    public static LaunchConfig defaults(ModelProfile p) {
        int len = p.contextLength() == null ? 0 : Math.min(p.contextLength(), 32768);
        return new LaunchConfig("auto", len, 0.90, 1, 8000, p.suggestedToolParser() != null);
    }

    public LaunchConfig validated() {
        if (!DTYPES.contains(dtype)) throw new PackException(PackException.Code.INVALID_INPUT, "dtype must be auto, bfloat16 or float16");
        if (maxModelLen != 0 && (maxModelLen < 256 || maxModelLen > 2_000_000)) {
            throw new PackException(PackException.Code.INVALID_INPUT, "Max context must be 0 (model default) or 256–2,000,000");
        }
        if (gpuMemoryUtilization < 0.10 || gpuMemoryUtilization > 0.99) {
            throw new PackException(PackException.Code.INVALID_INPUT, "GPU memory utilization must be between 0.10 and 0.99");
        }
        if (tensorParallelSize < 1 || tensorParallelSize > 8) throw new PackException(PackException.Code.INVALID_INPUT, "Tensor parallel size must be 1–8");
        if (remotePort < 1024 || remotePort > 65535) throw new PackException(PackException.Code.INVALID_INPUT, "Port must be 1024–65535");
        return this;
    }

    public String gpuUtilArg() {
        return String.format(java.util.Locale.ROOT, "%.2f", gpuMemoryUtilization);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dtype", dtype);
        m.put("maxModelLen", maxModelLen);
        m.put("gpuMemoryUtilization", gpuMemoryUtilization);
        m.put("tensorParallelSize", tensorParallelSize);
        m.put("remotePort", remotePort);
        m.put("enableToolCalling", enableToolCalling);
        return m;
    }

    public static LaunchConfig fromMap(Map<String, Object> m) {
        return new LaunchConfig(
                String.valueOf(m.getOrDefault("dtype", "auto")),
                ((Number) m.getOrDefault("maxModelLen", 0)).intValue(),
                ((Number) m.getOrDefault("gpuMemoryUtilization", 0.9)).doubleValue(),
                ((Number) m.getOrDefault("tensorParallelSize", 1)).intValue(),
                ((Number) m.getOrDefault("remotePort", 8000)).intValue(),
                Boolean.TRUE.equals(m.get("enableToolCalling")));
    }
}
