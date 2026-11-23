// Erzeugt Vergleichsdaten: die Logik des Webs (web/src/lib/compose) gegen feste Eingaben.
import { writeFileSync } from 'node:fs';
// Aufruf: node tools/compose-web-fixtures.mjs <TitanOS-Repo>/web feature-docker/src/test/resources/compose-web.json [<TitanOS-Apps>]
// Mit dem Katalog-Repo kommen echte Vorlagen dazu (immich, nextcloud, zigbee2mqtt, frigate, bambu-lab).
const WEB = process.argv[2];
const { readTemplate, check, build } = await import(WEB + '/src/lib/compose/template.ts');
const { fromCasaOS, isCasaOS } = await import(WEB + '/src/lib/compose/casaos.ts');
const { withHardware, hardwareSelections } = await import(WEB + '/src/lib/compose/hardware.ts');
const { readService, writeService, serviceNames } = await import(WEB + '/src/lib/compose/service.ts');
const { assignContainerLAN, lanAssignment } = await import(WEB + '/src/lib/compose/lan.ts');
const { parseDocument } = await import(WEB + '/node_modules/yaml/dist/index.js');

const nextcloud = `# Nextcloud mit Datenbank
x-titanos:
  main: app
  icon: https://example.org/nextcloud.png
  tips:
    de: Zuerst die Datenbank starten lassen.
    en: Let the database start first.
  variables:
    MYSQL_PASSWORD:
      label: { de: Datenbank-Passwort, en: Database password }
      secret: generate
    NEXTCLOUD_ADMIN_USER:
      label: Admin
      required: true
      placeholder: admin
    NEXTCLOUD_ADMIN_PASSWORD:
      secret: true
      required: true
    TZ:
      help: Zeitzone
  paths:
    /var/www/html:
      label: { de: Programm und Daten, en: App and data }
    /media:
      label: Medien
      suggest: media
  ports:
    "80":
      label: Weboberfläche
      webui: true
services:
  db:
    image: mariadb:11
    restart: unless-stopped
    environment:
      MYSQL_PASSWORD: ""
      MYSQL_DATABASE: nextcloud
    volumes:
      - ./db:/var/lib/mysql # Datenbank
  app:
    image: nextcloud:30
    restart: unless-stopped
    environment:
      - MYSQL_PASSWORD=
      - MYSQL_HOST=db
      - NEXTCLOUD_ADMIN_USER=
      - NEXTCLOUD_ADMIN_PASSWORD=
      - TZ=UTC
      - PUID=1000
    volumes:
      - ./html:/var/www/html
      - /mnt/Data/Medien:/media:ro
      - cache:/cache
    ports:
      - "8080:80"
      - 3478:3478/udp
    devices:
      - /dev/dri:/dev/dri
      - /dev/ttyUSB0:/dev/ttyUSB0
    labels:
      titanos.webui: "8080"
volumes:
  cache: {}
`;

const anchors = `x-common: &common
  restart: always
  environment: &env
    TZ: Europe/Berlin
    LOG: info
services:
  web:
    <<: *common
    image: nginx:1.27
    ports:
      - target: 80
        published: 8081
        protocol: tcp
    volumes:
      - type: bind
        source: ./site
        target: /usr/share/nginx/html
        read_only: true
  worker:
    <<: *common
    image: busybox
    environment: *env
    command: ["sh", "-c", "sleep infinity"]
`;

const casa = `name: big-bear-example
services:
  app:
    image: example/app:1
    environment:
      PUID: $PUID
      PGID: \${PGID}
      TZ: $TZ
      TOKEN: "[EUFY_USERNAME]"
      KEY: \${KEY:?Bitte den Schlüssel eintragen}
      URL: http://[CASAOS_IP]:8000
    volumes:
      - /DATA/AppData/$AppID/data:/data
      - /DATA/Media/Movies:/movies
      - /DATA/Downloads:/downloads
    ports:
      - "8000:8000"
      - 5353:5353/udp
    x-casaos:
      envs:
        - container: TOKEN
          description:
            en_US: Your Eufy user name
            de_DE: Dein Eufy-Benutzername
        - container: TZ
          description:
            en_US: Container Variable TZ
      volumes:
        - container: /data
          description:
            en_US: App data
      ports:
        - container: "8000"
          description:
            en_US: Web UI
x-casaos:
  architectures: [amd64, arm64]
  main: app
  category: Networking
  icon: https://example.org/icon.png
  index: /admin
  port_map: "8000"
  tips:
    before_install:
      en_US: Read the docs first.
  description:
    en_US: |
      A long text
      over several lines.
  title:
    en_US: Example
`;

const plain = 'services:\n  web:\n    image: traefik/whoami\n    ports:\n      - "8080:80"\n';

const base = { timezone: 'Europe/Berlin', uid: 1000, gid: 100, appdata: '/mnt/Data/appdata', listening: [{ port: 8080, protocol: 'tcp' }], missingDevices: ['/dev/ttyUSB0'] };
const shares = [{ name: 'Medien', path: '/mnt/Data/Medien' }, { name: 'Dokumente', path: '/mnt/Data/Dokumente' }];

