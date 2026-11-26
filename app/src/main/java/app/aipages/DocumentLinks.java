package app.aipages;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Base64;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.UUID;

/** Opaque, tool-scoped handles to user-selected documents. Called on the file I/O executor. */
final class DocumentLinks {
    private final ContentResolver resolver;
    private final SharedPreferences links;
    private final String prefix;
    DocumentLinks(Context context, String tool) {
        resolver = context.getContentResolver();
        links = context.getSharedPreferences("document-links", Context.MODE_PRIVATE);
        prefix = tool + "/";
    }

    private ParcelFileDescriptor.AutoCloseInputStream open(Uri uri) throws IOException {
        ParcelFileDescriptor fd = resolver.openFileDescriptor(uri, "r");
        if (fd == null) throw new IOException("Could not open document.");
        return new ParcelFileDescriptor.AutoCloseInputStream(fd);
    }

    JSONObject link(Uri uri) throws Exception {
        if (!"content".equals(uri.getScheme())) throw new IOException("Choose a document in Android Files.");
        // Pipes and providers without a usable length require the private-copy fallback.
        try (ParcelFileDescriptor.AutoCloseInputStream stream = open(uri)) {
            FileChannel channel = stream.getChannel();
            if (channel.size() <= 0) return null;
            channel.position(0);
        } catch (IOException e) { return null; }
        boolean persistent = true;
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException e) { persistent = false; }
        String id = UUID.randomUUID().toString();
        if (!links.edit().putString(prefix + id, uri.toString()).commit())
            throw new IOException("Could not remember document.");
        return new JSONObject().put("handle",id).put("persistent",persistent);
    }

    Object perform(String method, JSONObject args) throws Exception {
        String id = args.getString("handle");
        if (!id.matches("[a-f0-9-]{36}")) throw new IOException("Invalid document handle.");
        String key = prefix + id;
        String saved = links.getString(key, null);
        if (saved == null) throw new IOException("Open the PDF again to reconnect it.");
        Uri uri = Uri.parse(saved);
        if (method.equals("files.unlink")) {
            if (!links.edit().remove(key).commit()) throw new IOException("Could not forget document.");
            if (!links.getAll().containsValue(saved)) {
                try { resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                catch (SecurityException ignored) { /* Session-only access. */ }
            }
            return true;
        }
        try (ParcelFileDescriptor.AutoCloseInputStream stream = open(uri)) {
            FileChannel channel = stream.getChannel();
            long size = channel.size();
            if (method.equals("files.linkSize")) return size;
            long offset = args.getLong("offset");
            int length = args.getInt("length");
            if (offset < 0 || offset > size || length <= 0 || length > ToolFiles.CHUNK)
                throw new IOException("Invalid document range.");
            ByteBuffer bytes = ByteBuffer.allocate((int)Math.min(length, size - offset));
            channel.position(offset);
            while (bytes.hasRemaining()) {
                if (channel.read(bytes) <= 0) throw new IOException("Document changed while reading.");
            }
            return Base64.encodeToString(bytes.array(), Base64.NO_WRAP);
        } catch (SecurityException | java.io.FileNotFoundException e) {
            throw new IOException("Access to the original expired or it moved. Tap Open PDF and select it again.", e);
        }
    }
}
