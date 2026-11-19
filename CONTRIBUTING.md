# Contributing to TitanOS for Android

Thanks for your interest in the TitanOS Android apps! Issues, ideas and pull requests are welcome. Issues may be written in English or German.

## License

The apps are licensed under the **Functional Source License 1.1 with Apache 2.0 Future License (FSL-1.1-ALv2)**, see [LICENSE.md](LICENSE.md), the same as TitanOS.

In short:

- You may use, modify and share the apps for any purpose **except a competing use**, i.e. offering them (or something substantially based on them) as a commercial product or service.
- Personal use, use inside a company, education and research are permitted.
- Two years after each version is released, that version automatically becomes available under the **Apache License 2.0**.

The apps are therefore *Fair Source*. Please do not describe them as "open source" until a version has converted.

Third-party components (OkHttp, AndroidX, ZXing, IBM Plex, …) keep their own licenses. See `THIRD_PARTY_LICENSES`. Components under the GPL cannot be used, because they would put the whole app under the GPL.

## Contributor License Agreement (CLA)

Before an external contribution can be merged, its author must sign the
[TitanOS CLA](CLA.md) (version 1.0) – the same agreement as for
[TitanOS](https://github.com/ra5on/TitanOS/blob/main/CLA.md). When you open
your first pull request, the [CLA Assistant](https://cla-assistant.io/) posts
a comment with a link: sign in with GitHub and accept the agreement once; the
check "license/cla" on your pull requests then passes. If you already signed
it for TitanOS, nothing else is needed. Bug reports and discussion do not
require a CLA.

Why: the CLA lets the maintainer distribute your contribution under the FSL today, under the Apache License 2.0 when a version converts, and under separate licenses if needed. You keep the copyright to your contribution.

## Reporting security issues

Please **do not** open a public issue for security vulnerabilities. Use GitHub's private vulnerability reporting ("Security" tab → "Report a vulnerability") instead.

## How to contribute

1. Open an issue first for larger changes, so we can agree on the approach before you invest time.
2. Fork the repository and create a branch from `main`.
3. Keep changes focused: one topic per pull request.
4. Run the checks before opening a pull request:
   ```
   ./gradlew assembleDebug testDebugUnitTest lint
   ```
5. Describe what you changed and how you tested it. For UI changes, add a screenshot (`./gradlew recordRoborazziDebug`).

## Ground rules

- Text is never cut off: no `TextOverflow.Ellipsis`, no `maxLines`; let it wrap or scroll. Lint enforces this (`TitanNoTruncatedText`).
- A state is never shown by color alone; always add text or a distinct symbol.
- No hardcoded colors in screens; use the theme (`Titan.colors`). Colors, sizes and program symbols are generated from the TitanOS web UI by `web/scripts/android-theme.mjs` in the TitanOS repository – change them there, not in `core-ui/…/generated`.
- User-facing text goes into `strings.xml`, German (`values/`) and English (`values-en/`).
- Use the NAS's existing API routes, the same ones the web UI uses.
- No Google Play Services, Firebase or ML Kit.
