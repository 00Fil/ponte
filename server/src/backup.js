import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { Transform } from 'node:stream';
import { pipeline } from 'node:stream/promises';

const DEVICE = /^[A-Za-z0-9_-]{4,64}$/;

/**
 * Backup dei dispositivi: file caricati uno alla volta, con controllo di integrità (SHA-256 calcolato mentre arrivano),
 * e spazio libero del disco. Tutto sotto <dataDir>/backup/<dispositivo>/.
 */
export function createBackup(dataDir, { reserveBytes = 1024 ** 3 } = {}) {
  const root = path.join(dataDir, 'backup');
  fs.mkdirSync(root, { recursive: true });

  const resolve = (device, parts) => {
    if (!DEVICE.test(device)) return null;
    if (parts.some((p) => !p || p === '.' || p === '..' || p.includes('\0') || p.includes('\\'))) return null;
    const base = path.join(root, device);
    const full = path.join(base, ...parts);
    return full.startsWith(base + path.sep) ? { base, full } : null;
  };

  const walk = (dir, rel, out) => {
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
      if (e.name.endsWith('.part')) continue;
      const p = rel ? `${rel}/${e.name}` : e.name;
      if (e.isDirectory()) walk(path.join(dir, e.name), p, out);
      else if (e.isFile()) {
        const st = fs.statSync(path.join(dir, e.name));
        out.push({ p, s: st.size, m: Math.round(st.mtimeMs) });
      }
    }
    return out;
  };

  const disk = () => {
    const s = fs.statfsSync(dataDir);
    const total = Number(s.blocks) * Number(s.bsize);
    const free = Number(s.bavail) * Number(s.bsize);
    return { total, free, used: total - free };
  };

  let cache = { at: 0, v: null };
  const summary = () => {
    if (cache.v && Date.now() - cache.at < 30_000) return cache.v;
    const devices = {};
    for (const d of fs.readdirSync(root, { withFileTypes: true })) {
      if (!d.isDirectory()) continue;
      const files = walk(path.join(root, d.name), '', []);
      devices[d.name] = { files: files.length, bytes: files.reduce((a, f) => a + f.s, 0) };
    }
    cache = { at: Date.now(), v: devices };
    return devices;
  };

  const json = (res, code, body) => {
    res.statusCode = code;
    res.setHeader('content-type', 'application/json');
    res.end(JSON.stringify(body));
  };

  /** Gestisce /api/storage e /api/backup/...; restituisce false se la richiesta non è sua. */
  const handle = async (req, res, url) => {
    if (url.pathname === '/api/storage') {
      json(res, 200, { ...disk(), reserve: reserveBytes, backup: summary() });
      return true;
    }
    if (!url.pathname.startsWith('/api/backup/')) return false;
    let parts;
    try { parts = url.pathname.slice('/api/backup/'.length).split('/').map(decodeURIComponent); } catch { json(res, 400, { error: 'percorso non valido' }); return true; }
    const device = parts.shift();
    if (parts.length === 0 || (parts.length === 1 && parts[0] === '')) {
      if (req.method !== 'GET' || !DEVICE.test(device ?? '')) { json(res, 400, { error: 'dispositivo non valido' }); return true; }
      const dir = path.join(root, device);
      json(res, 200, { files: fs.existsSync(dir) ? walk(dir, '', []) : [] });
      return true;
    }
    const target = resolve(device, parts);
    if (!target) { json(res, 400, { error: 'percorso non valido' }); return true; }

    if (req.method === 'GET') {
      let st;
      try { st = fs.statSync(target.full); if (!st.isFile()) throw new Error(); } catch { res.statusCode = 404; res.end(); return true; }
      res.setHeader('content-type', 'application/octet-stream');
      res.setHeader('content-length', st.size);
      res.setHeader('x-mtime', String(Math.round(st.mtimeMs)));
      await pipeline(fs.createReadStream(target.full), res).catch(() => {});
      return true;
    }
    if (req.method === 'PUT') {
      const len = Number(req.headers['content-length']);
      if (!Number.isFinite(len) || len < 0) { json(res, 411, { error: 'serve content-length' }); return true; }
      const { free } = disk();
      if (free - len < reserveBytes) {
        res.setHeader('connection', 'close');
        json(res, 507, { error: 'spazio insufficiente sul server', free, needed: len, reserve: reserveBytes });
        return true;
      }
      fs.mkdirSync(path.dirname(target.full), { recursive: true });
      const part = `${target.full}.part`;
      const hash = crypto.createHash('sha256');
      let bytes = 0;
      const tee = new Transform({ transform(chunk, _e, cb) { hash.update(chunk); bytes += chunk.length; cb(null, chunk); } });
      try {
        await pipeline(req, tee, fs.createWriteStream(part));
      } catch {
        fs.rmSync(part, { force: true });
        if (!res.headersSent) json(res, 400, { error: 'caricamento interrotto' });
        return true;
      }
      if (bytes !== len) { fs.rmSync(part, { force: true }); json(res, 400, { error: 'dimensione diversa da quella annunciata', bytes, len }); return true; }
      fs.renameSync(part, target.full);
      const m = Number(req.headers['x-mtime']);
      if (Number.isFinite(m) && m > 0) fs.utimesSync(target.full, new Date(), new Date(m));
      cache.at = 0;
      json(res, 200, { size: bytes, sha256: hash.digest('hex') });
      return true;
    }
    res.statusCode = 405;
    res.end();
    return true;
  };

  return { handle, disk, summary };
}
