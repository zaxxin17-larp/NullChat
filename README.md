# NullChat

Android chat app (Kotlin + Jetpack Compose + Firebase) built to **never let
Storage run away with the bill**. Text, emoji, images, GIFs, voice notes and
short videos — with client-side compression, hard per-user limits, server cleanup
and moderation.

> Verified to build **without Android Studio**: both `assembleDebug` and a
> signed, R8-minified `assembleRelease` were produced natively on an aarch64
> Termux device. See "Building on Termux" below for the exact recipe.

## What's enforced where

| Concern | Mechanism | File |
|---|---|---|
| Image shrink to 1080px / WebP 80% | Client (Bitmap) | `media/ImageCompressor.kt` |
| GIF → muted looping MP4, ≤6s | Client (ffmpeg) | `media/GifTranscoder.kt` |
| Video ≤30s, ~2 Mbps | Client (Media3) | `media/VideoCompressor.kt` |
| Voice ≤60s, AAC 48 kbps | Client (MediaRecorder) | `media/VoiceRecorder.kt` |
| Size caps (2/8/1 MB) | Client **and** Storage rules | `MediaValidator.kt`, `firebase/storage.rules` |
| Allowed MIME types | Storage rules | `firebase/storage.rules` |
| Owner-only writes | Storage rules | `firebase/storage.rules` |
| 20 uploads/day + 100 MB quota | Firestore transaction | `media/UploadLimiter.kt` |
| Delete media on message delete | Cloud Function | `functions/index.js` |
| Orphan cleanup | Cloud Function (scheduled) | `functions/index.js` |
| 90-day hard delete | GCS lifecycle | `firebase/lifecycle.json` |
| Old message TTL | Firestore TTL | `firebase/firestore.indexes.json` |
| Thumbnail-first loading | Coil 256 MB disk cache | `ui/chat/ImageLoader.kt` |
| Duplicate detection | SHA-256 + media index | `media/MediaUploader.kt` |
| Favorites (reuse GIFs) | `favorite` flag, survives GC | `MediaUploader.setFavorite` |
| Report / moderation | `reports` + optional Vision | `functions/index.js` |
| Upload logging | `onObjectFinalized` | `functions/index.js` |
| Scripted-abuse defence | Firebase App Check | `ChatApplication.kt` |

## Project layout

```
NullChat/
├── app/                         Android app (Kotlin + Compose)
│   └── src/main/java/com/nullsec/nullchat/
│       ├── ChatApplication.kt   App Check bootstrap
│       ├── MainActivity.kt
│       ├── media/               compression + upload pipeline
│       ├── model/               Message, MediaIndexItem
│       ├── ui/chat/             ChatScreen, MediaBubble, ViewModel, Coil loader
│       ├── ui/theme/
│       └── util/                HashUtils
├── firebase/
│   ├── storage.rules            size + type + owner enforcement
│   ├── firestore.rules          quota/index/report protection
│   ├── firestore.indexes.json   (incl. TTL config)
│   └── lifecycle.json           GCS 90-day delete
├── functions/                   Cloud Functions (Node 20)
└── firebase.json
```

## 1. Build the app

1. Open the project in **Android Studio** (Ladybug or newer).
2. Create a Firebase project, add an **Android app** with package `com.nullsec.nullchat`.
3. Download `google-services.json` into `app/` (it is git-ignored).
4. Enable **App Check → Play Integrity** in the Firebase console.
5. `./gradlew assembleDebug` (needs JDK 17 + Android SDK 35).

**ffmpeg-kit note:** arthenica/ffmpeg-kit was retired in April 2025 and its
artifacts were pulled from Maven Central. This project uses the community
**drop-in fork** `dev.ffmpegkit-maintained:ffmpeg-kit-min-gpl:8.1.9` (same
`com.arthenica.ffmpegkit` API, retargeted to SDK 35 + 16 KB pages) — no code
changes needed. If that fork ever disappears, either pin another fork or move
GIF→MP4 transcoding server-side.

### Building on Termux (aarch64, no Android Studio)

This whole project was verified to `assembleDebug` inside Termux on an aarch64
device. The one non-obvious trick: Google only ships an **x86_64** `aapt2` for
Linux hosts, so you override it with Termux's native aarch64 `aapt2`.

```bash
pkg install openjdk-17 aapt2 unzip

# Gradle 8.11.1 — the Termux `gradle` package is 9.x, too new for AGP 8.7.3.
curl -sLO https://services.gradle.org/distributions/gradle-8.11.1-bin.zip
unzip -q gradle-8.11.1-bin.zip

# Android command-line tools + platform 35 (the tools are Java; the sdkmanager
# script just needs its /usr/bin/env shebang repointed to Termux).
curl -sLO https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
mkdir -p android-sdk/cmdline-tools
unzip -q commandlinetools-linux-11076708_latest.zip -d android-sdk/cmdline-tools
mv android-sdk/cmdline-tools/cmdline-tools android-sdk/cmdline-tools/latest
termux-fix-shebang android-sdk/cmdline-tools/latest/bin/*

export ANDROID_HOME="$PWD/android-sdk" ANDROID_SDK_ROOT="$PWD/android-sdk"
yes | sdkmanager --sdk_root="$ANDROID_HOME" --licenses
sdkmanager --sdk_root="$ANDROID_HOME" "platforms;android-35" "build-tools;35.0.0" "platform-tools"

cp firebase/google-services.sample.json app/google-services.json
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
```

Build:

```bash
~/gradle-8.11.1/bin/gradle :app:assembleDebug \
  -Pandroid.aapt2FromMavenOverride="$PREFIX/bin/aapt2"
# -> app/build/outputs/apk/debug/app-debug.apk
```

