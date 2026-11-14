package com.stocktracker.agent.skills;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocktracker.agent.AgentProfile;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The vetted agent skill packs (Packs build doc §9.2.1), loaded once at startup from a
 * pinned, checksummed bundle described by {@code skills/manifest.json} (written by
 * {@code scripts/skills/vendor-skills.mjs}).
 *
 * <p>Loading is all-or-nothing: a missing file, a sha256 mismatch, bad frontmatter, an
 * unpinned commit or a duplicate skill name throws {@link SkillBundleException}, which
 * fails application startup with every problem listed. A half-trusted bundle never loads.
 *
 * <p>Only files listed in the manifest are readable, which is the first of two defences
 * against path traversal in {@code load_skill}; {@link #normalisePath} is the second.
 */
public final class SkillRegistry {

    /** Reads one bundle-relative file; returns null when absent. */
    @FunctionalInterface
    public interface BundleReader {
        byte[] read(String relPath) throws IOException;
    }

    public record SkillFile(String path, String sha256, String content) { }

    public record Skill(String name, String packId, String cloud, String description,
                        List<String> topics, String sourcePath, String body,
                        Map<String, SkillFile> files) {
        public List<String> filePaths() {
            return List.copyOf(files.keySet());
        }
    }

    public record SkillPack(String id, String cloud, String label, String repo, String commit,
                            String license, String distribution, List<String> licenseFiles,
                            List<Skill> skills) {
        public String sourceUrl() {
            return "https://github.com/" + repo + "/tree/" + commit;
        }
    }

    static final int MAX_DESCRIPTION = 1024;
    static final Set<String> CLOUDS = Set.of("aws", "gcp", "azure");
    static final Pattern SKILL_NAME = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");
    static final Pattern PACK_ID    = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");
    static final Pattern REPO       = Pattern.compile("^[A-Za-z0-9-]+/[A-Za-z0-9._-]+$");
    static final Pattern SHA40      = Pattern.compile("^[0-9a-f]{40}$");
    static final Pattern SHA256     = Pattern.compile("^[0-9a-f]{64}$");
    static final Pattern SAFE_PATH  = Pattern.compile("^[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*$");
    static final Pattern DOC_FILE   = Pattern.compile("(?i).+\\.(md|txt)$");
    private static final Pattern FRONTMATTER =
            Pattern.compile("\\A---\\r?\\n(.*?)\\r?\\n---[ \\t]*(?:\\r?\\n|\\z)", Pattern.DOTALL);

    private final List<SkillPack> packs;
    private final Map<String, Skill> byName;

    private SkillRegistry(List<SkillPack> packs) {
        this.packs = List.copyOf(packs);
        Map<String, Skill> m = new LinkedHashMap<>();
        for (SkillPack p : packs) for (Skill s : p.skills()) m.put(s.name(), s);
        this.byName = m;
    }

    // ── Loading ───────────────────────────────────────────────────────────────

    /** The bundle shipped in the jar ({@code classpath:skills/}); fetched packs come from {@code fetchDir}. */
    public static SkillRegistry fromClasspath(Path fetchDir) {
        ClassLoader cl = SkillRegistry.class.getClassLoader();
        BundleReader classpath = rel -> {
            try (InputStream in = cl.getResourceAsStream("skills/" + rel)) {
                return in == null ? null : in.readAllBytes();
            }
        };
        return load(classpath, directory(fetchDir));
    }

    /** A bundle laid out on disk (tests, or an operator-supplied bundle). */
    public static SkillRegistry fromDirectory(Path bundleDir, Path fetchDir) {
        return load(directory(bundleDir), directory(fetchDir));
    }

    static BundleReader directory(Path root) {
        return rel -> {
            if (root == null) return null;
            Path p = root.resolve(rel).normalize();
            if (!p.startsWith(root.normalize()) || !Files.isRegularFile(p)) return null;
            return Files.readAllBytes(p);
        };
    }

    /**
     * @param vendored reader for committed files (licence texts and {@code vendored} packs) plus manifest.json
     * @param fetched  reader for {@code fetch} packs installed at install time
     */
    public static SkillRegistry load(BundleReader vendored, BundleReader fetched) {
        List<String> errors = new ArrayList<>();
        List<SkillPack> packs = new ArrayList<>();
        JsonNode manifest;
        try {
            byte[] raw = vendored.read("manifest.json");
            if (raw == null) throw new SkillBundleException(List.of("skills/manifest.json not found"));
            manifest = new ObjectMapper().readTree(raw);
        } catch (IOException e) {
            throw new SkillBundleException(List.of("skills/manifest.json unreadable: " + e.getMessage()));
        }
        if (manifest.path("version").asInt() != 1) errors.add("manifest.json: unsupported version");
        if (!manifest.path("packs").isArray()) errors.add("manifest.json: no packs array");

        Set<String> packIds = new LinkedHashSet<>();
        Set<String> skillNames = new LinkedHashSet<>();
        for (JsonNode p : manifest.path("packs")) {
            SkillPack pack = loadPack(p, vendored, fetched, errors, packIds, skillNames);
            if (pack != null) packs.add(pack);
        }
        if (!errors.isEmpty()) throw new SkillBundleException(errors);
        return new SkillRegistry(packs);
    }

    private static SkillPack loadPack(JsonNode p, BundleReader vendored, BundleReader fetched,
                                      List<String> errors, Set<String> packIds, Set<String> skillNames) {
        String id = p.path("id").asText("");
        String where = "pack '" + id + "'";
        int before = errors.size();
        if (!PACK_ID.matcher(id).matches()) errors.add(where + ": invalid id");
        else if (!packIds.add(id)) errors.add(where + ": duplicate pack id");
        String cloud = p.path("cloud").asText("");
        if (!CLOUDS.contains(cloud)) errors.add(where + ": cloud must be one of " + CLOUDS);
        String repo = p.path("repo").asText("");
        if (!REPO.matcher(repo).matches()) errors.add(where + ": repo must be owner/name");
        String commit = p.path("commit").asText("");
        if (!SHA40.matcher(commit).matches()) errors.add(where + ": commit must be a pinned 40-char SHA");
        String license = p.path("license").asText("");
        if (license.isBlank()) errors.add(where + ": licence not recorded");
        String distribution = p.path("distribution").asText("");
        if (!distribution.equals("vendored") && !distribution.equals("fetch")) {
            errors.add(where + ": distribution must be vendored or fetch");
        }
        if (errors.size() > before) return null;     // identity is broken; don't read files under it

        // Licence texts are always committed, even for fetch packs.
        List<String> licenseFiles = new ArrayList<>();
        if (!p.path("licenseFiles").isArray() || p.path("licenseFiles").isEmpty()) {
            errors.add(where + ": no licence file recorded");
        }
        for (JsonNode lf : p.path("licenseFiles")) {
            String path = lf.path("path").asText("");
            if (verified(vendored, id + "/" + path, lf.path("sha256").asText(""), where + " licence " + path, errors) != null) {
                licenseFiles.add(path);
            }
        }

        BundleReader files = distribution.equals("fetch") ? fetched : vendored;
        List<Skill> skills = new ArrayList<>();
        for (JsonNode s : p.path("skills")) {
            Skill skill = loadSkill(s, id, cloud, files, errors, skillNames);
            if (skill != null) skills.add(skill);
        }
        if (!p.path("skills").isArray() || p.path("skills").isEmpty()) errors.add(where + ": no skills");
        return new SkillPack(id, cloud, p.path("label").asText(id), repo, commit, license, distribution,
                List.copyOf(licenseFiles), List.copyOf(skills));
    }

    private static Skill loadSkill(JsonNode s, String packId, String cloud, BundleReader reader,
                                   List<String> errors, Set<String> skillNames) {
        String name = s.path("name").asText("");
        String where = "skill '" + packId + "/" + name + "'";
        if (!SKILL_NAME.matcher(name).matches() || name.length() > 64) {
            errors.add(where + ": invalid name");
            return null;
        }
        if (!skillNames.add(name)) {
            errors.add(where + ": duplicate skill name (names are global across packs)");
            return null;
        }
        List<String> topics = new ArrayList<>();
        for (JsonNode t : s.path("topics")) topics.add(t.asText());
        if (topics.isEmpty() || !AgentProfile.KNOWN_TOPICS.containsAll(topics)) {
            errors.add(where + ": topics must be a non-empty subset of " + AgentProfile.KNOWN_TOPICS);
        }

        Map<String, SkillFile> files = new LinkedHashMap<>();
        for (JsonNode f : s.path("files")) {
            String path = f.path("path").asText("");
            if (!SAFE_PATH.matcher(path).matches() || hasDotSegment(path) || !DOC_FILE.matcher(path).matches()) {
                errors.add(where + ": unsafe or non-document file path '" + path + "'");
                continue;
            }
            String content = verified(reader, packId + "/" + name + "/" + path,
                    f.path("sha256").asText(""), where + " file " + path, errors);
            if (content != null) files.put(path, new SkillFile(path, f.path("sha256").asText(), content));
        }
        SkillFile skillMd = files.get("SKILL.md");
        if (skillMd == null) {
            errors.add(where + ": SKILL.md missing");
            return null;
        }

        Frontmatter fm = parseFrontmatter(skillMd.content(), where, errors);
        if (fm == null) return null;
        if (!name.equals(fm.name)) {
            errors.add(where + ": frontmatter name '" + fm.name + "' does not match the skill directory");
            return null;
        }
        return new Skill(name, packId, cloud, fm.description, List.copyOf(topics),
                s.path("sourcePath").asText(""), fm.body, Map.copyOf(files));
    }

    /** Reads + checks one file; returns its text, or null after recording why not. */
    private static String verified(BundleReader reader, String rel, String expected, String what, List<String> errors) {
        if (!SHA256.matcher(expected).matches()) {
            errors.add(what + ": no sha256 recorded");
            return null;
        }
        byte[] bytes;
        try {
            bytes = reader.read(rel);
        } catch (IOException e) {
            errors.add(what + ": unreadable (" + e.getMessage() + ")");
            return null;
        }
        if (bytes == null) {
            errors.add(what + ": file missing (for a fetch pack, run scripts/skills/vendor-skills.mjs install <fetch-dir>)");
            return null;
        }
        String actual = sha256(bytes);
        if (!actual.equals(expected)) {
            errors.add(what + ": checksum mismatch (expected " + expected + ", got " + actual + ")");
            return null;
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            errors.add(what + ": not valid UTF-8");
            return null;
        }
    }

    record Frontmatter(String name, String description, String body) { }

    static Frontmatter parseFrontmatter(String text, String where, List<String> errors) {
        String t = text.startsWith("﻿") ? text.substring(1) : text;
        Matcher m = FRONTMATTER.matcher(t);
        if (!m.find()) {
            errors.add(where + ": SKILL.md has no YAML frontmatter");
            return null;
        }
        Object parsed;
        try {
            LoaderOptions opts = new LoaderOptions();
            opts.setAllowDuplicateKeys(false);
            opts.setMaxAliasesForCollections(10);
            opts.setCodePointLimit(64 * 1024);
            parsed = new Yaml(new SafeConstructor(opts)).load(m.group(1));
        } catch (Exception e) {
            errors.add(where + ": frontmatter is not valid YAML (" + e.getMessage().lines().findFirst().orElse("") + ")");
            return null;
        }
        if (!(parsed instanceof Map<?, ?> map)) {
            errors.add(where + ": frontmatter must be a mapping");
            return null;
        }
        Object name = map.get("name");
        Object description = map.get("description");
        boolean ok = true;
        if (!(name instanceof String n) || !SKILL_NAME.matcher(n).matches() || n.length() > 64) {
            errors.add(where + ": frontmatter 'name' missing or invalid (lowercase letters, digits, hyphens; ≤64)");
            ok = false;
        }
        if (!(description instanceof String d) || d.isBlank()) {
            errors.add(where + ": frontmatter 'description' missing");
            ok = false;
        } else if (d.length() > MAX_DESCRIPTION) {
            errors.add(where + ": frontmatter 'description' longer than " + MAX_DESCRIPTION + " characters");
            ok = false;
        }
        // Any other keys (allowed-tools, metadata, compatibility, license…) are ignored on purpose:
        // skill text cannot widen an agent's tools (§9.3).
        if (!ok) return null;
        return new Frontmatter((String) name, ((String) description).strip(), t.substring(m.end()).strip());
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public List<SkillPack> packs() {
        return packs;
    }

    public Optional<SkillPack> pack(String id) {
        return packs.stream().filter(p -> p.id().equals(id)).findFirst();
    }

    public Optional<Skill> skill(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    /**
     * A file inside one skill's directory. Only manifest-listed files exist; anything
     * else — {@code ..}, absolute paths, backslashes, other skills' files — is refused.
     */
    public Optional<SkillFile> file(Skill skill, String requested) {
        String path = normalisePath(requested);
        return Optional.ofNullable(skill.files().get(path));
    }

    /** Rejects traversal outright rather than resolving it. Throws IllegalArgumentException. */
    static String normalisePath(String requested) {
        if (requested == null || requested.isBlank()) return "SKILL.md";
        String p = requested.strip();
        if (p.startsWith("./")) p = p.substring(2);
        if (p.length() > 200 || p.indexOf('\0') >= 0 || p.contains("\\") || p.startsWith("/")
                || p.contains("%") || hasDotSegment(p) || !SAFE_PATH.matcher(p).matches()) {
            throw new IllegalArgumentException("Refused file path '" + requested
                    + "': must be a relative path inside the skill's directory, without '..'");
        }
        return p;
    }

    private static boolean hasDotSegment(String path) {
        for (String seg : path.split("/")) {
            if (seg.equals("..") || seg.equals(".") || seg.isEmpty()) return true;
        }
        return false;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
