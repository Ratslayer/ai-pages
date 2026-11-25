package app.aipages;

import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/** Catalogue access is scoped to the reader. Only Drive file IDs are accepted from the manifest. */
final class BookCatalogue {
    interface Download { InputStream get(String fileId) throws IOException; }
    private static final String LANGUAGE = "en";
    private final ReaderBooks books;
    private final SharedPreferences prefs;
    private final Download download;
    BookCatalogue(ReaderBooks books, SharedPreferences prefs, Download download) {
        this.books=books; this.prefs=prefs; this.download=download;
    }
    static InputStream drive(String id) throws IOException {
        checkId(id);
        URL url=new URL("https://drive.usercontent.google.com/download?export=download&id="+id);
        HttpURLConnection conn=(HttpURLConnection)url.openConnection();
        conn.setConnectTimeout(7000);conn.setReadTimeout(25000);conn.setUseCaches(false);
        try {
            if(conn.getResponseCode()!=200)throw new IOException("Drive could not download this file. Check its sharing settings.");
            return new java.io.FilterInputStream(conn.getInputStream()){
                @Override public void close() throws IOException {try {super.close();} finally {conn.disconnect();}}
            };
        } catch(IOException err){conn.disconnect();throw err;}
    }
    private static void checkId(String id) throws IOException {
        if(!id.matches("[A-Za-z0-9_-]{10,160}"))throw new IOException("Invalid Drive file ID.");
    }
    private static void checkAsset(JSONObject asset) throws Exception {
        checkId(asset.getString("fileId"));
        if(!asset.getString("sha256").matches("[a-f0-9]{64}"))throw new IOException("Invalid file checksum.");
    }
    private static JSONArray validate(String text) throws Exception {
        JSONObject json=new JSONObject(text);
        if(json.getInt("version")!=1)throw new IOException("Unsupported catalogue version.");
        JSONArray list=json.getJSONArray("books");
        Set<String> ids=new HashSet<>(), revisions=new HashSet<>();
        for(int i=0;i<list.length();i++){
            JSONObject b=list.getJSONObject(i);
            String id=b.getString("id"), revision=b.getString("bookId");
            int pages=b.getInt("pages");
            if(!id.matches("[a-z0-9][a-z0-9-]{0,95}") || !ids.add(id) || !revision.matches("[a-f0-9]{64}") ||
                    !revisions.add(revision) || b.getString("title").trim().isEmpty() || pages<1)
                throw new IOException("Invalid book in catalogue.");
            checkAsset(b.getJSONObject("source"));
            JSONObject languages=b.getJSONObject("languages");
            for(Iterator<String> it=languages.keys();it.hasNext();){
                JSONObject lang=languages.getJSONObject(it.next());checkAsset(lang);
                JSONArray ready=lang.getJSONArray("readyPages");
                for(int j=0;j<ready.length();j++)if(ready.getInt(j)<0 || ready.getInt(j)>=pages)
                    throw new IOException("Invalid translated page number.");
            }
        }
        return list;
    }
    private String source(){return prefs.getString("source","");}
    private JSONArray cached() throws Exception {return new JSONArray(prefs.getString("books","[]"));}
    private JSONArray refresh(String id) throws Exception {
        checkId(id);
        JSONArray list;
        try(InputStream in=download.get(id)){
            list=validate(new String(PageFeed.read(in,1024*1024),StandardCharsets.UTF_8));
        }
        if(!prefs.edit().putString("source",id).putString("books",list.toString()).commit())
            throw new IOException("Could not save the catalogue.");
        return list;
    }
    JSONObject shelf(String connect,boolean update) throws Exception {
        JSONArray list=connect!=null?refresh(connect):update && !source().isEmpty()?refresh(source()):cached();
        JSONArray items=new JSONArray();Set<String> listed=new HashSet<>();
        for(int i=0;i<list.length();i++){
            JSONObject b=list.getJSONObject(i);String revision=b.getString("bookId");
            listed.add(revision);
            String old=prefs.getString(activeKey(b.getString("id")),"");
            if(!old.isEmpty())listed.add(old);
            JSONObject lang=b.getJSONObject("languages").optJSONObject(LANGUAGE);
            Set<Integer> ready=books.readyPages(revision,LANGUAGE);
            if(lang!=null){JSONArray remote=lang.getJSONArray("readyPages");
                for(int j=0;j<remote.length();j++)ready.add(remote.getInt(j));}
            items.put(new JSONObject().put("id",b.getString("id")).put("title",b.getString("title"))
                    .put("author",b.optString("author")).put("pages",b.getInt("pages")).put("ready",ready.size()));
        }
        for(String revision:books.installed())if(!listed.contains(revision)){
            JSONObject local=books.describe(revision);
            items.put(new JSONObject().put("local",revision).put("title",local.getString("title"))
                    .put("pages",local.getInt("pages")).put("ready",books.readyPages(revision,LANGUAGE).size()));
        }
        return new JSONObject().put("connected",!source().isEmpty()).put("books",items);
    }
    private String activeKey(String id){return "active:"+source()+":"+id;}
    private static JSONObject find(JSONArray rows,String id) throws Exception {
        for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).getString("id").equals(id))return rows.getJSONObject(i);
        return null;
    }
    private void install(JSONObject asset,String revision,String lang) throws Exception {
        String hash=asset.getString("sha256"),key="installed:"+revision+":"+(lang==null?"source":lang);
        boolean exists=lang==null?books.hasBook(revision):books.hasLanguage(revision,lang);
        if(exists && hash.equals(prefs.getString(key,"")))return;
        try(InputStream in=download.get(asset.getString("fileId"))){
            books.importFile(in,revision,revision,lang,hash);
        }
        if(!prefs.edit().putString(key,hash).commit())throw new IOException("Could not checkpoint the download.");
    }
    JSONObject open(String id) throws Exception {
        JSONObject previous=find(cached(),id);
        if(previous==null)throw new IOException("Book is no longer in the catalogue. Refresh the list.");
        String prior=prefs.getString(activeKey(id),"");
        if(prior.isEmpty() && books.hasBook(previous.getString("bookId")))prior=previous.getString("bookId");
        JSONArray list;
        try {list=refresh(source());}
        catch(Exception error){
            if(!prior.isEmpty() && books.hasBook(prior))return books.describe(prior).put("notice","Catalogue update unavailable. Opened the downloaded copy.");
            // A valid cached manifest can still be used if only the manifest request failed.
            list=cached();
        }
        JSONObject b=find(list,id);
        if(b==null)throw new IOException("Book is no longer in the catalogue. Refresh the list.");
        String revision=b.getString("bookId");
        try{
            install(b.getJSONObject("source"),revision,null);
            JSONObject lang=b.getJSONObject("languages").optJSONObject(LANGUAGE);
            if(lang!=null)install(lang,revision,LANGUAGE);
            if(!prefs.edit().putString(activeKey(id),revision).commit())throw new IOException("Could not save the downloaded book.");
            return books.describe(revision);
        }catch(Exception error){
            if(!prior.isEmpty() && books.hasBook(prior))return books.describe(prior).put("notice","Update incomplete. Opened the downloaded copy; retry from Books.");
            throw error;
        }
    }
}
