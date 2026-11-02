import test from 'node:test';
import assert from 'node:assert/strict';
import { mergePlan } from '../src/plan.js';

test('piano: vince la modifica più recente, eliminati inclusi, unione commutativa', () => {
  const a = { ev: { x: { ti: 'a', u: 1 }, y: { ti: 'y', u: 5 } }, ses: { s: { m: 25, u: 2 } } };
  const b = { ev: { x: { ti: 'b', u: 4, d: 1 }, z: { ti: 'z', u: 2 } }, tm: { t: { p: 'focus', u: 3 } } };
  const ab = mergePlan(a, b);
  assert.deepEqual(ab, mergePlan(b, a));
  assert.equal(ab.ev.x.d, 1);
  assert.equal(ab.ev.y.ti, 'y');
  assert.equal(ab.tm.t.p, 'focus');
  assert.deepEqual(mergePlan(ab, ab), ab);
});
