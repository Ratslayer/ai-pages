package app.aipages;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Base64;

public final class CatalogKeyTest {
    public static void main(String[] args) throws Exception {
        String published = Files.readString(Path.of("app/src/main/assets/ai-pages-public.txt")).trim();
        var trusted = CatalogKey.parse(published);
        if (CatalogKey.requireMatch(trusted, "  " + published + "\n") != trusted) {
            throw new AssertionError("Matching keys should retain the trusted instance.");
        }

        String replacement = Base64.getEncoder().encodeToString(
                KeyPairGenerator.getInstance("RSA").generateKeyPair().getPublic().getEncoded());
        expectChanged(trusted, replacement);
        expectChanged(trusted, published + "%");
        CatalogKey.requireMatch(CatalogKey.parse(replacement), replacement);
        System.out.println("Catalog key matching and explicit rotation tests passed");
    }

    private static void expectChanged(java.security.PublicKey trusted, String published) throws IOException {
        try {
            CatalogKey.requireMatch(trusted, published);
            throw new AssertionError("A changed or invalid key must be rejected.");
        } catch (CatalogKey.Changed expected) {
            // Expected.
        }
    }
}
