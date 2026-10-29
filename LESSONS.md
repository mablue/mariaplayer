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
