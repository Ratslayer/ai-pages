package app.aipages;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.SharedPreferences;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class BookCatalogueTest {
    private static byte[] export(ReaderBooks store,String id,String lang) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        store.export(id,lang,out); return out.toByteArray();
    }
    private static JSONObject asset(String id,byte[] bytes) throws Exception {
        StringBuilder hash=new StringBuilder();
        for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))hash.append(String.format(java.util.Locale.ROOT,"%02x",b & 255));
        return new JSONObject().put("fileId",id).put("sha256",hash.toString());
    }
    private static JSONObject translation(String word) throws Exception {
        return new JSONObject().put("translation",word).put("groups",new JSONArray().put(
                new JSONArray().put(new JSONArray().put(0)).put(word).put(word)));
    }
    @Test public void downloadRefreshUpdateAndOfflineRecovery() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        File dir=Files.createTempDirectory(context.getFilesDir().toPath(),"shelf").toFile();
        ReaderBooks publisher=new ReaderBooks(new File(dir,"publisher"));
        String book=publisher.fromText("Libro","es","Uno. Dos.").getString("book");
        String index="example_index",source="example_source",english="example_english";
        Map<String,byte[]> drive=new HashMap<>();
        drive.put(source,export(publisher,book,"")); publisher.save(book,"en",1,translation("One"));
        drive.put(english,export(publisher,book,"en"));
        JSONObject pack=asset(english,drive.get(english)).put("readyPages",new JSONArray());
        JSONObject row=new JSONObject().put("id","libro").put("bookId",book).put("title","Libro").put("pages",1)
                .put("source",asset(source,drive.get(source))).put("languages",new JSONObject().put("en",pack));
        JSONObject indexFile=new JSONObject().put("version",1).put("books",new JSONArray().put(row));
        drive.put(index,indexFile.toString().getBytes(StandardCharsets.UTF_8));
        Map<String,Integer> reads=new HashMap<>();
        ReaderBooks device=new ReaderBooks(new File(dir,"device"));
        SharedPreferences prefs=context.getSharedPreferences(UUID.randomUUID().toString(),0);
        BookCatalogue shelf=new BookCatalogue(device,prefs,id->{
            reads.put(id,reads.getOrDefault(id,0)+1);
            if(!drive.containsKey(id))throw new IOException("Offline");
            return new ByteArrayInputStream(drive.get(id));
        });
        assertFalse(shelf.shelf(null,false).getBoolean("connected"));
        assertEquals(0,shelf.shelf(index,true).getJSONArray("books").getJSONObject(0).getInt("ready"));
        assertEquals(book,shelf.open("libro").getString("book"));shelf.open("libro");
        assertEquals(Integer.valueOf(1),reads.get(source)); assertEquals(Integer.valueOf(1),reads.get(english));
        device.save(book,"en",2,translation("Two"));
        assertEquals(1,shelf.shelf(null,false).getJSONArray("books").getJSONObject(0).getInt("ready"));
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(new File(dir,"publisher/"+book+".en.sqlite").getPath(),null,SQLiteDatabase.OPEN_READWRITE)){
            db.execSQL("UPDATE entries SET payload=? WHERE sid=1",new Object[]{translation("One!").toString()});
        }
        byte[] corrected=export(publisher,book,"en");
        row.getJSONObject("languages").put("en",asset(english,corrected).put("readyPages",new JSONArray()));
        drive.put(index,indexFile.toString().getBytes(StandardCharsets.UTF_8));
        assertTrue(shelf.open("libro").has("notice")); // Old bytes, new checksum.
        assertEquals("One",device.page(book,"en",0).getJSONObject(0).getJSONObject("entry").getString("translation"));
        drive.put(english,corrected);shelf.open("libro");
        JSONArray page=device.page(book,"en",0);
        assertEquals("One!",page.getJSONObject(0).getJSONObject("entry").getString("translation"));
        assertEquals("Two",page.getJSONObject(1).getJSONObject("entry").getString("translation"));
        assertEquals(Integer.valueOf(1),reads.get(source));
        drive.clear();
        assertTrue(shelf.open("libro").has("notice"));
        assertEquals(1,shelf.shelf(null,false).getJSONArray("books").length());
        assertThrows(IOException.class,()->shelf.shelf(null,true));
        assertEquals(book,shelf.open("libro").getString("book"));
    }
}
