// Holt die Testdaten der Funktionen (Vorausschau, Papierkram, Familie, Schutz)
// aus den Web-Tests scripts/test-ui-agent54-*.mjs nach
// feature-agent/src/test/resources/agent54-fixtures.json.
// node feature-agent/tools/fixtures54.mjs <TitanOS-Checkout>
import { readFileSync, writeFileSync } from "node:fs";
const S = (process.argv[2] || "../TitanOS") + "/scripts/";
function slice(file, from, to, ret) {
  const lines = readFileSync(S + file, "utf8").split("\n");
  const code = lines.slice(from - 1, to - 1).join("\n");
  return new Function(code + "\nreturn (" + ret + ");")();
}
const fore = slice("test-ui-agent54-foresight.mjs", 22, 250, `{storage, advisor: advisorView(false), disk: result({kind:"disk", disk: "sda"}), full: result({kind:"full", volume:"Data"})}`);
const docs = slice("test-ui-agent54-docs.mjs", 22, 202, `{paper}`);
const fam = slice("test-ui-agent54-family.mjs", 22, 98, `{buddy}`);
const prot = slice("test-ui-agent54-protect.mjs", 20, 194, `{handbook, emergency}`);
writeFileSync(new URL("../src/test/resources/agent54-fixtures.json", import.meta.url), JSON.stringify({ ...fore, ...docs, ...fam, ...prot }));

