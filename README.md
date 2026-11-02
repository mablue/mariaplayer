# MariaPlayer

Offline Persian-first Android music player for libraries with broken tags.

Built on: Kotlin · Jetpack Compose · Material 3 · Media3 (ExoPlayer + Session) · Room · Hilt · WorkManager

## Download

Grab the latest APK from [**Releases**](https://github.com/mablue/mariaplayer/releases/latest).

<!-- LATEST-BUILD:start -->
| Field | Value |
|---|---|
| Release | [v0.1.0-build.90-1fe9238](https://github.com/mablue/mariaplayer/releases/tag/v0.1.0-build.90-1fe9238) |
| Version | 0.1.0 |
| Commit | 1fe9238 |
| APK | [](https://github.com/mablue/mariaplayer/releases/latest/download/) |
| Size | 97757350 bytes |
| SHA-256 | f62fdbf4a168c8218b02e61dd47f8e04e226c5997d9c0fd941cb6c2a67beaf5e |
<!-- LATEST-BUILD:end -->

## Install

1. Tap the APK from the release page (or the direct link above).
2. Allow "Install from unknown sources" if prompted.
3. Open **MariaPlayer**.

## Status

Pre-alpha. P1 (play) in progress.

- [ ] **P1 Play**: scan → tap → play → notification → audio-focus → resume
- [ ] **P2 Find**: search, sort, filter, folders, playlists, favorites, queue
- [ ] **P3 Clean**: tag cleaner, Persian lyrics, backup/restore
- [ ] **P4 Optional**: EQ, cast, TTS, on-device models

## Why

My library is 592 tracks from 15+ years of loose downloads. Only 17% have clean filenames. The rest look like:

- `2020_MohammadReza-Shajarian.Dele-Sheyda(128).mp3`
- `Muslum Ggurses - 10 - 128.mp3`
- `1768850817146_120x120.mp3`
- `9zoA4EZMI34vvJQ333Hp+PD8PWAdzuys.m4a`

Every other player pretends this is fine. MariaPlayer is built for it.

## Build

Signing details: SIGNING.md


CI builds every push to `main` on GitHub Actions → Ubuntu + JDK 21 + SDK 36.
Successful builds publish a tagged **Release** with the APK attached, and the
block at the top of this README is auto-updated by CI.

Local APK build is **not** supported on Termux; see [`LESSONS.md`](./LESSONS.md) for why.

## Documents

This repo is documents-first. Read in order:

- `PROMPT.md` — mission, phases, work-loop, constraints
- `KNOWLEDGE.md` — architecture, gotchas, per-feature specs
- `LESSONS.md` — append-only record of failures and fixes
- `M0_ANALYSIS.md` — pre-flight data (library scan, competitor check)
