# My Rings for iOS

A port of the Android app (`../android`), which itself ports the reverse-engineered protocol in `D:\open_oura`. Same rule as everywhere in this repo:
check open_oura first, port faithfully, label estimates.

## Status

| Phase | What | State |
|---|---|---|
| 1 | `OuraKit`: protocol framing, requests, AES auth, event decoders, history batches | Written, **not yet compiled** (no Mac here). CI runs `swift test` on a macOS runner. |
| 2 | Analysis: clock, sleep stager, scores, drift, workouts, steps and resilience estimates | Not started |
| 3 | Storage (SQLite) and the Core Bluetooth ring client, sync, background sync | Not started |
| 4 | SwiftUI screens, charts, widget (WidgetKit), notifications | Not started |

## Building

iOS apps compile only on macOS with Xcode. Until a Mac is available, `.github/workflows/ios-kit.yml` builds and tests `OuraKit` on a GitHub macOS runner.
Running on a phone needs a Mac with Xcode, or a Windows sideloading tool once CI produces an app, plus an iPhone. The simulator has no Bluetooth,
so the ring itself can only be tested on a real device.

## Tests

`ios/OuraKit/Tests` reuses the Android vectors: the protocol cases from open_oura's Rust tests and `event_fixtures.tsv` (real Ring 4 bodies
decoded by the Rust reference). Both ports must agree with the reference.
