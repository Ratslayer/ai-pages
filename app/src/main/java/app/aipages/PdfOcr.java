package app.aipages;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import com.googlecode.tesseract.android.TessBaseAPI;
import java.io.*;
import java.nio.file.Files;
import java.util.Locale;

final class PdfOcr implements AutoCloseable {
    private final Context context;
    private final String language;
    private final byte[] pdf;
    private File temporary;
    private PdfRenderer renderer;
    private TessBaseAPI engine;

    PdfOcr(Context context, String source, byte[] pdf) {
        this.context = context;
        this.pdf = pdf;
        language = switch (source.toLowerCase(Locale.ROOT)) {
            case "de" -> "deu"; case "fr" -> "fra"; case "ru" -> "rus";
            case "es" -> "spa"; case "it" -> "ita"; case "pt" -> "por";
            case "nl" -> "nld"; case "sv" -> "swe"; case "pl" -> "pol";
            case "uk" -> "ukr"; case "tr" -> "tur"; case "ar" -> "ara";
            case "he" -> "heb"; case "hi" -> "hin"; case "ja" -> "jpn";
            case "zh" -> "chi_sim"; case "ko" -> "kor"; default -> "eng";
        };
    }

    private void initialize() throws IOException {
        File root = new File(context.getCacheDir(), "ocr-fast-8741641");
        File data = new File(root, "tessdata");
        if (!data.isDirectory() && !data.mkdirs() && !data.isDirectory()) throw new IOException("Cannot prepare OCR.");
        File model = new File(data, language + ".traineddata");
        if (!model.isFile()) {
            File staging = File.createTempFile("model-", ".tmp", data);
            try (InputStream asset = context.getAssets().open("ocr/" + model.getName());
                 OutputStream out = new FileOutputStream(staging)) {
                byte[] buffer = new byte[65536];
                for (int count; (count = asset.read(buffer)) != -1;) out.write(buffer, 0, count);
            } catch (IOException error) {
                staging.delete();
                throw error;
            }
            if (!staging.renameTo(model)) {
                staging.delete();
                if (!model.isFile()) throw new IOException("Cannot install OCR language.");
            }
        }
        engine = new TessBaseAPI();
        if (!engine.init(root.getAbsolutePath(), language)) throw new IOException("Cannot load OCR language.");
        temporary = File.createTempFile("reader-", ".pdf", context.getCacheDir());
        Files.write(temporary.toPath(), pdf);
        ParcelFileDescriptor fd = ParcelFileDescriptor.open(temporary, ParcelFileDescriptor.MODE_READ_ONLY);
        try {
            renderer = new PdfRenderer(fd);
        } catch (RuntimeException | IOException e) {
            fd.close();
            throw e;
        }
    }

    String read(int index) throws IOException {
        if (renderer == null) initialize();
        try (PdfRenderer.Page page = renderer.openPage(index)) {
            int width = page.getWidth(), height = page.getHeight();
            float scale = Math.min(3f, 2800f / Math.max(width, height));
            Bitmap bitmap = Bitmap.createBitmap(Math.max(1, Math.round(width * scale)),
                    Math.max(1, Math.round(height * scale)), Bitmap.Config.ARGB_8888);
            try {
                bitmap.eraseColor(Color.WHITE);
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                engine.setImage(bitmap);
                return engine.getUTF8Text();
            } finally {
                engine.clear();
                bitmap.recycle();
            }
        }
    }

    @Override public void close() {
        if (renderer != null) renderer.close();
        if (engine != null) engine.recycle();
        if (temporary != null) temporary.delete();
    }
}
