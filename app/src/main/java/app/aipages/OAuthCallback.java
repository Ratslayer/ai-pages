package app.aipages;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;

record OAuthCallback(String code, String client, String error) {
    static OAuthCallback parse(String input, String redirect, String state) throws IOException {
        try {
            String url = input.trim();
            if (url.length() > 8192 || !url.startsWith(redirect + "?")) {
                throw new IOException("Copy the full redirect URL from the current browser tab.");
            }
            URI uri = URI.create(url);
            if (uri.getRawFragment() != null || uri.getRawQuery() == null) {
                throw new IOException("This URL does not match the current sign-in.");
            }
            Map<String, String> values = new HashMap<>();
            for (String field : uri.getRawQuery().split("&")) {
                String[] pair = field.split("=", 2);
                String name = URLDecoder.decode(pair[0], "UTF-8");
                String value = URLDecoder.decode(pair.length == 2 ? pair[1] : "", "UTF-8");
                if (values.putIfAbsent(name, value) != null) {
                    throw new IOException("The redirect contains duplicate parameters.");
                }
            }
            if (!state.equals(values.get("state"))) {
                throw new IOException("This URL belongs to a different sign-in attempt.");
            }
            return new OAuthCallback(values.get("code"), values.get("client_id"), values.get("error"));
        } catch (IllegalArgumentException e) {
            throw new IOException("The redirect URL is invalid.");
        }
    }
}
