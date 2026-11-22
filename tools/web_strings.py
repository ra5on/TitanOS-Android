#!/usr/bin/env python3
"""Übernimmt die Texte der Weboberfläche für den Bereich Rechnen (Docker,
VMs, LXC, NVR, Terminal) als Android-Ressourcen in core-compute.

Aufruf: tools/web_strings.py <TitanOS-Repo>
Schreibt core-compute/src/main/res/values{,-en}/web_strings.xml (Deutsch ist
die Grundsprache der App) und core-compute/.../WebKeys.kt (Schlüssel des Webs
→ Ressource). Platzhalter bleiben wie im Web ({name}); t() setzt sie ein.
"""
import json, os, re, sys

repo = sys.argv[1]
here = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
i18n = os.path.join(repo, "web/src/lib/i18n")

def load(lang):
    d = json.load(open(os.path.join(i18n, f"{lang}.json")))
    feat = os.path.join(i18n, "features")
    for f in sorted(os.listdir(feat)):
        if f.endswith(f".{lang}.json") and f.split(".")[0] in ("nvr", "errors", "ui", "viewer", "appaccess", "webapps", "design", "phone", "desktop", "menubar"):
            d.update(json.load(open(os.path.join(feat, f))))
    return d

de, en = load("de"), load("en")

WHOLE = ["nvr", "vms", "docker", "dres", "appform", "lxc", "apps", "media", "terminal", "snapshots", "snapshot",
         "dnet", "appopens", "tailnetPublish", "lan", "schedule", "hardware", "proxy", "netx", "autoUpdate",
         "appaccess", "error", "common", "cleanup", "ops", "report.day", "backup.dayShort", "backup.day",
         "desktop.side", "desktop.app", "state", "status", "duration", "confirm", "delete", "keep", "digest",
         "bridge", "network", "webapps", "viewer", "editor.restart", "editor.network", "settings.terminal", "settings.terminalDesc", "settings.consoles", "settings.consoleIdle", "settings.consoleIdleMinutes", "settings.consoleIdleNever", "settings.consoleIdleDesc", "ui"]

# Einzelne Schlüssel aus anderen Bereichen, die die Seiten nutzen.
src_dirs = ["routes/docker", "routes/vms", "routes/lxc", "routes/nvr", "routes/terminal", "routes/apps", "lib/nvr",
            "lib/components", "lib"]
used = set()
pat = re.compile(r"""\bt\(\s*['"`]([a-zA-Z0-9_.]+)['"`]""")
for d in src_dirs:
    base = os.path.join(repo, "web/src", d)
    for root, _, files in os.walk(base):
        if d in ("lib", "lib/components") and root != base:
            continue
        for f in files:
            if f.endswith((".svelte", ".ts")):
                used.update(pat.findall(open(os.path.join(root, f), encoding="utf8").read()))

keys = sorted(k for k in de if any(k == p or k.startswith(p + ".") for p in WHOLE) or k in used)

def res_name(k):
    return "w_" + re.sub(r"[^A-Za-z0-9]", "_", k)

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
    out = os.path.join(here, "core-compute/src/main/res", "values" if lang == "de" else "values-en")
    os.makedirs(out, exist_ok=True)
    with open(os.path.join(out, "web_strings.xml"), "w") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n<!-- Erzeugt von tools/web_strings.py aus den Texten der Weboberfläche. Nicht von Hand ändern. -->\n')
        f.write('<resources xmlns:tools="http://schemas.android.com/tools" tools:ignore="UnusedResources,Typos">\n')
        for k in keys:
            v = table.get(k, de[k])
            fmt = ' formatted="false"' if "%" in v else ""
            f.write(f'    <string name="{names[k]}"{fmt}>{esc(v)}</string>\n')
        f.write("</resources>\n")

write("de", de)
write("en", en)

kt = os.path.join(here, "core-compute/src/main/kotlin/io/github/ra5on/titanos/compute/WebKeys.kt")
chunks = [keys[i:i + 300] for i in range(0, len(keys), 300)]
with open(kt, "w") as f:
    f.write("// Erzeugt von tools/web_strings.py aus den Texten der Weboberfläche. Nicht von Hand ändern.\n")
    f.write("package io.github.ra5on.titanos.compute\n\n")
    f.write("/** Schlüssel der Weboberfläche (z. B. `docker.start`) → Ressource in core-compute. */\n")
    f.write("internal object WebKeys {\n")
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
