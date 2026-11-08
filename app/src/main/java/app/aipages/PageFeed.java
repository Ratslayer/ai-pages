package app.aipages;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.net.ssl.HttpsURLConnection;

final class PageFeed {
    private static final String CONTENTS = "https://api.github.com/repos/Ratslayer/ai-pages/contents/";
    private static final String CATALOG_PATH = "feed/catalog.json";
    private static final String KEY_FILE = "ai-pages-public.txt";
    private static final String KEY_PATH = "app/src/main/assets/" + KEY_FILE;
    private static final int MAX_KEY_SIZE = 4_096;
    private static final int MAX_SIZE = 900_000;
    static final List<String> ASSETS = Arrays.asList("index.html", "app.js", "style.css");

    record Page(String id, String title, Map<String, String> files) {}
    private record Catalog(String revision, Map<String, Page> pages) {}

    private final Context context;
    private final SharedPreferences settings;
    private final AtomicFile current;
    private final AtomicFile previous;

    PageFeed(Context context) {
        this.context = context.getApplicationContext();
        settings = context.getSharedPreferences("page-feed", Context.MODE_PRIVATE);
        current = new AtomicFile(new File(context.getFilesDir(), "catalog.json"));
        previous = new AtomicFile(new File(context.getFilesDir(), "previous-catalog.json"));
    }

    String publicKey() {
        return settings.getString("public-key", "");
    }

    boolean hasKeyWarning() {
        return settings.getBoolean("catalog-key-changed", false);
    }

