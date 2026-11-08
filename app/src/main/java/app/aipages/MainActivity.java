package app.aipages;

import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.core.content.FileProvider;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
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
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends ComponentActivity {
    private static final int PICK_PHOTO = 1, TAKE_PHOTO = 2, EXPORT = 3, IMPORT = 4, IMPORT_CATALOG = 5;
    private static final int MAX_DATA = 350_000;
    private static final String FINISH_SIGN_IN = "app.aipages.FINISH_SIGN_IN";
    private static final String SIGN_IN_ACTION_ID = "app.aipages.SIGN_IN_ACTION_ID";
    private static final String CSP = "default-src 'none'; script-src 'self'; style-src 'self'; "
            + "base-uri 'none'; frame-ancestors 'none'; form-action 'none'";
    private enum Route { HOME, SETTINGS, RECOVERY, TOOL }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService authWorker = Executors.newSingleThreadExecutor();
    private PageFeed feed;
    private ChatGpt chat;
    private ChatGpt.SignIn pendingSignIn;
    private PendingIntent finishSignInAction;
    private String signInActionId;
    private boolean signingIn;
    private boolean updating;
    private String status = "";
    private Route route = Route.HOME;
    private WebScreen activePage;
    private File cameraFile;
    private final OnBackPressedCallback backToHome = new OnBackPressedCallback(false) {
        @Override public void handleOnBackPressed() {
            if (activePage == null || !activePage.busy) showHome();
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getOnBackPressedDispatcher().addCallback(this, backToHome);
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
        if (FINISH_SIGN_IN.equals(getIntent().getAction())) {
            // A recreated process has no live OAuth attempt or PKCE verifier.
            setIntent(new Intent(this, MainActivity.class));
            status = "This sign-in attempt ended. Start sign-in again.";
        }
        PageFeed.Catalog catalog = renderLauncher();
        if ((catalog == null || catalog.home() == null) && !feed.hasKeyWarning()) {
            update(feed::refresh);
        } else {
            worker.execute(() -> {
                try {
                    feed.checkSigningKey();
                } catch (CatalogKey.Changed changed) {
                    runOnUiThread(() -> {
                        if (!isDestroyed() && !updating) renderLauncher();
                    });
                } catch (Exception ignored) {
                    // Cached tools stay available offline. Refresh reports network errors.
                }
            });
        }
    }

    private void showHome() {
        route = Route.HOME;
        renderLauncher();
    }

    private void showRecovery() {
        route = Route.RECOVERY;
        renderLauncher();
    }

    private PageFeed.Catalog renderLauncher() {
        if (route == Route.TOOL) return null;
        if (activePage != null) destroyPage();
        backToHome.setEnabled(route != Route.HOME);
        PageFeed.Catalog catalog = null;
        try {
            catalog = feed.cachedCatalog();
        } catch (Exception ignored) {
            // Keep the native update controls available if the cache cannot be read.
        }
        if (catalog != null && route != Route.RECOVERY && !feed.hasKeyWarning()) {
            PageFeed.Page page = route == Route.HOME ? catalog.home() : catalog.settings();
            if (page != null && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                openPage(page, route, catalog);
                return catalog;
            }
        }
        renderRecovery(catalog);
        return catalog;
    }

    private void renderRecovery(PageFeed.Catalog catalog) {
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = column();
        scroll.addView(body);
        TextView title = new TextView(this);
        title.setText("AI Pages recovery");
        title.setTextSize(30);
        body.addView(title);
        if (feed.hasKeyWarning()) {
            TextView warning = new TextView(this);
            warning.setText("PUBLIC KEY HAS CHANGED\nTool updates are paused. Make sure you are the one who changed "
                    + "the key. Get it from your own signing setup, then update it in Configure updates.");
            warning.setTextSize(20);
            warning.setTypeface(null, Typeface.BOLD);
            warning.setTextColor(Color.WHITE);
            warning.setBackgroundColor(Color.rgb(160, 20, 20));
            int inset = Math.round(12 * getResources().getDisplayMetrics().density);
            warning.setPadding(inset, inset, inset, inset);
            warning.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE);
            body.addView(warning);
        }
        TextView message = new TextView(this);
        message.setText(status);
        message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        body.addView(message);
        if (route == Route.RECOVERY && catalog != null && catalog.home() != null && !feed.hasKeyWarning()) {
            addButton(body, "Open Home", this::showHome);
        }
        if (pendingSignIn != null) {
            addButton(body, "Paste redirect URL", this::pasteRedirect);
            addButton(body, "Stop sign-in", () -> close(pendingSignIn));
        } else if (!signingIn) {
            if (chat.connected()) {
                addButton(body, "Test ChatGPT connection", this::testChatConnection).setEnabled(!updating);
                addButton(body, "Disconnect ChatGPT", this::disconnectChat).setEnabled(!updating);
            } else {
                addButton(body, "Continue with ChatGPT", this::beginSignIn).setEnabled(!updating);
            }
        }
        boolean canUpdate = !updating && !signingIn;
        addButton(body, "Configure updates", this::configureFeed).setEnabled(canUpdate);
        addButton(body, updating ? "Working…" : "Refresh catalog", () -> update(feed::refresh)).setEnabled(canUpdate);
        addButton(body, "Import signed catalog", this::chooseCatalog)
                .setEnabled(canUpdate && !feed.hasKeyWarning());
        if (feed.hasPrevious()) {
            addButton(body, "Restore previous bundle", () -> update(() -> {
                feed.restorePrevious();
                return "Restored the previous bundle.";
            })).setEnabled(canUpdate);
        }
        if (catalog == null || catalog.home() == null) {
            TextView help = new TextView(this);
            help.setText(catalog == null ? "Could not open the cached catalog. Check the signing key and refresh."
                    : "Refresh the catalog to download Home and Settings.");
            body.addView(help);
        }
        if (catalog != null) {
            for (PageFeed.Page page : catalog.pages().values()) {
                addButton(body, page.title(), () -> openTool(page)).setEnabled(!signingIn);
            }
        }
        setContentView(scroll);
    }

    private void destroyPage() {
        WebView view = activePage.view;
        if (view.getParent() instanceof ViewGroup parent) parent.removeView(view);
        view.destroy();
        activePage = null;
    }

    private void chooseCatalog() {
        try {
            startActivityForResult(document(Intent.ACTION_OPEN_DOCUMENT, "*/*"), IMPORT_CATALOG);
        } catch (ActivityNotFoundException e) {
            toast("No document picker is available.");
        }
    }

    private void configureFeed() {
        LinearLayout fields = column();
        EditText token = secretField("GitHub read token (optional)", 256);
        EditText key = new EditText(this);
        key.setHint("Catalog signing public key");
        key.setText(feed.publicKey());
        fields.addView(token);
        fields.addView(key);
        new AlertDialog.Builder(this).setTitle("Tool updates")
                .setMessage("AI Pages verifies the published key against the key in this app or one you have saved. Enter a replacement only if you changed it yourself. Leave the GitHub token blank for the public catalog.")
                .setView(fields).setNegativeButton("Cancel", null).setPositiveButton("Save", (dialog, button) -> {
                    String credential = token.getText().toString().trim();
                    String publicKey = key.getText().toString().trim();
                    token.setText("");
                    update(() -> {
                        feed.configure(credential, publicKey);
                        return "Update settings saved. Refresh the catalog.";
                    });
                }).show();
    }

    private void beginSignIn() {
        if (signingIn || updating) return;
        signingIn = true;
        status = "Opening ChatGPT sign-in…";
        renderLauncher();
        authWorker.execute(() -> {
            try (ChatGpt.SignIn attempt = chat.beginSignIn()) {
                runOnUiThread(() -> {
                    if (isDestroyed()) {
                        close(attempt);
                        return;
                    }
                    pendingSignIn = attempt;
                    status = "If the browser times out at 127.0.0.1, tap the Finish sign-in checkmark in the browser.";
                    renderLauncher();
                    try {
                        new CustomTabsIntent.Builder().setShareState(CustomTabsIntent.SHARE_STATE_OFF)
                                .setActionButton(finishSignInIcon(), "Finish sign-in", createFinishSignInAction(), true)
                                .build().launchUrl(this, attempt.browserUrl);
                    } catch (ActivityNotFoundException e) {
                        close(attempt);
                    }
                });
                attempt.finish(() -> runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    pendingSignIn = null;
                    clearFinishSignInAction();
                    status = "Browser response received. Connecting to OpenAI…";
                    renderLauncher();
                }));
                finishSignIn("Connected. Tools can use your ChatGPT plan.");
            } catch (Exception e) {
                finishSignIn(message(e, "Could not connect to ChatGPT."));
            }
        });
    }

    private PendingIntent createFinishSignInAction() {
        signInActionId = UUID.randomUUID().toString();
        Intent action = new Intent(this, MainActivity.class).setAction(FINISH_SIGN_IN)
                .putExtra(SIGN_IN_ACTION_ID, signInActionId)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        // Custom Tabs supplies the current URL as the intent data.
        finishSignInAction = PendingIntent.getActivity(this, 0, action,
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_CANCEL_CURRENT);
        return finishSignInAction;
    }

    private void clearFinishSignInAction() {
        if (finishSignInAction != null) finishSignInAction.cancel();
        finishSignInAction = null;
        signInActionId = null;
    }

    private Bitmap finishSignInIcon() {
        int size = Math.round(24 * getResources().getDisplayMetrics().density);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.scale(size / 24f, size / 24f);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        paint.setStrokeWidth(2.5f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLines(new float[]{4, 12, 9, 17, 9, 17, 20, 6}, paint);
        return bitmap;
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!FINISH_SIGN_IN.equals(intent.getAction()) || pendingSignIn == null || signInActionId == null
                || !signInActionId.equals(intent.getStringExtra(SIGN_IN_ACTION_ID))) {
            return;
        }
        try {
            Uri url = intent.getData();
            if (url == null) throw new IOException("The browser did not send a redirect URL.");
            pendingSignIn.submitRedirect(url.toString());
            status = "Browser response received. Connecting to OpenAI…";
        } catch (IOException e) {
            status = "The browser did not send the current sign-in redirect. Return to the browser and tap the "
                    + "checkmark at 127.0.0.1, or use Paste redirect URL.";
        }
        renderLauncher();
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
            clearFinishSignInAction();
            signingIn = false;
            status = result;
            renderLauncher();
        });
    }

    private void testChatConnection() {
        new AlertDialog.Builder(this).setTitle("Test ChatGPT connection?")
                .setMessage("AI Pages will send a short test prompt using your ChatGPT plan.")
                .setNegativeButton("Cancel", null).setPositiveButton("Send", (dialog, button) -> update(() ->
                        "ChatGPT replied: " + chat.run("Reply with the single word Connected.", null))).show();
    }

    private void disconnectChat() {
        new AlertDialog.Builder(this).setTitle("Disconnect ChatGPT?")
                .setNegativeButton("Cancel", null).setPositiveButton("Disconnect", (dialog, button) -> update(() -> {
                    chat.disconnect();
                    return "ChatGPT disconnected on this phone.";
                })).show();
    }

    private void openTool(PageFeed.Page page) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            status = "Update Android System WebView to open tools.";
            renderLauncher();
            return;
        }
        openPage(page, Route.TOOL, null);
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    private void openPage(PageFeed.Page page, Route destination, PageFeed.Catalog catalog) {
        if (activePage != null) destroyPage();
        route = destination;
        WebScreen screen = new WebScreen(page, destination, catalog);
        activePage = screen;
        backToHome.setEnabled(destination != Route.HOME);
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
                    String name = path != null && path.startsWith("/") ? path.substring(1) : "";
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

            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (destination != Route.TOOL && request.isForMainFrame() && activePage == screen) {
                    status = "Could not open " + page.title() + ".";
                    showRecovery();
                }
            }

            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                if (activePage == screen) {
                    status = "The page stopped responding. Try opening it again or restore the previous catalog.";
                    showRecovery();
                }
                return true;
            }
        });
        WebViewCompat.addWebMessageListener(view, "AiPages", Collections.singleton(screen.origin),
                (webView, message, origin, main, reply) -> {
                    if (main && screen.origin.equals(origin.toString()) && activePage == screen) {
                        onBridgeMessage(screen, message.getData(), reply);
                    }
                });
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        LinearLayout bar = new LinearLayout(this);
        if (destination == Route.TOOL) {
            addButton(bar, "Tools", () -> { if (!screen.busy) showHome(); });
            addButton(bar, "Backup", () -> backup(screen));
            addButton(bar, "Restore", () -> restore(screen));
        } else {
            bar.setGravity(Gravity.END);
            addButton(bar, "Recovery", this::showRecovery);
        }
        layout.addView(bar);
        layout.addView(view, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(layout);
        view.loadUrl(screen.origin + "/index.html");
    }

    private void onBridgeMessage(WebScreen screen, String data, JavaScriptReplyProxy proxy) {
        if (data == null || data.length() > 6 * MAX_DATA + 10_000) return;
        String id = "";
        try {
            JSONObject request = new JSONObject(data);
            id = request.getString("id");
            if (!id.matches("[0-9]{1,12}")) return;
            if (screen.route != Route.TOOL) {
                onLauncherMessage(screen, request, proxy, id);
                return;
            }
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
                    if (!chat.connected()) throw new IOException("Connect ChatGPT in AI Pages Settings first.");
                    String prompt = request.getString("prompt");
                    if (prompt.trim().isEmpty() || prompt.length() > 8_000) throw new IOException("Invalid prompt.");
                    byte[] photo = request.optBoolean("image") ? screen.photo : null;
                    if (request.optBoolean("image") && photo == null) throw new IOException("Choose a photo first.");
                    confirmRun(screen, proxy, id, prompt, photo);
                }
                default -> throw new IOException("Unsupported tool action.");
            }
        } catch (Exception e) {
            reply(proxy, id, null, message(e, "Could not complete the action."));
        }
    }

    private void onLauncherMessage(WebScreen screen, JSONObject request, JavaScriptReplyProxy proxy, String id)
            throws Exception {
        String method = request.getString("method");
        if (screen.route == Route.HOME) {
            switch (method) {
                case "home.services" -> {
                    JSONArray services = new JSONArray();
                    for (PageFeed.Page page : screen.catalog.pages().values()) {
                        services.put(new JSONObject().put("id", page.id()).put("title", page.title()));
                    }
                    reply(proxy, id, services, null);
                }
                case "home.openTool" -> {
                    if (signingIn) throw new IOException("Finish connecting ChatGPT in Settings first.");
                    PageFeed.Page page = screen.catalog.pages().get(request.getString("serviceId"));
                    if (page == null) throw new IOException("This tool is not in the downloaded catalog.");
                    reply(proxy, id, true, null);
                    openTool(page);
                }
                case "home.openSettings" -> {
                    reply(proxy, id, true, null);
                    route = Route.SETTINGS;
                    renderLauncher();
                }
                default -> throw new IOException("Unsupported Home action.");
            }
            return;
        }

        if ("settings.state".equals(method)) {
            JSONObject state = new JSONObject().put("connected", chat.connected()).put("signingIn", signingIn)
                    .put("canFinishSignIn", pendingSignIn != null).put("updating", updating).put("status", status);
            reply(proxy, id, state, null);
            return;
        }
        if ("settings.openHome".equals(method)) {
            reply(proxy, id, true, null);
            showHome();
            return;
        }
        if ("settings.pasteRedirect".equals(method) || "settings.stopSignIn".equals(method)) {
            if (pendingSignIn == null) throw new IOException("There is no sign-in attempt to finish.");
            if ("settings.pasteRedirect".equals(method)) pasteRedirect();
            else close(pendingSignIn);
            reply(proxy, id, true, null);
            return;
        }
        if (updating || signingIn) throw new IOException("Finish the current action first.");

        switch (method) {
            case "settings.signIn" -> {
                if (chat.connected()) throw new IOException("ChatGPT is already connected.");
                reply(proxy, id, true, null);
                beginSignIn();
                return;
            }
            case "settings.testConnection" -> testChatConnection();
            case "settings.disconnect" -> disconnectChat();
            case "settings.refresh" -> {
                reply(proxy, id, true, null);
                update(feed::refresh);
                return;
            }
            case "settings.importCatalog" -> chooseCatalog();
            case "settings.configureUpdates" -> configureFeed();
            default -> throw new IOException("Unsupported Settings action.");
        }
        reply(proxy, id, true, null);
    }

    private void confirmRun(WebScreen screen, JavaScriptReplyProxy proxy, String id, String prompt, byte[] photo) {
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

    private void pickPhoto(WebScreen screen, JavaScriptReplyProxy proxy, String id) {
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
        Uri uri = data == null ? null : data.getData();
        if (request == IMPORT_CATALOG) {
            if (result == RESULT_OK && uri != null) {
                update(() -> {
                    try (InputStream input = getContentResolver().openInputStream(uri)) {
                        return feed.importCatalog(input);
                    }
                });
            }
            return;
        }
        WebScreen screen = route == Route.TOOL ? activePage : null;
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

    private void backup(WebScreen screen) {
        if (screen.busy) return;
        chooseBackup(screen, document(Intent.ACTION_CREATE_DOCUMENT, "application/json")
                .putExtra(Intent.EXTRA_TITLE, screen.page.id() + "-backup.json"), EXPORT);
    }

    private void restore(WebScreen screen) {
        if (screen.busy) return;
        new AlertDialog.Builder(this).setTitle("Replace this tool’s local data?")
                .setMessage("Restoring a backup replaces the current data for " + screen.page.title() + ".")
                .setNegativeButton("Cancel", null).setPositiveButton("Choose backup", (dialog, button) ->
                        chooseBackup(screen, document(Intent.ACTION_OPEN_DOCUMENT, "application/json"), IMPORT)).show();
    }

    private void chooseBackup(WebScreen screen, Intent intent, int request) {
        try {
            screen.busy = true;
            startActivityForResult(intent, request);
        } catch (ActivityNotFoundException e) {
            screen.busy = false;
            toast("No file picker is available.");
        }
    }

    private void transfer(WebScreen screen, int request, Uri uri) {
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
            if (isDestroyed() || activePage != screen) return;
            screen.busy = false;
            if (request == IMPORT && "Backup restored.".equals(notification)) {
                openTool(screen.page);
            }
            toast(notification);
        });
    }

    private static void saveState(WebScreen screen, String state) throws Exception {
        if (state.getBytes(StandardCharsets.UTF_8).length > MAX_DATA) throw new IOException("Tool storage limit reached.");
        JSONObject value = new JSONObject(state);
        if (!screen.data.edit().putString("state", value.toString()).commit()) {
            throw new IOException("Could not save tool data.");
        }
    }

    private void finishPhoto(WebScreen screen, boolean selected, String error) {
        JavaScriptReplyProxy proxy = screen.photoReply;
        screen.photoReply = null;
        if (proxy != null) complete(screen, proxy, screen.photoId, selected, error);
    }

    private void complete(WebScreen screen, JavaScriptReplyProxy proxy, String id, Object value, String error) {
        screen.busy = false;
        if (!isDestroyed() && activePage == screen) reply(proxy, id, value, error);
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
        status = "Working…";
        renderLauncher();
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
                renderLauncher();
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

    @Override protected void onDestroy() {
        clearFinishSignInAction();
        close(pendingSignIn);
        if (activePage != null) destroyPage();
        deleteCameraFile();
        authWorker.shutdownNow();
        worker.shutdownNow();
        super.onDestroy();
    }

    private final class WebScreen {
        final PageFeed.Page page;
        final Route route;
        final PageFeed.Catalog catalog;
        final WebView view = new WebView(MainActivity.this);
        final SharedPreferences data;
        final String host;
        final String origin;
        boolean busy;
        byte[] photo;
        JavaScriptReplyProxy photoReply;
        String photoId;

        WebScreen(PageFeed.Page page, Route route, PageFeed.Catalog catalog) {
            this.page = page;
            this.route = route;
            this.catalog = catalog;
            data = route == Route.TOOL ? getSharedPreferences("tool-" + page.id(), MODE_PRIVATE) : null;
            host = page.id() + (route == Route.TOOL ? ".ai-pages.invalid" : ".shell.ai-pages.invalid");
            origin = "https://" + host;
        }
    }
}
