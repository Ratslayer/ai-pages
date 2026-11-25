package app.aipages;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

// All file operations run on the reader's single IO executor. Only app-created translation
// databases are ever opened writable. External packs are validated before becoming visible.
final class ReaderBooks {
    private final File root;
    ReaderBooks(File root) { this.root = root; }

    private static void require(boolean ok, String message) throws IOException {
        if (!ok) throw new IOException(message);
    }
    private File file(String book, String language) throws IOException {
        require(book.matches("[a-f0-9]{64}"), "Invalid book ID.");
        require(language.isEmpty() || language.matches("[a-z]{2,3}(-[A-Za-z0-9]{2,8})*"), "Invalid language.");
        return new File(root, book + (language.isEmpty() ? ".book" : "." + language) + ".sqlite");
    }
    private File languageFile(String book,String lang) throws IOException {
        require(!lang.isEmpty(),"Choose a translation language.");
        return file(book,lang);
    }
    private static SQLiteDatabase read(File f) {
        return SQLiteDatabase.openDatabase(f.getPath(), null, SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
    }
    private static String scalar(SQLiteDatabase db, String query, String... args) throws IOException {
        try (Cursor c = db.rawQuery(query, args)) {
            require(c.moveToFirst(), "Missing book data.");
            return c.getString(0);
        }
    }
    private static void table(SQLiteDatabase db, String name) throws IOException {
        require("table".equals(scalar(db,"SELECT type FROM sqlite_master WHERE name=?",name)), "Invalid " + name + " table.");
    }
    private static JSONObject info(SQLiteDatabase db) throws Exception {
        table(db,"meta");
        try (Cursor c = db.rawQuery("SELECT format,book_id,lang,title FROM meta",null)) {
            require(c.moveToFirst() && c.getCount()==1,"Invalid pack metadata.");
            return new JSONObject().put("format",c.getString(0)).put("book",c.getString(1))
                    .put("lang",c.getString(2)).put("title",c.getString(3));
        }
    }
    private static void checkSpans(String text, JSONArray words) throws Exception {
        require(!text.isEmpty() && text.length()<=4096 && words.length()<=1400,"Invalid sentence size.");
        int end=0;
        for (int i=0;i<words.length();i++) {
            JSONArray w=words.getJSONArray(i);
            int start=w.getInt(0),len=w.getInt(1);
            require(start>=end && len>0 && start<=text.length()-len,"Invalid word position.");
            end=start+len;
        }
    }
    static void checkEntry(JSONObject entry,int count) throws Exception {
        require(!entry.getString("translation").trim().isEmpty(),"Missing sentence translation.");
        boolean[] seen=new boolean[count];
        JSONArray groups=entry.getJSONArray("groups");
        for (int i=0;i<groups.length();i++) {
            JSONArray g=groups.getJSONArray(i), ids=g.getJSONArray(0);
            require(ids.length()>0 && !g.getString(1).trim().isEmpty() && !g.getString(2).trim().isEmpty(),"Incomplete word meaning.");
            for (int j=0;j<ids.length();j++) {
                int id=ids.getInt(j);
                require(id>=0 && id<count && !seen[id],"Invalid or duplicate word ID.");
                seen[id]=true;
            }
        }
        for(boolean covered:seen) require(covered,"Some words still need a translation.");
    }
    private SQLiteDatabase translations(String book,String lang) throws Exception {
        File target=languageFile(book,lang);
        SQLiteDatabase db=SQLiteDatabase.openOrCreateDatabase(target,null);
        try {
            db.beginTransaction();
            try {
                db.execSQL("CREATE TABLE IF NOT EXISTS meta(format TEXT,book_id TEXT,lang TEXT,title TEXT)");
                db.execSQL("INSERT INTO meta SELECT ?,?,?,? WHERE NOT EXISTS(SELECT 1 FROM meta)",
                        new Object[]{"reader.translation.1",book,lang,""});
                db.execSQL("CREATE TABLE IF NOT EXISTS entries(sid INTEGER PRIMARY KEY,payload TEXT NOT NULL)");
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
            return db;
        } catch(Exception e){ db.close(); throw e; }
    }
    private static void insert(SQLiteDatabase source, SQLiteStatement stmt,int sid,String payload) throws Exception {
        require(payload.length()<=180000,"Translation record is too large.");
        String words=scalar(source,"SELECT words FROM sentences WHERE id=?",String.valueOf(sid));
        checkEntry(new JSONObject(payload),new JSONArray(words).length());
        stmt.bindLong(1,sid);stmt.bindString(2,payload);stmt.executeInsert();
    }
    private static SQLiteStatement writer(SQLiteDatabase db) {
        return db.compileStatement("INSERT OR IGNORE INTO entries(sid,payload) VALUES(?,?)");
    }
    JSONObject importFile(InputStream input,String current) throws Exception {
        return importFile(input,current,null,null,null);
    }
    JSONObject importFile(InputStream input,String current,String expectedBook,String expectedLang,String expectedSha) throws Exception {
        Files.createDirectories(root.toPath());
        File tmp=File.createTempFile("import-", ".tmp",root);
        try {
            java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
            try(OutputStream out=new java.security.DigestOutputStream(Files.newOutputStream(tmp.toPath()),digest)){
                byte[] buf=new byte[32768]; int size=0,n;
                while((n=input.read(buf))!=-1){size+=n;require(size<=64*1024*1024,"Book pack exceeds 64 MiB.");out.write(buf,0,n);}
            }
            if(expectedSha!=null){
                StringBuilder hex=new StringBuilder();
                for(byte b:digest.digest())hex.append(String.format(java.util.Locale.ROOT,"%02x",b & 255));
                require(expectedSha.contentEquals(hex),"Downloaded file checksum differs from the catalogue. Refresh and retry.");
            }
            JSONObject meta;
            try(SQLiteDatabase incoming=read(tmp)) {
                require("ok".equals(scalar(incoming,"PRAGMA quick_check(1)")),"Damaged SQLite file.");
                meta=info(incoming);
                String id=meta.getString("book"),lang=meta.getString("lang");
                languageFile(id,lang);
                if(expectedBook!=null){
                    require(expectedBook.equals(id),"Book revision differs from catalogue.");
                    String format=expectedLang==null?"reader.book.1":"reader.translation.1";
                    require(format.equals(meta.getString("format")) && (expectedLang==null || expectedLang.equals(lang)),"Wrong catalogue file.");
                }
                if ("reader.translation.1".equals(meta.getString("format"))) {
                    require(id.equals(current),"Open the matching book before its translation.");
                    table(incoming,"entries");
                    try(SQLiteDatabase book=read(file(id,"")); SQLiteDatabase local=translations(id,lang);
                        Cursor c=incoming.rawQuery("SELECT sid,payload FROM entries",null); SQLiteStatement stmt=expectedSha==null?writer(local):local.compileStatement("INSERT OR REPLACE INTO entries(sid,payload) VALUES(?,?)")) {
                        local.beginTransaction();
                        try {while(c.moveToNext())insert(book,stmt,c.getInt(0),c.getString(1));local.setTransactionSuccessful();}
                        finally{local.endTransaction();}
                    }
                    return meta;
                }
                require("reader.book.1".equals(meta.getString("format")),"Unsupported book file.");
                table(incoming,"sentences");
                int page=-1,last=0;
                try(Cursor c=incoming.rawQuery("SELECT id,page,para,chapter,pdf_start,pdf_end,text,words FROM sentences ORDER BY id",null)){
                    while(c.moveToNext()){
                        int p=c.getInt(1);
                        require(c.getInt(0)>last && (p==page || p==page+1) && p>=0,"Invalid book pagination.");
                        last=c.getInt(0);page=p;
                        checkSpans(c.getString(6),new JSONArray(c.getString(7)));
                    }
                }
                require(last>0,"Book is empty.");meta.put("pages",page+1);
            }
            File dest=file(meta.getString("book"),"");
            if(expectedSha!=null || !dest.exists())Files.move(tmp.toPath(),dest.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            return meta;
        } finally { Files.deleteIfExists(tmp.toPath()); }
    }
    JSONObject fromText(String title,String lang,String text) throws Exception {
        Files.createDirectories(root.toPath());
        java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
        String identity="reader.book.1\n"+lang+"\n"+text;
        byte[] hash=digest.digest(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder hex=new StringBuilder();
        for(byte b:hash)hex.append(String.format(java.util.Locale.ROOT,"%02x",b & 255));
        String id=hex.toString();File dest=file(id,"");file(id,lang);
        if(dest.exists())return describe(id);
        File tmp=File.createTempFile("build-", ".tmp",root);
        try {
            try(SQLiteDatabase db=SQLiteDatabase.openOrCreateDatabase(tmp,null)){
                db.beginTransaction();
                try {
                    db.execSQL("CREATE TABLE meta(format TEXT,book_id TEXT,lang TEXT,title TEXT)");
                    db.execSQL("INSERT INTO meta VALUES(?,?,?,?)",new Object[]{"reader.book.1",id,lang,title});
                    db.execSQL("CREATE TABLE sentences(id INTEGER PRIMARY KEY,page INTEGER,para INTEGER,chapter TEXT,pdf_start INTEGER,pdf_end INTEGER,text TEXT,words TEXT)");
                    db.execSQL("CREATE INDEX pages ON sentences(page,id)");
                    java.util.Locale locale=java.util.Locale.forLanguageTag(lang);
                    android.icu.text.BreakIterator sentences=android.icu.text.BreakIterator.getSentenceInstance(locale);
                    android.icu.text.BreakIterator words=android.icu.text.BreakIterator.getWordInstance(locale);
                    int page=0,used=0,sid=0,para=0,pdf=0;
                    try(SQLiteStatement add=db.compileStatement("INSERT INTO sentences VALUES(?,?,?,'',?,?,?,?)")){
                        for(String sourcePage:text.split("\f",-1)){
                            pdf++;
                            for(String paragraph:sourcePage.split("\\R+")){
                                para++;sentences.setText(paragraph);
                                for(int begin=sentences.first(),end=sentences.next();end!=android.icu.text.BreakIterator.DONE;begin=end,end=sentences.next()){
                                    int pos=begin;
                                    while(pos<end){
                                        int cut=Math.min(pos+1050,end);
                                        if(cut<end){
                                            int space=paragraph.lastIndexOf(' ',cut-1);
                                            if(space>pos+500)cut=space+1;
                                            else if(Character.isHighSurrogate(paragraph.charAt(cut-1)))cut--;
                                        }
                                        String part=paragraph.substring(pos,cut).trim();pos=cut;
                                        if(part.isEmpty())continue;
                                        if(used>0 && used+part.length()>1400){page++;used=0;}
                                        JSONArray spans=new JSONArray();words.setText(part);
                                        for(int a=words.first(),b=words.next();b!=android.icu.text.BreakIterator.DONE;a=b,b=words.next()){
                                            if(words.getRuleStatus()>=100)spans.put(new JSONArray().put(a).put(b-a));
                                        }
                                        add.bindLong(1,++sid);add.bindLong(2,page);add.bindLong(3,para);
                                        add.bindLong(4,pdf);add.bindLong(5,pdf);add.bindString(6,part);add.bindString(7,spans.toString());add.executeInsert();
                                        used+=part.length()+1;
                                    }
                                }
                            }
                        }
                    }
                    require(sid>0,"No readable text in this book.");db.setTransactionSuccessful();
                } finally { db.endTransaction(); }
            }
            Files.move(tmp.toPath(),dest.toPath(),StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(tmp.toPath()); }
        return describe(id);
    }
    JSONObject describe(String book) throws Exception {
        try(SQLiteDatabase db=read(file(book,""))){
            return info(db).put("pages",Integer.parseInt(scalar(db,"SELECT max(page)+1 FROM sentences")));
        }
    }
    boolean hasBook(String id) throws IOException { return file(id,"").isFile(); }
    boolean hasLanguage(String id,String lang) throws IOException { return languageFile(id,lang).isFile(); }
    java.util.List<String> installed() {
        java.util.List<String> ids=new java.util.ArrayList<>();
        String[] names=root.list();
        if(names!=null)for(String name:names)if(name.matches("[a-f0-9]{64}\\.book\\.sqlite"))ids.add(name.substring(0,64));
        java.util.Collections.sort(ids);
        return ids;
    }
    java.util.Set<Integer> readyPages(String id,String lang) throws Exception {
        java.util.Set<Integer> ready=new java.util.HashSet<>();
        if(!hasLanguage(id,lang))return ready;
        try(SQLiteDatabase source=read(file(id,"")); SQLiteDatabase pack=read(file(id,lang));
            Cursor sentences=source.rawQuery("SELECT id,page FROM sentences ORDER BY id",null);
            Cursor entries=pack.rawQuery("SELECT sid FROM entries ORDER BY sid",null)){
            boolean hasEntry=entries.moveToFirst(), complete=true;
            int page=-1;
            while(sentences.moveToNext()){
                int next=sentences.getInt(1),sid=sentences.getInt(0);
                if(next!=page){
                    if(page>=0 && complete)ready.add(page);
                    page=next;complete=true;
                }
                while(hasEntry && entries.getInt(0)<sid)hasEntry=entries.moveToNext();
                if(!hasEntry || entries.getInt(0)!=sid)complete=false;
            }
            if(page>=0 && complete)ready.add(page);
        }
        return ready;
    }
    JSONArray page(String book,String lang,int page) throws Exception {
        File trans=languageFile(book,lang);
        try(SQLiteDatabase db=read(file(book,""));SQLiteDatabase pack=trans.exists()?read(trans):null;
            Cursor c=db.rawQuery("SELECT id,para,chapter,pdf_start,pdf_end,text,words FROM sentences WHERE page=? ORDER BY id",new String[]{String.valueOf(page)})){
            JSONArray out=new JSONArray();
            while(c.moveToNext()){
                JSONObject row=new JSONObject().put("id",c.getInt(0)).put("para",c.getInt(1)).put("chapter",c.getString(2))
                        .put("pdf",c.getInt(3)).put("pdfEnd",c.getInt(4)).put("text",c.getString(5)).put("words",new JSONArray(c.getString(6)));
                if(pack!=null)try(Cursor t=pack.rawQuery("SELECT payload FROM entries WHERE sid=?",new String[]{c.getString(0)})){
                    if(t.moveToFirst())row.put("entry",new JSONObject(t.getString(0)));
                }
                out.put(row);
            }
            require(out.length()>0,"Page does not exist.");return out;
        }
    }
    void save(String book,String lang,int sid,JSONObject entry) throws Exception {
        try(SQLiteDatabase source=read(file(book,"")); SQLiteDatabase db=translations(book,lang);SQLiteStatement stmt=writer(db)){
            insert(source,stmt,sid,entry.toString());
        }
    }
    void export(String book,String lang,OutputStream out) throws Exception {
        File f=file(book,lang);
        require(f.exists(),"There are no translations to export yet.");
        // No writer is open on this serial executor. SQLite's rollback journal has been committed.
        Files.copy(f.toPath(),out);
    }
}
