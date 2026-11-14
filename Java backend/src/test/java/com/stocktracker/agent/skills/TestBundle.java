package com.stocktracker.agent.skills;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds a skill bundle on disk exactly as vendor-skills.mjs lays it out, with real sha256s. */
final class TestBundle {

    static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";

    final Path root;
    private final List<Map<String, Object>> packs = new ArrayList<>();

    TestBundle(Path root) {
        this.root = root;
    }

    static String skillMd(String name, String description, String body) {
        return "---\nname: " + name + "\ndescription: \"" + description + "\"\n---\n\n" + body + "\n";
    }

    PackBuilder pack(String id, String cloud) {
        return new PackBuilder(id, cloud);
    }

    final class PackBuilder {
        final Map<String, Object> pack = new LinkedHashMap<>();
        final List<Map<String, Object>> skills = new ArrayList<>();

        PackBuilder(String id, String cloud) {
            pack.put("id", id);
            pack.put("cloud", cloud);
            pack.put("label", id);
            pack.put("repo", "example/" + id);
            pack.put("commit", COMMIT);
            pack.put("license", "Apache-2.0");
            pack.put("distribution", "vendored");
            pack.put("licenseFiles", List.of(file(id + "/LICENSE", "LICENSE", "Apache License 2.0\n")));
        }

        PackBuilder set(String key, Object value) {
            pack.put(key, value);
            return this;
        }

        /** files: relative path → content; must include SKILL.md. */
        PackBuilder skill(String name, List<String> topics, Map<String, String> files) {
            List<Map<String, Object>> entries = new ArrayList<>();
            String base = pack.get("distribution").equals("fetch") ? null : pack.get("id") + "/" + name + "/";
            files.forEach((rel, content) -> entries.add(base == null ? meta(rel, content) : file(base + rel, rel, content)));
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("name", name);
            s.put("sourcePath", "skills/" + name);
            s.put("topics", topics);
            s.put("files", entries);
            skills.add(s);
            return this;
        }

        TestBundle done() {
            pack.put("skills", skills);
            packs.add(pack);
            return TestBundle.this;
        }
    }

    Path write() throws IOException {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("version", 1);
        manifest.put("packs", packs);
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(root.resolve("manifest.json").toFile(), manifest);
        return root;
    }

    private Map<String, Object> file(String diskPath, String rel, String content) {
        try {
            Path p = root.resolve(diskPath);
            Files.createDirectories(p.getParent());
            Files.writeString(p, content);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return meta(rel, content);
    }

    static Map<String, Object> meta(String rel, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", rel);
        m.put("sha256", SkillRegistry.sha256(content.getBytes(StandardCharsets.UTF_8)));
        return m;
    }
}
