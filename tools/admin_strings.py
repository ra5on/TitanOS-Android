#!/usr/bin/env python3
"""Übernimmt die Texte der Weboberfläche für „Ablage und Werkzeuge“ (Speicher,
Freigaben, Benutzer, Plugins, Werkzeuge) als Android-Ressourcen in core-admin.

Aufruf: tools/admin_strings.py <TitanOS-Repo>
Schreibt core-admin/src/main/res/values{,-en}/admin_web_strings.xml (Deutsch ist
die Grundsprache der App) und core-admin/.../AdminKeys.kt (Schlüssel des Webs →
Ressource). Platzhalter bleiben wie im Web ({name}); t() setzt sie ein.

Eigenes Präfix `a_` und eigene Dateinamen, damit die Ressourcen nie die des
Bereichs Rechnen (core-compute, `w_`) überdecken.
"""
import json, os, re, sys

repo = sys.argv[1]
here = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
i18n = os.path.join(repo, "web/src/lib/i18n")

def load(lang):
    d = json.load(open(os.path.join(i18n, f"{lang}.json")))
    feat = os.path.join(i18n, "features")
    for f in sorted(os.listdir(feat)):
        if f.endswith(f".{lang}.json"):
            for k, v in json.load(open(os.path.join(feat, f))).items():
                d.setdefault(k, v)
    return d

de, en = load("de"), load("en")

# Ganze Bereiche (auch Schlüssel, die die Seiten zusammensetzen: `storage.state.${x}`).
WHOLE = ["storage", "share", "shares", "users", "user", "plugins", "plugin", "tools", "ops", "activity", "hwcheck",
         "smart", "smartHistory", "disktest", "tasks", "scripts", "crypt", "cache", "btrfs", "disk", "diskSleep",
         "snapshots", "snapshot", "snapshotRestore", "schedule", "error", "common", "help", "state", "status", "keep",
         "cleanup", "taskmanager", "runs", "logs", "journal", "monitor", "monitoring", "maintenance", "create", "detail",
         "sync", "syncSpeed", "level", "shortcuts", "report", "duration", "confirm", "delete", "nav", "desktop.side",
         "desktop.app", "ui", "import", "replace", "swap", "recovery", "bundle", "hardware", "nvidia", "drivers",
         "unbound", "volume", "volumes", "raid", "account", "access", "quota", "quotas", "sysdata", "uas", "wake",
         "phone", "format", "backup.day", "backup.dayShort", "report.day", "usage", "danger", "stay", "dashboard",
         "temperatures", "fans", "services", "tailscale", "netbird", "agentData", "accountAgent", "growth", "smr"]

# Jede Zeichenkette in den Seiten und den Bausteinen, die sie benutzen und die ein Schlüssel ist.
src_dirs = ["routes/storage", "routes/shares", "routes/users", "routes/plugins", "routes/tools", "routes/logs",
            "lib/operations", "lib/components", "lib/components/settings", "lib/components/explorer", "lib"]
lit = re.compile(r"""['"`]([a-zA-Z][a-zA-Z0-9_]*(?:\.[a-zA-Z0-9_]+)+)['"`]""")
used = set()
for d in src_dirs:
    base = os.path.join(repo, "web/src", d)
    for root, _, files in os.walk(base):
        if d.startswith("lib") and root != base:
            continue
        for f in files:
            if f.endswith((".svelte", ".ts")) and not f.endswith(".test.ts"):
                used.update(k for k in lit.findall(open(os.path.join(root, f), encoding="utf8").read()) if k in de)

keys = sorted(k for k in de if any(k == p or k.startswith(p + ".") for p in WHOLE) or k in used)

def res_name(k):
    return "a_" + re.sub(r"[^A-Za-z0-9]", "_", k)

def esc(t):
    t = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    t = t.replace("\\", "\\\\").replace("'", "\\'").replace('"', '\\"').replace("\n", "\\n")
    if t.startswith(("@", "?")):
        t = "\\" + t
    return t

names, seen = {}, set()
for k in keys:
    n = res_name(k)
    while n in seen:
        n += "_"
    seen.add(n)
    names[k] = n

def write(lang, table):
    out = os.path.join(here, "core-admin/src/main/res", "values" if lang == "de" else "values-en")
    os.makedirs(out, exist_ok=True)
    with open(os.path.join(out, "admin_web_strings.xml"), "w") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n<!-- Erzeugt von tools/admin_strings.py aus den Texten der Weboberfläche. Nicht von Hand ändern. -->\n')
        f.write('<resources xmlns:tools="http://schemas.android.com/tools" tools:ignore="UnusedResources,Typos">\n')
        for k in keys:
            v = table.get(k, de[k])
            fmt = ' formatted="false"' if "%" in v else ""
            f.write(f'    <string name="{names[k]}"{fmt}>{esc(v)}</string>\n')
        f.write("</resources>\n")

write("de", de)
write("en", en)

kt = os.path.join(here, "core-admin/src/main/kotlin/io/github/ra5on/titanos/admin/AdminKeys.kt")
os.makedirs(os.path.dirname(kt), exist_ok=True)
chunks = [keys[i:i + 300] for i in range(0, len(keys), 300)]
with open(kt, "w") as f:
    f.write("// Erzeugt von tools/admin_strings.py aus den Texten der Weboberfläche. Nicht von Hand ändern.\n")
    f.write("package io.github.ra5on.titanos.admin\n\n")
    f.write("/** Schlüssel der Weboberfläche (z. B. `storage.create`) → Ressource in core-admin. */\n")
    f.write("internal object AdminKeys {\n")
    f.write("    val map: Map<String, Int> by lazy {\n        HashMap<String, Int>(%d).apply {\n" % (len(keys) * 2))
    for i in range(len(chunks)):
        f.write(f"            part{i}(this)\n")
    f.write("        }\n    }\n")
    for i, c in enumerate(chunks):
        f.write(f"\n    private fun part{i}(m: HashMap<String, Int>) {{\n")
        for k in c:
            f.write(f'        m["{k}"] = R.string.{names[k]}\n')
        f.write("    }\n")
    f.write("}\n")
print(len(keys), "keys")

# Die Strichsymbole der Weboberfläche (Icon.svelte, 24-px-Raster) für AdminIcons.
icon_src = open(os.path.join(repo, "web/src/lib/components/Icon.svelte"), encoding="utf8").read()
block = icon_src[icon_src.index("const PATHS = {"):icon_src.index("} as const;")]
icons = re.findall(r"(\w+):\s*'([^']*)'", block)
ikt = os.path.join(here, "core-admin/src/main/kotlin/io/github/ra5on/titanos/admin/AdminIconPaths.kt")
with open(ikt, "w") as f:
    f.write("// Erzeugt von tools/admin_strings.py aus web/src/lib/components/Icon.svelte. Nicht von Hand ändern.\n")
    f.write("package io.github.ra5on.titanos.admin\n\n")
    f.write("/** Pfade der Strichsymbole des Webs (24-px-Raster), Name wie `IconName` im Web. */\n")
    f.write("internal val ICON_PATHS: Map<String, String> = mapOf(\n")
    for name, d in icons:
        f.write(f'    "{name}" to "{d}",\n')
    f.write(")\n")
print(len(icons), "icons")
