package app.aipages;

import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.io.Writer;

final class PdfText {
    static String extract(byte[] bytes) throws IOException {
        try (PDDocument document = PDDocument.load(bytes)) {
            if (!document.getCurrentAccessPermission().canExtractContent()) {
                throw new IOException("This PDF restricts text extraction.");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setLineSeparator(" ");
            stripper.setParagraphEnd("\n");
            stripper.setPageEnd("\n\n");
            StringBuilder text = new StringBuilder();
            Writer output = new Writer() {
                @Override public void write(char[] buffer, int start, int count) throws IOException {
                    if (count > 4 * 1024 * 1024 - text.length()) {
                        throw new IOException("This PDF contains too much text. Import a shorter excerpt.");
                    }
                    text.append(buffer, start, count);
                }
                @Override public void flush() {}
                @Override public void close() {}
            };
            stripper.writeText(document, output);
            String result = text.toString().trim();
            if (result.isEmpty()) throw new IOException("This PDF has no selectable text. Run OCR on the scan first.");
            return result;
        } catch (InvalidPasswordException e) {
            throw new IOException("Unlock this PDF before opening it in the reader.", e);
        }
    }
}
