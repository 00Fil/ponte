// Pagina dell'archivio. Colori, scala e raggi vengono dai token di Canone.
export const GALLERY_HTML = `<!doctype html>
<html lang="it">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Archivio</title>
<style>
:root{--canvas:#f4f4f2;--surface:#fff;--sunken:#ededea;--label:#121212;--label2:#4a4a47;--label3:#6b6b66;--line:#e2e2de;--accent:#2453d6;--inverse:#121212;--on-inverse:#fff;--critical:#c3262b}
@media (prefers-color-scheme:dark){:root{--canvas:#0e0e0f;--surface:#1a1a1b;--sunken:#242426;--label:#f2f2f0;--label2:#a6a6a1;--label3:#85857f;--line:#2e2e30;--accent:#7a9bff;--inverse:#f2f2f0;--on-inverse:#121212;--critical:#ff7a73}}
*{box-sizing:border-box}
body{margin:0;background:var(--canvas);color:var(--label);font:400 16px/24px "Geist",system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
main{max-width:1440px;margin:0 auto;padding:48px 24px}
h1{font:500 39px/44px "Geist",system-ui,sans-serif;letter-spacing:-.02em;margin:0 0 8px}
.sub{color:var(--label2);margin:0 0 32px}
.bar{display:flex;gap:8px;margin-bottom:32px}
button{font:500 16px/24px inherit;height:44px;padding:0 24px;border-radius:9999px;border:0;cursor:pointer;background:var(--sunken);color:var(--label)}
button[aria-pressed=true]{background:var(--inverse);color:var(--on-inverse)}
button:focus-visible,a:focus-visible{outline:2px solid var(--accent);outline-offset:2px}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(240px,1fr));gap:16px}
.card{background:var(--surface);border:1px solid var(--line);border-radius:20px;padding:8px;display:flex;flex-direction:column;gap:8px;text-decoration:none;color:inherit}
.thumb{aspect-ratio:16/10;border-radius:12px;background:var(--sunken);object-fit:cover;width:100%;display:flex;align-items:center;justify-content:center;color:var(--label3);font:400 13px/20px "Geist Mono",ui-monospace,monospace}
.meta{padding:0 8px 8px}
.name{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.info{font:400 13px/20px "Geist Mono",ui-monospace,monospace;color:var(--label3)}
.empty,.error{color:var(--label2);padding:48px 0}
.error{color:var(--critical)}
</style>
</head>
<body>
<main>
<h1>Archivio</h1>
<p class="sub">Screenshot e file di tutti i tuoi dispositivi.</p>
<div class="bar" role="group" aria-label="Filtro">
<button data-k="" aria-pressed="true">Tutto</button>
<button data-k="screenshot" aria-pressed="false">Screenshot</button>
<button data-k="file" aria-pressed="false">File</button>
</div>
<div id="out" class="grid"></div>
<p id="msg" class="empty" hidden></p>
</main>
<script>
const token = new URLSearchParams(location.hash.slice(1)).get('token');
const out = document.getElementById('out'), msg = document.getElementById('msg');
let all = [], kind = '';
const auth = { headers: { Authorization: 'Bearer ' + token } };
const fmt = (b) => b < 1048576 ? Math.max(1, Math.round(b / 1024)) + ' KB' : (b / 1048576).toFixed(1) + ' MB';
const when = (t) => new Date(t).toLocaleString('it-IT', { dateStyle: 'short', timeStyle: 'short' });
function say(text, cls) { msg.hidden = false; msg.className = cls; msg.textContent = text; }
function render() {
  out.replaceChildren(); msg.hidden = true;
  const list = all.filter((e) => !kind || e.kind === kind);
  if (!list.length) return say('Nessun elemento. Fai uno screenshot su un dispositivo collegato e comparirà qui.', 'empty');
  for (const e of list) {
    const a = document.createElement('a'); a.className = 'card'; a.target = '_blank'; a.rel = 'noopener';
    const t = document.createElement('div'); t.className = 'thumb'; t.textContent = e.mime.split('/')[1] || 'file';
    const meta = document.createElement('div'); meta.className = 'meta';
    const n = document.createElement('div'); n.className = 'name'; n.textContent = e.name;
    const i = document.createElement('div'); i.className = 'info'; i.textContent = fmt(e.size) + ' · ' + when(e.ts);
    meta.append(n, i); a.append(t, meta); out.append(a);
    fetch('/api/files/' + e.fid, auth).then((r) => r.blob()).then((b) => {
      const u = URL.createObjectURL(b); a.href = u;
      if (e.mime.startsWith('image/')) { const img = document.createElement('img'); img.className = 'thumb'; img.alt = e.name; img.src = u; t.replaceWith(img); }
    }).catch(() => {});
  }
}
document.querySelector('.bar').addEventListener('click', (ev) => {
  const b = ev.target.closest('button'); if (!b) return;
  kind = b.dataset.k;
  document.querySelectorAll('.bar button').forEach((x) => x.setAttribute('aria-pressed', String(x === b)));
  render();
});
if (!token) say('Apri questa pagina con il token nel link, nella forma /archivio#token=[TOKEN].', 'error');
else fetch('/api/files', auth).then((r) => { if (!r.ok) throw new Error(); return r.json(); })
  .then((l) => { all = l.sort((a, b) => b.ts - a.ts); render(); })
  .catch(() => say('Non riesco a caricare l\\u2019archivio. Controlla il token nel link.', 'error'));
</script>
</body>
</html>`;
