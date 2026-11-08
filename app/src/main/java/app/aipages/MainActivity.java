package app.aipages;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.browser.customtabs.CustomTabsIntent;
import androidx.core.content.FileProvider;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int PICK_PHOTO = 1, TAKE_PHOTO = 2, EXPORT = 3, IMPORT = 4;
    private static final int MAX_DATA = 350_000;
    private static final String CSP = "default-src 'none'; script-src 'self'; style-src 'self'; "
            + "base-uri 'none'; frame-ancestors 'none'; form-action 'none'";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService authWorker = Executors.newSingleThreadExecutor();
    private PageFeed feed;
    private ChatGpt chat;
    private ChatGpt.SignIn pendingSignIn;
    private boolean signingIn;
    private boolean updating;
    private String status = "";
    private ToolScreen tool;
    private File cameraFile;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (android.os.Build.VERSION.SDK_INT >= 35) {
            getWindow().getDecorView().setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets padding = insets.getInsets(android.view.WindowInsets.Type.systemBars());
                view.setPadding(padding.left, padding.top, padding.right, padding.bottom);
                return insets;
            });
        }
        if (saved == null) new File(getCacheDir(), "meal-photos/meal.jpg").delete();
        feed = new PageFeed(this);
        chat = new ChatGpt(this);
        showHome();
    }

    private void showHome() {
        if (tool != null) {
            destroyTool();
        }
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = column();
        scroll.addView(body);
        TextView title = new TextView(this);
        title.setText("AI Pages");
        title.setTextSize(30);
        body.addView(title);
        TextView message = new TextView(this);
        message.setText(status);
        message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        body.addView(message);
        if (pendingSignIn != null) {
            addButton(body, "Paste redirect URL", this::pasteRedirect);
            addButton(body, "Stop sign-in", () -> close(pendingSignIn));
        } else if (!signingIn) {
            if (chat.connected()) {
                addButton(body, "Test ChatGPT connection", () -> update(() ->
                        "ChatGPT replied: " + chat.run("Reply with the single word Connected.", null)));
                addButton(body, "Disconnect ChatGPT", () -> update(() -> {
                    chat.disconnect();
                    return "ChatGPT disconnected on this phone.";
                }));
            } else {
                addButton(body, "Continue with ChatGPT", this::beginSignIn);
            }
        }
        addButton(body, "Configure updates", () -> { if (!updating) configureFeed(); });
        addButton(body, updating ? "Working…" : "Refresh tools", () -> update(feed::refresh)).setEnabled(!updating);
        if (feed.hasPrevious()) {
            addButton(body, "Restore previous bundle", () -> update(() -> {
                feed.restorePrevious();
                return "Restored the previous bundle.";
            }));
        }
        try {
            Map<String, PageFeed.Page> pages = feed.cachedPages();
            if (pages.isEmpty()) {
                TextView empty = new TextView(this);
                empty.setText("Configure the private GitHub feed, then refresh to download tools.");
                body.addView(empty);
            }
            for (PageFeed.Page page : pages.values()) {
                addButton(body, page.title(), () -> openTool(page)).setEnabled(!updating && !signingIn);
            }
        } catch (Exception e) {
            TextView problem = new TextView(this);
            problem.setText("Could not open the cached catalog. Check the signing key and refresh.");
            body.addView(problem);
        }
        setContentView(scroll);
    }

    private void destroyTool() {
        WebView view = tool.view;
        if (view.getParent() instanceof ViewGroup parent) parent.removeView(view);
        view.destroy();
        tool = null;
    }

    private void configureFeed() {
        LinearLayout fields = column();
        EditText token = secretField("GitHub read token", 256);
        EditText key = new EditText(this);
        key.setHint("Catalog signing public key");
        key.setText(feed.publicKey());
        fields.addView(token);
        fields.addView(key);
        new AlertDialog.Builder(this).setTitle("Private GitHub feed")
                .setMessage("Use a token scoped to AI Pages with Contents read-only. Leave it blank to keep an existing token.")
                .setView(fields).setNegativeButton("Cancel", null).setPositiveButton("Save", (dialog, button) -> {
                    String credential = token.getText().toString().trim();
                    String publicKey = key.getText().toString().trim();
                    token.setText("");
                    update(() -> {
                        feed.configure(credential, publicKey);
                        return "Update settings saved. Tap Refresh tools.";
                    });
                }).show();
    }

    private void beginSignIn() {
        signingIn = true;
        status = "Opening ChatGPT sign-in…";
        showHome();
        authWorker.execute(() -> {
            try (ChatGpt.SignIn attempt = chat.beginSignIn()) {
                runOnUiThread(() -> {
                    if (isDestroyed()) {
                        close(attempt);
                        return;
                    }
                    pendingSignIn = attempt;
                    status = "If the browser times out, copy its address and tap Paste redirect URL.";
                    showHome();
                    try {
                        new CustomTabsIntent.Builder().setShareState(CustomTabsIntent.SHARE_STATE_OFF)
                                .build().launchUrl(this, attempt.browserUrl);
                    } catch (ActivityNotFoundException e) {
                        close(attempt);
                    }
                });
                attempt.finish(() -> runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    pendingSignIn = null;
                    status = "Browser response received. Connecting to OpenAI…";
                    showHome();
                }));
                finishSignIn("Connected. Tools can use your ChatGPT plan.");
            } catch (Exception e) {
                finishSignIn(message(e, "Could not connect to ChatGPT."));
            }
        });
    }

    private void pasteRedirect() {
        ChatGpt.SignIn attempt = pendingSignIn;
        EditText address = secretField("Full redirect URL", ChatGpt.MAX_CALLBACK_LENGTH);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Finish ChatGPT sign-in")
                .setMessage("Copy the full address from the current browser tab. It contains a one-time code.")
                .setView(address).setNegativeButton("Back", null).setPositiveButton("Continue", null).show();
        dialog.setOnDismissListener(ignored -> address.setText(""));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                attempt.submitRedirect(address.getText().toString());
                dialog.dismiss();
            } catch (IOException e) {
                address.setError(e.getMessage());
            }
        });
    }

    private void finishSignIn(String result) {
        runOnUiThread(() -> {
            if (isDestroyed()) return;
            pendingSignIn = null;
            signingIn = false;
            status = result;
            showHome();
        });
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    private void openTool(PageFeed.Page page) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            status = "Update Android System WebView to open tools.";
            showHome();
            return;
        }
        ToolScreen screen = new ToolScreen(page);
        tool = screen;
        WebView view = screen.view;
        view.getSettings().setJavaScriptEnabled(true);
        view.getSettings().setAllowFileAccess(false);
        view.getSettings().setAllowContentAccess(false);
        // Requests are rejected by the interceptor so the synthetic HTTPS origin can still load.
        view.setWebChromeClient(new android.webkit.WebChromeClient());
        view.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView webView, WebResourceRequest request) {
                return true;
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView webView, WebResourceRequest request) {
                Uri url = request.getUrl();
                if ("GET".equals(request.getMethod()) && "https".equals(url.getScheme())
                        && screen.host.equals(url.getEncodedAuthority()) && url.getEncodedQuery() == null) {
                    String path = url.getEncodedPath();
                    String name = path == null ? "" : path.substring(1);
                    String content = page.files().get(name);
                    if (content != null) {
                        String mime = switch (name) {
                            case "index.html" -> "text/html";
                            case "app.js" -> "text/javascript";
                            default -> "text/css";
                        };
                        Map<String, String> headers = new HashMap<>();
                        headers.put("Content-Security-Policy", CSP);
                        headers.put("X-Content-Type-Options", "nosniff");
                        headers.put("Cache-Control", "no-store");
                        return new WebResourceResponse(mime, "UTF-8", 200, "OK", headers,
                                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
                    }
                }
                return new WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden",
                        Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
            }
        });
        WebViewCompat.addWebMessageListener(view, "AiPages", Collections.singleton(screen.origin),
                (webView, message, origin, main, reply) -> {
                    if (main && screen.origin.equals(origin.toString()) && tool == screen) {
                        onBridgeMessage(screen, message.getData(), reply);
                    }
                });
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        LinearLayout bar = new LinearLayout(this);
        addButton(bar, "Tools", () -> { if (!screen.busy) showHome(); });
        addButton(bar, "Backup", () -> backup(screen));
        addButton(bar, "Restore", () -> restore(screen));
        layout.addView(bar);
        layout.addView(view, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(layout);
        view.loadUrl(screen.origin + "/index.html");
    }

    private void onBridgeMessage(ToolScreen screen, String data, JavaScriptReplyProxy proxy) {
        if (data == null || data.length() > 6 * MAX_DATA + 10_000) return;
        String id = "";
        try {
            JSONObject request = new JSONObject(data);
            id = request.getString("id");
            if (!id.matches("[0-9]{1,12}")) return;
            switch (request.getString("method")) {
                case "storage.read" -> reply(proxy, id, screen.data.getString("state", null), null);
                case "storage.write" -> {
                    if (screen.busy) throw new IOException("Finish the current action first.");
                    String state = request.getString("value");
                    saveState(screen, state);
                    reply(proxy, id, true, null);
                }
                case "photos.pick" -> {
                    if (screen.busy) throw new IOException("Finish the current action first.");
                    pickPhoto(screen, proxy, id);
                }
                case "ai.run" -> {
                    if (screen.busy) throw new IOException("Finish the current action first.");
                    if (!chat.connected()) throw new IOException("Connect ChatGPT from the Tools screen first.");
                    String prompt = request.getString("prompt");
                    if (prompt.trim().isEmpty() || prompt.length() > 8_000) throw new IOException("Invalid prompt.");
                    byte[] photo = request.optBoolean("image") ? screen.photo : null;
                    if (request.optBoolean("image") && photo == null) throw new IOException("Choose a photo first.");
                    confirmRun(screen, proxy, id, prompt, photo);
                }
                default -> throw new IOException("Unsupported tool action.");
            }
        } catch (Exception e) {
            reply(proxy, id, null, message(e, "Could not complete the tool action."));
        }
    }

    private void confirmRun(ToolScreen screen, JavaScriptReplyProxy proxy, String id, String prompt, byte[] photo) {
        screen.busy = true;
        Runnable cancel = () -> {
            screen.busy = false;
            reply(proxy, id, null, "Request canceled.");
        };
        new AlertDialog.Builder(this).setTitle("Send to ChatGPT?")
                .setMessage(screen.page.title() + (photo == null ? " will send a prompt." : " will send the selected photo and a prompt."))
                .setNegativeButton("Cancel", (dialog, button) -> cancel.run()).setOnCancelListener(d -> cancel.run())
                .setPositiveButton("Send", (dialog, button) -> worker.execute(() -> {
                    try {
                        String output = chat.run(prompt, photo);
                        runOnUiThread(() -> complete(screen, proxy, id, output, null));
                    } catch (Exception e) {
                        runOnUiThread(() -> complete(screen, proxy, id, null, message(e, "ChatGPT request failed.")));
                    }
                })).show();
    }

    private void pickPhoto(ToolScreen screen, JavaScriptReplyProxy proxy, String id) {
        screen.busy = true;
        screen.photoReply = proxy;
        screen.photoId = id;
        new AlertDialog.Builder(this).setTitle("Choose a photo")
                .setItems(new String[]{"Photo library", "Camera"}, (dialog, choice) -> {
                    try {
                        if (choice == 0) {
                            startActivityForResult(document(Intent.ACTION_OPEN_DOCUMENT, "image/*"), PICK_PHOTO);
                        } else {
                            cameraFile = new File(getCacheDir(), "meal-photos/meal.jpg");
                            File parent = cameraFile.getParentFile();
                            if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot prepare camera.");
                            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".photos", cameraFile);
                            Intent capture = new Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                                    .putExtra(MediaStore.EXTRA_OUTPUT, uri)
                                    .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            capture.setClipData(android.content.ClipData.newRawUri("meal", uri));
                            startActivityForResult(capture, TAKE_PHOTO);
                        }
                    } catch (Exception e) {
                        deleteCameraFile();
                        finishPhoto(screen, false, "Could not open the photo source.");
                    }
                }).setOnCancelListener(dialog -> finishPhoto(screen, false, null)).show();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        ToolScreen screen = tool;
        Uri uri = data == null ? null : data.getData();
        if (request == PICK_PHOTO || request == TAKE_PHOTO) {
            if (screen == null || result != RESULT_OK || (uri == null && request == PICK_PHOTO)
                    || (cameraFile == null && request == TAKE_PHOTO)) {
                deleteCameraFile();
                if (screen != null) finishPhoto(screen, false, null);
                return;
            }
            ImageDecoder.Source source = request == TAKE_PHOTO ? ImageDecoder.createSource(cameraFile)
                    : ImageDecoder.createSource(getContentResolver(), uri);
            worker.execute(() -> {
                try {
                    byte[] bytes = resize(source);
                    runOnUiThread(() -> {
                        screen.photo = bytes;
                        finishPhoto(screen, true, null);
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> finishPhoto(screen, false, "Could not read the photo."));
                } finally {
                    if (request == TAKE_PHOTO) deleteCameraFile();
                }
            });
        } else if (screen != null && (request == EXPORT || request == IMPORT)) {
            if (result != RESULT_OK || uri == null) {
                screen.busy = false;
                return;
            }
            worker.execute(() -> transfer(screen, request, uri));
        }
    }

    private void backup(ToolScreen screen) {
        if (screen.busy) return;
        chooseBackup(screen, document(Intent.ACTION_CREATE_DOCUMENT, "application/json")
                .putExtra(Intent.EXTRA_TITLE, screen.page.id() + "-backup.json"), EXPORT);
    }

    private void restore(ToolScreen screen) {
        if (screen.busy) return;
        new AlertDialog.Builder(this).setTitle("Replace this tool’s local data?")
                .setMessage("Restoring a backup replaces the current data for " + screen.page.title() + ".")
                .setNegativeButton("Cancel", null).setPositiveButton("Choose backup", (dialog, button) ->
                        chooseBackup(screen, document(Intent.ACTION_OPEN_DOCUMENT, "application/json"), IMPORT)).show();
    }

    private void chooseBackup(ToolScreen screen, Intent intent, int request) {
        try {
            screen.busy = true;
            startActivityForResult(intent, request);
        } catch (ActivityNotFoundException e) {
            screen.busy = false;
            toast("No file picker is available.");
        }
    }

    private void transfer(ToolScreen screen, int request, Uri uri) {
        String result;
        try {
            if (request == EXPORT) {
                JSONObject backup = new JSONObject().put("version", 1).put("tool", screen.page.id())
                        .put("data", new JSONObject(screen.data.getString("state", "{}")));
                try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                    if (output == null) throw new IOException("Could not open the backup file.");
                    output.write(backup.toString().getBytes(StandardCharsets.UTF_8));
                }
                result = "Backup saved.";
            } else {
                try (InputStream input = getContentResolver().openInputStream(uri)) {
                    JSONObject backup = new JSONObject(new String(PageFeed.read(input, MAX_DATA + 1024),
                            StandardCharsets.UTF_8));
                    if (backup.getInt("version") != 1 || !screen.page.id().equals(backup.getString("tool"))) {
                        throw new IOException("This backup belongs to another tool or version.");
                    }
                    saveState(screen, backup.getJSONObject("data").toString());
                }
                result = "Backup restored.";
            }
        } catch (Exception e) {
            result = message(e, "Could not process the backup.");
        }
        String notification = result;
        runOnUiThread(() -> {
            if (isDestroyed() || tool != screen) return;
            screen.busy = false;
            if (request == IMPORT && "Backup restored.".equals(notification)) {
                showHome();
                openTool(screen.page);
            }
            toast(notification);
        });
    }

    private static void saveState(ToolScreen screen, String state) throws Exception {
        if (state.getBytes(StandardCharsets.UTF_8).length > MAX_DATA) throw new IOException("Tool storage limit reached.");
        JSONObject value = new JSONObject(state);
        if (!screen.data.edit().putString("state", value.toString()).commit()) {
            throw new IOException("Could not save tool data.");
        }
    }

    private void finishPhoto(ToolScreen screen, boolean selected, String error) {
        JavaScriptReplyProxy proxy = screen.photoReply;
        screen.photoReply = null;
        if (proxy != null) complete(screen, proxy, screen.photoId, selected, error);
    }

    private void complete(ToolScreen screen, JavaScriptReplyProxy proxy, String id, Object value, String error) {
        screen.busy = false;
        if (!isDestroyed() && tool == screen) reply(proxy, id, value, error);
    }

    private static void reply(JavaScriptReplyProxy proxy, String id, Object value, String error) {
        try {
            JSONObject response = new JSONObject().put("id", id);
            if (error != null) response.put("error", error);
            else response.put("value", value == null ? JSONObject.NULL : value);
            proxy.postMessage(response.toString());
        } catch (Exception ignored) {
            // The tool may have closed before the request completed.
        }
    }

    private static Bitmap decode(ImageDecoder.Source source) throws IOException {
        return ImageDecoder.decodeBitmap(source, (decoder, info, ignored) -> {
            int width = info.getSize().getWidth();
            int height = info.getSize().getHeight();
            double scale = Math.min(1.0, 1_200.0 / Math.max(width, height));
            decoder.setTargetSize(Math.max(1, (int) (width * scale)), Math.max(1, (int) (height * scale)));
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
        });
    }

    private static byte[] resize(ImageDecoder.Source source) throws IOException {
        Bitmap bitmap = decode(source);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, bytes)) throw new IOException("Cannot prepare photo.");
            return bytes.toByteArray();
        } finally {
            bitmap.recycle();
        }
    }

    private void deleteCameraFile() {
        if (cameraFile != null) {
            cameraFile.delete();
            cameraFile = null;
        }
    }

    private static Intent document(String action, String mime) {
        return new Intent(action).addCategory(Intent.CATEGORY_OPENABLE).setType(mime);
    }

    private interface Work { String run() throws Exception; }

    private void update(Work work) {
        if (updating) return;
        updating = true;
        showHome();
        worker.execute(() -> {
            String result;
            try {
                result = work.run();
            } catch (Exception e) {
                result = message(e, "The action could not be completed.");
            }
            String done = result;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                updating = false;
                status = done;
                showHome();
            });
        });
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(20 * getResources().getDisplayMetrics().density);
        view.setPadding(padding, padding, padding, padding);
        return view;
    }

    private Button addButton(LinearLayout layout, String text, Runnable click) {
        Button button = new Button(this);
        button.setText(text);
        button.setOnClickListener(view -> click.run());
        layout.addView(button);
        return button;
    }

    private EditText secretField(String hint, int max) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setSingleLine();
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(max)});
        field.setSaveEnabled(false);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        return field;
    }

    private static String message(Exception e, String fallback) {
        return e instanceof IOException && e.getMessage() != null ? e.getMessage() : fallback;
    }

    private void toast(String text) {
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show();
    }

    private static void close(ChatGpt.SignIn attempt) {
        if (attempt != null) {
            try { attempt.close(); } catch (IOException ignored) { }
        }
    }

    @Override public void onBackPressed() {
        if (tool == null) super.onBackPressed();
        else if (!tool.busy) showHome();
    }

    @Override protected void onDestroy() {
        close(pendingSignIn);
        if (tool != null) destroyTool();
        deleteCameraFile();
        authWorker.shutdownNow();
        worker.shutdownNow();
        super.onDestroy();
    }

    private final class ToolScreen {
        final PageFeed.Page page;
        final WebView view = new WebView(MainActivity.this);
        final SharedPreferences data;
        final String host;
        final String origin;
        boolean busy;
        byte[] photo;
        JavaScriptReplyProxy photoReply;
        String photoId;

        ToolScreen(PageFeed.Page page) {
            this.page = page;
            data = getSharedPreferences("tool-" + page.id(), MODE_PRIVATE);
            host = page.id() + ".ai-pages.invalid";
            origin = "https://" + host;
        }
    }
}
