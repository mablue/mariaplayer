# MariaPlayer

**Offline, Persian-first Android music player built for messy libraries.**

MariaPlayer is a lightweight Material-You music player for Android 8+ with first-class support for Persian audio, broken or missing ID3 tags, synced lyrics, and online translation of lyrics into Persian or English.

[فارسی ↓](#فارسی)

---

## Table of Contents

- [Features](#-features)
- [Tech Stack](#-tech-stack)
- [Download & Install](#-download--install)
- [Architecture](#-architecture)
- [Lyrics & Translation](#-lyrics--translation)
- [Self-Test](#-self-test)
- [Building from Source](#-building-from-source)
- [Author & Support](#-author--support)
- [License](#-license)

---

## ✨ Features

### 🎵 Playback
- ExoPlayer 1.5 (Media3) with MediaSession integration
- Full notification + lock-screen controls
- Seekbar with drag, rewind 15 s / forward 30 s
- Per-track resume — every song remembers its last position, even across restarts
- Auto-skip on corrupted files with a safe 3-error pause guard
- Shuffle + repeat modes

### 📚 Library
- Scans Music folder and any location indexed by MediaStore
- Alphabet index with grab-to-jump, auto-scrolls back to playing track after 15 s idle
- Multi-select for bulk delete / bulk share / bulk add-to-playlist
- Sort by Title, Artist, Album, Recent, Duration
- Folder filter, favorites filter, and search across title/artist/album
- Album art in list rows, full-screen player, and blurred lyrics backdrop
- Dark + light Material 3 (standard, no hardcoded brand colors)

### 📝 Lyrics
- Auto-fetch from LRCLIB, Genius, lyrics.ovh
- Synced LRC parsing with active-line highlight and manual offset
- Translation into Persian / English via a 3-tier chain with automatic failover:
  MS Edge → Google → MyMemory
- Manual paste for songs with no online lyrics
- Per-track translation cache
- 12-color lyrics palette

### 🎧 Podcasts & Long-form
- Rewind / forward buttons tuned for podcast listening
- Resume works equally well for audiobooks and 2-hour episodes

### 🧪 Quality
- 39 automated self-tests covering data, lyrics, translation, playback SSOT, and fonts
- SelfTest includes an SSOT drift detector that exposes duplicate listeners, missed saves, or leaked pollers
- CI builds on every push → APK published automatically to GitHub Releases

---

## 🛠 Tech Stack

| Layer | Choice |
|---|---|
| Language | Kotlin 2.x |
| UI | Jetpack Compose + Material 3 |
| Playback | AndroidX Media3 (ExoPlayer + MediaSession) |
| Persistence | DataStore Preferences (no Room, no Hilt, no WorkManager) |
| Images | Custom LruCache + disk cache (no Coil) |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 36 (Android 16) |
| ABI | arm64-v8a only (~2.4 MB APK) |

Zero third-party SDKs. No analytics, no ads, no trackers, no cloud sync.

---

## 📥 Download & Install

### Latest APK

Every push to `main` produces a signed release APK:

👉 **[Download the latest release](https://github.com/mablue/mariaplayer/releases/latest)**

### Install

1. Download the `.apk` from the release page.
2. Allow "Install from unknown sources" if prompted.
3. Open MariaPlayer → grant Music & Audio permission.
4. The library scans automatically on first launch.

### Supported formats

MP3 · M4A / AAC · FLAC · OGG · OPUS · WAV — whatever ExoPlayer decodes.

---

## 🏗 Architecture

MariaPlayer follows a single-source-of-truth (SSOT) model. There is exactly one place that owns playback state:

    ┌─────────────────────────────────────────────┐
    │  PlaybackRepository                         │
    │  • 1 Player.Listener                        │
    │  • 1 position poller (500 ms)               │
    │  • StateFlow<PlaybackSnapshot>              │
    │  • all PlaybackPrefs writes                 │
    └───────────────────┬─────────────────────────┘
                        │ collectAsState
      ┌─────────────┬───┴────┬──────────────┐
      ▼             ▼        ▼              ▼
    NowPlaying   NowPlaying  Lyrics     MainActivity
       Bar        Screen     Screen      (Library)

No screen ever registers its own `Player.Listener`. All playback state flows from a single repository, which eliminates the classic "two UIs disagree about whether the song is playing" bug and makes the whole app debuggable.

### Package layout

    ir.mariaplayer
    ├── MainActivity.kt        — LibraryScreen, AppRoot
    ├── DeleteActivity.kt      — MediaStore delete confirmation
    ├── data/                  — Track, Scanner, Prefs (DataStore)
    ├── playback/              — PlaybackService, PlaybackRepository,
    │                            NowPlayingBar, NowPlayingScreen
    ├── lyrics/                — Fetcher, LrcParser, Scorer, Cache
    ├── translate/             — OnlineTranslator, TranslationProbe
    ├── art/                   — AlbumArtCache
    ├── ui/                    — Composables, Theme, Type, SelfTest
    └── util/                  — PlaybackDiagnostics, Sanitize

### Data stores

| Store | Contents |
|---|---|
| playback | last track + per-track position map (500 entries, LRU) |
| favorites | track IDs marked as favorites |
| library | sort mode |
| playlists | JSON-encoded playlists |
| lyrics_cache | synced + plain lyrics, offsets, translations |

---

## 🌐 Lyrics & Translation

### Lyrics sources (queried in parallel, auto-scored)

1. LRCLIB — /api/get + /api/search
2. Genius — HTML scrape with strict filtering
3. lyrics.ovh — plain-text fallback

Results are ranked by a fuzzy scorer (title + artist + duration) and presented to the user — no silent auto-picking.

### Translation chain (tries each tier in order)

1. MS Edge / Azure Translator — fast, no key
2. Google clients5 — fast, no key
3. MyMemory — slow, 500-byte/request, works everywhere

Each tier detects source language by script (Persian vs Latin) and falls back automatically.

---

## 🧪 Self-Test

Open the app → toolbar → Self-Test icon. 39 checks run in 2–3 seconds:

- Audio permission, library scan
- Favorites, sort, resume-state round-trips
- Playlist CRUD
- Lyrics cache, LRC parser, LRC activeIndex, 60 s guard
- Lyrics scorer, empty/scorer boundary, Persian RTL
- Query normalizer, delete API level
- Translation probe (Edge + Google + MyMemory)
- Theme defaults, Mikhak font loading + glyph coverage
- SSOT drift snapshot, counter integrity, listener balance

If any test fails the app is considered broken.

---

## 🔨 Building from Source

### Requirements

- JDK 21
- Android SDK 36 (build-tools 36.0.0)
- Gradle 8.11 (wrapper included)

### Build

    git clone https://github.com/mablue/mariaplayer.git
    cd mariaplayer
    ./gradlew :app:assembleRelease

APK appears at app/build/outputs/apk/release/app-release.apk.

### Signing

Provide signing config via environment variables:

    export KEYSTORE_PATH=/path/to/keystore.jks
    export KEYSTORE_PASS=...
    export KEY_ALIAS=mariaplayer
    export KEY_PASS=...

### CI

Every push to main triggers .github/workflows/build.yml, which:
- caches Android SDK + Gradle
- runs unit tests
- builds a signed release APK
- publishes it as a GitHub Release

---

## 👤 Author & Support

**مسعود عزیزی — Masoud Azizi**
Computer Science · University of Tabriz · Tabriz, Iran

- Email: [mablue92@gmail.com](mailto:mablue92@gmail.com)
- Website: [masoudsoft.ir](https://masoudsoft.ir)
- GitHub: [@mablue](https://github.com/mablue)

### Support the project

If MariaPlayer is useful to you, consider rating it on Iranian app stores:

- [Bazaar](https://cafebazaar.ir/app/ir.mariaplayer)
- [Myket](https://myket.ir/app/ir.mariaplayer)

---

## 📄 License

Source code is released under the MIT License.

Bundled assets:
- **Mikhak** font — SIL Open Font License 1.1 — by Amin Abedi
- **Vazirmatn** font (legacy) — SIL OFL 1.1 — by Saber Rastikerdar

---

# فارسی

**پخش‌کننده موسیقی آفلاین اندروید، با پشتیبانی کامل از فارسی.**

MariaPlayer یک پخش‌کننده سبک با Material You برای اندروید ۸ به بالا است که برای کتابخانه‌های نامرتب ساخته شده: تگ‌های ID3 شکسته یا غایب، متن ترانه‌های همگام، و ترجمه آنلاین متن به فارسی یا انگلیسی.

## 📥 دانلود و نصب

هر push روی main یک APK امضاشده می‌سازد:

👉 **[دانلود آخرین نسخه](https://github.com/mablue/mariaplayer/releases/latest)**

نصب:

1. فایل apk را از صفحه release دانلود کنید.
2. اگر پیام آمد، «نصب از منابع ناشناس» را فعال کنید.
3. MariaPlayer را باز کنید و مجوز موسیقی و صدا را بدهید.
4. کتابخانه در اولین اجرا خودکار اسکن می‌شود.

## ✨ امکانات

### 🎵 پخش
- ExoPlayer 1.5 (Media3) با یکپارچگی MediaSession
- کنترل کامل در نوتیفیکیشن و صفحه قفل
- نوار جستجو، عقب ۱۵ ثانیه / جلو ۳۰ ثانیه
- حفظ موقعیت هر آهنگ — حتی بعد از بستن برنامه
- رد کردن خودکار فایل‌های خراب با توقف امن پس از ۳ خطا
- حالت‌های Shuffle و Repeat

### 📚 کتابخانه
- اسکن پوشه Music و هر جای دیگری که MediaStore ایندکس می‌کند
- نمایه الفبایی با قابلیت گرفتن حرف و پرش، و اسکرول خودکار به آهنگ در حال پخش بعد از ۱۵ ثانیه بی‌حرکتی
- انتخاب چندگانه — حذف گروهی، اشتراک گروهی، افزودن گروهی به پلی‌لیست
- مرتب‌سازی بر اساس عنوان، هنرمند، آلبوم، اخیر، مدت زمان
- فیلتر پوشه، فیلتر علاقه‌مندی‌ها، جستجو در عنوان/هنرمند/آلبوم
- کاور آلبوم در همه جا — ردیف‌های لیست، صفحه پخش تمام‌صفحه، پس‌زمینه لیریک
- تم روشن و تیره Material 3 (استاندارد، بدون رنگ برند hardcode شده)

### 📝 متن ترانه
- دریافت خودکار از LRCLIB، Genius، lyrics.ovh
- پارس LRC همگام با هایلایت خط فعال و تنظیم offset دستی
- ترجمه به فارسی یا انگلیسی از طریق زنجیره سه‌لایه با failover خودکار:
  MS Edge → Google → MyMemory
- چسباندن دستی برای آهنگ‌هایی که متن آنلاین ندارند
- کش ترجمه برای هر آهنگ
- پالت ۱۲ رنگ برای لیریک

### 🎧 پادکست و محتوای بلند
- دکمه‌های عقب/جلو مناسب گوش دادن به پادکست
- حفظ موقعیت برای کتاب‌های صوتی و اپیزودهای دو ساعته به یک اندازه کار می‌کند

### 🧪 کیفیت
- ۳۹ تست خودکار پوشش‌دهنده داده، لیریک، ترجمه، SSOT پخش، و فونت
- تست خودکار شامل drift detector برای SSOT که listenerهای تکراری، saveهای جامانده، یا pollerهای نشت‌کرده را نشان می‌دهد
- CI روی هر push اجرا می‌شود → APK خودکار در GitHub Releases منتشر می‌شود

## 🛠 پشته فناوری

| لایه | انتخاب |
|---|---|
| زبان | Kotlin 2.x |
| رابط کاربری | Jetpack Compose + Material 3 |
| پخش | AndroidX Media3 (ExoPlayer + MediaSession) |
| ذخیره‌سازی | DataStore Preferences (بدون Room، Hilt، WorkManager) |
| تصاویر | LruCache + دیسک کش سفارشی (بدون Coil) |
| حداقل SDK | ۲۶ (اندروید ۸) |
| هدف SDK | ۳۶ (اندروید ۱۶) |
| معماری | arm64-v8a (APK ~2.4 MB) |

هیچ SDK شخص ثالثی وجود ندارد. بدون آنالیتیکس، تبلیغات، ردیاب، یا همگام‌سازی ابری.

## 🏗 معماری

MariaPlayer از مدل single-source-of-truth (SSOT) پیروی می‌کند. فقط یک نقطه وجود دارد که مالک state پخش است:

    ┌─────────────────────────────────────────────┐
    │  PlaybackRepository                         │
    │  • ۱ Player.Listener                        │
    │  • ۱ poller موقعیت (۵۰۰ میلی‌ثانیه)         │
    │  • StateFlow<PlaybackSnapshot>              │
    │  • تمام نوشتن‌های PlaybackPrefs             │
    └───────────────────┬─────────────────────────┘
                        │ collectAsState
      ┌─────────────┬───┴────┬──────────────┐
      ▼             ▼        ▼              ▼
    NowPlaying   NowPlaying  Lyrics     MainActivity
       Bar        Screen     Screen      (Library)

هیچ صفحه‌ای Player.Listener خودش را ثبت نمی‌کند. تمام state پخش از یک repository می‌آید، که باگ کلاسیک «دو UI در مورد پخش بودن آهنگ اختلاف دارند» را حذف می‌کند.

### ساختار پکیج‌ها

    ir.mariaplayer
    ├── MainActivity.kt        — LibraryScreen, AppRoot
    ├── DeleteActivity.kt      — تأیید حذف MediaStore
    ├── data/                  — Track، Scanner، Prefs (DataStore)
    ├── playback/              — PlaybackService، PlaybackRepository،
    │                            NowPlayingBar، NowPlayingScreen
    ├── lyrics/                — Fetcher، LrcParser، Scorer، Cache
    ├── translate/             — OnlineTranslator، TranslationProbe
    ├── art/                   — AlbumArtCache
    ├── ui/                    — Composables، Theme، Type، SelfTest
    └── util/                  — PlaybackDiagnostics، Sanitize

### ذخیره‌گاه‌های داده

| ذخیره‌گاه | محتویات |
|---|---|
| playback | آخرین آهنگ + نقشه موقعیت هر آهنگ (۵۰۰ ورودی، LRU) |
| favorites | شناسه آهنگ‌های مورد علاقه |
| library | حالت مرتب‌سازی |
| playlists | پلی‌لیست‌های JSON |
| lyrics_cache | لیریک همگام + ساده، offset، ترجمه |

## 🌐 متن ترانه و ترجمه

### منابع لیریک (موازی، با امتیازدهی خودکار)

1. LRCLIB — /api/get + /api/search
2. Genius — HTML scrape با فیلتر سختگیرانه
3. lyrics.ovh — fallback متن ساده

نتایج با یک scorer فازی (عنوان + هنرمند + مدت) رتبه‌بندی می‌شوند و به کاربر نمایش داده می‌شوند — بدون انتخاب خودکار.

### زنجیره ترجمه (به ترتیب تلاش)

1. MS Edge / Azure Translator — سریع، بدون کلید
2. Google clients5 — سریع، بدون کلید
3. MyMemory — کند، ۵۰۰ بایت per request، همه جا کار می‌کند

هر لایه زبان مبدأ را از روی اسکریپت تشخیص می‌دهد (فارسی در برابر لاتین) و خودکار fallback می‌کند.

## 🧪 تست خودکار

برنامه را باز کنید → نوار ابزار → آیکون Self-Test. ۳۹ بررسی در ۲-۳ ثانیه اجرا می‌شود:

- مجوز صدا، اسکن کتابخانه
- رفت و برگشت علاقه‌مندی، sort، وضعیت resume
- CRUD پلی‌لیست
- کش لیریک، پارسر LRC، activeIndex، محافظ ۶۰ ثانیه
- امتیازدهی لیریک، مرز empty، RTL فارسی
- نرمالایزر جستجو، سطح API حذف
- probe ترجمه (Edge + Google + MyMemory)
- پیش‌فرض‌های تم، بارگذاری فونت Mikhak + پوشش glyph
- snapshot drift SSOT، یکپارچگی شمارنده، تعادل listener

اگر هر تستی fail شود، برنامه خراب در نظر گرفته می‌شود.

## 🔨 ساخت از سورس

### پیش‌نیازها

- JDK 21
- Android SDK 36 (build-tools 36.0.0)
- Gradle 8.11 (wrapper موجود است)

### Build

    git clone https://github.com/mablue/mariaplayer.git
    cd mariaplayer
    ./gradlew :app:assembleRelease

APK در app/build/outputs/apk/release/app-release.apk قرار می‌گیرد.

### امضا

پیکربندی امضا از طریق متغیرهای محیطی:

    export KEYSTORE_PATH=/path/to/keystore.jks
    export KEYSTORE_PASS=...
    export KEY_ALIAS=mariaplayer
    export KEY_PASS=...

### CI

هر push روی main فایل .github/workflows/build.yml را اجرا می‌کند که:
- Android SDK و Gradle را کش می‌کند
- unit test اجرا می‌کند
- APK امضاشده می‌سازد
- به عنوان GitHub Release منتشر می‌کند

## 👤 نویسنده و حمایت

**مسعود عزیزی — Masoud Azizi**
علوم کامپیوتر · دانشگاه تبریز · تبریز، ایران

- ایمیل: [mablue92@gmail.com](mailto:mablue92@gmail.com)
- وب‌سایت: [masoudsoft.ir](https://masoudsoft.ir)
- گیت‌هاب: [@mablue](https://github.com/mablue)

### حمایت از پروژه

اگر MariaPlayer برایتان مفید است، به آن در فروشگاه‌های ایرانی امتیاز دهید:

- [بازار](https://cafebazaar.ir/app/ir.mariaplayer)
- [مایکت](https://myket.ir/app/ir.mariaplayer)

## 📄 مجوز

کد منبع تحت مجوز MIT منتشر شده است.

دارایی‌های همراه:
- فونت **Mikhak** — مجوز SIL Open Font License 1.1 — طراح: امین عابدی
- فونت **Vazirmatn** (قدیمی) — SIL OFL 1.1 — طراح: صابر راستی‌کردار
