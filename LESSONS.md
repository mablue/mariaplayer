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

## 2026-10-30 ux — delete flow had 3 stacked confirmations
- tried: DeleteActivity AlertDialog + MediaStore.createDeleteRequest + notification fallback
- why-failed: from library: 2 dialogs. from notification: 4 user actions
  (tap delete in media notif -> heads-up notif -> pull down -> tap Delete ->
   activity dialog -> system dialog -> Allow). Unusable.
  Also list stayed stale after delete (produceState(tracks) never re-ran)
  and player queue kept the deleted MediaItem.
- learned: system createDeleteRequest dialog IS already a confirmation.
  Any custom AlertDialog before it is redundant and hostile.
  Fix: kill DeleteActivity's UI (Theme.Translucent.NoTitleBar), go straight
  to launcher. From MainActivity use launcher directly (no DeleteActivity hop).
  For rescan: broadcast ACTION_TRACK_DELETED from delete success; MainActivity
  receiver bumps reloadKey -> produceState(tracks) re-runs.
  For queue: removeMediaItem on matching URIs; ExoPlayer auto-advances on
  current-item removal. Never leave dangling MediaItems pointing at deleted URIs.
- fixed-by: this commit

## 2026-10-30 audit — spec compliance gaps found after device confirm
- tried: ship P2/P3 as done without running the SPD.MUSIC_PLAYER audit
- why-failed: three code-level violations:
  Omega-14: delete re-scanned ALL tracks via produceState(reloadKey)
  Omega-16: tryDirectDelete + removeMediaItem had empty catch { }
  Gamma-14: PlaybackPrefs ticker was 3000ms -> up to 3s drift on kill
  Lambda-28: no typed PlaybackError -> generic or silent playback failures
- learned: run the audit BEFORE declaring next phase done. Gamma-gates are
  cheap to check; violating them after user says "works" wastes user trust.
  Fixes: incremental list removal on delete broadcast; 500ms ticker + save
  on onIsPlayingChanged + READY/ENDED; typed PlaybackException with toast +
  auto-skip; no catch(_: Exception) {} anywhere that mutates state or files.
- fixed-by: this commit

## 2026-10-30 lyrics-v1 (Λ79/Ω04 override)
- tried: deferring lyrics to v2 per Λ79+Ω04
- user-directed override: "نمیخوام تگ گلینر داشته باشم... الگوریتم اینا رو با استراتژی های جستجوی لیریک ادغام کن"
- reason: user prioritizes lyrics over tag-cleaner; algorithm (S10.1) reused as query normalizer only — no MediaStore writes
- resulting: PR-L1 ships QueryNormalizer.kt (pure Kotlin) — used by upcoming LyricsFetcher in PR-L2

## 2026-10-30 PR-P1b1..d wiring never landed
- tried: python s.replace() with assumed anchor strings
- why-failed: anchor for LibraryScreen call assumed onLyricsClick BEFORE onDeleteRequest; actual order was reverse (PR-L3b added onLyricsClick first). SystemExit fired BEFORE p.write_text -> MainActivity unchanged. get-apk.sh green because untouched file compiles fine.
- learned: (1) verify every anchor with grep -n BEFORE replacing; (2) python patch must print OK per step AND abort with real context on failure; (3) run grep -c verify AFTER commit; (4) get-apk.sh SUCCESS does NOT mean the feature works.
- fixed-by: PR-P1-fix-2 — corrected anchors (onDeleteRequest included), verified counts, rewrote both screens.

## 2026-10-30 vectors break with attr/colorControlNormal
- tried: new vector drawables with android:tint="?attr/colorControlNormal"
- why-failed: app theme is @android:style/Theme.Material.NoActionBar (platform), not AppCompat. Platform provides ?android:attr/colorControlNormal, NOT ?attr/colorControlNormal. AAPT link fails.
- existing vectors (ic_sort, ic_shuffle, etc.) have no tint attribute — they rely on Compose Icon()'s tint param / LocalContentColor.
- learned: NEVER add android:tint to vector XML in this project. Compose Icon() handles tint. If a raw ImageView needs tint, use android:tint="@android:color/white" or set at runtime.
- fixed-by: sed removed tint from ic_add, ic_arrow_up, ic_arrow_down, ic_lyrics, ic_playlist.

## 2026-10-30 sed on XML attribute destroys tag close
- tried: sed -i '/android:tint=/d' to remove tint from vectors
- why-failed: the tint attribute shared a line with `>`, so sed deleted the closing `>` of <vector>. XML became <vector ... attrs <path> with no close.
- learned: (1) NEVER sed-delete lines from XML; rewrite the file with cat >. (2) always validate XML with `python3 -c "import xml.etree.ElementTree as ET; ET.parse('file.xml')"` BEFORE commit.
- fixed-by: rewrote 5 vectors without tint, validated with ElementTree.

## 2026-10-30 combinedClickable needs ExperimentalFoundationApi opt-in
- tried: Modifier.combinedClickable in LibraryScreen
- why-failed: foundation API is @RequiresOptIn(level=ERROR). Without @OptIn the compiler treats it as error.
- learned: any use of combinedClickable / detectDragGesturesAfterLongPress etc. requires @OptIn(ExperimentalFoundationApi::class) on the enclosing composable.
- fixed-by: added to LibraryScreen @OptIn alongside ExperimentalMaterial3Api.

## 2026-10-30 QueryNormalizer missing 4 regexes that Python build had
- tried: port S10.1 to Kotlin by copying only RX chain
- why-failed: Python clean_v2 applied VERSION_V2_RE and BITRATE_BARE_RE separately, outside RX. Kotlin port omitted both. Also Persian site list in RX was incomplete (had موزیکدل but not آهنگدونی/موزیکفا/آپموزیک/نواک — those existed in Python as a separate PERSIAN_SITES list that was also dropped).
- tests urlHttp was written wrong: input was a pure URL, all consumed → title="" correctly, but test expected title="Song". Fixed test to have a bare title alongside URL.
- learned: (1) when porting multi-regex pipelines, diff the sets of Regex patterns between source and target; (2) write tests FIRST against the Python implementation then port — don't port then write tests; (3) an all-URL title is correctly empty, not the trailing path segment.
- fixed-by: added VERSION_V2_RE, BITRATE_BARE_RE; extended RX site list; corrected urlHttp test.
