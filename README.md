# AI Pages

One Android shell for private HTML and TypeScript tools. AI Pages owns ChatGPT sign-in, photo selection, tool data, and backup and restore. Tool updates are fetched separately from the APK.

## Install and check sign-in

1. Download **ai-pages-debug** from a successful **Android APK** workflow run, extract it, and install the APK on Android 9 or newer. Use a current Android System WebView.
2. Tap **Continue with ChatGPT** and grant **Use your ChatGPT plan**. Return to the app after the browser receives the response.
3. If the browser times out at `127.0.0.1`, copy its **full address**. In AI Pages tap **Paste redirect URL** and paste it. The address contains a one-time code. Use the address from the current attempt. If the attempt expires or Android kills the app process, start a fresh sign-in.
4. Tap **Test ChatGPT connection**. A response verifies model access on this device and account.

This uses OpenAI's [Sign in with ChatGPT for local clients](https://developers.openai.com/siwc/token-sharing-open-source). The app has no API-key configuration. Credentials are encrypted with an Android Keystore key.

## Set up the private tool feed

Generate a signing key on a trusted machine:

```sh
umask 077
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out ai-pages-private.pem
openssl pkey -in ai-pages-private.pem -pubout -outform DER | openssl base64 -A
```

1. In **Ratslayer/calorie-watcher**, configure `AI_PAGES_SIGNING_KEY` with the private PEM and `AI_PAGES_PUBLISH_TOKEN` with a fine-grained token scoped to **Ratslayer/ai-pages** with **Contents: Read and write**. Run **Calorie Watcher page** on `main` once. The workflow creates the catalog at `feed/catalog.json`.
2. Create a separate fine-grained token scoped to **Ratslayer/ai-pages** with **Contents: Read-only**. On the phone, tap **Configure updates** and enter that token and the base64 public key printed above.
3. Tap **Refresh tools**, then open **Calorie Watcher**. Later web releases use the same refresh button. The app verifies signatures, keeps a cached bundle for offline use, and offers **Restore previous bundle** after an update.

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

With JDK 17, Gradle 8.13, and Android SDK platform 36, run `bash tests/check-callback.sh` and `gradle lintDebug assembleDebug`. The APK is at `app/build/outputs/apk/debug/app-debug.apk`.
