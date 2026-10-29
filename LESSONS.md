## 2026-10-29 build/scaffold — three build-config failures

### Attempt 1: default `pkg install gradle` (9.8.2)
- tried: `gradle :app:assembleDebug` with system Gradle 9.8.2
- why-failed: AGP 8.7.2 plugin not compatible with Gradle 9.x
- learned: Termux `pkg gradle` is always latest; never assume
  compatibility with a pinned AGP. Pin Gradle explicitly.
- fixed-by: manual Gradle distribution download (see Attempt 2)

### Attempt 2: Gradle 8.7 + AGP 8.7.2
- tried: downloaded Gradle 8.7 distribution manually
- why-failed: "Minimum supported Gradle version is 8.9"
  (AGP 8.7.2 requires Gradle >= 8.9)
- learned: check AGP <-> Gradle compatibility matrix before pinning.
  AGP 8.7.x -> Gradle 8.9+. AGP 8.5.x -> Gradle 8.7+.
- fixed-by: bump to Gradle 8.11.1 (this attempt)

### aapt2 on Termux
- tried: default aapt2 from build-tools (x86_64 binary)
- why-failed: exec format error on aarch64 Termux (expected)
- learned: MUST override with `android.aapt2FromMavenOverride`
  pointing at Termux `pkg install android-tools` aapt2
- fixed-by: gradle.properties override
## 2026-10-29 build/scaffold — three build-config failures

### Attempt 1: default `pkg install gradle` (9.8.2)
- tried: `gradle :app:assembleDebug` with system Gradle 9.8.2
- why-failed: AGP 8.7.2 plugin not compatible with Gradle 9.x
- learned: Termux `pkg gradle` is always latest; never assume
  compatibility with a pinned AGP. Pin Gradle explicitly.
- fixed-by: manual Gradle distribution download (see Attempt 2)

### Attempt 2: Gradle 8.7 + AGP 8.7.2
- tried: downloaded Gradle 8.7 distribution manually
- why-failed: "Minimum supported Gradle version is 8.9"
  (AGP 8.7.2 requires Gradle >= 8.9)
- learned: check AGP <-> Gradle compatibility matrix before pinning.
  AGP 8.7.x -> Gradle 8.9+. AGP 8.5.x -> Gradle 8.7+.
- fixed-by: bump to Gradle 8.11.1 (this attempt)

### aapt2 on Termux
- tried: default aapt2 from build-tools (x86_64 binary)
- why-failed: exec format error on aarch64 Termux (expected)
- learned: MUST override with `android.aapt2FromMavenOverride`
  pointing at Termux `pkg install android-tools` aapt2
- fixed-by: gradle.properties override
## 2026-10-29 build-strategy — Termux-native APK build abandoned
- tried: compile APK entirely on Termux (aarch64, android-tools aapt2)
- why-failed: repeated AGP<->Gradle version traps + fragile aapt2
  override + risk of d8/R8 OOM on device; hours spent for zero APK
- learned: never build APK on Termux. Use Termux only for edit +
  git push + `pm install`. Compile on GitHub Actions (ubuntu-latest).
- fixed-by: .github/workflows/build.yml with temurin 21 + setup-android
## 2026-10-29 CI — setup-android v3 fails on removed 'tools' package
- tried: android-actions/setup-android@v3 with default packages
- why-failed: action defaults to `tools platform-tools`; Google removed
  the legacy `tools` package from the SDK repo on 2026-09-17, so
  sdkmanager exits 1 with "Failed to find package 'tools'" before any
  Gradle step runs.
- learned: never rely on setup-android's default `packages`. Pass
  `packages: ''` to skip the removed `tools`, then install
  platform-tools / platforms;android-36 / build-tools explicitly.
  (v4.0.2+ also fixes this upstream, but v3 + packages:'' is the
  minimal-change fix and is what multiple OSS repos use.)
- fixed-by: workflow patch in this commit
## 2026-10-29 CI — no Release, no README auto-update
- tried: workflow only uploaded APK as Actions artifact
- why-failed: artifacts are CI-debug surface, not user-facing.
  Users expect tap-to-install from a Releases page. README did not
  exist and had no auto-maintained "latest build" block.
- learned: for a sideloaded app, Releases is THE download surface.
  Actions artifacts are for CI debugging. Every successful `main`
  push must (a) create a tagged Release with the APK attached,
  (b) update README's `<!-- LATEST-BUILD -->` block to point at
  `releases/latest`. GITHUB_TOKEN pushes do not re-trigger
  workflows, so no infinite loop.
- fixed-by: softprops/action-gh-release@v2 in `apk` job +
  `update-readme` job that rewrites README between markers

## 2026-10-29 tooling — SIGPIPE exit 141 from tr </dev/urandom | head
- tried: STOREPASS=$(tr -dc 'A-Za-z0-9' </dev/urandom | head -c 24)
- why-failed: /dev/urandom never EOFs, tr keeps reading, head -c 24
  closes the pipe after 24 bytes, tr receives SIGPIPE. Under
  `set -euo pipefail`, the pipeline exits 141 and aborts the script.
- learned: same class of bug as `grep | head -1` in CI. Never terminate
  an infinite producer with head/cut on the reading side. Either produce
  finite output (python3 -c secrets.choice, openssl rand) or use a
  reader that consumes to EOF (tr -d, cut -c). In workflow, prefer
  `grep -m1` over `grep | head -1`.
- fixed-by: gen_pass() using python3 -c secrets.choice; workflow uses
  grep -m1 (max-1 match) instead of grep | head -1

## 2026-10-29 compose — Icons.Filled.Pause not in material-icons-core
- tried: import Icons.Filled.Pause in NowPlayingBar
- why-failed: material-icons-core ships a small subset (PlayArrow, Menu,
  Search, etc). Pause / SkipNext / SkipPrevious / Shuffle are only in
  material-icons-extended (~50 MB). Unresolved reference: 'Pause'.
- learned: for P1 prefer TextButton("Play"/"Pause") instead of pulling in
  material-icons-extended. If a future UI genuinely needs the extended set,
  add it once and keep it — never mix.
- fixed-by: NowPlayingBar uses TextButton with "Play"/"Pause" labels