const scenarios = [
  { id: 'nextcloud', compose: nextcloud, name: 'nextcloud', shares, used: { '8080/tcp': '', '8081/tcp': 'jellyfin' }, installed: ['jellyfin'],
    edits: { vars: { NEXTCLOUD_ADMIN_USER: 'chef', NEXTCLOUD_ADMIN_PASSWORD: 'geheim!' }, paths: [{ i: 0, host: '/mnt/Data/nc' }],
      own: { vars: [{ name: 'TZ', value: 'Europe/Vienna' }, { name: 'EXTRA', value: '1' }], paths: [{ host: '/mnt/Data/x', container: '/x' }], ports: [{ host: '9000', container: '9000', protocol: 'tcp' }], labels: [{ name: 'a.b', value: 'c' }], devices: ['/dev/net/tun'] },
      cpuset: '0-1', memLimit: '2g', publicUrl: 'https://cloud.example.org', hardware: { app: ['intel-gpu'] } } },
  { id: 'anchors', compose: anchors, name: 'web', shares: [], used: {}, installed: [], edits: { images: { web: 'nginx:1.28' } } },
  { id: 'casa', compose: casa, casa: { appId: 'example', ctx: { timezone: 'Europe/Berlin', uid: 1000, gid: 100, host: '192.168.1.5' } }, name: 'example', shares, used: { '8000/tcp': 'other' }, installed: [],
    edits: { vars: { TOKEN: 'me', KEY: 'k' } } },
  { id: 'plain', compose: plain, name: 'whoami', shares: [], used: { '8080/tcp': '' }, installed: ['whoami'], edits: {} },
];

const { readFileSync } = await import('node:fs');
const APPS = process.argv[4];
if (APPS) {
  for (const id of ['immich', 'nextcloud', 'zigbee2mqtt', 'frigate', 'bambu-lab']) {
    const compose = readFileSync(`${APPS}/${id}/compose.yaml`, 'utf8');
    // Pflichtfelder ausfüllen, damit build() wie beim Installieren läuft.
    const probe = readTemplate({ ...base, compose }, id, shares, new Map());
    const vars = {};
    for (const v of probe.vars) if (v.required && v.value === '') vars[v.name] = 'wert-' + v.name.toLowerCase();
    scenarios.push({ id: 'katalog-' + id, compose, name: id, shares, used: { '8080/tcp': '' }, installed: [], edits: { vars, own: { vars: [{ name: 'EXTRA', value: 'ja' }], paths: [], ports: [], labels: [], devices: [] } } });
  }
}
const out = [];
for (const s of scenarios) {
  let compose = s.compose;
  if (s.casa) compose = fromCasaOS(compose, s.casa.appId, s.casa.ctx);
  const tpl = { ...base, compose };
  const used = new Map(Object.entries(s.used));
  const form = readTemplate(tpl, s.name, s.shares, used);
  for (const v of form.vars) if (v.origin === 'generated') v.value = 'GEN_' + v.name;
  const read = JSON.parse(JSON.stringify(form));
  const e = s.edits;
  for (const v of form.vars) if (e.vars && v.name in e.vars) v.value = e.vars[v.name];
  for (const p of e.paths ?? []) { form.paths[p.i].host = p.host; form.paths[p.i].touched = true; }
  for (const im of form.images) if (e.images && im.service in e.images) im.image = e.images[im.service];
  if (e.own) form.own = { vars: [], paths: [], ports: [], labels: [], devices: [], ...e.own };
  if (e.cpuset) form.cpuset = e.cpuset;
  if (e.memLimit) form.memLimit = e.memLimit;
  if (e.publicUrl) form.publicUrl = e.publicUrl;
  const problems = check(form, s.installed, used);
  const built = build(tpl, form);
  const finalCompose = e.hardware ? withHardware(built.compose, e.hardware) : built.compose;
  out.push({ id: s.id, input: s, casaCompose: s.casa ? compose : null, read, problems, compose: finalCompose, env: built.env,
    plain: parseDocument(finalCompose, { merge: true }).toJS(), casaPlain: s.casa ? parseDocument(compose).toJS() : null,
    hardware: hardwareSelections(finalCompose) });
}

// Formular eines Dienstes: lesen, ändern, schreiben.
const edits = [];
for (const [id, text, service, change] of [
  ['anchors-web', anchors, 'web', (f) => { f.ports[0].host = '9090'; f.env.push({ name: 'NEW', value: 'x' }); f.restart = 'unless-stopped'; }],
  ['anchors-worker', anchors, 'worker', (f) => { f.env = f.env.filter((p) => p.name !== 'LOG'); f.privileged = true; f.network = 'host'; }],
  ['nextcloud-app', nextcloud, 'app', (f) => { f.paths[1].readOnly = false; f.devices = f.devices.slice(0, 1); f.webui = '8080/login'; f.labels.push({ name: 'x', value: 'y' }); f.memLimit = '1g'; }],
  ['plain-web', plain, 'web', (f) => { f.ports.push({ host: '', container: '443', protocol: 'tcp' }); f.paths.push({ host: './data', container: '/data', readOnly: true }); }],
]) {
  const doc = parseDocument(text, { merge: true });
  const before = readService(doc, service);
  const f = JSON.parse(JSON.stringify(before));
  change(f);
  writeService(doc, service, f);
  const result = doc.toString({ lineWidth: 0 });
  edits.push({ id, input: text, service, before, after: f, compose: result, plain: parseDocument(result, { merge: true }).toJS(), services: serviceNames(doc) });
}

// LAN-Netz eines Dienstes.
const lanIn = 'services:\n  app:\n    image: x\n    networks: [backend]\nnetworks:\n  backend: {}\n';
const lanOut = assignContainerLAN(lanIn, 'app', 'home-lan', '192.168.1.50');
const lanBack = assignContainerLAN(lanOut, 'app', '', '');
const lan = { input: lanIn, out: parseDocument(lanOut).toJS(), back: parseDocument(lanBack).toJS(), assignment: lanAssignment(lanOut, 'app') };

writeFileSync(process.argv[3], JSON.stringify({ scenarios: out, edits, lan }, null, 1));
console.log('ok', out.length, edits.length);
