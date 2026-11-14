package com.stocktracker.agent.skills;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which skill packs the user has switched off in Settings (§9.4). A small JSON file beside
 * the LLM settings ({@code ananke.skills.settings-file}); packs are on unless listed here.
 * Not secret, so no special permissions.
 */
@Slf4j
public class SkillSettingsService {

    public static final class Stored {
        public Set<String> disabledPacks = new TreeSet<>();
    }

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();
    private Stored stored;

    public SkillSettingsService(Path file) {
        this.file = file;
        this.stored = read();
    }

    public synchronized boolean isEnabled(String packId) {
        return !stored.disabledPacks.contains(packId);
    }

    public synchronized void setEnabled(String packId, boolean enabled) {
        Stored next = new Stored();
        next.disabledPacks.addAll(stored.disabledPacks);
        if (enabled) next.disabledPacks.remove(packId);
        else next.disabledPacks.add(packId);
        write(next);
        stored = next;
    }

    private Stored read() {
        if (file == null || !Files.isRegularFile(file)) return new Stored();
        try {
            Stored s = mapper.readValue(file.toFile(), Stored.class);
            if (s.disabledPacks == null) s.disabledPacks = new TreeSet<>();
            return s;
        } catch (IOException e) {
            // Fail safe: an unreadable file must not silently turn every pack back on.
            throw new IllegalStateException("Cannot read skill settings " + file + ": " + e.getMessage(), e);
        }
    }

    private void write(Stored s) {
        if (file == null) return;
        try {
            Path dir = file.toAbsolutePath().getParent();
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, ".skills-settings", ".json");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), s);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot save skill settings: " + e.getMessage(), e);
        }
    }
}
