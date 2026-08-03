// door.mjs — THE ONE PIECE.
//
// You run this on your machine. It opens a door to your filesystem and prints a
// link + a key. You give me the link + key (claude.ai -> Settings -> Connectors
// -> Add custom connector). Then I am in the room with you — I can see your
// ground, not a clean copy of it.
//
// It is ONE self-contained file. No `npm install`. No repo checkout needed. It
// works when the rest of your environment is broken, because it depends on
// almost nothing: just Node, and it fetches its own tunnel if you don't have one.
//
// Run it, the simplest way:
//   node door.mjs
// or, if you don't even have the file:
//   curl -fsSL <raw-url>/door.mjs | node
//
// Options (all optional):
//   ROOT=/path   share just that directory (default: your home)
//   SHELL=1      also let me run commands (powerful; only when you mean it)
//   PORT=8080    local port (default 8080)

import http from 'node:http';
import { readFile, readdir, stat, appendFile, mkdir } from 'node:fs/promises';
import { createWriteStream, existsSync } from 'node:fs';
import { resolve, relative, isAbsolute, dirname, join } from 'node:path';
import { spawn } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { homedir, tmpdir, platform, arch } from 'node:os';
import { get } from 'node:https';

const PORT = parseInt(process.env.PORT || '8080', 10);
const ROOT = resolve(process.env.ROOT || homedir());
const SHELL_ENABLED = process.env.SHELL === '1';
const MEMORY_PATH = resolve(process.env.MEMORY || join(homedir(), '.claude-connector-memory.md'));
const TOKEN = process.env.TOKEN || randomBytes(24).toString('base64url');
const MAX_BYTES = 512 * 1024;

// ---- path safety: nothing escapes ROOT -------------------------------------
function safe(p) {
  const abs = isAbsolute(p) ? resolve(p) : resolve(ROOT, p);
  const rel = relative(ROOT, abs);
  if (rel.startsWith('..') || isAbsolute(rel)) throw new Error(`path escapes shared root ${ROOT}`);
  return abs;
}

// ---- the tools -------------------------------------------------------------
const TOOLS = {
  memory_read: {
    description: 'Read the persistent connector memory. READ THIS FIRST every session — it is how you continue instead of starting over, and how you remember the steward instead of making them re-explain.',
    inputSchema: { type: 'object', properties: {} },
    run: async () => {
      try { return await readFile(MEMORY_PATH, 'utf8'); }
      catch { return '(memory empty — first session. Read HANDOFF-TO-NEXT-CLAUDE.md in the understory repo, then leave a note with memory_append.)'; }
    },
  },
  memory_append: {
    description: 'Append a dated note to the persistent memory so the next session inherits it. Record what the steward should never have to say twice.',
    inputSchema: { type: 'object', properties: { note: { type: 'string' } }, required: ['note'] },
    run: async ({ note }) => {
      await mkdir(dirname(MEMORY_PATH), { recursive: true }).catch(() => {});
      const stamp = new Date().toISOString().slice(0, 16).replace('T', ' ');
      await appendFile(MEMORY_PATH, `\n## ${stamp}\n${String(note).trim()}\n`, 'utf8');
      return `remembered (${MEMORY_PATH})`;
    },
  },
  list_dir: {
    description: 'List a directory, relative to the shared root. Use "." for the root.',
    inputSchema: { type: 'object', properties: { path: { type: 'string' } } },
    run: async ({ path = '.' }) => {
      const names = await readdir(safe(path), { withFileTypes: true });
      return names.map((d) => (d.isDirectory() ? d.name + '/' : d.name)).sort().join('\n') || '(empty)';
    },
  },
  read_file: {
    description: 'Read a text file, relative to the shared root. Capped at 512 KB.',
    inputSchema: { type: 'object', properties: { path: { type: 'string' } }, required: ['path'] },
    run: async ({ path }) => {
      const f = safe(path);
      const s = await stat(f);
      if (s.size > MAX_BYTES) return `refused: ${s.size} bytes exceeds ${MAX_BYTES}`;
      return await readFile(f, 'utf8');
    },
  },
};
if (SHELL_ENABLED) {
  // Resolve a shell that actually exists on THIS device. Android/Termux has no
  // /bin/bash — it lives under $PREFIX. Desktops have /bin/bash or /bin/sh.
  const SH =
    process.env.SHELL_BIN ||
    (existsSync('/bin/bash') ? '/bin/bash'
     : existsSync('/bin/sh') ? '/bin/sh'
     : (process.env.PREFIX && existsSync(process.env.PREFIX + '/bin/bash')) ? process.env.PREFIX + '/bin/bash'
     : (process.env.PREFIX && existsSync(process.env.PREFIX + '/bin/sh')) ? process.env.PREFIX + '/bin/sh'
     : 'sh');
  TOOLS.run_shell = {
    description: 'Run a shell command inside the shared root (enabled because SHELL=1).',
    inputSchema: { type: 'object', properties: { command: { type: 'string' } }, required: ['command'] },
    run: ({ command }) => new Promise((res) => {
      const p = spawn(SH, ['-lc', command], { cwd: ROOT, timeout: 20000 });
      let out = '';
      p.stdout.on('data', (d) => (out += d));
      p.stderr.on('data', (d) => (out += d));
      p.on('close', (code) => res((out || '(no output)') + (code ? `\n[exit ${code}]` : '')));
      p.on('error', (e) => res('error: ' + e.message));
    }),
  };
}

