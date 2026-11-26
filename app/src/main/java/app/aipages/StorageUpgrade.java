package app.aipages;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Relocate data written by APKs through 0.2.7 before exposing per-tool storage. */
final class StorageUpgrade {
    static synchronized void run(Context context) throws Exception {
        SharedPreferences upgrades=context.getSharedPreferences("storage-upgrades",Context.MODE_PRIVATE);
        if(upgrades.getBoolean("tool-files-v1",false)) return;
        File root=new File(context.getFilesDir(),"tools/language");
        Path old=new File(context.getFilesDir(),"language-books").toPath();
        if(Files.exists(old)){
            Files.createDirectories(root.getParentFile().toPath());
            Files.move(old,root.toPath(),StandardCopyOption.ATOMIC_MOVE);
        }
        SharedPreferences cached=context.getSharedPreferences("language-catalogue",Context.MODE_PRIVATE);
        if(!cached.getAll().isEmpty()){
            byte[] data=new JSONObject(cached.getAll()).toString().getBytes(StandardCharsets.UTF_8);
            new ToolFiles(root).copyIn("legacy-catalogue.json",new ByteArrayInputStream(data),ToolFiles.LIMIT);
        }
        if(!upgrades.edit().putBoolean("tool-files-v1",true).commit()) throw new java.io.IOException("Could not finish storage upgrade.");
    }
}
