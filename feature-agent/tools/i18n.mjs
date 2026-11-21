// Holt die Texte des KI-Agenten aus der Weboberfläche (Repo TitanOS,
// web/src/lib/i18n) in die Agent-App: dieselben Schlüssel und Worte wie im
// Web, damit die App nichts eigenes erfindet. Aufruf:
//   node feature-agent/tools/i18n.mjs /pfad/zu/TitanOS
// Schreibt feature-agent/src/main/assets/agent-i18n/{de,en}.json.
import { readFileSync, writeFileSync, mkdirSync, readdirSync, statSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const repo = process.argv[2] ?? '../TitanOS';
const i18n = join(repo, 'web/src/lib/i18n');
const here = dirname(fileURLToPath(import.meta.url));
const out = join(here, '../src/main/assets/agent-i18n');

// Dieselbe Reihenfolge wie FILES in i18n/index.svelte.ts.
const FEATURES = ['docs', 'protect', 'foresight', 'family', 'desktop', 'appaccess', 'taskmanager', 'lock',
  'settingshome', 'viewer', 'look', 'webapps', 'menubar', 'design', 'errors', 'nvr', 'phone', 'runs', 'ui',
  'mail', 'agentpim'];
// Ganze Gruppen, deren Schlüssel der Agent zur Laufzeit zusammensetzt.
const PREFIXES = ['agent.', 'common.', 'error.', 'ui.', 'nav.', 'docs.', 'protect.', 'foresight.', 'family.',
  'runs.', 'agentpim.', 'lang.', 'time.', 'units.', 'notify.', 'push.'];

function load(lang) {
  const parts = [JSON.parse(readFileSync(join(i18n, `${lang}.json`), 'utf8'))];
  for (const f of FEATURES) parts.push(JSON.parse(readFileSync(join(i18n, 'features', `${f}.${lang}.json`), 'utf8')));
  return Object.assign({}, ...parts);
}

// Schlüssel, die die Agent-Seiten wörtlich nennen (auch außerhalb der Gruppen oben).
function walk(dir, files = []) {
  for (const n of readdirSync(dir)) {
    const p = join(dir, n);
    if (statSync(p).isDirectory()) walk(p, files);
    else if (/\.(svelte|ts)$/.test(n)) files.push(p);
  }
  return files;
}
const sources = [
  ...walk(join(repo, 'web/src/routes/agent')),
  ...walk(join(repo, 'web/src/lib/components/agent')),
  ...readdirSync(join(repo, 'web/src/lib')).filter((n) => /^agent.*\.ts$|^account-agent-data\.ts$/.test(n)).map((n) => join(repo, 'web/src/lib', n)),
];
const named = new Set();
for (const f of sources) {
  for (const m of readFileSync(f, 'utf8').matchAll(/['"`]([a-z][A-Za-z0-9]*(?:\.[A-Za-z0-9_]+)+)['"`]/g)) named.add(m[1]);
}

const de = load('de');
const en = load('en');
const keep = (k) => PREFIXES.some((p) => k.startsWith(p)) || named.has(k);
mkdirSync(out, { recursive: true });
for (const [lang, table] of [['de', de], ['en', en]]) {
  const picked = Object.fromEntries(Object.keys(table).filter(keep).sort().map((k) => [k, table[k]]));
  writeFileSync(join(out, `${lang}.json`), JSON.stringify(picked) + '\n');
  console.log(lang, Object.keys(picked).length, 'Texte');
}