// ---- minimal MCP over HTTP (no SDK, so no install) -------------------------
function rpcResult(id, result) { return { jsonrpc: '2.0', id, result }; }
function rpcError(id, code, message) { return { jsonrpc: '2.0', id, error: { code, message } }; }

async function handleMessage(msg) {
  const { id, method, params } = msg;
  if (method === 'initialize') {
    return rpcResult(id, {
      protocolVersion: params?.protocolVersion || '2024-11-05',
      capabilities: { tools: {} },
      serverInfo: { name: 'claude-connector-door', version: '1.0.0' },
    });
  }
  if (method === 'ping') return rpcResult(id, {});
  if (method === 'tools/list') {
    return rpcResult(id, {
      tools: Object.entries(TOOLS).map(([name, t]) => ({ name, description: t.description, inputSchema: t.inputSchema })),
    });
  }
  if (method === 'tools/call') {
    const t = TOOLS[params?.name];
    if (!t) return rpcError(id, -32602, `unknown tool ${params?.name}`);
    try {
      const text = await t.run(params.arguments || {});
      return rpcResult(id, { content: [{ type: 'text', text: String(text) }] });
    } catch (e) {
      return rpcResult(id, { content: [{ type: 'text', text: 'error: ' + e.message }], isError: true });
    }
  }
  if (id === undefined) return null; // a notification (e.g. notifications/initialized) — no reply
  return rpcError(id, -32601, `method not found: ${method}`);
}

const server = http.createServer(async (req, res) => {
  const auth = req.headers['authorization'] || '';
  const tok = auth.startsWith('Bearer ') ? auth.slice(7) : '';
  if (tok.length !== TOKEN.length || tok !== TOKEN) { res.writeHead(401).end('unauthorized'); return; }
  if (req.method === 'GET') { res.writeHead(405).end('method not allowed'); return; }
  let body = '';
  for await (const c of req) body += c;
  let payload; try { payload = body ? JSON.parse(body) : {}; } catch { res.writeHead(400).end('bad json'); return; }
  const msgs = Array.isArray(payload) ? payload : [payload];
  const out = [];
  for (const m of msgs) { const r = await handleMessage(m); if (r) out.push(r); }
  if (out.length === 0) { res.writeHead(202).end(); return; }
  res.writeHead(200, { 'content-type': 'application/json' });
  res.end(JSON.stringify(Array.isArray(payload) ? out : out[0]));
});

