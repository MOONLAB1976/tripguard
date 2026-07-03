# TripGuard

Base Android app for reading Uber and Bolt offer screens through Accessibility and applying your own accept or reject rules.

## First baseline

- Reads visible screen text from the active app.
- Tries to parse:
  - source app (`Uber`, `Bolt`)
  - fare in EUR
  - pickup duration in minutes
  - pickup distance in km
  - trip duration in minutes
  - trip distance in km
  - pickup and destination hints
  - pickup and destination postal prefixes
  - estimated `EUR/km` and `EUR/h`
- Applies starter rules:
  - reject blocked Gaia-related postal prefixes such as `4400`, `4410`, `4420`, `4430`, `4435`, `4440`
  - reject blocked zone keywords such as `Vila Nova de Gaia`
  - reject fare below `6.50 EUR`
  - reject pickup above `4.0 km`
  - reject long trips above `25 km`
  - reject yield below `1.10 EUR/km`
  - reject hourly return below `18.00 EUR/h`
- Shows an always-visible floating override with `Aceitar` and `Recusar`.
- Attempts to press matching accept or reject buttons on screen through Accessibility.
- Stores the last `10` detected offers locally.
- Suggests blocked postal prefixes and local advice based on recent offer quality.

## Current limits

- The floating buttons already try to tap the matching on-screen buttons, but this still needs tuning against real Uber and Bolt layouts.
- Parsing is generic and still needs tuning against the real Uber or Bolt offer screens.
- The history analysis is heuristic for now, not yet trained on your true profit outcomes.
- Address parsing still needs tuning against your real offer screens.

## Why this structure

This base follows the useful parts we found in the other apps:

- `PEGGA`: native Android structure, real-time screen reading, local proposal history, quick access thinking
- `Mystro`: filter profiles, zone-based accept or reject logic, trip history concepts, reservations and app-state automation

## Current UI direction

The home screen is now being shaped around:

- live offer summary
- manual accept and reject override status
- recent 10-offer analysis with `EUR/km` and `EUR/h`
- AI-style advice generated locally from the last 10 offers
- menu preview for `Inicio`, `Ofertas`, `Insights`, `Zonas`, `Conselho IA`, `Definicoes`

## Next engineering step

The next useful milestone is to capture 3 to 5 real offer screens from your phone and tune:

1. pickup line detection
2. destination line detection
3. exact Uber and Bolt text patterns
4. Gaia block rules by postal prefix and area name

## Next suggested steps

1. Build and install the app on your phone.
2. Enable the Accessibility service.
3. Capture real offer text from Uber or Bolt.
4. Refine the parser for the exact layout you see on screen.
5. Add trip history and zone scoring.

## GitHub updates

The app now supports checking a remote `tripguard-update.json` file and downloading a newer APK from GitHub.

Useful files:

- `tripguard-update.json`
- `prepare-github-release.ps1`
- `GITHUB_UPDATE_SETUP.md`
