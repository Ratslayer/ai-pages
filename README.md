# AI Pages

One Android shell for HTML and TypeScript tools. AI Pages owns ChatGPT sign-in, photo selection, tool data, and backup and restore. Tool updates are fetched separately from the APK.

## Install and check sign-in

1. Download **ai-pages-installable** from a successful **Android APK** workflow run on `main`, extract it, and install the APK on Android 9 or newer. Set up the stable signer below before the first install. Use a current Android System WebView.
2. Tap **Continue with ChatGPT** and grant **Use your ChatGPT plan**. Return to the app after the browser receives the response.
3. If the browser times out at `127.0.0.1`, tap the **Finish sign-in** checkmark in the browser toolbar. If your browser does not pass the redirect back, copy its full address and use **Paste redirect URL** in AI Pages. The address contains a one-time code for the current attempt.
4. Tap **Test ChatGPT connection**. A response verifies model access on this device and account.

This uses OpenAI's [Sign in with ChatGPT for local clients](https://developers.openai.com/siwc/token-sharing-open-source). The app has no API-key configuration. Credentials are encrypted with an Android Keystore key.

## Set up APK signing once

From a local checkout, generate an Android signing secret with OpenSSL:

```sh
bash scripts/create-android-signing-secret.sh "$HOME/ai-pages-android-signing.secret"
pbcopy < "$HOME/ai-pages-android-signing.secret"
```

Paste the full file into `AI_PAGES_ANDROID_SIGNING` in the [AI Pages Actions secrets](https://github.com/Ratslayer/ai-pages/settings/secrets/actions). Keep a private backup of the file. Use the same secret for future APKs.

Run [**Android APK**](https://github.com/Ratslayer/ai-pages/actions/workflows/android.yml) on `main` and download **ai-pages-installable**. Switching from the earlier preview APK requires one reinstall and sign-in. After that, install new **ai-pages-installable** APKs over the current app to keep the session and tool data. If you explicitly uninstall, choose **Keep app data** when Android offers it and reinstall with the same signer to retain local data.

## Set up the tool feed

The Android app ships with the [catalog public key](app/src/main/assets/ai-pages-public.txt). It checks the public copy on GitHub on first launch and during refresh. It saves that key after verifying the first signed catalog. Later launches also check for a key change.

The matching private PEM belongs in the [AI Pages Actions secrets](https://github.com/Ratslayer/ai-pages/settings/secrets/actions).

| Secret | Value |
| --- | --- |
| `AI_PAGES_SIGNING_KEY` | The private PEM matching the committed public key. |
| `AI_PAGES_SOURCE_TOKEN` | A fine-grained token for private source repositories with **Contents: Read-only**. Select `Ratslayer/calorie-watcher` for the current catalog. |

1. In [AI Pages Actions](https://github.com/Ratslayer/ai-pages/actions/workflows/publish-catalog.yml), run **Publish catalog** on `main`. It checks that the signing secret matches the committed public key before publishing.
2. Open the app or tap **Refresh tools** to download the signed catalog. To publish later page changes, run **Publish catalog** again.

If you deliberately rotate the signing key, update the secret and the committed public key together, then publish. Existing installations warn and stop accepting updates until you paste the new key from your signing machine into **Configure updates** and refresh. Use the same key file for future APK builds.

Add new pages to [catalog-sources.json](catalog-sources.json). Each source repo uses Node 22 and emits `dist/index.html`, `dist/app.js`, and `dist/style.css` with `npm ci` and `npm run build`. Add a new private source to the read token's repository access. Public sources need only a list entry. The compiled pages are published in the public AI Pages catalog.

To back up one tool, open it and tap **Backup**. Pick an on-device location or a cloud provider available in the Android file picker. **Restore** replaces that tool's local data. Treat the exported JSON as private. It includes entries and settings for the tool. It excludes sign-in credentials and photos. It does not sync devices.

## Bridge version 1

A verified tool supplies `index.html`, `app.js`, and `style.css`. The shell serves each tool from its own local HTTPS origin and accepts bridge messages only from the top-level document. The `AiPages` JavaScript object takes JSON strings with a numeric string `id` and a `method`. Replies have the same `id` and either `value` or `error`.

| Method | Input and output |
| --- | --- |
| `storage.read` | Returns the tool's JSON state as a string or `null`. |
| `storage.write` | Takes a `value` string containing a JSON object and saves it locally. |
| `photos.pick` | Opens the Android library or camera and returns whether a photo was selected. |
| `ai.run` | Takes a `prompt` and optional `image: true`. Asks for approval and returns text from the completed response. |

## Build

With JDK 17, Gradle 8.13, and Android SDK platform 36, run `bash tests/check-java.sh` and `gradle lintDebug assembleDebug`. The APK is at `app/build/outputs/apk/debug/app-debug.apk`.
