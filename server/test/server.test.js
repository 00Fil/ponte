import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import { WebSocket } from 'ws';
import { start } from '../src/server.js';

const TOKEN = 'token-di-prova-abcdefghij';
let srv, dir, base;

before(async () => {
  dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ponte-'));
  srv = await start({ port: 0, token: TOKEN, dataDir: dir, maxFileBytes: 1024 * 1024 });
  base = `127.0.0.1:${srv.port}`;
});
after(async () => {
  await srv.close();
  fs.rmSync(dir, { recursive: true, force: true });
});

const uid = () => Math.random().toString(36).slice(2) + Date.now();

function connect(id, name = id, token = TOKEN) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`ws://${base}/ws`, { headers: { Authorization: `Bearer ${token}` } });
    ws.inbox = [];
    ws.bin = [];
    ws.on('message', (d, isBin) => (isBin ? ws.bin.push(d) : ws.inbox.push(JSON.parse(d.toString()))));
    ws.on('open', () => {
      ws.send(JSON.stringify({ t: 'hello', id: uid(), from: id, name, platform: 'windows', caps: ['clip'] }));
      resolve(ws);
    });
    ws.on('error', reject);
  });
}
const msg = (ws, t, extra = {}) => ws.send(JSON.stringify({ t, id: uid(), ...extra }));
const waitFor = async (fn, ms = 2000) => {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    const v = fn();
    if (v) return v;
    await new Promise((r) => setTimeout(r, 10));
  }
  throw new Error('timeout');
};

test('rifiuta chi non ha il token', async () => {
  await assert.rejects(connect('dispositivo-x', 'x', 'sbagliato'));
  const r = await fetch(`http://${base}/api/files`);
  assert.equal(r.status, 401);
  assert.equal((await fetch(`http://${base}/healthz`)).status, 200);
});

test('gli appunti arrivano agli altri ma non a chi li manda', async () => {
  const a = await connect('disp-aaaa');
  const b = await connect('disp-bbbb');
  msg(a, 'clip', { text: 'ciao' });
  const got = await waitFor(() => b.inbox.find((m) => m.t === 'clip'));
  assert.equal(got.text, 'ciao');
  assert.equal(got.from, 'disp-aaaa');
  await new Promise((r) => setTimeout(r, 50));
  assert.equal(a.inbox.some((m) => m.t === 'clip'), false);
  a.close(); b.close();
});

test('la lista dei dispositivi si aggiorna', async () => {
  const a = await connect('disp-lista-a', 'Fisso');
  const b = await connect('disp-lista-b', 'Tablet');
  const l = await waitFor(() => a.inbox.filter((m) => m.t === 'devices').find((m) => m.list.length >= 2));
  assert.ok(l.list.some((d) => d.name === 'Tablet'));
  b.close();
  await waitFor(() => a.inbox.filter((m) => m.t === 'devices').at(-1)?.list.every((d) => d.name !== 'Tablet'));
  a.close();
});

test('i messaggi con "to" vanno solo al destinatario', async () => {
  const a = await connect('disp-to-aaaa');
  const b = await connect('disp-to-bbbb');
  const c = await connect('disp-to-cccc');
  msg(a, 'audio.route', { to: 'disp-to-bbbb', sink: 'disp-to-cccc' });
  await waitFor(() => b.inbox.find((m) => m.t === 'audio.route'));
  await new Promise((r) => setTimeout(r, 50));
  assert.equal(c.inbox.some((m) => m.t === 'audio.route'), false);
  a.close(); b.close(); c.close();
});

test('screenshot: caricato a blocchi, archiviato e scaricabile', async () => {
  const a = await connect('disp-file-aaaa');
  const b = await connect('disp-file-bbbb');
  const fid = 'shot_' + uid();
  const bytes = Buffer.from('contenuto-finto-di-uno-screenshot'.repeat(100));
  msg(a, 'file.begin', { fid, name: '../../evil/Schermata 1.png', kind: 'screenshot', mime: 'image/png', size: bytes.length });
  for (let i = 0, seq = 0; i < bytes.length; i += 1000, seq++) {
    msg(a, 'file.chunk', { fid, seq, data: bytes.subarray(i, i + 1000).toString('base64') });
  }
  msg(a, 'file.end', { fid });
  await waitFor(() => b.inbox.find((m) => m.t === 'file.end' && m.fid === fid));

  const list = await (await fetch(`http://${base}/api/files?since=0`, { headers: { Authorization: `Bearer ${TOKEN}` } })).json();
  const e = list.find((x) => x.fid === fid);
  assert.ok(e);
  assert.equal(e.name, 'Schermata 1.png');
  assert.equal(e.kind, 'screenshot');
  const dl = await fetch(`http://${base}/api/files/${fid}`, { headers: { Authorization: `Bearer ${TOKEN}` } });
  assert.deepEqual(Buffer.from(await dl.arrayBuffer()), bytes);
  const none = await (await fetch(`http://${base}/api/files?since=${e.ts}`, { headers: { Authorization: `Bearer ${TOKEN}` } })).json();
  assert.equal(none.some((x) => x.fid === fid), false);
  a.close(); b.close();
});

