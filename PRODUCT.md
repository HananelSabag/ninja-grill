# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users
Owners of a Ninja Woodfire Pro Connect XL (OG901), first in Israel (where the official app isn't on Google Play), standing next to the grill outdoors (often in sun, sometimes at night) or checking from inside the house. Hebrew speakers. They cook mostly by probe temperature and doneness, not by recipe. Anyone with the same grill should be able to pick it up without explanation.

## Product Purpose
One app to watch and run the grill: live probe and grill readings, start/stop/change a cook, and doneness guidance (pull temperature, carryover). Success: during a cook you open the app and know in one glance how close the meat is, and any action is one tap away.

## Positioning
Talks to the grill over local Bluetooth and the SharkNinja cloud, each working alone or together; merges the grill's own probes with third-party wireless probes (Tempwise) into one view; no recipes, no wizards. Works alongside the official app.

## Operating Context
Used beside a hot grill with one free hand, at arm's length, outdoors in daylight or at dusk; also glanced at from indoors over the cloud. Commands drive a heating element.

## Capabilities and Constraints
- Monitoring: BLE advertisement beacon (phase, lid, probes, grill sensor), AWS cloud telemetry, GATT state replies.
- Control: 7 modes with the vendor's own ranges, Woodfire smoke, skip preheat, probe targets, stop, live changes. Start needs a confirmation; stop never does.
- External probes: Tempwise TNT-2BW two-probe base, read only (shared GATT link, one reading a second).
- Hebrew, right-to-left. Android only (Kotlin + Jetpack Compose).
- No recipes or cooking charts (explicit). Presets and history are deferred.

## Brand Commitments
- App name: "Ninja Grill". Unofficial, not affiliated with SharkNinja; no vendor logos or product photos in the published app.
- Colour anchor: an open sky (day and night), with the grill's slate blue hood in front of it.
- Both light and dark themes, chosen in settings.
- Inspiration: the official Ninja Pro Connect app minus its step-by-step wizards and repeated "did you add a thermometer?" prompts; leading thermometer and cooking apps.

## Evidence on Hand
- Real captures from an OG901 (beacon, GATT, cloud) under app/src/test/resources, with the device's identity zeroed.

## Product Principles
1. One screen, one glance: the probe reading leads, everything else supports it.
2. Never ask twice: no wizards, no confirmations except before turning heat on.
3. Tell the truth about the grill: show off/offline/stale plainly, never a guessed state.
4. Each channel stands alone: Bluetooth works without an account, the cloud without range.

## Accessibility & Inclusion
Readable in direct sun: large numerals, high contrast in both themes, 48dp+ targets for use with greasy or gloved hands.
