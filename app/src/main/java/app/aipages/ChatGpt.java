package app.aipages;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.net.ssl.HttpsURLConnection;

final class ChatGpt {
    private static final String AUTH = "https://auth.openai.com";
    private static final String API = "https://api.openai.com/v1";
    private static final String PLAN_SCOPE = "chatgpt.tokens.use.direct";
    private static final String SCOPES = "openid profile email offline_access resource.invoke " + PLAN_SCOPE;
    static final String CALLBACK = "/auth/callback";
    static final int MAX_CALLBACK_LENGTH = 8_192;
    private static final String KEY_ALIAS = "chatgpt-session";
    private static final int B64 = Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SharedPreferences prefs;

    ChatGpt(Context context) {
        prefs = context.getSharedPreferences("chatgpt", Context.MODE_PRIVATE);
    }

    boolean connected() {
        return prefs.contains("session");
    }

    synchronized void disconnect() {
        prefs.edit().remove("session").apply();
    }

    SignIn beginSignIn() throws Exception {
        String host = prefs.getString("host", null);
        if (host == null) {
            host = "urn:uuid:" + UUID.randomUUID();
            if (!prefs.edit().putString("host", host).commit()) {
                throw new IOException("Could not save the installation ID.");
            }
        }
        return new SignIn(host, prefs.getString("client", "dynamic_agent_client"));
    }

    final class SignIn implements AutoCloseable {
        private final ServerSocket listener;
        private final String state = randomValue();
        private final String nonce = randomValue();
        private final String verifier = randomValue();
        private final AtomicReference<OAuthCallback> receivedCallback = new AtomicReference<>();
        private final long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10);
        private final String redirect;
        private final String client;
        final Uri browserUrl;

        SignIn(String host, String client) throws Exception {
            this.client = client;
            String challenge = encode(MessageDigest.getInstance("SHA-256").digest(bytes(verifier)));
            listener = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
            redirect = "http://127.0.0.1:" + listener.getLocalPort() + CALLBACK;
            Uri.Builder builder = Uri.parse(AUTH + "/api/accounts/authorize").buildUpon()
                    .appendQueryParameter("client_id", client)
                    .appendQueryParameter("ext_agent_host_id", host)
                    .appendQueryParameter("response_type", "code")
                    .appendQueryParameter("redirect_uri", redirect)
                    .appendQueryParameter("scope", SCOPES)
                    .appendQueryParameter("resource", API)
                    .appendQueryParameter("state", state)
                    .appendQueryParameter("nonce", nonce)
                    .appendQueryParameter("code_challenge_method", "S256")
                    .appendQueryParameter("code_challenge", challenge);
            if ("dynamic_agent_client".equals(client)) {
                builder.appendQueryParameter("agent_name_hint", "AI Pages");
            } else {
                builder.appendQueryParameter("prompt", "consent");
            }
            browserUrl = builder.build();
        }

        void finish(Runnable onCallback) throws Exception {
            OAuthCallback callback = awaitCallback();
            listener.close();
            onCallback.run();
            String code = callback.code();
            if (callback.error() != null || code == null || code.trim().isEmpty()) {
                throw new IOException("ChatGPT sign-in was canceled or declined.");
            }
            String issuedClient = callback.client();
            if (!"dynamic_agent_client".equals(client)) {
                if (issuedClient != null && !client.equals(issuedClient)) {
                    throw new IOException("The ChatGPT client did not match this sign-in.");
                }
                issuedClient = client;
            }
            if (issuedClient == null || issuedClient.trim().isEmpty() || "dynamic_agent_client".equals(issuedClient)) {
                throw new IOException("OpenAI did not return a client ID.");
            }

            JSONObject tokens;
            try {
                tokens = exchange(form("grant_type", "authorization_code",
                        "client_id", issuedClient, "code", code, "code_verifier", verifier,
                        "redirect_uri", redirect, "resource", API));
                verifyIdentity(tokens.getString("id_token"), issuedClient, nonce);
            } catch (SocketTimeoutException e) {
                throw new IOException("The browser returned successfully, but OpenAI did not respond in time.", e);
            }
            requirePlanScope(tokens.getString("scope"));
            saveSession(tokens, issuedClient);
        }

        void submitRedirect(String input) throws IOException {
            OAuthCallback callback = OAuthCallback.parse(input, redirect, state);
            if (callback.code() == null || callback.code().trim().isEmpty() || callback.error() != null) {
                throw new IOException("This URL does not contain a successful sign-in.");
            }
            if (!acceptCallback(callback)) throw new IOException("This sign-in is finishing or has expired.");
            listener.close();
        }

