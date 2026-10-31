import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { start } from '../src/server.js';

const TOKEN = 'token-di-prova-abcdefghij';
let srv, dir, base;
const H = { authorization: `Bearer ${TOKEN}` };

before(async () => {
  dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ponte-bk-'));
  srv = await start({ port: 0, token: TOKEN, dataDir: dir, maxFileBytes: 1024 * 1024 });
  base = `http://127.0.0.1:${srv.port}`;
});
after(async () => {
  await srv.close();
  fs.rmSync(dir, { recursive: true, force: true });
});

test('storage: spazio libero e totale', async () => {
  const r = await fetch(`${base}/api/storage`, { headers: H });
  assert.equal(r.status, 200);
  const j = await r.json();
  assert.ok(j.total > 0 && j.free > 0 && j.free <= j.total);
});

test('senza token: 401', async () => {
  assert.equal((await fetch(`${base}/api/storage`)).status, 401);
});

test('carica, elenca, scarica e verifica sha256', async () => {
  const body = crypto.randomBytes(200_000);
  const sha = crypto.createHash('sha256').update(body).digest('hex');
  const put = await fetch(`${base}/api/backup/phone-1/DCIM/Camera/a%20b.jpg`, { method: 'PUT', headers: { ...H, 'x-mtime': '1700000000000' }, body });
  assert.equal(put.status, 200);
  const j = await put.json();
  assert.equal(j.sha256, sha);
  assert.equal(j.size, body.length);
  const list = await (await fetch(`${base}/api/backup/phone-1`, { headers: H })).json();
  assert.deepEqual(list.files.map((f) => [f.p, f.s, f.m]), [['DCIM/Camera/a b.jpg', body.length, 1700000000000]]);
  const get = await fetch(`${base}/api/backup/phone-1/DCIM/Camera/a%20b.jpg`, { headers: H });
  assert.equal(get.status, 200);
  assert.deepEqual(Buffer.from(await get.arrayBuffer()), body);
  const st = await (await fetch(`${base}/api/storage`, { headers: H })).json();
  assert.equal(st.backup['phone-1'].files, 1);
});

test('percorsi pericolosi rifiutati', async () => {
  for (const p of ['phone-1/%2e%2e/x', 'phone-1/a/..%2F..%2Fx', 'x/y', 'phone-1/a%5Cb']) {
    const r = await fetch(`${base}/api/backup/${p}`, { method: 'PUT', headers: H, body: 'ciao' });
    assert.equal(r.status, 400, p);
  }
  assert.ok(!fs.existsSync(path.join(dir, 'x')));
});

test('spazio insufficiente: 507', async () => {
  const r = await fetch(`${base}/api/backup/phone-1/big.bin`, { method: 'PUT', headers: { ...H, 'content-length': String(2 ** 50) }, body: 'x' }).catch((e) => e);
  // il client può rifiutare il body incoerente; accettiamo errore di rete oppure 507
  if (r instanceof Error) return;
  assert.equal(r.status, 507);
});