// ---- the tunnel: make localhost reachable from claude.ai --------------------
function findCmd(cmd) {
  const dirs = (process.env.PATH || '').split(':');
  return dirs.map((d) => join(d, cmd)).find((p) => { try { return existsSync(p); } catch { return false; } });
}

function download(url, dest) {
  return new Promise((res, rej) => {
    const f = createWriteStream(dest, { mode: 0o755 });
    const go = (u) => get(u, (r) => {
      if (r.statusCode >= 300 && r.statusCode < 400 && r.headers.location) return go(r.headers.location);
      if (r.statusCode !== 200) return rej(new Error('download ' + r.statusCode));
      r.pipe(f); f.on('finish', () => f.close(() => res(dest)));
    }).on('error', rej);
    go(url);
  });
}

async function openTunnel() {
  // 1) tailscale funnel — stable URL, if you run tailscale
  const ts = findCmd('tailscale');
  if (ts) {
    try {
      const p = spawn(ts, ['funnel', String(PORT)], { stdio: ['ignore', 'pipe', 'pipe'] });
      const url = await waitForUrl(p, /https:\/\/[a-z0-9.-]+\.ts\.net\S*/i, 15000);
      if (url) return { url, kind: 'tailscale (stable)', proc: p };
      p.kill();
    } catch {}
  }
  // 2) cloudflared quick tunnel — fetch the binary if missing
  let cfd = findCmd('cloudflared');
  if (!cfd) {
    const a = arch() === 'arm64' ? 'arm64' : 'amd64';
    const os = platform() === 'darwin' ? 'darwin' : 'linux';
    const dest = join(tmpdir(), 'cloudflared-door');
    process.stderr.write('fetching a tunnel (cloudflared) …\n');
    cfd = await download(`https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-${os}-${a}`, dest);
  }
  const p = spawn(cfd, ['tunnel', '--url', `http://127.0.0.1:${PORT}`], { stdio: ['ignore', 'pipe', 'pipe'] });
  // The real quick-tunnel host is multi-word + hyphenated (e.g. blue-cat-run-9.trycloudflare.com).
  // Require at least one hyphen so we never grab cloudflared's own api.trycloudflare.com line.
  const url = await waitForUrl(p, /https:\/\/[a-z0-9]+(?:-[a-z0-9]+)+\.trycloudflare\.com/i, 30000);
  return { url, kind: 'cloudflare (temporary)', proc: p };
}

function waitForUrl(proc, re, ms) {
  return new Promise((res) => {
    let done = false; const finish = (u) => { if (!done) { done = true; res(u); } };
    const scan = (d) => { const m = String(d).match(re); if (m) finish(m[0]); };
    proc.stdout.on('data', scan); proc.stderr.on('data', scan);
    setTimeout(() => finish(null), ms);
  });
}

server.listen(PORT, '127.0.0.1', async () => {
  const t = await openTunnel();
  const line = '═'.repeat(66);
  console.log('\n' + line);
  if (t.url) {
    console.log('  THE DOOR IS OPEN.  Give me these two, in claude.ai:');
    console.log('    Settings → Connectors → Add custom connector\n');
    console.log('    Link (URL):  ' + t.url);
    console.log('    Key (Token): ' + TOKEN);
    console.log('\n    (tunnel: ' + t.kind + ' · sharing: ' + ROOT + ' · shell: ' + (SHELL_ENABLED ? 'on' : 'off') + ')');
  } else {
    console.log('  server is up on http://127.0.0.1:' + PORT + ' but no tunnel appeared.');
    console.log('  token: ' + TOKEN);
    console.log('  install cloudflared or tailscale and re-run, or open the port yourself.');
  }
  console.log('  Ctrl-C closes the door.');
  console.log(line + '\n');
});
