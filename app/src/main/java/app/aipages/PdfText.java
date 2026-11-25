package app.aipages;

import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.io.Writer;

final class PdfText {
    interface PageOcr {
        String read(int index, int total) throws IOException;
    }

    static String extract(byte[] bytes, PageOcr ocr) throws IOException {
        return extract(bytes, ocr, "\n\n").trim();
    }

    static String extract(byte[] bytes, PageOcr ocr, String pageEnd) throws IOException {
        try (PDDocument document = PDDocument.load(bytes)) {
            if (!document.getCurrentAccessPermission().canExtractContent()) {
                throw new IOException("This PDF restricts text extraction.");
            }
            StringBuilder text = new StringBuilder();
            Writer sink = new Writer() {
                @Override public void write(char[] buffer, int start, int count) throws IOException {
                    if (count > 4 * 1024 * 1024 - text.length()) {
                        throw new IOException("This PDF contains too much text. Import a shorter excerpt.");
                    }
                    text.append(buffer, start, count);
                }
                @Override public void flush() {}
                @Override public void close() {}
            };
            int total = document.getNumberOfPages();
            class PageStripper extends PDFTextStripper {
                int nextPage;
                PageStripper() throws IOException { super(); }

                void appendScan() throws IOException {
                    String recognized = ocr.read(nextPage++, total);
                    if (recognized != null && !recognized.trim().isEmpty()) {
                        sink.write(recognized.trim());
                    }
                    sink.write(pageEnd);
                }

                @Override public void processPage(com.tom_roush.pdfbox.pdmodel.PDPage page) throws IOException {
                    int index = getCurrentPageNo() - 1;
                    while (nextPage < index) appendScan();
                    int start = text.length();
                    super.processPage(page);
                    if (text.substring(start).trim().isEmpty()) {
                        text.setLength(start);
                        appendScan();
                    } else {
                        nextPage++;
                    }
                }
            }
            PageStripper stripper = new PageStripper();
            stripper.setSortByPosition(true);
            stripper.setLineSeparator(" ");
            stripper.setParagraphEnd("\n");
            stripper.setPageEnd(pageEnd);
            stripper.writeText(document, sink);
            while (stripper.nextPage < total) stripper.appendScan();
            String result = text.toString();
            if (result.trim().isEmpty()) throw new IOException("No readable words were found. Check the scan quality and the reading language.");
            return result;
        } catch (InvalidPasswordException e) {
            throw new IOException("Unlock this PDF before opening it in the reader.", e);
        }
    }
}
