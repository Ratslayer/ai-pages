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
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.net.ssl.HttpsURLConnection;

final class PageFeed {
    private static final String ENDPOINT =
            "https://api.github.com/repos/Ratslayer/ai-pages/contents/feed/catalog.json?ref=main";
    private static final int MAX_SIZE = 900_000;
    static final List<String> ASSETS = Arrays.asList("index.html", "app.js", "style.css");

    record Page(String id, String title, Map<String, String> files) {}
    private record Catalog(String revision, Map<String, Page> pages) {}

    private final SharedPreferences settings;
    private final AtomicFile current;
    private final AtomicFile previous;

    PageFeed(Context context) {
        settings = context.getSharedPreferences("page-feed", Context.MODE_PRIVATE);
        current = new AtomicFile(new File(context.getFilesDir(), "catalog.json"));
        previous = new AtomicFile(new File(context.getFilesDir(), "previous-catalog.json"));
    }

    String publicKey() {
        return settings.getString("public-key", "");
    }

    void configure(String token, String publicKey) throws Exception {
        if (token.isEmpty() && !settings.contains("token")) throw new IOException("Enter a GitHub read token.");
        if (!token.isEmpty() && !token.matches("[A-Za-z0-9_]{20,256}")) {
            throw new IOException("Enter a valid GitHub token.");
        }
        parseKey(publicKey);
        SharedPreferences.Editor changes = settings.edit().putString("public-key", publicKey);
        if (!token.isEmpty()) {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, ChatGpt.secretKey());
            changes.putString("token-iv", encode(cipher.getIV()));
            changes.putString("token", encode(cipher.doFinal(token.getBytes(StandardCharsets.UTF_8))));
        }
        if (!changes.commit()) throw new IOException("Could not save the update settings.");
    }

    Map<String, Page> cachedPages() throws Exception {
        if (publicKey().isEmpty()) return Collections.emptyMap();
        try {
            return verify(current.readFully()).pages();
        } catch (Exception ignored) {
            if (previous.getBaseFile().exists()) return verify(previous.readFully()).pages();
            if (current.getBaseFile().exists()) throw ignored;
            return Collections.emptyMap();
        }
    }

    boolean hasPrevious() {
        return previous.getBaseFile().exists();
    }

    synchronized void restorePrevious() throws Exception {
        byte[] old = previous.readFully();
        verify(old);
        write(current, old);
        previous.delete();
    }

    synchronized String refresh() throws Exception {
        String token = settings.getString("token", null);
        if (token == null || publicKey().isEmpty()) throw new IOException("Configure updates first.");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, ChatGpt.secretKey(),
                new GCMParameterSpec(128, decode(settings.getString("token-iv", ""))));
        String bearer = new String(cipher.doFinal(decode(token)), StandardCharsets.UTF_8);
        HttpsURLConnection connection = (HttpsURLConnection) new URL(ENDPOINT).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(15_000);
        connection.setRequestProperty("Authorization", "Bearer " + bearer);
        connection.setRequestProperty("Accept", "application/vnd.github.raw+json");
        byte[] data;
        try {
            int status = connection.getResponseCode();
            if (status != 200) {
                throw new IOException("GitHub returned HTTP " + status + ". Check your read token and feed publication.");
            }
            try (InputStream input = connection.getInputStream()) {
                data = read(input, MAX_SIZE);
            }
        } finally {
            connection.disconnect();
        }
        Catalog incoming = verify(data);
        if (current.getBaseFile().exists()) {
            byte[] old = current.readFully();
            Catalog installed = null;
            try {
                installed = verify(old);
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

    private Catalog verify(byte[] envelope) throws Exception {
        if (envelope.length > MAX_SIZE) throw new IOException("The tool catalog is too large.");
        JSONObject outer = new JSONObject(new String(envelope, StandardCharsets.UTF_8));
        byte[] payload = decode(outer.getString("payload"));
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(parseKey(publicKey()));
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

    private static PublicKey parseKey(String value) throws IOException {
        try {
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(decode(value)));
        } catch (Exception e) {
            throw new IOException("Enter the catalog signing public key (base64 DER).");
        }
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