test('file troppo grande o con dimensione sbagliata non vengono archiviati', async () => {
  const a = await connect('disp-big-aaaa');
  const big = 'big_' + uid();
  msg(a, 'file.begin', { fid: big, name: 'x.bin', kind: 'file', size: 5 * 1024 * 1024 });
  msg(a, 'file.end', { fid: big });
  const short = 'short_' + uid();
  msg(a, 'file.begin', { fid: short, name: 'y.bin', kind: 'file', size: 100 });
  msg(a, 'file.chunk', { fid: short, seq: 0, data: Buffer.from('poco').toString('base64') });
  msg(a, 'file.end', { fid: short });
  await new Promise((r) => setTimeout(r, 200));
  const list = await (await fetch(`http://${base}/api/files`, { headers: { Authorization: `Bearer ${TOKEN}` } })).json();
  assert.equal(list.some((x) => x.fid === big || x.fid === short), false);
  a.close();
});

test('audio: il binario passa solo dalla sorgente al destinatario annunciato', async () => {
  const src = await connect('disp-aud-src1');
  const sink = await connect('disp-aud-snk1');
  const other = await connect('disp-aud-oth1');
  msg(src, 'audio.start', { sid: 77, to: 'disp-aud-snk1', rate: 48000, ch: 2 });
  await waitFor(() => sink.inbox.find((m) => m.t === 'audio.start'));
  const pkt = Buffer.alloc(4 + 3840, 1);
  pkt.writeUInt32BE(77, 0);
  src.send(pkt);
  await waitFor(() => sink.bin.length);
  assert.equal(sink.bin[0].length, pkt.length);
  other.send(pkt); // non è la sorgente: va scartato
  await new Promise((r) => setTimeout(r, 100));
  assert.equal(sink.bin.length, 1);
  src.close(); sink.close(); other.close();
});

test('gli appunti restano sul server e si recuperano dopo', async () => {
  const a = await connect('dispositivo-a');
  const id = uid();
  a.send(JSON.stringify({ t: 'clip', id, text: 'ciao dal server' }));
  await new Promise((r) => setTimeout(r, 100));
  const res = await fetch(`http://${base}/api/clips?since=0`, { headers: { Authorization: `Bearer ${TOKEN}` } });
  const list = await res.json();
  assert.ok(list.some((c) => c.id === id && c.text === 'ciao dal server' && c.from === 'dispositivo-a'));
  const noAuth = await fetch(`http://${base}/api/clips`);
  assert.equal(noAuth.status, 401);
  a.close();
});

test('la lista dispositivi porta gli indirizzi diretti', async () => {
  const a = await new Promise((resolve, reject) => {
    const ws = new WebSocket(`ws://${base}/ws`, { headers: { Authorization: `Bearer ${TOKEN}` } });
    ws.inbox = [];
    ws.on('message', (d, isBin) => { if (!isBin) ws.inbox.push(JSON.parse(d.toString())); });
    ws.on('open', () => {
      ws.send(JSON.stringify({ t: 'hello', id: uid(), from: 'disp-addr', name: 'x', platform: 'android', caps: [], addrs: ['100.64.0.5', 'bad host!'], port: 47800 }));
      resolve(ws);
    });
    ws.on('error', reject);
  });
  const list = await waitFor(() => a.inbox.find((m) => m.t === 'devices' && m.list.some((d) => d.id === 'disp-addr'))?.list);
  const me = list.find((d) => d.id === 'disp-addr');
  assert.deepEqual(me.addrs, ['100.64.0.5']);
  assert.equal(me.port, 47800);
  a.close();
});

test('aggiornamenti: gira la release di GitHub ai dispositivi', async () => {
  const gh = http.createServer((req, res) => {
    const base = `http://127.0.0.1:${gh.address().port}`;
    if (req.url === '/repos/io/ponte/releases/tags/latest') {
      res.setHeader('content-type', 'application/json');
      return res.end(JSON.stringify({ assets: [
        { name: 'version.json', url: `${base}/asset/1`, size: 14 },
        { name: 'Ponte-Android.apk', url: `${base}/asset/2`, size: 4 },
      ] }));
    }
    if (req.url === '/asset/1') return res.end('{"build": 42}');
    if (req.url === '/asset/2') return res.end('APK!');
    res.statusCode = 404;
    res.end();
  });
  await new Promise((r) => gh.listen(0, r));
  const d = fs.mkdtempSync(path.join(os.tmpdir(), 'ponte-up-'));
  const s2 = await start({ port: 0, token: TOKEN, dataDir: d, github: { repo: 'io/ponte', token: 'x', api: `http://127.0.0.1:${gh.address().port}` } });
  const auth = { headers: { Authorization: `Bearer ${TOKEN}` } };
  const info = await (await fetch(`http://127.0.0.1:${s2.port}/api/update`, auth)).json();
  assert.deepEqual(info, { build: 42, android: true, windows: false });
  const apk = await fetch(`http://127.0.0.1:${s2.port}/api/update/android`, auth);
  assert.equal(apk.status, 200);
  assert.equal(await apk.text(), 'APK!');
  assert.equal((await fetch(`http://127.0.0.1:${s2.port}/api/update/windows`, auth)).status, 404);
  assert.equal((await fetch(`http://127.0.0.1:${s2.port}/api/update`)).status, 401);
  await s2.close();
  gh.close();
  fs.rmSync(d, { recursive: true, force: true });
});
