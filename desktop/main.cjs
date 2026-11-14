// Ananke desktop shell.
//
// Runs the TanStack Start server bundle (built with ANANKE_TARGET=electron into
// dist-electron/, copied to desktop/web/) in-process on a loopback port, and opens
// a window on it. SSR, server functions and cookies behave exactly as in the web
// build — Electron is just the host. The Spring backend / Jupyter stack is NOT
// bundled; the app talks to it at VITE_API_BASE_URL (baked at build time,
// default http://localhost:8080), same as the web app.
const { app, BrowserWindow, shell, Menu } = require("electron");
const http = require("node:http");
const fs = require("node:fs");
const path = require("node:path");
const { Readable } = require("node:stream");
const { pathToFileURL } = require("node:url");

const APP_DIR = path.join(__dirname, "web");
const CLIENT_DIR = path.join(APP_DIR, "client");
const SERVER_ENTRY = path.join(APP_DIR, "server", "server.js");

const MIME = {
  ".js": "text/javascript",
  ".mjs": "text/javascript",
  ".css": "text/css",
  ".html": "text/html",
  ".json": "application/json",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".ico": "image/x-icon",
  ".woff": "font/woff",
  ".woff2": "font/woff2",
  ".ttf": "font/ttf",
  ".wasm": "application/wasm",
  ".txt": "text/plain",
};

function tryStatic(urlPath, res) {
  let rel;
  try {
    rel = decodeURIComponent(urlPath.split("?")[0]);
  } catch {
    return false;
  }
  if (rel === "/" || rel.endsWith("/")) return false;
  const file = path.normalize(path.join(CLIENT_DIR, rel));
  if (!file.startsWith(CLIENT_DIR + path.sep)) return false;
  let stat;
  try {
    stat = fs.statSync(file);
  } catch {
    return false;
  }
  if (!stat.isFile()) return false;
  const headers = {
    "content-type": MIME[path.extname(file).toLowerCase()] || "application/octet-stream",
    "content-length": stat.size,
  };
  // Vite's hashed assets are immutable.
  if (rel.startsWith("/assets/")) headers["cache-control"] = "public, max-age=31536000, immutable";
  res.writeHead(200, headers);
  fs.createReadStream(file).pipe(res);
  return true;
}

async function startServer() {
  const mod = await import(pathToFileURL(SERVER_ENTRY).href);
  const handler = mod.default;
  if (!handler || typeof handler.fetch !== "function") {
    throw new Error("Server bundle has no default { fetch } export");
  }

  const server = http.createServer(async (req, res) => {
    try {
      if ((req.method === "GET" || req.method === "HEAD") && tryStatic(req.url, res)) return;

      const origin = `http://${req.headers.host}`;
      const headers = new Headers();
      for (const [k, v] of Object.entries(req.headers)) {
        if (Array.isArray(v)) v.forEach((x) => headers.append(k, x));
        else if (v != null) headers.set(k, v);
      }
      const hasBody = req.method !== "GET" && req.method !== "HEAD";
      const request = new Request(new URL(req.url, origin), {
        method: req.method,
        headers,
        body: hasBody ? Readable.toWeb(req) : undefined,
        duplex: hasBody ? "half" : undefined,
      });

      const response = await handler.fetch(request);
      const outHeaders = {};
      response.headers.forEach((value, key) => {
        if (key === "set-cookie") return;
        outHeaders[key] = value;
      });
      const cookies = response.headers.getSetCookie?.() ?? [];
      if (cookies.length) outHeaders["set-cookie"] = cookies;
      res.writeHead(response.status, outHeaders);
      if (response.body && req.method !== "HEAD") {
        Readable.fromWeb(response.body).pipe(res);
      } else {
        res.end();
      }
    } catch (err) {
      console.error("[ananke] request failed", err);
      if (!res.headersSent) res.writeHead(500, { "content-type": "text/plain" });
      res.end("Internal error");
    }
  });

  await new Promise((resolve) =>
    server.listen(Number(process.env.ANANKE_PORT) || 0, "127.0.0.1", resolve),
  );
  return `http://127.0.0.1:${server.address().port}`;
}

function createWindow(baseUrl) {
  const win = new BrowserWindow({
    width: 1600,
    height: 1000,
    minWidth: 1100,
    minHeight: 700,
    title: "Ananke",
    backgroundColor: "#0b0d10",
    show: false,
    webPreferences: {
      contextIsolation: true,
      sandbox: true,
      nodeIntegration: false,
    },
  });

  // Show on first paint, but never leave the user with no window if a load stalls.
  let shown = false;
  const reveal = () => {
    if (!shown && !win.isDestroyed()) {
      shown = true;
      win.show();
    }
  };
  win.once("ready-to-show", reveal);
  setTimeout(reveal, 3000);
  win.webContents.on("did-fail-load", (_e, code, desc, url) =>
    console.error(`[ananke] load failed ${code} ${desc} ${url}`),
  );
  win.webContents.on("render-process-gone", (_e, details) =>
    console.error("[ananke] renderer gone", details),
  );
  win.webContents.on("console-message", (_e, level, message) => {
    if (level >= 2) console.error("[renderer]", message);
  });

  // Anything that isn't the app itself opens in the user's browser.
  const isInternal = (url) => url.startsWith(baseUrl);
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (!isInternal(url)) shell.openExternal(url);
    return { action: isInternal(url) ? "allow" : "deny" };
  });
  win.webContents.on("will-navigate", (event, url) => {
    if (!isInternal(url)) {
      event.preventDefault();
      shell.openExternal(url);
    }
  });

  // Dev aid: ANANKE_SNAPSHOT=/path.png writes a capture of the window after load.
  if (process.env.ANANKE_SNAPSHOT) {
    win.webContents.once("did-finish-load", () =>
      setTimeout(async () => {
        const img = await win.webContents.capturePage();
        fs.writeFileSync(process.env.ANANKE_SNAPSHOT, img.toPNG());
      }, 4000),
    );
  }

  // Dev aid: ANANKE_START_PATH=/copilot opens a specific route.
  win.loadURL(baseUrl + (process.env.ANANKE_START_PATH || ""));
  return win;
}

function buildMenu() {
  const template = [
    { role: "appMenu" },
    { role: "editMenu" },
    {
      label: "View",
      submenu: [
        { role: "reload" },
        { role: "forceReload" },
        { role: "toggleDevTools" },
        { type: "separator" },
        { role: "resetZoom" },
        { role: "zoomIn" },
        { role: "zoomOut" },
        { type: "separator" },
        { role: "togglefullscreen" },
      ],
    },
    { role: "windowMenu" },
  ];
  Menu.setApplicationMenu(Menu.buildFromTemplate(template));
}

let baseUrl;

app.whenReady().then(async () => {
  buildMenu();
  try {
    baseUrl = await startServer();
  } catch (err) {
    console.error("[ananke] failed to start embedded server", err);
    const { dialog } = require("electron");
    dialog.showErrorBox("Ananke failed to start", String(err && err.stack ? err.stack : err));
    app.quit();
    return;
  }
  createWindow(baseUrl);

  app.on("activate", () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow(baseUrl);
  });
});

app.on("window-all-closed", () => {
  if (process.platform !== "darwin") app.quit();
});
