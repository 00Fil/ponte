import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { timingSafeEqual } from 'node:crypto';
import { Readable } from 'node:stream';
import { createBackup } from './backup.js';
import { createFinance } from './finance.js';
import { createPlan } from './plan.js';
import { createBody } from './body.js';
import { WebSocketServer } from 'ws';
import { GALLERY_HTML } from './gallery.js';

const FID_RE = /^[A-Za-z0-9_-]{8,64}$/;
const MAX_JSON = 1024 * 1024 + 4096;
const MAX_BUFFERED = 512 * 1024;
const KINDS = new Set(['screenshot', 'file']);

function safeEqual(a, b) {
  const x = Buffer.from(a);
  const y = Buffer.from(b);
  return x.length === y.length && timingSafeEqual(x, y);
}

function cleanName(name) {
  const base = path.basename(String(name ?? 'file')).replace(/[\u0000-\u001f<>:"/\\|?*]/g, '_').trim();
  return (base || 'file').slice(0, 200);
}

export async function start({
  port = 8080, token, dataDir, maxFileBytes = 200 * 1024 * 1024,
  github = { repo: '00Fil/ponte', token: '', api: 'https://api.github.com' },
}) {
  // Aggiornamenti: il server legge la release "latest" su GitHub (anche da repo privato, con un token)
  // e la gira ai dispositivi, che così si aggiornano da soli senza credenziali GitHub.
  const UPDATE_ASSETS = { android: 'Ponte-Android.apk', windows: 'Ponte-Windows-x64.zip', setup: 'Ponte-Setup.exe' };
  let releaseCache = { at: 0, data: null };
  const ghHeaders = (accept) => ({
    accept, 'user-agent': 'ponte-server', 'x-github-api-version': '2022-11-28',
    ...(github.token ? { authorization: `Bearer ${github.token}` } : {}),
  });
  const latestRelease = async (fresh = false) => {
    if (!fresh && releaseCache.data && Date.now() - releaseCache.at < 60_000) return releaseCache.data;
    const r = await fetch(`${github.api}/repos/${github.repo}/releases/tags/latest`, { headers: ghHeaders('application/vnd.github+json') });
    if (!r.ok) throw new Error(`github ${r.status}`);
    const rel = await r.json();
    const assets = Object.fromEntries((rel.assets ?? []).map((a) => [a.name, a]));
    let build = 0;
    if (assets['version.json']) {
      const v = await fetch(assets['version.json'].url, { headers: ghHeaders('application/octet-stream') });
      if (v.ok) build = Number((await v.json()).build) || 0;
    }
    releaseCache = { at: Date.now(), data: { build, assets } };
    return releaseCache.data;
  };
  const handleUpdate = async (url, res) => {
    let rel;
    try { rel = await latestRelease(url.searchParams.has('fresh')); } catch (err) {
      res.statusCode = 502;
      res.setHeader('content-type', 'application/json');
      const why = String(err?.message ?? err);
      return void res.end(JSON.stringify({ error: why, hint: why.includes('404') || why.includes('401') ? 'imposta PONTE_GITHUB_TOKEN sul server (repo privato)' : 'GitHub non raggiungibile' }));
    }
    if (url.pathname === '/api/update') {
      res.setHeader('content-type', 'application/json');
      res.setHeader('cache-control', 'no-store');
      return void res.end(JSON.stringify({
        build: rel.build,
        android: Boolean(rel.assets[UPDATE_ASSETS.android]),
        windows: Boolean(rel.assets[UPDATE_ASSETS.windows]),
      }));
    }
    const which = url.pathname.slice('/api/update/'.length);
    const asset = rel.assets[UPDATE_ASSETS[which] ?? ''];
    if (!asset) {
      res.statusCode = 404;
      return void res.end();
    }
    const a = await fetch(asset.url, { headers: ghHeaders('application/octet-stream') });
    if (!a.ok || !a.body) {
      res.statusCode = 502;
      return void res.end();
    }
    res.setHeader('content-type', 'application/octet-stream');
    res.setHeader('content-length', asset.size);
    res.setHeader('x-ponte-build', String(rel.build));
    Readable.fromWeb(a.body).pipe(res);
  };

  const finance = createFinance(dataDir);
  const plan = createPlan(dataDir);
  const body = createBody(dataDir);
  const backup = createBackup(dataDir, { reserveBytes: Number(process.env.PONTE_BACKUP_RESERVE_MB ?? 1024) * 1024 * 1024 });
  const filesDir = path.join(dataDir, 'files');
  const tmpDir = path.join(dataDir, 'tmp');
  const indexPath = path.join(dataDir, 'index.jsonl');
  fs.mkdirSync(filesDir, { recursive: true });
  fs.rmSync(tmpDir, { recursive: true, force: true });
  fs.mkdirSync(tmpDir, { recursive: true });

  /** @type {Map<string, object>} fid -> metadati */
  const index = new Map();
  if (fs.existsSync(indexPath)) {
    for (const line of fs.readFileSync(indexPath, 'utf8').split('\n')) {
      if (!line.trim()) continue;
      try {
        const e = JSON.parse(line);
        if (e.fid && fs.existsSync(path.join(filesDir, e.fid))) index.set(e.fid, e);
      } catch { /* riga rovinata: la salto */ }
    }
  }

  // Cronologia degli appunti: resta sul server e i dispositivi la recuperano quando si riconnettono.
  const clipsPath = path.join(dataDir, 'clips.jsonl');
  /** @type {Array<{id: string, from: string, ts: number, text: string}>} */
  let clips = [];
  if (fs.existsSync(clipsPath)) {
    for (const line of fs.readFileSync(clipsPath, 'utf8').split('\n')) {
      if (!line.trim()) continue;
      try { clips.push(JSON.parse(line)); } catch { /* riga rovinata */ }
    }
  }
  const clipIds = new Set(clips.map((c) => c.id));

  // Tetto morbido sull'archivio (file + appunti): mai restrittivo. Un caricamento entra sempre; se poi si sfora, in coda
  // parte la rimozione delle cose più vecchie finché si rientra.
  const capBytes = Number(process.env.PONTE_STORE_CAP_GB ?? 50) * 1024 ** 3;
  const clipBytes = (c) => Buffer.byteLength(c.text);
  let evicting = false;
  let evictAgain = false;
  const evict = () => {
    if (evicting) { evictAgain = true; return; }
    evicting = true;
    setImmediate(() => {
      try {
        let total = 0;
        for (const e of index.values()) total += e.size || 0;
        for (const c of clips) total += clipBytes(c);
        if (total > capBytes) {
          const items = [
            ...[...index.values()].map((e) => ({ file: true, ts: e.ts || 0, e, size: e.size || 0 })),
            ...clips.map((c) => ({ file: false, ts: c.ts || 0, c, size: clipBytes(c) })),
          ].sort((a, b) => a.ts - b.ts);
          const gone = new Set();
          let files = false;
          for (const it of items) {
            if (total <= capBytes) break;
            total -= it.size;
            if (it.file) { index.delete(it.e.fid); fs.rmSync(path.join(filesDir, it.e.fid), { force: true }); files = true; }
            else { clipIds.delete(it.c.id); gone.add(it.c); }
          }
          if (files) fs.writeFileSync(indexPath, [...index.values()].map((e) => JSON.stringify(e) + '\n').join(''));
          if (gone.size) {
            clips = clips.filter((c) => !gone.has(c));
            fs.writeFileSync(clipsPath, clips.map((c) => JSON.stringify(c) + '\n').join(''));
          }
        }
      } catch { /* alla prossima volta */ } finally {
        evicting = false;
        if (evictAgain) { evictAgain = false; evict(); }
      }
    });
  };

  const saveClip = (m) => {
    if (typeof m.text !== 'string' || !m.text || clipIds.has(m.id)) return;
    const c = { id: m.id, from: m.from, ts: m.ts, text: m.text };
    clips.push(c);
    clipIds.add(c.id);
    fs.appendFileSync(clipsPath, JSON.stringify(c) + '\n');
    evict();
  };

  /** @type {Map<string, {ws: import('ws').WebSocket, info: object}>} */
  const devices = new Map();
  /** @type {Map<number, {from: string, to: string}>} */
  const streams = new Map();
  /** @type {Map<string, {fd: number, tmp: string, meta: object, written: number}>} */
  const incoming = new Map();

  const authed = (req) => {
    const h = req.headers.authorization ?? '';
    if (h.startsWith('Bearer ') && safeEqual(h.slice(7), token)) return true;
    const q = new URL(req.url, 'http://x').searchParams.get('token');
    return q !== null && safeEqual(q, token);
  };

  const server = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://x');
    if (url.pathname === '/healthz') return void res.end('ok');
    if (url.pathname === '/archivio') {
      res.setHeader('content-type', 'text/html; charset=utf-8');
      res.setHeader('cache-control', 'no-store');
      res.setHeader('referrer-policy', 'no-referrer');
      return void res.end(GALLERY_HTML);
    }
    if (!url.pathname.startsWith('/api/')) {
      res.statusCode = 404;
      return void res.end();
    }
    const h = req.headers.authorization ?? '';
    if (!(h.startsWith('Bearer ') && safeEqual(h.slice(7), token))) {
      res.statusCode = 401;
      return void res.end();
    }
    if (url.pathname === '/api/update' || url.pathname.startsWith('/api/update/')) {
      return void handleUpdate(url, res).catch(() => { if (!res.headersSent) res.statusCode = 500; res.end(); });
    }
    if (url.pathname === '/api/storage' || url.pathname.startsWith('/api/backup/')) {
      return void backup.handle(req, res, url).catch(() => { if (!res.headersSent) res.statusCode = 500; res.end(); });
    }
    if (url.pathname === '/api/finance') {
      return void finance.handle(req, res).catch(() => { if (!res.headersSent) res.statusCode = 500; res.end(); });
    }
    if (url.pathname === '/api/body') {
      return void body.handle(req, res).catch(() => { if (!res.headersSent) res.statusCode = 500; res.end(); });
    }
    if (url.pathname === '/api/plan') {
      return void plan.handle(req, res).catch(() => { if (!res.headersSent) res.statusCode = 500; res.end(); });
    }
    if (url.pathname === '/api/clips') {
      const since = Number(url.searchParams.get('since') ?? 0) || 0;
      const limit = Number(url.searchParams.get('limit') ?? 0) || clips.length;
      const list = clips.filter((c) => c.ts > since).slice(-limit);
      res.setHeader('content-type', 'application/json');
      return void res.end(JSON.stringify(list));
    }
    if (url.pathname === '/api/files') {
      const since = Number(url.searchParams.get('since') ?? 0) || 0;
      const list = [...index.values()].filter((e) => e.ts > since).sort((a, b) => a.ts - b.ts);
      res.setHeader('content-type', 'application/json');
      return void res.end(JSON.stringify(list));
    }
    const m = url.pathname.match(/^\/api\/files\/([A-Za-z0-9_-]{8,64})$/);
    if (m && index.has(m[1])) {
      const e = index.get(m[1]);
      res.setHeader('content-type', e.mime || 'application/octet-stream');
      res.setHeader('content-length', fs.statSync(path.join(filesDir, e.fid)).size);
      res.setHeader('content-disposition', `inline; filename*=UTF-8''${encodeURIComponent(e.name)}`);
      res.setHeader('x-content-type-options', 'nosniff');
      return void fs.createReadStream(path.join(filesDir, e.fid)).pipe(res);
    }
    res.statusCode = 404;
    res.end();
  });

  const wss = new WebSocketServer({ noServer: true, maxPayload: MAX_JSON });
  server.on('upgrade', (req, socket, head) => {
    if (new URL(req.url, 'http://x').pathname !== '/ws' || !authed(req)) {
      socket.write('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n');
      return void socket.destroy();
    }
    wss.handleUpgrade(req, socket, head, (ws) => wss.emit('connection', ws, req));
  });

  const send = (ws, obj) => {
    if (ws.readyState === ws.OPEN) ws.send(JSON.stringify(obj));
  };
  const broadcastDevices = () => {
    const list = [...devices.values()].map((d) => d.info);
    for (const d of devices.values()) send(d.ws, { t: 'devices', id: `dev-${Date.now()}-${Math.random()}`, from: 'hub', ts: Date.now(), list });
  };

  const abortIncoming = (key) => {
    const f = incoming.get(key);
    if (!f) return;
    try { fs.closeSync(f.fd); } catch { /* già chiuso */ }
    fs.rmSync(f.tmp, { force: true });
    incoming.delete(key);
  };

  function handleFile(dev, m) {
    if (typeof m.fid !== 'string' || !FID_RE.test(m.fid)) return false;
    const key = `${dev.id}:${m.fid}`;
    if (m.t === 'file.begin') {
      const size = Number(m.size);
      if (!KINDS.has(m.kind) || !Number.isFinite(size) || size < 0 || size > maxFileBytes || index.has(m.fid)) return false;
      abortIncoming(key);
      const tmp = path.join(tmpDir, key.replace(':', '_'));
      incoming.set(key, {
        fd: fs.openSync(tmp, 'w'),
        tmp,
        written: 0,
        meta: { fid: m.fid, name: cleanName(m.name), kind: m.kind, mime: String(m.mime ?? 'application/octet-stream').slice(0, 100), size, from: dev.id },
      });
      return true;
    }
    const f = incoming.get(key);
    if (!f) return false;
    if (m.t === 'file.chunk') {
      const buf = Buffer.from(String(m.data ?? ''), 'base64');
      if (f.written + buf.length > Math.min(f.meta.size, maxFileBytes)) {
        abortIncoming(key);
        return false;
      }
      fs.writeSync(f.fd, buf);
      f.written += buf.length;
      return true;
    }
    if (m.t === 'file.end') {
      fs.closeSync(f.fd);
      incoming.delete(key);
      if (f.written !== f.meta.size) {
        fs.rmSync(f.tmp, { force: true });
        return false;
      }
      fs.renameSync(f.tmp, path.join(filesDir, f.meta.fid));
      const entry = { ...f.meta, ts: Date.now() };
      index.set(entry.fid, entry);
      fs.appendFileSync(indexPath, JSON.stringify(entry) + '\n');
      evict();
      return true;
    }
    return false;
  }

  wss.on('connection', (ws) => {
    let dev = null;
    ws.lastSeen = Date.now();
    const seenNow = () => { ws.lastSeen = Date.now(); };
    ws.on('pong', seenNow);
    ws.on('ping', seenNow);

    ws.on('message', (data, isBinary) => {
      if (!dev) {
        if (isBinary) return;
        let hello;
        try { hello = JSON.parse(data.toString()); } catch { return ws.close(1008, 'hello non valido'); }
        if (hello.t !== 'hello' || typeof hello.from !== 'string' || hello.from.length < 4 || hello.from.length > 64) return ws.close(1008, 'hello non valido');
        const old = devices.get(hello.from);
        if (old) old.ws.close(1000, 'sostituito');
        dev = { id: hello.from };
        const info = {
          id: hello.from,
          name: String(hello.name ?? 'Dispositivo').slice(0, 60),
          platform: hello.platform === 'android' ? 'android' : 'windows',
          caps: Array.isArray(hello.caps) ? hello.caps.filter((c) => typeof c === 'string').slice(0, 12) : [],
          // Indirizzi su cui il dispositivo accetta collegamenti diretti (rete locale, rete privata Headscale).
          addrs: Array.isArray(hello.addrs)
            ? hello.addrs.filter((x) => typeof x === 'string' && /^[0-9a-fA-F:.]{3,45}$/.test(x)).slice(0, 8)
            : [],
          port: Number.isInteger(hello.port) && hello.port > 0 && hello.port < 65536 ? hello.port : 47800,
        };
        devices.set(dev.id, { ws, info });
        broadcastDevices();
        return;
      }

      if (isBinary) {
        if (data.length < 5) return;
        const st = streams.get(data.readUInt32BE(0));
        const target = st && st.from === dev.id ? devices.get(st.to) : null;
        if (target && target.ws.bufferedAmount < MAX_BUFFERED) target.ws.send(data, { binary: true });
        return;
      }

      seenNow();
      let m;
      try { m = JSON.parse(data.toString()); } catch { return; }
      if (typeof m.t !== 'string' || typeof m.id !== 'string') return;
      m.from = dev.id;
      m.ts = Number(m.ts) || Date.now();

      if (m.t.startsWith('file.')) {
        if (!handleFile(dev, m)) return;
      } else if (m.t === 'audio.start') {
        if (!Number.isInteger(m.sid) || typeof m.to !== 'string') return;
        streams.set(m.sid, { from: dev.id, to: m.to });
      } else if (m.t === 'audio.stop') {
        const st = streams.get(m.sid);
        if (st && st.from === dev.id) streams.delete(m.sid);
      } else if (m.t === 'clip') {
        saveClip(m);
      } else if (!['notif', 'audio.route', 'bt.claim', 'bt.state', 'focus', 'audio.lock', 'media.take', 'media.state', 'media.pause', 'media.cmd', 'fin', 'plan', 'body'].includes(m.t)) {
        return;
      }

      const out = JSON.stringify(m);
      if (typeof m.to === 'string') {
        const t = devices.get(m.to);
        if (t && t.ws.readyState === t.ws.OPEN) t.ws.send(out);
      } else {
        for (const [id, d] of devices) if (id !== dev.id && d.ws.readyState === d.ws.OPEN) d.ws.send(out);
      }
    });

    ws.on('close', () => {
      if (!dev) return;
      if (devices.get(dev.id)?.ws === ws) {
        devices.delete(dev.id);
        for (const [sid, st] of streams) if (st.from === dev.id || st.to === dev.id) streams.delete(sid);
        broadcastDevices();
      }
      for (const key of [...incoming.keys()]) if (key.startsWith(`${dev.id}:`)) abortIncoming(key);
    });
    ws.on('error', () => ws.terminate());
  });

  // Il server non manda ping: sono i client a farlo (ogni 55-60 s), così il telefono non si sveglia di più.
  // Qui si chiude solo chi tace da troppo.
  const beat = setInterval(() => {
    const now = Date.now();
    for (const ws of wss.clients) if (now - ws.lastSeen > 180_000) ws.terminate();
  }, 60_000);

  server.requestTimeout = 0; // i backup caricano file grandi su connessioni lente
  await new Promise((resolve) => server.listen(port, resolve));
  return {
    port: server.address().port,
    close: async () => {
      clearInterval(beat);
      for (const ws of wss.clients) ws.terminate();
      for (const key of [...incoming.keys()]) abortIncoming(key);
      await new Promise((resolve) => server.close(resolve));
    },
  };
}