        private synchronized boolean acceptCallback(OAuthCallback callback) {
            return System.nanoTime() < deadline && !listener.isClosed()
                    && receivedCallback.compareAndSet(null, callback);
        }

        private OAuthCallback awaitCallback() throws IOException {
            while (receivedCallback.get() == null && System.nanoTime() < deadline) {
                int remaining = (int) TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                try {
                    listener.setSoTimeout(Math.max(1, remaining));
                    try (Socket socket = listener.accept()) {
                        try {
                            readBrowserCallback(socket);
                        } catch (IOException ignored) {
                            // Speculative browser connections must not end sign-in.
                        }
                    }
                } catch (SocketTimeoutException ignored) {
                    // A pre-opened browser socket may never send a request.
                } catch (SocketException e) {
                    if (listener.isClosed()) break;
                    throw e;
                }
            }
            OAuthCallback callback = receivedCallback.get();
            if (callback != null) return callback;
            if (listener.isClosed()) throw new IOException("Sign-in stopped. Start a fresh sign-in from the app.");
            throw new IOException("No valid browser response reached AI Pages. Start a fresh sign-in from the app.");
        }

        private void readBrowserCallback(Socket socket) throws IOException {
            socket.setSoTimeout(2_000);
            InputStream input = socket.getInputStream();
            String line = readHeaderLine(input);
            int headerCount = 0;
            while (!readHeaderLine(input).isEmpty() && ++headerCount < 40) {
                // Consume the browser's request headers before replying.
            }
            String[] parts = line.split(" ", 3);
            boolean valid = false;
            if (parts.length == 3 && "GET".equals(parts[0]) && parts[1].startsWith(CALLBACK + "?")) {
                try {
                    OAuthCallback callback = OAuthCallback.parse(
                            redirect.substring(0, redirect.length() - CALLBACK.length()) + parts[1],
                            redirect, state);
                    valid = (callback.error() != null || (callback.code() != null
                            && !callback.code().trim().isEmpty())) && acceptCallback(callback);
                } catch (IOException ignored) {
                    // Leave the listener open for the actual callback.
                }
            }
            String status = valid ? "200 OK" : "400 Bad Request";
            byte[] body = bytes(valid ? "Sign-in response received. Return to AI Pages."
                    : "This sign-in request is invalid or expired.");
            try {
                OutputStream out = socket.getOutputStream();
                out.write(bytes("HTTP/1.1 " + status + "\r\nContent-Type: text/plain; charset=utf-8"
                        + "\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nConnection: close"
                        + "\r\nContent-Length: " + body.length + "\r\n\r\n"));
                out.write(body);
                out.flush();
            } catch (IOException ignored) {
                // A tab can close after delivering the callback.
            }
        }

