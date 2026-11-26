package app.aipages;

import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.util.Arrays;

/** Private, durable files for one signed tool. The caller supplies only relative names. */
final class ToolFiles {
    static final int CHUNK = 192 * 1024, LIMIT = 64 * 1024 * 1024;
    private final Path root;
    ToolFiles(File root) { this.root = root.toPath(); }

    private Path path(String name) throws IOException {
        if (name.isEmpty() || name.length()>512) throw new IOException("Invalid file name.");
        for (String part : name.split("/", -1))
            if (!part.matches("[a-zA-Z0-9_-][a-zA-Z0-9._-]{0,159}")) throw new IOException("Invalid file name.");
        Path file = root.resolve(name);
        if (!file.toFile().getCanonicalFile().toPath().equals(root.toFile().getCanonicalFile().toPath().resolve(name))) throw new IOException("Invalid file path.");
        return file;
    }
    private Path target(String name) throws IOException {
        Path file = path(name);
        Files.createDirectories(file.getParent());
        return file;
    }
    void copyIn(String name, InputStream input, int limit) throws IOException {
        Path dest = target(name);
        Path tmp = Files.createTempFile(dest.getParent(), ".transfer-", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                byte[] buf = new byte[32768];
                int total = 0, n;
                while ((n=input.read(buf)) != -1) {
                    total += n;
                    if (total > limit) throw new IOException("File exceeds the size limit.");
                    out.write(buf,0,n);
                }
            }
            Files.move(tmp,dest,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(tmp); }
    }
    void copyOut(String name, OutputStream out) throws IOException { Files.copy(path(name),out); }

    Object perform(String method, JSONObject args) throws Exception {
        String name = args.optString("path");
        switch (method) {
            case "files.available": return true;
            case "files.list": {
                Path dir = name.isEmpty() ? root : path(name);
                if (!Files.exists(dir)) return new JSONArray();
                String[] names = dir.toFile().list((d,n) -> !n.startsWith("."));
                if (names == null) throw new IOException("Cannot list directory.");
                Arrays.sort(names);
                return new JSONArray(Arrays.asList(names));
            }
            case "files.size": {
                Path file=path(name);
                return Files.isRegularFile(file) ? Files.size(file) : -1L;
            }
            case "files.read": {
                try (RandomAccessFile in = new RandomAccessFile(path(name).toFile(),"r")) {
                    long offset=args.getLong("offset");
                    if(offset<0 || offset>in.length()) throw new IOException("Invalid read offset.");
                    in.seek(offset);
                    byte[] bytes=new byte[(int)Math.min(CHUNK,in.length()-offset)];
                    in.readFully(bytes);
                    return Base64.encodeToString(bytes,Base64.NO_WRAP);
                }
            }
            case "files.write": {
                byte[] bytes=Base64.decode(args.getString("data"),Base64.DEFAULT);
                if(bytes.length>CHUNK) throw new IOException("Chunk too large.");
                long offset=args.getLong("offset");
                if(offset==0) copyIn(name,new ByteArrayInputStream(bytes),CHUNK);
                else try(RandomAccessFile out=new RandomAccessFile(target(name).toFile(),"rw")) {
                    if(offset!=out.length() || offset>LIMIT-bytes.length) throw new IOException("Invalid write offset.");
                    out.seek(offset);out.write(bytes);
                }
                return true;
            }
            case "files.move":
                Files.move(path(name),target(args.getString("to")),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                return true;
            case "files.remove": return Files.deleteIfExists(path(name));
            case "files.downloadDrive": {
                String id=args.getString("fileId");
                if(!id.matches("[A-Za-z0-9_-]{10,160}")) throw new IOException("Invalid Drive file ID.");
                URL url=new URL("https://drive.usercontent.google.com/download?export=download&id="+id);
                HttpURLConnection conn=(HttpURLConnection)url.openConnection();
                conn.setConnectTimeout(7000);conn.setReadTimeout(25000);conn.setUseCaches(false);
                int limit=args.optInt("maxBytes",LIMIT);
                if(limit<1 || limit>LIMIT) throw new IOException("Invalid download limit.");
                try {
                    if(conn.getResponseCode()!=200) throw new IOException("Drive download failed. Check file sharing.");
                    try(InputStream in=conn.getInputStream()){copyIn(name,in,limit);}
                } finally {conn.disconnect();}
                return true;
            }
            default: throw new IOException("Unsupported file action.");
        }
    }
}
