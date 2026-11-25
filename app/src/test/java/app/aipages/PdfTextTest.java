package app.aipages;

import static org.junit.Assert.*;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PdfTextTest {
    private byte[] makePdf(String... pages) throws IOException {
        PDFBoxResourceLoader.init(RuntimeEnvironment.getApplication());
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            for (String line : pages) {
                PDPage page = new PDPage();
                doc.addPage(page);
                if (line.isEmpty()) continue;
                try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                    content.beginText();
                    content.setFont(PDType1Font.HELVETICA, 12);
                    content.newLineAtOffset(60, 700);
                    content.showText(line);
                    content.endText();
                }
            }
            doc.save(bytes);
            return bytes.toByteArray();
        }
    }

    @Test public void extractsReadableTextInPageOrder() throws IOException {
        String text = PdfText.extract(makePdf("Er hörte auf.", "Weiter geht es."));
        assertEquals("Er hörte auf. Weiter geht es.", text.replaceAll("\\s+", " "));
    }

    @Test public void explainsWhenOcrIsNeeded() throws IOException {
        byte[] blank = makePdf("");
        IOException error = assertThrows(IOException.class, () -> PdfText.extract(blank));
        assertTrue(error.getMessage().contains("OCR"));
    }
}
