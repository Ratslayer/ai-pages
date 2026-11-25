package app.aipages;

import android.content.Context;
import android.content.Intent;
import android.graphics.*;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import java.io.ByteArrayOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ScannedPdfTest {
    @Test public void readsRasterOnlyPdfAndRegistersFileHandler() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(
                Uri.parse("content://example.files/test.pdf"), "application/pdf").setPackage(context.getPackageName());
        assertNotNull(view.resolveActivity(context.getPackageManager()));
        Bitmap scan = Bitmap.createBitmap(960, 240, Bitmap.Config.ARGB_8888);
        scan.eraseColor(Color.WHITE);
        Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        ink.setTextSize(58);
        new Canvas(scan).drawText("Der Garten ist grün.", 35, 125, ink);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PdfDocument doc = new PdfDocument();
        try {
            PdfDocument.Page page = doc.startPage(new PdfDocument.PageInfo.Builder(480, 120, 1).create());
            page.getCanvas().drawBitmap(scan, null, new Rect(0, 0, 480, 120), null);
            doc.finishPage(page);
            doc.writeTo(output);
        } finally {
            doc.close();
        }
        scan.recycle();
        byte[] pdf = output.toByteArray();
        PDFBoxResourceLoader.init(context);
        try (PdfOcr ocr = new PdfOcr(context, "de", pdf)) {
            String text = PdfText.extract(pdf, (index, total) -> ocr.read(index));
            assertTrue(text, text.contains("Garten"));
        }
    }
}
