import { start } from './server.js';

const token = process.env.PONTE_TOKEN ?? '';
if (token.length < 16) {
  console.error('PONTE_TOKEN mancante o troppo corto: servono almeno 16 caratteri.');
  process.exit(1);
}

const s = await start({
  port: Number(process.env.PORT ?? 8080),
  token,
  dataDir: process.env.PONTE_DATA ?? './data',
  maxFileBytes: Number(process.env.PONTE_MAX_FILE_MB ?? 200) * 1024 * 1024,
  github: {
    repo: process.env.PONTE_GITHUB_REPO ?? '00Fil/ponte',
    token: process.env.PONTE_GITHUB_TOKEN ?? '',
    api: 'https://api.github.com',
  },
});
console.log(`Relay in ascolto sulla porta ${s.port}`);

for (const sig of ['SIGINT', 'SIGTERM']) {
  process.on(sig, async () => {
    await s.close();
    process.exit(0);
  });
}
