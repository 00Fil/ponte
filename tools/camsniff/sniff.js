// Cattura cosa cerca e manda il registratore delle cam quando usa questo server come DNS.
// 1) DNS su :53 (udp/tcp): registra ogni nome richiesto e risponde con l'IP di questo server.
// 2) Il primo IP che fa domande DNS viene "agganciato": tutto il suo TCP finisce sull'ascoltatore :9999 (iptables),
//    tutto il suo UDP su :9998. Si salva ogni byte ricevuto, con porta di destinazione originale.
const dgram = require('dgram'), net = require('net'), fs = require('fs'), os = require('os'), { execSync } = require('child_process');
const PUB = process.env.PUBLIC_IP;           // IP pubblico del server
const OUT = '/data'; fs.mkdirSync(OUT, { recursive: true });
const log = (...a) => { const l = new Date().toISOString() + ' ' + a.join(' '); console.log(l); fs.appendFileSync(OUT + '/log.txt', l + '\n'); };
const hooked = new Set();
function hook(ip) {
  if (hooked.has(ip) || ip.startsWith('127.')) return; hooked.add(ip);
  try {
    execSync(`iptables -t nat -I PREROUTING -s ${ip} -p tcp ! --dport 53 -j REDIRECT --to-ports 9999`);
    execSync(`iptables -t nat -I PREROUTING -s ${ip} -p udp ! --dport 53 -j REDIRECT --to-ports 9998`);
    log('AGGANCIATO', ip);
  } catch (e) { log('iptables errore', e.message); }
}
function origPort(sock) {
  try { return execSync(`conntrack -L -p tcp --src ${sock.remoteAddress} --sport ${sock.remotePort} 2>/dev/null | grep -o 'dport=[0-9]*' | head -1`).toString().trim(); } catch { return '?'; }
}
// ---- DNS
function qname(b, o) { const p = []; while (b[o]) { p.push(b.slice(o + 1, o + 1 + b[o]).toString()); o += b[o] + 1; } return [p.join('.'), o + 1]; }
function answer(q) {
  const [name, end] = qname(q, 12); const type = q.readUInt16BE(end);
  const h = Buffer.from(q.slice(0, 12)); h.writeUInt16BE(0x8180, 2); h.writeUInt16BE(1, 4); h.writeUInt16BE(type === 1 ? 1 : 0, 6); h.writeUInt16BE(0, 8); h.writeUInt16BE(0, 10);
  const parts = [h, q.slice(12, end + 4)];
  if (type === 1) { const a = Buffer.alloc(16); a.writeUInt16BE(0xc00c, 0); a.writeUInt16BE(1, 2); a.writeUInt16BE(1, 4); a.writeUInt32BE(60, 6); a.writeUInt16BE(4, 10); PUB.split('.').forEach((x, i) => a[12 + i] = +x); parts.push(a); }
  return [name, type, Buffer.concat(parts)];
}
const udp = dgram.createSocket('udp4');
udp.on('message', (m, r) => { try { const [n, t, a] = answer(m); log('DNS', r.address, n, 'tipo', t); hook(r.address); udp.send(a, r.port, r.address); } catch (e) {} });
udp.bind(53, PUB);
net.createServer(s => { s.once('data', d => { try { const [n, t, a] = answer(d.slice(2)); log('DNS-TCP', s.remoteAddress, n); hook(s.remoteAddress.replace('::ffff:', '')); const l = Buffer.alloc(2); l.writeUInt16BE(a.length); s.end(Buffer.concat([l, a])); } catch { s.destroy(); } }); }).listen(53, PUB);
// ---- ascoltatori
let n = 0;
net.createServer(s => {
  const id = ++n, port = origPort(s); log('TCP', id, s.remoteAddress, '->', port);
  const f = `${OUT}/tcp-${id}-${port.replace('dport=', '')}.bin`;
  s.on('data', d => { fs.appendFileSync(f, d); log('TCP', id, d.length, 'byte', d.slice(0, 64).toString('hex')); });
  s.on('error', () => {}); s.setTimeout(60000, () => s.destroy());
}).listen(9999);
const u2 = dgram.createSocket('udp4');
u2.on('message', (m, r) => { log('UDP', r.address + ':' + r.port, m.length, 'byte', m.slice(0, 96).toString('hex')); fs.appendFileSync(`${OUT}/udp.txt`, `${Date.now()} ${r.address}:${r.port} ${m.toString('hex')}\n`); });
u2.bind(9998);
log('pronto su', PUB);
// alla chiusura si tolgono le regole aggiunte
function clean() { for (const ip of hooked) { try { execSync(`iptables -t nat -D PREROUTING -s ${ip} -p tcp ! --dport 53 -j REDIRECT --to-ports 9999`); execSync(`iptables -t nat -D PREROUTING -s ${ip} -p udp ! --dport 53 -j REDIRECT --to-ports 9998`); } catch {} } process.exit(0); }
process.on('SIGTERM', clean); process.on('SIGINT', clean);
