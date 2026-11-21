// Übernimmt die Strichsymbole der Weboberfläche (web/src/lib/components/Icon.svelte)
// als Kotlin (WebIcons.kt): dieselben Namen und Pfade wie im Web.
//   node feature-agent/tools/icons.mjs /pfad/zu/TitanOS
import { readFileSync, writeFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
const repo = process.argv[2] ?? '../TitanOS';
const src = readFileSync(join(repo, 'web/src/lib/components/Icon.svelte'), 'utf8');
const body = src.slice(src.indexOf('const PATHS = {') + 15, src.indexOf('} as const;'));
const out = [];
for (const m of body.matchAll(/^\s*([a-zA-Z0-9]+):\s*\n?\s*'([^']+)'/gm)) out.push([m[1], m[2]]);
const here = dirname(fileURLToPath(import.meta.url));
const kt = `package io.github.ra5on.titanos.agent.core

// Erzeugt von feature-agent/tools/icons.mjs aus web/src/lib/components/Icon.svelte – nicht von Hand ändern.

/** Die Pfade der Strichsymbole des Webs (24er Raster). */
internal val WEB_ICON_PATHS: Map<String, String> = mapOf(
${out.map(([k, v]) => `    "${k}" to "${v}",`).join('\n')}
)
`;
writeFileSync(join(here, '../src/main/kotlin/io/github/ra5on/titanos/agent/core/WebIconPaths.kt'), kt);
console.log(out.length, 'Symbole');
