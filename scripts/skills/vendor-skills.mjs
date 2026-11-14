#!/usr/bin/env node
// Vendors the agent skill packs listed in sources.json (Ananke Packs build doc §9.1).
//
//   node scripts/skills/vendor-skills.mjs                 # vendor: fetch + verify + write bundle and manifest
//   node scripts/skills/vendor-skills.mjs install <dir>   # install-time fetch for `distribution: "fetch"` packs
//
// vendor mode: for every pack, fetch the git tree at the pinned commit, take ONLY the
// documentation files (.md / .txt) under each listed skill directory — never scripts, hooks,
// templates or telemetry — download each at that commit, check its git blob SHA against the tree
// (so the bytes provably come from the pinned commit), and record a sha256 per file in
// manifest.json. SkillRegistry re-verifies every sha256 at backend startup and refuses to boot on a
// mismatch. Packs whose licence does not allow redistribution use `distribution: "fetch"`: their
// files are checksummed into the manifest but not committed; `install` downloads them into the
// directory the backend reads as `ananke.skills.fetch-dir`, verifying each sha256 again.
//
// Set GITHUB_TOKEN to lift the anonymous API rate limit (5 tree calls per run; usually unnecessary).

import { createHash } from "node:crypto";
import { mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { dirname, join, posix, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, "../..");
const sourcesPath = join(here, "sources.json");
const bundleDir = join(repoRoot, "Java backend/src/main/resources/skills");
const manifestPath = join(bundleDir, "manifest.json");

const DOC_FILE = /\.(md|txt)$/i;
const SAFE_PATH = /^[A-Za-z0-9._-]+(\/[A-Za-z0-9._-]+)*$/;
const PACK_ID = /^[a-z0-9][a-z0-9-]{0,63}$/;
const SHA40 = /^[0-9a-f]{40}$/;

const ghHeaders = {
  "User-Agent": "ananke-skill-vendor",
  Accept: "application/vnd.github+json",
  ...(process.env.GITHUB_TOKEN ? { Authorization: `Bearer ${process.env.GITHUB_TOKEN}` } : {}),
};

const sha256 = (buf) => createHash("sha256").update(buf).digest("hex");
const gitBlobSha = (buf) =>
  createHash("sha1")
    .update(Buffer.concat([Buffer.from(`blob ${buf.length}\0`), buf]))
    .digest("hex");

function fail(msg) {
  console.error(`vendor-skills: ${msg}`);
  process.exit(1);
}

async function fetchBytes(url, headers = {}) {
  for (let attempt = 1; ; attempt++) {
    const res = await fetch(url, { headers: { "User-Agent": "ananke-skill-vendor", ...headers } });
    if (res.ok) return Buffer.from(await res.arrayBuffer());
    if (attempt >= 4 || (res.status < 500 && res.status !== 429)) {
      fail(`GET ${url} → HTTP ${res.status}`);
    }
    await new Promise((r) => setTimeout(r, 500 * 2 ** attempt));
  }
}

const rawUrl = (repo, commit, path) =>
  `https://raw.githubusercontent.com/${repo}/${commit}/${path.split("/").map(encodeURIComponent).join("/")}`;

async function tree(repo, commit) {
  const buf = await fetchBytes(
    `https://api.github.com/repos/${repo}/git/trees/${commit}?recursive=1`,
    ghHeaders,
  );
  const json = JSON.parse(buf.toString("utf8"));
  if (json.truncated) fail(`${repo}@${commit}: tree listing truncated`);
  return new Map(json.tree.filter((e) => e.type === "blob").map((e) => [e.path, e.sha]));
}

function frontmatterName(text) {
  const m = /^---\r?\n([\s\S]*?)\r?\n---/.exec(text);
  if (!m) return null;
  const line = m[1].split(/\r?\n/).find((l) => /^name\s*:/.test(l));
  return line
    ? line
        .replace(/^name\s*:\s*/, "")
        .replace(/^["']|["']$/g, "")
        .trim()
    : null;
}

async function fetchVerified(repo, commit, path, blobs) {
  const expected = blobs.get(path);
  if (!expected) fail(`${repo}@${commit}: ${path} is not in the tree`);
  const buf = await fetchBytes(rawUrl(repo, commit, path));
  const actual = gitBlobSha(buf);
  if (actual !== expected) fail(`${repo}@${commit}: ${path} blob ${actual} ≠ tree ${expected}`);
  return buf;
}

async function vendor() {
  const sources = JSON.parse(await readFile(sourcesPath, "utf8"));
  const out = { version: 1, packs: [] };
  const writes = []; // [absPath, buf]
  const seenSkills = new Set();

  for (const p of sources.packs) {
    if (!PACK_ID.test(p.id)) fail(`bad pack id ${p.id}`);
    if (!SHA40.test(p.commit)) fail(`${p.id}: commit must be a 40-char SHA`);
    if (!["vendored", "fetch"].includes(p.distribution)) fail(`${p.id}: bad distribution`);
    console.log(`· ${p.id}  ${p.repo}@${p.commit.slice(0, 12)}`);
    const blobs = await tree(p.repo, p.commit);
    const commit = p.commit;
    const pack = {
      id: p.id,
      cloud: p.cloud,
      label: p.label,
      repo: p.repo,
      commit,
      license: p.license,
      distribution: p.distribution,
      licenseFiles: [],
      skills: [],
    };

    for (const lf of p.licenseFiles) {
      const buf = await fetchVerified(p.repo, commit, lf, blobs);
      pack.licenseFiles.push({ path: lf, sha256: sha256(buf) });
      writes.push([join(bundleDir, p.id, lf), buf]); // licence texts are always committed
    }

    for (const s of p.skills) {
      const name = posix.basename(s.path);
      if (seenSkills.has(name)) fail(`duplicate skill name ${name}`);
      seenSkills.add(name);
      const prefix = `${s.path}/`;
      const docs = [...blobs.keys()].filter((k) => k.startsWith(prefix) && DOC_FILE.test(k)).sort();
      if (!docs.includes(`${prefix}SKILL.md`)) fail(`${p.id}: ${s.path} has no SKILL.md`);
      const skipped = [...blobs.keys()].filter((k) => k.startsWith(prefix) && !DOC_FILE.test(k));
      const files = [];
      for (const src of docs) {
        const rel = src.slice(prefix.length);
        if (!SAFE_PATH.test(rel)) fail(`${p.id}/${name}: unsafe path ${rel}`);
        const buf = await fetchVerified(p.repo, commit, src, blobs);
        if (rel === "SKILL.md" && frontmatterName(buf.toString("utf8")) !== name) {
          fail(`${p.id}/${name}: frontmatter name does not match directory`);
        }
        files.push({ path: rel, sha256: sha256(buf), bytes: buf.length });
        if (p.distribution === "vendored") writes.push([join(bundleDir, p.id, name, rel), buf]);
      }
      pack.skills.push({
        name,
        sourcePath: s.path,
        topics: s.topics,
        files,
        excludedNonDocFiles: skipped.length,
      });
      console.log(
        `    ${name}: ${files.length} doc file(s)${skipped.length ? `, ${skipped.length} non-doc file(s) excluded` : ""}`,
      );
    }
    out.packs.push(pack);
  }

  // Regenerate the bundle from scratch so a dropped skill can't linger on disk.
  for (const p of sources.packs) await rm(join(bundleDir, p.id), { recursive: true, force: true });
  for (const [abs, buf] of writes) {
    await mkdir(dirname(abs), { recursive: true });
    await writeFile(abs, buf);
  }
  await writeFile(manifestPath, JSON.stringify(out, null, 2) + "\n");
  console.log(`wrote ${writes.length} file(s) + ${manifestPath}`);
}

async function install(dest) {
  if (!dest) fail("usage: vendor-skills.mjs install <dir>");
  const manifest = JSON.parse(await readFile(manifestPath, "utf8"));
  let n = 0;
  for (const p of manifest.packs.filter((x) => x.distribution === "fetch")) {
    for (const s of p.skills) {
      for (const f of s.files) {
        const buf = await fetchBytes(rawUrl(p.repo, p.commit, `${s.sourcePath}/${f.path}`));
        if (sha256(buf) !== f.sha256) fail(`${p.id}/${s.name}/${f.path}: checksum mismatch`);
        const abs = join(resolve(dest), p.id, s.name, f.path);
        await mkdir(dirname(abs), { recursive: true });
        await writeFile(abs, buf);
        n++;
      }
    }
  }
  console.log(`installed ${n} fetched file(s) into ${resolve(dest)}`);
}

const [mode, arg] = process.argv.slice(2);
if (mode === "install") await install(arg);
else if (!mode) await vendor();
else fail(`unknown mode ${mode}`);