    void configure(String token, String publicKey) throws Exception {
        if (!token.isEmpty() && !token.matches("[A-Za-z0-9_]{20,256}")) {
            throw new IOException("Enter a valid GitHub token.");
        }
        SharedPreferences.Editor changes = settings.edit();
        if (!publicKey.isEmpty() && !publicKey.equals(publicKey())) {
            PublicKey entered = CatalogKey.parse(publicKey);
            changes.putString("public-key", encode(entered.getEncoded()));
        }
        if (!token.isEmpty()) {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, ChatGpt.secretKey());
            changes.putString("token-iv", encode(cipher.getIV()));
            changes.putString("token", encode(cipher.doFinal(token.getBytes(StandardCharsets.UTF_8))));
        } else {
            changes.remove("token").remove("token-iv");
        }
        if (!changes.commit()) throw new IOException("Could not save the update settings.");
    }

    synchronized Map<String, Page> cachedPages() throws Exception {
        if (publicKey().isEmpty()) return Collections.emptyMap();
        PublicKey key = trustedKey();
        try {
            return verify(current.readFully(), key).pages();
        } catch (Exception ignored) {
            if (previous.getBaseFile().exists()) return verify(previous.readFully(), key).pages();
            if (current.getBaseFile().exists()) throw ignored;
            return Collections.emptyMap();
        }
    }

    boolean hasPrevious() {
        return previous.getBaseFile().exists();
    }

    synchronized void restorePrevious() throws Exception {
        byte[] old = previous.readFully();
        verify(old, trustedKey());
        write(current, old);
        previous.delete();
    }

    void checkSigningKey() throws Exception {
        checkedSigningKey(readToken());
    }

    String refresh() throws Exception {
        String bearer = readToken();
        PublicKey key = checkedSigningKey(bearer);
        byte[] data = download(CATALOG_PATH, bearer, MAX_SIZE);
        String result = install(data, key);
        if (!settings.edit().putString("public-key", encode(key.getEncoded()))
                .remove("catalog-key-changed").commit()) {
            throw new IOException("Could not save the trusted catalog key.");
        }
        return result;
    }

    private PublicKey trustedKey() throws IOException {
        String saved = publicKey();
        if (!saved.isEmpty()) {
            // Existing installs stored the original input using Android's Base64 decoder.
            try {
                return CatalogKey.parse(decode(saved));
            } catch (IllegalArgumentException e) {
                throw new IOException("The saved catalog signing public key is invalid.", e);
            }
        }
        try (InputStream bundled = context.getAssets().open(KEY_FILE)) {
            return CatalogKey.parse(new String(read(bundled, MAX_KEY_SIZE), StandardCharsets.UTF_8));
        }
    }

    private PublicKey checkedSigningKey(String bearer) throws Exception {
        PublicKey trusted = trustedKey();
        byte[] published = download(KEY_PATH, bearer, MAX_KEY_SIZE);
        try {
            return CatalogKey.requireMatch(trusted, new String(published, StandardCharsets.UTF_8));
        } catch (CatalogKey.Changed e) {
            settings.edit().putBoolean("catalog-key-changed", true).apply();
            throw e;
        }
    }

    private static byte[] download(String path, String bearer, int max) throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(CONTENTS + path + "?ref=main").openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(15_000);
        if (bearer != null) connection.setRequestProperty("Authorization", "Bearer " + bearer);
        connection.setRequestProperty("Accept", "application/vnd.github.raw+json");
        try {
            int status = connection.getResponseCode();
            if (status == 404 && CATALOG_PATH.equals(path)) {
                throw new IOException("Catalog not found. Check that the page workflow published it.");
            }
            if (status == 404) throw new IOException("The catalog public key could not be found on GitHub.");
            if (status != 200) throw new IOException("GitHub returned HTTP " + status + ".");
            try (InputStream input = connection.getInputStream()) {
                return read(input, max);
            }
        } finally {
            connection.disconnect();
        }
    }

    private String readToken() throws Exception {
        String token = settings.getString("token", null);
        if (token == null) return null;
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, ChatGpt.secretKey(),
                new GCMParameterSpec(128, decode(settings.getString("token-iv", ""))));
        return new String(cipher.doFinal(decode(token)), StandardCharsets.UTF_8);
    }

    private synchronized String install(byte[] data, PublicKey key) throws Exception {
        Catalog incoming = verify(data, key);
        if (current.getBaseFile().exists()) {
            byte[] old = current.readFully();
            Catalog installed = null;
            try {
                installed = verify(old, key);
            } catch (Exception ignored) {
                // The signing key may have changed. Only keep a verified previous catalog.
            }
            if (installed != null) {
                if (installed.revision().equals(incoming.revision())) return "Tools are up to date.";
                write(previous, old);
            }
        }
        write(current, data);
        return "Updated " + incoming.pages().size() + " tool(s).";
    }

    private Catalog verify(byte[] envelope, PublicKey key) throws Exception {
        if (envelope.length > MAX_SIZE) throw new IOException("The tool catalog is too large.");
        JSONObject outer = new JSONObject(new String(envelope, StandardCharsets.UTF_8));
        byte[] payload = decode(outer.getString("payload"));
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(key);
        verifier.update(payload);
        if (!verifier.verify(decode(outer.getString("signature")))) {
            throw new IOException("The tool catalog signature did not match your trusted key.");
        }
        JSONObject catalog = new JSONObject(new String(payload, StandardCharsets.UTF_8));
        if (catalog.getInt("bridgeVersion") != 1) throw new IOException("Update AI Pages to use this catalog.");
        JSONArray entries = catalog.getJSONArray("pages");
        Map<String, Page> pages = new LinkedHashMap<>();
        for (int index = 0; index < entries.length(); index++) {
            JSONObject item = entries.getJSONObject(index);
            String id = item.getString("id");
            String title = item.getString("title");
            if (!id.matches("[a-z][a-z0-9-]{0,39}") || title.trim().isEmpty() || title.length() > 80
                    || pages.containsKey(id)) {
                throw new IOException("The tool catalog contains an invalid page.");
            }
            Map<String, String> assets = new LinkedHashMap<>();
            for (String asset : ASSETS) assets.put(asset, item.getJSONObject("files").getString(asset));
            pages.put(id, new Page(id, title, assets));
        }
        return new Catalog(catalog.getString("revision"), pages);
    }

    static byte[] read(InputStream input, int max) throws IOException {
        if (input == null) throw new IOException("Could not open the file.");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int length; (length = input.read(buffer)) != -1; ) {
            if (bytes.size() + length > max) throw new IOException("The file exceeds the size limit.");
            bytes.write(buffer, 0, length);
        }
        return bytes.toByteArray();
    }

    private static void write(AtomicFile file, byte[] data) throws IOException {
        FileOutputStream output = file.startWrite();
        try {
            output.write(data);
            file.finishWrite(output);
        } catch (IOException e) {
            file.failWrite(output);
            throw e;
        }
    }

    private static String encode(byte[] bytes) { return Base64.encodeToString(bytes, Base64.NO_WRAP); }
    private static byte[] decode(String value) { return Base64.decode(value, Base64.DEFAULT); }
}
