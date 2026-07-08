# TripGuard App Trust and Distribution

This document tracks what is ready, what is risky, and what must be true before broader distribution.

## Current app trust baseline

- App version is visible in the app under `Definicoes`.
- Accessibility and overlay status are visible on the home screen.
- Diagnostics can be shared from the phone with `Enviar relatorio para o PC`.
- Diagnostic reports use Android `FileProvider` and cache storage, so no storage permission is needed.
- Android backup is disabled because local trip history can contain sensitive operational patterns.
- Reports avoid raw screen text and full addresses by default.

## Update and download path

Current channel:

- local/manual APK install for private validation
- version shown as `versionName` and `versionCode`
- diagnostic report includes the installed version

Before public or wider testing:

- publish through Google Play internal testing or closed testing
- keep `versionCode` increasing for every distributed build
- maintain a small release note for every build
- verify install, update and uninstall behavior on a clean phone
- keep a rollback APK for private testing builds

## Play Store requirements and risks

High attention areas:

- Accessibility Service: must be disclosed clearly as reading Uber/Bolt offer screens to assist driver decisions.
- Overlay permission: must be disclosed as a visible manual control surface.
- Automation: Play review risk is higher if the app appears to secretly automate another app. Keep user-visible controls and explain the purpose.
- Data safety: declare local trip metrics, diagnostics and any future network upload honestly.
- Background behavior: avoid unnecessary background work beyond accessibility events.
- Screenshots/listing: show the app as a driver decision assistant, not a hidden modifier of Uber/Bolt.

Current implementation risks:

- Parser still needs real Uber/Bolt screen samples.
- Accessibility button matching needs more on-device validation.
- There is no authenticated server update channel yet.
- No production signing plan is documented in the repo.
- No automated tests yet for parser, filters, report redaction or version display.
- Release minification/resource shrinking is disabled for the first stable validation build; re-enable it only after a signed release build is tested on-device.

## Diagnostics sent to PC

The in-app report includes:

- app version and package
- phone model and Android version
- accessibility and overlay status
- last offer summary and decision
- recent offer metrics with postal prefixes only
- privacy note and next release checks

It intentionally excludes:

- raw accessibility tree text
- full pickup or destination addresses
- tokens, API keys or credentials

## Future AI/API integration

Keep AI advice as opt-in.

Recommended constraints:

- default to local advice from the last 10 offers
- require explicit user consent before sending diagnostics or trip data to an API
- redact full addresses unless the user deliberately includes them
- send only structured metrics where possible: fare, km, minutes, decision, postal prefix
- put endpoint and API key outside source control
- show when advice is local versus remote
- keep a fail-closed path: if API fails, the app should continue with local rules

## Release checklist

- Build debug APK for phone validation.
- Build release APK/AAB with production signing.
- Confirm version appears correctly in the app and diagnostic report.
- Enable accessibility and overlay on a clean phone.
- Capture at least 10 real offers across Uber and Bolt.
- Export a diagnostic report to PC and confirm no raw address/screen text is present.
- Tune parser and button matching from real captures.
- Add store disclosure text and privacy policy before Play Store submission.
