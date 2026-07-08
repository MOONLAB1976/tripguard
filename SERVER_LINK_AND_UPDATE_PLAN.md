# TripGuard server link and update plan

This document describes the independent architecture for:

- app to computer/server sync
- server-side advice and publishing
- app update delivery
- remote configuration delivery for drivers

## Goal

The system should work even if:

- Google Drive is not available
- Play Store is not available yet
- the user's PC is not always online
- different drivers use different phones

That means we separate the architecture into four independent layers.

## Layer 1: App data capture

The Android app remains responsible for:

- reading offer data locally
- applying local accept/reject logic
- storing recent history on-device
- generating a redacted diagnostic report

The app must always keep a local-first mode.

If the server is down, the driver should still be able to:

- see the current status
- export diagnostics
- use local rules
- receive no remote advice without breaking the app

## Layer 2: Independent sync to the computer/server

The safest independent sync path is:

1. app produces a structured report
2. app can share or upload it
3. PC/server receives it in an inbox
4. PC/server normalizes it
5. PC/server publishes derived outputs for analysis and the website

### Recommended transport priority

1. local HTTP upload to the PC mini-server on the same Wi-Fi
2. fallback share to Google Drive, email, or USB
3. optional future cloud API when needed

### Why this is the right shape

- It does not depend on a single provider.
- It works in local private deployments.
- It can later be moved from one PC to another server with minimal change.
- The app only needs one concept: send report.

## Layer 3: Independent server

The independent server should be treated as a small ingestion and publishing service.

### Minimum responsibilities

- receive uploads
- store raw files
- index by device and reference
- generate summaries
- publish JSON for the website
- expose update/config endpoints for the app

### Deployment options

The same logic should support these three modes:

1. local laptop/desktop server
2. small VPS server
3. NAS or mini-PC always on

### Recommendation

Keep the current file-based structure and put a thin HTTP API in front of it.

That gives us:

- low operational complexity
- easy backups
- easy migration
- no database dependency on day one

### OpenAI key location

The OpenAI key must live only on the PC/server.

It must not be:

- hardcoded in Kotlin
- shipped inside the APK
- committed to the repo
- stored in public website JSON files

Recommended local file:

- `F:\Claude\com.mystrodriver\sync\tripguard_server.env`

Example template:

- `sync/tripguard_server.env.example`

The server reads:

- `OPENAI_API_KEY`
- `TRIPGUARD_OPENAI_MODEL`
- `TRIPGUARD_UPLOAD_TOKEN`
- `TRIPGUARD_ADVICE_SYSTEM_PROMPT`

## Layer 4: Update and remote configuration channel

This must be split into two independent channels.

### A. App binary updates

Purpose:

- install newer APK versions
- tell drivers when a new version exists

Current good path:

- static JSON manifest
- APK hosted in GitHub Releases or another file host

Example:

- `tripguard-update.json`
- `versionCode`
- `versionName`
- `apkUrl`
- `notes`

The app checks:

1. current installed version
2. remote update manifest
3. whether a newer version exists
4. shows the driver a clear update action

This is already a good independent pattern because:

- the manifest can live on any host
- the APK can live on any host
- the app does not care whether the backend is GitHub, a VPS, or something else later

### B. Driver rules and remote configuration

Purpose:

- change filters without reinstalling the app
- alter thresholds for drivers
- enable or disable features
- attach advice windows by reference or device

This should not use the same file as binary updates.

Recommended second manifest:

- `tripguard-config.json`

Suggested fields:

- schema version
- generated time
- target scope: global, by reference, by device
- minimum fare
- minimum EUR/km
- minimum EUR/hour
- maximum pickup km
- blocked postal prefixes
- blocked zone keywords
- remote advice enabled
- advice endpoint URL
- polling interval
- message to show in the app

## Best architecture for independence

Use three public-facing artifacts, each with a separate role:

1. `tripguard-update.json`
2. `tripguard-config.json`
3. `tripguard-sync-results.json`

That separation matters because:

- updates change binaries
- config changes behavior
- sync results feed dashboards and analysis

They should not be merged into one document.

## How the app should talk to the server

The app should have two network behaviors.

### Push path

Used for:

- sending diagnostics
- sending summarized recent offers
- optionally sending a redacted advice payload

Preferred endpoint shape:

- `POST /api/upload`
- future: `POST /api/reports`
- `POST /api/advice/request`

### Pull path

Used for:

- checking updates
- checking config
- fetching latest advice prepared for the device/reference

Preferred endpoint shape:

- `GET /tripguard-update.json`
- `GET /tripguard-config.json`
- future: `GET /api/advice/latest?device_id=...&reference=...`

## Driver-specific change flow

When you asked "enviar para a app para os motoristas alterar", the clean way is:

1. the PC/server receives fresh reports
2. it computes advice and updated recommended thresholds
3. it writes those recommendations into `tripguard-config.json`
4. the app fetches that config periodically
5. the app shows suggested changes
6. the driver can apply them manually or opt into auto-apply later

This is much safer than forcing silent rule changes.

### Recommended first version

Do not auto-apply remote rules immediately.

Instead:

- download remote suggestions
- show "Sugestoes do servidor"
- let the driver accept them

That gives us:

- auditability
- trust
- lower support risk
- easier Play Store positioning

## Security model

For this system, independence is not enough. We also need predictable trust boundaries.

### For uploads

Use a simple device token or signed upload token per driver/reference.

Minimum good version:

- a token stored in app config
- sent in header like `X-TripGuard-Token`
- server validates before accepting upload

### For update/config download

Use HTTPS whenever the endpoint is not purely local LAN.

### For stored data

Store:

- device id
- reference
- metrics
- decisions
- postal prefixes

Avoid storing by default:

- raw screen text
- full addresses
- credentials
- personal identity fields

## Recommended roadmap

### Phase 1: local independent baseline

- app exports redacted reports
- PC mini-server receives uploads
- PC publishes `tripguard-sync-results.json`
- app checks `tripguard-update.json`

This is already mostly in place.

### Phase 2: remote config

- create `tripguard-config.json`
- app fetches it on startup and periodically
- app shows suggestions by reference/device

### Phase 3: remote advice every 3 hours

- app sends a redacted advice payload
- server computes or forwards to API
- app receives a summarized recommendation

### Phase 4: always-on server

- move the same file and API structure from local PC to VPS or mini-PC
- keep the app protocol unchanged

## What I recommend we do next

The most stable path is:

1. keep the sync/upload path file-based and independent
2. add `tripguard-config.json` as the remote rules channel
3. make the app fetch config separately from update JSON
4. only after that, turn on periodic API advice

That order keeps the system understandable and recoverable.

## Current status in this repo

Already prepared:

- upload to local PC mini-server
- indexing by reference and device
- site publication for trip insights
- update manifest pattern
- periodic API advice plumbing in the app

Still missing for the clean final architecture:

- app UI to edit `Sync-Reference`
- dedicated `tripguard-config.json`
- token validation on uploads
- app UI to review and apply remote suggestions
- a production hosting location for manifests

## Current secure setup in this repo

The local mini-server now supports:

- `POST /api/upload`
- `POST /api/advice/request`
- `GET /api/advice/status`

The `Start-TripGuardMiniServer.ps1` script loads local environment variables from:

- `sync\tripguard_server.env`

That means the app can call the server for advice, and the server can call OpenAI without exposing the secret to drivers.
