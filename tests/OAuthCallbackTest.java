package app.aipages;

import java.io.IOException;

public class OAuthCallbackTest {
    public static void main(String[] args) throws Exception {
        String redirect = "http://127.0.0.1:34567/auth/callback";
        String query = "?code=one%2Btwo&state=current&client_id=issued";
        OAuthCallback result = OAuthCallback.parse(" " + redirect + query + " ", redirect, "current");
        if (!"one+two".equals(result.code()) || !"issued".equals(result.client())) throw new AssertionError();
        if (!"access_denied".equals(OAuthCallback.parse(
                redirect + "?state=current&error=access_denied", redirect, "current").error())) {
            throw new AssertionError("Browser rejection was not preserved");
        }
        for (String wrong : new String[] {
                redirect + "?code=abc&state=old", redirect + query + "&%73tate=old",
                redirect + query + "#fragment", redirect.replace("34567", "34568") + query,
                redirect.replace("127.0.0.1", "localhost") + query,
                redirect.replace("/auth/callback", "/callback") + query, redirect + "?code=%zz&state=current"
        }) {
            try {
                OAuthCallback.parse(wrong, redirect, "current");
                throw new AssertionError("Accepted an invalid callback");
            } catch (IOException expected) {
                // Expected.
            }
        }
        System.out.println("OAuth callback regression cases passed");
    }
}