Notes: the aapt2 override applies to **all** Gradle invocations (it is passed on
the command line rather than committed to `gradle.properties`, so it can't break
other machines). AGP 8.x aligns the APK in-process (zipflinger), so the x86_64
`zipalign` is never invoked and no box64/qemu is needed.

### Release build & signing

Release builds are minified + resource-shrunk by R8. Signing is opt-in via a
**gitignored** `keystore.properties` at the repo root:

```properties
storeFile=/abs/path/release.keystore
storePassword=...
keyAlias=...
keyPassword=...
```

Generate the keystore once:

```bash
keytool -genkeypair -v -keystore release.keystore -alias nullchat \
  -keyalg RSA -keysize 2048 -validity 10000
```

```bash
gradle :app:assembleRelease -Pandroid.aapt2FromMavenOverride="$PREFIX/bin/aapt2"
# -> app/build/outputs/apk/release/app-release.apk
```

If `keystore.properties` is absent (fresh clone / CI) the release APK is left
unsigned instead of failing the build.

Verified in Termux: signed (APK Signature Scheme **v2**), zip-aligned (194
STORED entries, 0 misaligned, `.so` at 16384), R8-minified (~50 MB with all four
ffmpeg ABIs vs ~71 MB debug).

### CI

- `.github/workflows/build.yml` — **compile-only** `assembleDebug` on every push
  and PR. Uses the stub `google-services.json`; never touches a real project.
- `.github/workflows/release.yml` — **signed release APK** on `v*` tags (or
  manual dispatch). Reconstructs `google-services.json` and the keystore from
  repo secrets and attaches the APK to a GitHub Release.

The Gradle wrapper jar is intentionally **not** committed — CI installs Gradle
8.11.1 itself. (If you later commit a real wrapper, switch `gradle ...` to
`./gradlew ...` in both workflows.)

See [`firebase/README.md`](firebase/README.md) for how to obtain
`google-services.json` and which repository secrets to set.

## 2. Deploy rules + functions

```bash
npm --prefix functions install
firebase deploy --only firestore,storage,functions
```

`firestore.indexes.json` also declares the **TTL policy** for `messages.expireAt`.

## 3. Lifecycle rule (90-day hard delete)

Console: **Cloud Storage → Buckets → (your bucket) → Lifecycle → Add rule**
→ *Object deletion*, *Age* = `90` days, *Prefix* = `chat_media/`.

Or via CLI:

```bash
gsutil lifecycle set firebase/lifecycle.json gs://<project-id>.appspot.com
gsutil lifecycle get gs://<project-id>.appspot.com   # verify
```

## 4. Budget alerts ($5 and $10)

Console: **Billing → Budgets & alerts → Create budget**

1. Scope = your project (or all projects).
2. Amount = **$10** — create a second budget at **$5**.
3. Thresholds: 50% / 90% / 100% (add forecasted if you like).
4. Alerts → tick **Email alerts** and add recipients.

CLI alternative:

```bash
gcloud billing budgets create \
  --billing-account=<ACCOUNT_ID> \
  --display-name="NullChat \$10" \
  --budget-amount=10USD \
  --threshold-rule=percent=0.5 --threshold-rule=percent=0.9 --threshold-rule=percent=1.0
```

Budget alerts **notify only** — they never cap spending. The real caps are the
Storage rules + Firestore quota.

## 5. Firestore model

```
users/{uid}                         { storageBytes, storageQuota, lastUploadAt }
users/{uid}/uploadStats/{yyyyMMdd}  { count }
user_media_index/{uid}/items/{id}   { sha256, kind, storagePath, thumbPath, url,
                                      thumbUrl, sizeBytes, refCount, favorite, ... }
chats/{chatId}/messages/{msgId}     { type, mediaUrl, thumbUrl, storagePath,
                                      thumbPath, mediaId, sizeBytes, senderId,
                                      createdAt, expireAt, reported }
reports/{id}                        { chatId, messageId, reporterId, reason, status }
upload_logs/{id}                    { uid, path, sizeBytes, contentType, createdAt }
moderation/{id}                     { path, adult, violence, racy, createdAt }
```

## 6. Limits reference

| Media | Max size | Duration | Compression |
|---|---|---|---|
| Image | 2 MB | — | longest side 1080px, WebP q80 |
| GIF | 8 MB | 6 s | muted looping MP4, H.264 crf30, 15fps |
| Video | 8 MB | 30 s | H.264 720p ~2 Mbps + AAC |
| Voice | 1 MB | 60 s | AAC 48 kbps mono 22.05 kHz |
| Per user | 100 MB total | 20 uploads/day | — |

## 7. Trade-offs

- **Client-side enforcement is bypassable.** Compression, quota checks and hashing
  all run in the app. Storage rules cover size/type/owner; nothing client-side can
  measure dimensions or duration. Enable App Check and, for a hard guarantee, use
  the `reserveUpload` Callable Function and make the counters server-only.
- **ffmpeg-kit is retired** and adds ~10–15 MB to the APK (GPL build). Server-side
  transcoding avoids the size but adds a service to run.
- **GCS lifecycle deletes by creation age**, not last access — it can remove media
  that a still-live message points at. If that matters, keep the message and render
  a "media expired" placeholder.
- **Firestore TTL is best-effort** (up to ~72 h, `Timestamp`-only, collection-group
  index). It triggers `onMessageDeleted`, which is what reclaims the Storage object.
- **Thumbnails cost an extra object each** (~20 KB) but cut bandwidth and make the
  list render instantly.
- **SHA-256 dedupe is per user.** Cross-user dedupe would save more but leaks the
  fact that two users hold identical files.
- **Vision moderation costs per call.** Gate it to `contentType`-matching uploads,
  or only to messages that get reported.
