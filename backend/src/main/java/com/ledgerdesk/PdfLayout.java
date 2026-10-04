package com.ledgerdesk;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

final class PdfLayout implements AutoCloseable {
    private final PDDocument document;
    private final PDType0Font font;
    private final String footer;
    private PDPageContentStream stream;
    private float y;
    private int page;
    private boolean substituted;
    boolean substituted() { return substituted; }
    private static final float LEFT = 50;
    private static final float WIDTH = PDRectangle.LETTER.getWidth() - 100;

    PdfLayout(PDDocument document, PDType0Font font, String footer) throws IOException {
        this.document = document;
        this.font = font;
        this.footer = footer;
        nextPage();
    }

    private void nextPage() throws IOException {
        if (stream != null) stream.close();
        PDPage sheet = new PDPage(PDRectangle.LETTER);
        document.addPage(sheet);
        stream = new PDPageContentStream(document, sheet);
        page++;
        draw(footer + "  |  Page " + page, 9, 30);
        y = 735;
    }

    private String printable(String raw) throws IOException {
        StringBuilder result = new StringBuilder();
        for (int cp : raw.codePoints().toArray()) {
            if (Character.isISOControl(cp) || Character.isWhitespace(cp)) { result.append(' '); continue; }
            String glyph = new String(Character.toChars(cp));
            try { font.encode(glyph); result.append(glyph); }
            catch (IllegalArgumentException unsupported) {
                result.append(String.format(java.util.Locale.ROOT, "[U+%04X]", cp));
                substituted = true;
            }
        }
        return result.toString();
    }

    void gap(float height) { y -= height; }

    void keepTogether(float height) throws IOException {
        if (y - height < 65) nextPage();
    }

    void text(String raw, float size) throws IOException {
        String text = printable(raw);
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        // Split by code point so a long email or unbroken description cannot run off the page.
        for (int cp : text.codePoints().toArray()) {
            String glyph = new String(Character.toChars(cp));
            if (line.length() > 0 && font.getStringWidth(line + glyph) / 1000 * size > WIDTH) {
                lines.add(line.toString()); line.setLength(0);
            }
            line.append(glyph);
        }
        if (line.length() > 0) lines.add(line.toString());
        for (String value : lines) {
            if (y - size < 65) nextPage();
            draw(value, size, y);
            y -= size * 1.5f;
        }
    }

    private void draw(String text, float size, float atY) throws IOException {
        stream.beginText();
        stream.setFont(font, size);
        stream.setNonStrokingColor(24 / 255f, 43 / 255f, 53 / 255f);
        stream.newLineAtOffset(LEFT, atY);
        stream.showText(text);
        stream.endText();
    }

    @Override public void close() throws IOException { stream.close(); }
}
