import fs from 'node:fs';
import path from 'node:path';

/**
 * Corpo sincronizzato: alimenti salvati (fd), diario dei pasti (lg), piano settimanale (sl), pesate (wt) e obiettivi (cfg).
 * Ogni gruppo è una mappa id -> record "l'ultimo che scrive vince" (data di modifica `u`, `d: 1` se eliminato), come per Agenda e Finanze.
 * Le foto restano sul telefono che le scatta e non passano di qui.
 */
export const GROUPS = ['fd', 'lg', 'sl', 'wt', 'cfg'];

export function mergeBody(a, b) {
  const out = {};
  for (const g of GROUPS) {
    out[g] = { ...(a?.[g] ?? {}) };
    for (const [id, r] of Object.entries(b?.[g] ?? {})) {
      if (r && typeof r === 'object' && (!out[g][id] || (r.u ?? 0) > (out[g][id].u ?? 0))) out[g][id] = r;
    }
  }
  return out;
}

export function createBody(dataDir) {
  const file = path.join(dataDir, 'body.json');
  let doc = mergeBody({}, {});
  try { doc = mergeBody(doc, JSON.parse(fs.readFileSync(file, 'utf8'))); } catch { /* primo avvio */ }

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
        doc = mergeBody(doc, incoming);
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
