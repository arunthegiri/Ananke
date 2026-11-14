package com.stocktracker.packs;

import com.stocktracker.packs.runtime.RemoteRuntime;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

/** Pre-deploy machine checks — the "missing GPU / VRAM" failures. */
class PackServiceTest {

    private static ModelProfile profile(Double gib) {
        return new ModelProfile("Qwen/Qwen3-8B", "0".repeat(40), "main", "text-generation", List.of("Qwen3ForCausalLM"),
                "qwen3", "apache-2.0", "false", false, 40960, "bfloat16", null, null, 8_000_000_000L, gib,
                true, true, false, true, "hermes", "supported", List.of(), List.of());
    }

    private static RemoteRuntime.Probe machine(int... gpuMiB) {
        return new RemoteRuntime.Probe("Ubuntu", "x86_64", "3.10", true, 200,
                java.util.Arrays.stream(gpuMiB).mapToObj(m -> new RemoteRuntime.Gpu("GPU", m, "550")).toList());
    }

    private static final LaunchConfig CFG = new LaunchConfig("auto", 8192, 0.9, 1, 8000, true);

    private static PackException.Code code(Runnable r) {
        try { r.run(); } catch (PackException e) { return e.code(); }
        return null;
    }

    @Test
    void noGpu() {
        assertThat(code(() -> PackService.checkMachine(machine(), profile(18.0), CFG))).isEqualTo(PackException.Code.NO_GPU);
    }

    @Test
    void tooLittleVram() {
        assertThat(code(() -> PackService.checkMachine(machine(16_000), profile(18.0), CFG))).isEqualTo(PackException.Code.INSUFFICIENT_VRAM);
    }

    @Test
    void enoughVram() {
        assertThatCode(() -> PackService.checkMachine(machine(24_000), profile(18.0), CFG)).doesNotThrowAnyException();
    }

    @Test
    void tensorParallelNeedsGpus() {
        LaunchConfig tp2 = new LaunchConfig("auto", 8192, 0.9, 2, 8000, true);
        assertThat(code(() -> PackService.checkMachine(machine(80_000), profile(18.0), tp2))).isEqualTo(PackException.Code.INSUFFICIENT_VRAM);
    }

    @Test
    void launchConfigRejectsOutOfRange() {
        assertThatThrownBy(() -> new LaunchConfig("float8", 0, 0.9, 1, 8000, false).validated()).isInstanceOf(PackException.class);
        assertThatThrownBy(() -> new LaunchConfig("auto", 0, 1.5, 1, 8000, false).validated()).isInstanceOf(PackException.class);
        assertThatThrownBy(() -> new LaunchConfig("auto", 0, 0.9, 1, 22, false).validated()).isInstanceOf(PackException.class);
    }
}
