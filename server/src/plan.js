import fs from 'node:fs';
import path from 'node:path';

/**
 * Agenda e Studio sincronizzati. Il documento è un insieme di gruppi (ev eventi, sub materie, tk compiti, ex esami,
 * ses sessioni di studio, card flashcard, cfg impostazioni, tm timer, gev eventi di Google Calendar copiati dai telefoni), ognuno una mappa id -> record "l'ultimo che scrive vince":
 * ogni record ha la data di modifica `u` e, se eliminato, `d: 1`. L'unione è commutativa e idempotente come per le Finanze.
 */
export const GROUPS = ['ev', 'sub', 'tk', 'ex', 'ses', 'card', 'cfg', 'tm', 'gev'];

export function mergePlan(a, b) {
  const out = {};
  for (const g of GROUPS) {
    out[g] = { ...(a?.[g] ?? {}) };
    for (const [id, r] of Object.entries(b?.[g] ?? {})) {
      if (r && typeof r === 'object' && (!out[g][id] || (r.u ?? 0) > (out[g][id].u ?? 0))) out[g][id] = r;
    }
  }
  return out;
}

export function createPlan(dataDir) {
  const file = path.join(dataDir, 'plan.json');
  let doc = mergePlan({}, {});
  try { doc = mergePlan(doc, JSON.parse(fs.readFileSync(file, 'utf8'))); } catch { /* primo avvio */ }

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
        doc = mergePlan(doc, incoming);
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
