package app.aipages;

import static org.junit.Assert.*;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.*;
import java.nio.file.Files;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ReaderBooksTest {
    @Test public void checkpointSurvivesReopenAndFailedImport() throws Exception {
        File root=Files.createTempDirectory(RuntimeEnvironment.getApplication().getFilesDir().toPath(),"reader").toFile();
        ReaderBooks store=new ReaderBooks(root);
        JSONObject book=store.fromText("Libro","es","Uno. Dos.");
        String id=book.getString("book");
        JSONObject entry=new JSONObject("{\"translation\":\"One.\",\"groups\":[[[0],\"uno\",\"one\"]]}");
        store.save(id,"en",1,entry);
        ReaderBooks reopened=new ReaderBooks(root);
        assertEquals("One.",reopened.page(id,"en",0).getJSONObject(0).getJSONObject("entry").getString("translation"));
        assertFalse(reopened.page(id,"en",0).getJSONObject(1).has("entry"));
        JSONObject broken=new JSONObject("{\"translation\":\"Two.\",\"groups\":[]}");
        assertThrows(IOException.class,()->reopened.save(id,"en",2,broken));
        assertFalse(reopened.page(id,"en",0).getJSONObject(1).has("entry"));

        ByteArrayOutputStream data=new ByteArrayOutputStream();
        reopened.export(id,"en",data);
        File external=File.createTempFile("external",".sqlite",root);
        Files.write(external.toPath(),data.toByteArray());
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(external.getPath(),null,SQLiteDatabase.OPEN_READWRITE)){
            db.execSQL("DELETE FROM entries");
            db.execSQL("INSERT INTO entries VALUES(2,?)",new Object[]{entry.toString()});
            db.execSQL("INSERT INTO entries VALUES(3,?)",new Object[]{broken.toString()});
        }
        try(InputStream in=Files.newInputStream(external.toPath())){
            assertThrows(Exception.class,()->reopened.importFile(in,id));
        }
        JSONArray page=reopened.page(id,"en",0);
        assertTrue(page.getJSONObject(0).has("entry"));assertFalse(page.getJSONObject(1).has("entry"));
        assertFalse(reopened.page(id,"ru",0).getJSONObject(0).has("entry"));

        ByteArrayOutputStream source=new ByteArrayOutputStream();
        reopened.export(id,"",source);
        ReaderBooks other=new ReaderBooks(new File(root,"other"));
        JSONObject imported=other.importFile(new ByteArrayInputStream(source.toByteArray()),"");
        assertEquals(id,imported.getString("book"));
        other.importFile(new ByteArrayInputStream(data.toByteArray()),id);
        assertTrue(other.page(id,"en",0).getJSONObject(0).has("entry"));
        assertEquals(2,other.page(id,"en",0).length());
    }
}
