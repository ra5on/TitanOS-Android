# TitanOS für Android

Die native Android-App zu [TitanOS](https://github.com/ra5on/TitanOS), dem NAS-Betriebssystem, das vom USB-Stick bootet. Mit ihr hast du dein NAS auf dem Handy: Dateien, Zustand des NAS und nach und nach alles, was die Weboberfläche kann – im selben Stil wie TitanOS.

> **Status: Testfassung 0.01.** Das ist ein frühes Grundgerüst zum Ausprobieren, keine fertige App. Vieles zeigt noch „kommt in einer späteren Testfassung“.

## Was diese Testfassung kann

- **Koppeln** mit dem NAS, ohne dass Passwort oder 2FA aufs Handy kommen:
  - QR-Code scannen, den TitanOS unter Mein Konto › Geräte › „Android-App koppeln“ zeigt;
  - „Auf diesem Handy öffnen“ im Browser des Handys (App-Link `titanos://pair?…`);
  - oder die Adresse von Hand eingeben und den Fingerabdruck des Zertifikats mit dem Browser vergleichen.
  - Bestätigt wird im Browser. Danach meldet sich die App mit einem eigenen Schlüssel im Android Keystore an; das Zertifikat des NAS ist festgehalten (SPKI-Pinning).
- **Start:** Zustand der Volumes, Laufzeit, Speicherbelegung und die Programme, die das Konto sehen darf.
- **Dateien:** Freigaben und Ordner, Teilen, In Downloads speichern, Umbenennen, In den Papierkorb, Hochladen über „+“, eine einfache Liste der Übertragungen.
- Hell und dunkel nach der Einstellung des Handys, Deutsch und Englisch.

Noch nicht dabei: Foto-Sicherung, Mitteilungen, Kontakte und Kalender, Offline-Dateien, Widgets, Selbst-Update und die Agent-App (`:app-agent` ist eine leere Hülle).

Die App braucht ein NAS mit TitanOS, das die Kopplung für Android-Apps kennt (`docs/ANDROID-APP.md` im Repo TitanOS).

## Voraussetzungen

- Android 13 oder neuer.
- Keine Google-Dienste: kein Google Play Services, kein Firebase, kein ML Kit.

## Bauen

Nötig sind JDK 21 und das Android-SDK mit `platforms;android-37.0` und `build-tools;37.0.0`.

```
export JAVA_HOME=/pfad/zu/jdk-21
export ANDROID_HOME=/pfad/zu/android-sdk
./gradlew assembleDebug testDebugUnitTest lint
```

Die Debug-APK liegt danach unter `app-titanos/build/outputs/apk/debug/`.

- **Bildtests:** `./gradlew recordRoborazziDebug` schreibt Bilder aller Bildschirme in 412 dp Breite, hell und dunkel, nach `app-titanos/build/outputs/roborazzi/`.
- **Release:** Signiert wird nur über Umgebungsvariablen: `TITANOS_KEYSTORE` (Pfad zum Keystore) und `TITANOS_KEYSTORE_PASSWORD`, Alias `titanos-release`. Ohne sie bleibt `assembleRelease` unsigniert.
- **Stil:** Farben, Maße, Programmsymbole und die Schrift (IBM Plex) erzeugt `web/scripts/android-theme.mjs` im Repo TitanOS aus der Weboberfläche:
  ```
  node web/scripts/android-theme.mjs ../TitanOS-Android
  ```
  Das Ergebnis unter `core-ui/src/main/kotlin/…/ui/generated/` und `core-ui/src/main/res/font/` ist eingecheckt.

## Aufbau

| Modul | Inhalt |
|---|---|
| `:core-model` | Datentypen der API |
| `:core-net` | OkHttp, Zertifikatsprüfung mit SPKI-Pinning, Geräteschlüssel im Keystore, Kopplung, Sitzung |
| `:core-ui` | TitanOS-Theme hell/dunkel, Bausteine, Symbole, Schrift |
| `:feature-pairing` | Willkommen, QR-Code, Adresse von Hand, Koppeln bestätigen, Berechtigungen |
| `:feature-home` | Start mit Zustandskarte und Programmen |
| `:feature-files` | Freigaben, Ordner, Datei-Aktionen, Übertragungen |
| `:app-titanos` | die App „TitanOS“ (`io.github.ra5on.titanos`) |
| `:app-agent` | die App „Agent“ (`io.github.ra5on.titanos.agent`), noch leer |
| `:lint-rules` | Lint-Regel gegen abgeschnittenen Text |

## Lizenz

Die App ist **Fair Source** unter der [Functional Source License 1.1 mit Apache-2.0-Zukunftslizenz (FSL-1.1-ALv2)](LICENSE.md), wie TitanOS: Nutzen, Ändern und Weitergeben ist erlaubt, nur kein konkurrierendes kommerzielles Angebot. Zwei Jahre nach Erscheinen steht jede Version unter der Apache License 2.0. Bis dahin ist sie nicht „Open Source“.

Fremde Bestandteile behalten ihre Lizenz, siehe [THIRD_PARTY_LICENSES](THIRD_PARTY_LICENSES). Beiträge nur mit dem [CLA](CLA.md), siehe [CONTRIBUTING.md](CONTRIBUTING.md).
