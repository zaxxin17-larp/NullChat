# Firebase setup

## 1. Get your real `google-services.json`

1. Create a project at <https://console.firebase.google.com>.
2. **Add app → Android**, and set the package name **exactly** to
   `com.nullsec.nullchat` (must match `applicationId` in `app/build.gradle.kts`).
3. (Recommended) add your signing SHA-1 so Play Integrity / App Check works:
   `keytool -list -v -keystore ~/nullchat-release.keystore -alias nullchat`.
4. Download `google-services.json` and place it at **`app/google-services.json`**.

The gradle `com.google.gms.google-services` plugin reads that one file. It is
**git-ignored** — never commit it (it contains API keys).

The shape the plugin requires (this is what `google-services.sample.json` mimics):

```json
{
  "project_info": { "project_number": "...", "project_id": "...", "storage_bucket": "..." },
  "client": [{
    "client_info": {
      "mobilesdk_app_id": "1:...:android:...",
      "android_client_info": { "package_name": "com.nullsec.nullchat" }
    },
    "api_key": [{ "current_key": "AIza..." }],
    "oauth_client": [],
    "services": { "appinvite_service": { "other_platform_oauth_client": [] } }
  }],
  "configuration_version": "1"
}
```

## 2. Enable the services the app uses

| Service | Why | Notes |
|---|---|---|
| Authentication | sign-in | enable Anonymous or Email/Password |
| Cloud Firestore | messages, counters | start in production mode; publish `firestore.rules` |
| Cloud Storage | media | create the default bucket; publish `storage.rules` |
| Cloud Functions | cleanup, moderation | requires the **Blaze** plan |
| App Check | anti-abuse | register **Play Integrity**; see below |
| Crashlytics | crash reports | optional |

## 3. Build without a real project (local / Termux)

If you just want the build to run, copy the fake stub:

```bash
cp firebase/google-services.sample.json app/google-services.json
```

Fake IDs let Gradle + the app **compile and install**, but every Firebase call
fails at runtime until you swap in the real file.

## 4. App Check gotcha

`ChatApplication.kt` installs the Play Integrity provider. While you develop,
either leave App Check **unenforced**, or register the auto-generated **debug
token** (printed in Logcat on first run) under **App Check → Apps → Manage debug
tokens**. Enforce it only once the app builds fine on real devices.

## 5. CI secrets

The release workflow (`.github/workflows/release.yml`) builds a **signed** APK
from repository secrets. Add these under **Settings → Secrets and variables →
Actions**:

| Secret | Value |
|---|---|
| `GOOGLE_SERVICES_JSON` | full text of your real `app/google-services.json` |
| `KEYSTORE_BASE64` | `base64 -w0 release.keystore` (macOS: `base64 -i release.keystore`) |
| `KEYSTORE_PASSWORD` | the keystore's store password |
| `KEY_ALIAS` | the key alias (e.g. `nullchat`) |
| `KEY_PASSWORD` | the key password |

If `KEYSTORE_BASE64` is missing the workflow still runs and produces an
**unsigned** APK (handy for forks). If `GOOGLE_SERVICES_JSON` is missing it falls
back to the committed stub.

`keystore.properties` and the keystore itself are git-ignored and reconstructed
only inside the CI runner.
