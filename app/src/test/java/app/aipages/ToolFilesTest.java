package app.aipages;

import static org.junit.Assert.*;
import android.content.Context;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.*;
import java.nio.file.Files;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class ToolFilesTest {
    @Test public void privatePathsAndFailedReplacement() throws Exception {
        File root=Files.createTempDirectory("tools").toFile();
        ToolFiles a=new ToolFiles(new File(root,"one")), b=new ToolFiles(new File(root,"two"));
        a.copyIn("nested/cache",new ByteArrayInputStream(new byte[]{42}),1);
        assertThrows(IOException.class,()->a.copyIn("nested/cache",new ByteArrayInputStream(new byte[2]),1));
        ByteArrayOutputStream out=new ByteArrayOutputStream(); a.copyOut("nested/cache",out);
        assertArrayEquals(new byte[]{42},out.toByteArray());
        assertThrows(IOException.class,()->b.copyOut("../one/nested/cache",out));
        assertEquals(-1L,b.perform("files.size",new JSONObject().put("path","nested/cache")));
    }
    @Test public void migratesOldFilesAndPreferencesOnce() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        File old=new File(context.getFilesDir(),"language-books");
        Files.createDirectories(old.toPath());
        Files.write(new File(old,"existing.sqlite").toPath(),new byte[]{1,2,3});
        context.getSharedPreferences("language-catalogue",0).edit().putString("books","[]").commit();
        StorageUpgrade.run(context);
        File migrated=new File(context.getFilesDir(),"tools/language/existing.sqlite");
        assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(migrated.toPath()));
        assertTrue(new File(migrated.getParent(),"legacy-catalogue.json").exists());
        StorageUpgrade.run(context);
        assertFalse(old.exists());
    }
}
