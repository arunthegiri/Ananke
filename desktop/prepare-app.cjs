// Copies the Node-target web build (../dist-electron) into ./app for packaging.
// The server bundle is ESM, so app/ gets its own {"type":"module"} marker.
const fs = require("node:fs");
const path = require("node:path");
const src = path.join(__dirname, "..", "dist-electron");
const dest = path.join(__dirname, "web");
if (!fs.existsSync(path.join(src, "server", "server.js"))) {
  console.error("dist-electron/ missing — run `npm run desktop:web` from the repo root first.");
  process.exit(1);
}
fs.rmSync(dest, { recursive: true, force: true });
fs.cpSync(src, dest, { recursive: true });
fs.writeFileSync(path.join(dest, "package.json"), JSON.stringify({ type: "module" }) + "\n");
console.log("desktop/web ready");
