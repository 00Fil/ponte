import test from 'node:test';
import assert from 'node:assert/strict';
import { mergeBody } from '../src/body.js';

test('corpo: vince la modifica più recente, eliminati inclusi, unione commutativa e idempotente', () => {
  const a = { fd: { x: { n: 'riso', u: 1 }, y: { n: 'pollo', u: 5 } }, wt: { '20261125': { w: 812, u: 2 } } };
  const b = { fd: { x: { n: 'riso basmati', u: 4 }, z: { n: 'olio', u: 2, d: 1 } }, cfg: { g: { k: 2200, u: 3 } } };
  const ab = mergeBody(a, b);
  assert.deepEqual(ab, mergeBody(b, a));
  assert.equal(ab.fd.x.n, 'riso basmati');
  assert.equal(ab.fd.y.n, 'pollo');
  assert.equal(ab.fd.z.d, 1);
  assert.equal(ab.wt['20261125'].w, 812);
  assert.equal(ab.cfg.g.k, 2200);
  assert.deepEqual(mergeBody(ab, ab), ab);
});