        @Override public synchronized void close() throws IOException {
            listener.close();
        }
    }

    private static String readHeaderLine(InputStream input) throws IOException {
        StringBuilder line = new StringBuilder();
        while (line.length() < MAX_CALLBACK_LENGTH) {
            int next = input.read();
            if (next == '\n' || next == -1) return line.toString();
            if (next != '\r') line.append((char) next);
        }
        throw new IOException("The sign-in callback was too large.");
    }

    private static void verifyIdentity(String jwt, String client, String nonce) throws Exception {
        String[] parts = jwt.split("\\.", -1);
        if (parts.length != 3) throw new IOException("OpenAI returned an invalid identity token.");
        JSONObject header = new JSONObject(new String(decode(parts[0]), StandardCharsets.UTF_8));
        if (!"RS256".equals(header.optString("alg"))) {
            throw new IOException("OpenAI returned an unsupported identity signature.");
        }
        JSONArray keys = requestJson(AUTH + "/.well-known/jwks.json", null, null, null).getJSONArray("keys");
        boolean verified = false;
        for (int i = 0; i < keys.length(); i++) {
            JSONObject key = keys.getJSONObject(i);
            if (!"RSA".equals(key.optString("kty")) || !header.getString("kid").equals(key.optString("kid"))) {
                continue;
            }
            RSAPublicKeySpec spec = new RSAPublicKeySpec(
                    new BigInteger(1, decode(key.getString("n"))), new BigInteger(1, decode(key.getString("e"))));
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(KeyFactory.getInstance("RSA").generatePublic(spec));
            signature.update(bytes(parts[0] + "." + parts[1]));
            verified = signature.verify(decode(parts[2]));
            break;
        }
        if (!verified) throw new IOException("Could not verify the OpenAI identity signature.");
        JSONObject claims = new JSONObject(new String(decode(parts[1]), StandardCharsets.UTF_8));
        Object aud = claims.get("aud");
        boolean matchesAudience = client.equals(aud);
        if (aud instanceof JSONArray audiences) {
            for (int i = 0; i < audiences.length(); i++) {
                matchesAudience |= client.equals(audiences.optString(i));
            }
            if (audiences.length() > 1) matchesAudience &= client.equals(claims.optString("azp"));
        }
        if (!AUTH.equals(claims.optString("iss")) || !matchesAudience
                || claims.optLong("exp") <= System.currentTimeMillis() / 1_000
                || !nonce.equals(claims.optString("nonce")) || claims.optString("sub").trim().isEmpty()) {
            throw new IOException("The OpenAI identity token did not match this sign-in.");
        }
    }

    private synchronized String accessToken() throws Exception {
        String encrypted = prefs.getString("session", null);
        if (encrypted == null) throw new IOException("Connect your ChatGPT plan first.");
        JSONObject session;
        try {
            byte[] payload = decode(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(128, payload, 0, 12));
            session = new JSONObject(new String(cipher.doFinal(payload, 12, payload.length - 12),
                    StandardCharsets.UTF_8));
        } catch (Exception e) {
            disconnect();
            throw new IOException("The saved sign-in could not be read. Connect again.");
        }
        if (session.getLong("expires_at") > System.currentTimeMillis() / 1_000 + 60) {
            return session.getString("access_token");
        }
        String client = session.getString("client_id");
        JSONObject refreshed;
        try {
            refreshed = exchange(form("grant_type", "refresh_token",
                    "client_id", client, "refresh_token", session.getString("refresh_token"), "resource", API));
        } catch (HttpFailure e) {
            if (e.code == 400 || e.code == 401) {
                disconnect();
                throw new IOException("Your ChatGPT session expired. Connect again.");
            }
            throw e;
        }
        requirePlanScope(refreshed.optString("scope", session.getString("scope")));
        refreshed.put("scope", refreshed.optString("scope", session.getString("scope")));
        refreshed.put("refresh_token", refreshed.optString("refresh_token", session.getString("refresh_token")));
        saveSession(refreshed, client);
        return refreshed.getString("access_token");
    }

    private void saveSession(JSONObject tokens, String client) throws Exception {
        JSONObject session = new JSONObject()
                .put("client_id", client)
                .put("scope", tokens.getString("scope"))
                .put("access_token", tokens.getString("access_token"))
                .put("refresh_token", tokens.getString("refresh_token"))
                .put("expires_at", System.currentTimeMillis() / 1_000 + tokens.getLong("expires_in"));
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey());
        byte[] iv = cipher.getIV();
        byte[] ciphertext = cipher.doFinal(bytes(session.toString()));
        byte[] payload = Arrays.copyOf(iv, iv.length + ciphertext.length);
        System.arraycopy(ciphertext, 0, payload, iv.length, ciphertext.length);
        if (!prefs.edit().putString("session", encode(payload)).putString("client", client).commit()) {
            throw new IOException("Could not save the ChatGPT session.");
        }
    }

    static synchronized SecretKey secretKey() throws Exception {
        KeyStore keystore = KeyStore.getInstance("AndroidKeyStore");
        keystore.load(null);
        if (keystore.getKey(KEY_ALIAS, null) instanceof SecretKey key) return key;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }

    private static void requirePlanScope(String scope) throws IOException {
        if (!Arrays.asList(scope.split("\\s+")).contains(PLAN_SCOPE)) {
            throw new IOException("Grant Use your ChatGPT plan in the browser to run AI tools.");
        }
    }

    private static JSONObject exchange(String body) throws Exception {
        return requestJson(AUTH + "/api/accounts/oauth/token", null, "application/x-www-form-urlencoded", body);
    }

    String run(String prompt, byte[] jpeg) throws Exception {
        String token = accessToken();
        JSONArray content = new JSONArray().put(new JSONObject().put("type", "input_text").put("text", prompt));
        if (jpeg != null) {
            content.put(new JSONObject().put("type", "input_image")
                    .put("image_url", "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP)));
        }
        JSONObject input = new JSONObject().put("role", "user").put("content", content);
        JSONObject body = new JSONObject().put("model", "gpt-6.1-sol")
                .put("store", false).put("stream", true).put("input", new JSONArray().put(input));
        HttpsURLConnection conn = connection(API + "/responses", token, "application/json", body.toString());
        try {
            checkResponse(conn);
            StringBuilder text = new StringBuilder();
            try (BufferedReader reader = reader(conn.getInputStream())) {
                for (String line; (line = reader.readLine()) != null; ) {
                    if (!line.startsWith("data: ")) continue;
                    String data = line.substring(6);
                    if ("[DONE]".equals(data)) break;
                    JSONObject event = new JSONObject(data);
                    switch (event.optString("type")) {
                        case "response.output_text.delta" -> {
                            text.append(event.getString("delta"));
                            if (text.length() > 32_768) throw new IOException("The ChatGPT response was too long.");
                        }
                        case "response.completed" -> { return text.toString(); }
                        case "response.failed" -> {
                            JSONObject response = event.optJSONObject("response");
                            throw apiError(0, response == null ? event : response);
                        }
                        case "error" -> throw apiError(0, event);
                        case "response.incomplete" -> throw new IOException("The ChatGPT response was incomplete.");
                    }
                }
            }
            throw new IOException("The ChatGPT response stopped before completing. Try again.");
        } finally {
            conn.disconnect();
        }
    }

    private static JSONObject requestJson(String url, String token, String contentType, String body) throws Exception {
        HttpsURLConnection conn = connection(url, token, contentType, body);
        try {
            checkResponse(conn);
            return new JSONObject(readBody(conn.getInputStream()));
        } finally {
            conn.disconnect();
        }
    }

    private static HttpsURLConnection connection(String url, String token, String contentType, String body)
            throws IOException {
        HttpsURLConnection conn = (HttpsURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(90_000);
        if (token != null) conn.setRequestProperty("Authorization", "Bearer " + token);
        if (body != null) {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", contentType);
            byte[] payload = bytes(body);
            conn.setFixedLengthStreamingMode(payload.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(payload);
            } catch (IOException e) {
                conn.disconnect();
                throw e;
            }
        }
        return conn;
    }

    private static void checkResponse(HttpsURLConnection conn) throws IOException {
        int code = conn.getResponseCode();
        if (code >= 200 && code < 300) return;
        JSONObject response;
        try {
            response = new JSONObject(readBody(conn.getErrorStream()));
        } catch (Exception ignored) {
            response = new JSONObject();
        }
        throw apiError(code, response);
    }

    private static HttpFailure apiError(int status, JSONObject response) {
        JSONObject error = response.optJSONObject("error");
        String code = error == null ? response.optString("code") : error.optString("code");
        String message = switch (code) {
            case "subscription_sharing_usage_limit_exceeded" -> "ChatGPT plan usage limit reached. Check ChatGPT Settings → Usage.";
            case "subscription_sharing_user_not_eligible" ->
                    "This ChatGPT account or workspace cannot use its plan in AI Pages.";
            case "subscription_sharing_usage_unavailable" -> "ChatGPT plan usage is temporarily unavailable.";
            case "subscription_sharing_unsupported_capability", "model_not_found" ->
                    "This ChatGPT account could not use the selected model for this request.";
            default -> status == 401 ? "ChatGPT rejected the sign-in. Disconnect and try connecting again."
                    : "OpenAI could not complete the request"
                    + (status == 0 ? "" : " (HTTP " + status + ")") + ".";
        };
        return new HttpFailure(status, message);
    }

    private static final class HttpFailure extends IOException {
        final int code;
        HttpFailure(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    private static BufferedReader reader(InputStream input) {
        return new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
    }

    private static String readBody(InputStream input) throws IOException {
        if (input == null) return "";
        try (BufferedReader in = reader(input)) {
            StringBuilder result = new StringBuilder();
            for (String line; (line = in.readLine()) != null; ) result.append(line);
            return result.toString();
        }
    }

    private static String form(String... fields) {
        Uri.Builder query = new Uri.Builder();
        for (int i = 0; i < fields.length; i += 2) query.appendQueryParameter(fields[i], fields[i + 1]);
        return query.build().getEncodedQuery();
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String encode(byte[] value) { return Base64.encodeToString(value, B64); }
    private static byte[] decode(String value) { return Base64.decode(value, B64); }

    private static String randomValue() {
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        return encode(random);
    }
}
