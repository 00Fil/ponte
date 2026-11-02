import test from 'node:test';
import assert from 'node:assert/strict';
import { mergeDocs } from '../src/finance.js';

test('vince la modifica più recente e l\'unione è commutativa', () => {
  const a = { tx: { x: { c: -100, u: 1 }, y: { c: -5, u: 5 } }, cash: { 500: [2, 3] }, rev: [100, 1, 1] };
  const b = { tx: { x: { c: -100, u: 4, d: 1 }, z: { c: 7, u: 2 } }, cash: { 500: [1, 9] }, rev: [50, 2, 2] };
  const ab = mergeDocs(a, b);
  const ba = mergeDocs(b, a);
  assert.deepEqual(ab, ba);
  assert.equal(ab.tx.x.d, 1);
  assert.equal(ab.tx.y.c, -5);
  assert.deepEqual(ab.cash['500'], [1, 9]);
  assert.deepEqual(ab.rev, [50, 2, 2]);
  assert.deepEqual(mergeDocs(ab, ab), ab);
});
