// Holt aus der Weboberfläche (Repo TitanOS) alles, was Systemsteuerung und
// „Mein Konto“ der App wie im Web zeigen: die Texte mit denselben Schlüsseln,
// den Suchindex über jede Einstellung (settings-index.gen.ts, Synonyme,
// Stichwörter, Bereiche) und die Strichsymbole. Nichts wird erfunden.
//   node feature-settings/tools/web.mjs /pfad/zu/TitanOS
// Schreibt feature-settings/src/main/assets/settings-i18n/{de,en}.json,
// …/settings-index.json und core-ui/…/ui/web/WebIconPaths.kt.
import { readFileSync, writeFileSync, mkdirSync, readdirSync, statSync, existsSync } from 'node:fs';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const repo = resolve(process.argv[2] ?? '../TitanOS');
const web = join(repo, 'web/src');
const here = dirname(fileURLToPath(import.meta.url));
const assets = join(here, '../src/main/assets');

// Texte: Grundtabelle und alle Feature-Dateien (wie FILES in i18n/index.svelte.ts).
function load(lang) {
  const parts = [JSON.parse(readFileSync(join(web, 'lib/i18n', `${lang}.json`), 'utf8'))];
  for (const f of readdirSync(join(web, 'lib/i18n/features')).filter((n) => n.endsWith(`.${lang}.json`)).sort())
    parts.push(JSON.parse(readFileSync(join(web, 'lib/i18n/features', f), 'utf8')));
  return Object.assign({}, ...parts);
}

// Die Quellen: Seiten der Systemsteuerung und von „Mein Konto“ und alles, was sie einbinden.
function walk(dir, files = []) {
  for (const n of readdirSync(dir)) {
    const p = join(dir, n);
    if (statSync(p).isDirectory()) walk(p, files);
    else if (/\.(svelte|ts)$/.test(n)) files.push(p);
  }
  return files;
}
const seen = new Set();
const queue = [...walk(join(web, 'routes/settings')), ...walk(join(web, 'routes/account'))];
function resolveImport(from, spec) {
  let p;
  if (spec.startsWith('#lib/')) p = join(web, 'lib', spec.slice(5));
  else if (spec.startsWith('$lib/')) p = join(web, 'lib', spec.slice(5));
  else if (spec.startsWith('.')) p = join(dirname(from), spec);
  else return null;
  for (const c of [p, p + '.ts', p + '.svelte', join(p, 'index.ts')]) if (existsSync(c) && statSync(c).isFile()) return c;
  return null;
}
const named = new Set();
while (queue.length) {
  const f = queue.pop();
  if (seen.has(f)) continue;
  seen.add(f);
  // Riesige erzeugte Dateien (Index) nur über ihre Einträge, nicht als Quelle.
  if (f.endsWith('.gen.ts') || f.includes('/i18n/')) continue;
  const src = readFileSync(f, 'utf8');
  for (const m of src.matchAll(/['"`]([a-z][A-Za-z0-9]*(?:\.[A-Za-z0-9_]+)+)['"`]/g)) named.add(m[1]);
  // Zur Laufzeit gebaute Schlüssel: `notify.event.${id}` → Vorsilbe „notify.event.“.
  for (const m of src.matchAll(/`([a-z][A-Za-z0-9]*(?:\.[A-Za-z0-9_]+)*\.)\$\{/g)) named.add(m[1] + '*');
  for (const m of src.matchAll(/from\s+['"]([^'"]+)['"]/g)) {
    const r = resolveImport(f, m[1]);
    if (r) queue.push(r);
  }
}

const search = await import(pathToFileURL(join(web, 'lib/settings-search.ts')));
const find = await import(pathToFileURL(join(web, 'lib/settings-find.ts')));
const gen = await import(pathToFileURL(join(web, 'lib/settings-index.gen.ts')));

const de = load('de');
const en = load('en');
// Ganze Gruppen, deren erster Teil die Seiten nennen (Schlüssel entstehen oft zur Laufzeit).
const spaces = new Set([...named].map((k) => k.split('.')[0]).filter((s) => Object.keys(de).some((k) => k.startsWith(s + '.'))));
for (const s of ['error', 'errors', 'common', 'lang', 'time', 'units', 'settingshome', 'menu', 'ui']) spaces.add(s);
const extra = new Set([
  ...gen.settingsIndex.map((e) => e.k).filter(Boolean),
  ...gen.settingsIndex.flatMap((e) => e.in ?? []).filter((r) => !r.startsWith('=')),
  ...search.settingsAreas.map((a) => a.label),
  ...search.settingsGroups.map((g) => `settingshome.group.${g}`),
  ...find.otherPages.map((o) => o.label),
]);
const keep = (k) => spaces.has(k.split('.')[0]) || extra.has(k);
mkdirSync(join(assets, 'settings-i18n'), { recursive: true });
for (const [lang, table] of [['de', de], ['en', en]]) {
  const picked = Object.fromEntries(Object.keys(table).filter(keep).sort().map((k) => [k, table[k]]));
  writeFileSync(join(assets, 'settings-i18n', `${lang}.json`), JSON.stringify(picked) + '\n');
  console.log(lang, Object.keys(picked).length, 'Texte');
}

// Suchindex und Bereiche wie settings-search.ts und settings-find.ts.
const index = {
  groups: search.settingsGroups,
  groupIcons: search.settingsGroupIcons,
  areas: search.settingsAreas.map((a) => ({ ...a, keywords: search.settingsKeywords(a.id) })),
  synonyms: find.synonyms,
  otherPages: find.otherPages,
  entries: gen.settingsIndex,
};
writeFileSync(join(assets, 'settings-index.json'), JSON.stringify(index) + '\n');
console.log(index.entries.length, 'Einträge im Suchindex,', index.areas.length, 'Seiten');

// Strichsymbole (Icon.svelte) für core-ui.
const icon = readFileSync(join(web, 'lib/components/Icon.svelte'), 'utf8');
const body = icon.slice(icon.indexOf('const PATHS = {') + 15, icon.indexOf('} as const;'));
const paths = [...body.matchAll(/^\s*([a-zA-Z0-9]+):\s*\n?\s*'([^']+)'/gm)].map((m) => [m[1], m[2]]);
const kt = `package io.github.ra5on.titanos.ui.web

// Erzeugt von feature-settings/tools/web.mjs aus web/src/lib/components/Icon.svelte – nicht von Hand ändern.

/** Die Pfade der Strichsymbole des Webs (24er Raster). */
internal val WEB_ICON_PATHS: Map<String, String> = mapOf(
${paths.map(([k, v]) => `    "${k}" to "${v}",`).join('\n')}
)
`;
const ktDir = join(here, '../../core-ui/src/main/kotlin/io/github/ra5on/titanos/ui/web');
mkdirSync(ktDir, { recursive: true });
writeFileSync(join(ktDir, 'WebIconPaths.kt'), kt);
console.log(paths.length, 'Symbole');
