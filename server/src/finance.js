import fs from 'node:fs';
import path from 'node:path';

/**
 * Finanze sincronizzate. Il documento è un insieme di registri "l'ultimo che scrive vince", ognuno con la sua data di modifica `u`:
 *   tx   : id -> { t: data, c: centesimi, k: categoria, n: nota, s: origine, u, d: 1 se eliminato }
 *   cash : taglio -> [quante, u]
 *   rev  : [saldo Revolut, data del saldo, u]
 * L'unione di due documenti è commutativa e idempotente: i dispositivi possono scambiarsela in qualsiasi ordine, dal server o tra loro.
 */
export function mergeDocs(a, b) {
  const out = { tx: { ...(a.tx ?? {}) }, cash: { ...(a.cash ?? {}) }, rev: a.rev ?? [0, 0, 0] };
  for (const [id, r] of Object.entries(b.tx ?? {})) {
    if (!out.tx[id] || (r.u ?? 0) > (out.tx[id].u ?? 0)) out.tx[id] = r;
  }
  for (const [k, v] of Object.entries(b.cash ?? {})) {
    if (!out.cash[k] || (v[1] ?? 0) > (out.cash[k][1] ?? 0)) out.cash[k] = v;
  }
  if (b.rev && (b.rev[2] ?? 0) > (out.rev[2] ?? 0)) out.rev = b.rev;
  return out;
}

export function createFinance(dataDir) {
  const file = path.join(dataDir, 'finance.json');
  let doc = { tx: {}, cash: {}, rev: [0, 0, 0] };
  try { doc = mergeDocs(doc, JSON.parse(fs.readFileSync(file, 'utf8'))); } catch { /* primo avvio */ }

  const save = () => {
    const tmp = file + '.part';
    fs.writeFileSync(tmp, JSON.stringify(doc));
    fs.renameSync(tmp, file);
  };

  return {
    doc: () => doc,
    async handle(req, res) {
      if (req.method === 'POST') {
        const chunks = [];
        let size = 0;
        for await (const c of req) {
          size += c.length;
          if (size > 8 * 1024 * 1024) { res.statusCode = 413; return void res.end(); }
          chunks.push(c);
        }
        let incoming;
        try { incoming = JSON.parse(Buffer.concat(chunks).toString('utf8')); } catch { res.statusCode = 400; return void res.end(); }
        if (typeof incoming !== 'object' || incoming === null) { res.statusCode = 400; return void res.end(); }
        doc = mergeDocs(doc, incoming);
        save();
      } else if (req.method !== 'GET') {
        res.statusCode = 405;
        return void res.end();
      }
      res.setHeader('content-type', 'application/json');
      res.end(JSON.stringify(doc));
    },
  };
}
