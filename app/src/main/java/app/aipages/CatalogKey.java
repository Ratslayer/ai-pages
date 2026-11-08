package app.aipages;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

final class CatalogKey {
    static PublicKey parse(String text) throws IOException {
        try {
            return parse(Base64.getDecoder().decode(text.replaceAll("\\s+", "")));
        } catch (IllegalArgumentException e) {
            throw new IOException("Enter the catalog signing public key (base64 DER).", e);
        }
    }

    static PublicKey parse(byte[] der) throws IOException {
        try {
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (GeneralSecurityException e) {
            throw new IOException("Enter the catalog signing public key (base64 DER).", e);
        }
    }

    static PublicKey requireMatch(PublicKey trusted, String publishedText) throws Changed {
        PublicKey published;
        try {
            published = parse(publishedText);
        } catch (IOException e) {
            throw new Changed();
        }
        if (!Arrays.equals(trusted.getEncoded(), published.getEncoded())) throw new Changed();
        return trusted;
    }

    static final class Changed extends IOException {
        Changed() {
            super("The published public key differs from the trusted key. Tool updates are paused.");
        }
    }
}
