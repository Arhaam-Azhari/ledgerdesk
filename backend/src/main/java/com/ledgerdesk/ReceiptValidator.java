package com.ledgerdesk;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.*;
import org.springframework.stereotype.Component;

@Component
public class ReceiptValidator {
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    public record Validated(String filename, String mediaType, String sha, byte[] bytes) {}

    public Validated validate(String filename, String declaredType, byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES)
            throw new IllegalArgumentException("Choose a nonempty receipt no larger than 2 MiB.");
        String clean = filename == null ? "" : filename.replace('\\', '/');
        clean = clean.substring(clean.lastIndexOf('/') + 1);
        clean = LedgerService.text(clean, 120, "Receipt filename");
        if (clean.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Receipt filename contains invalid characters.");
        String lower = clean.toLowerCase(Locale.ROOT);
        String type;
        byte[] stored;
        try {
            if (lower.endsWith(".pdf") && "application/pdf".equals(declaredType)) {
                if (bytes.length < 5 || !new String(bytes, 0, 5, java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-"))
                    throw new IllegalArgumentException("This file is not a PDF.");
                validatePdf(bytes);
                type = "application/pdf";
                stored = bytes;
            } else if (lower.endsWith(".png") && "image/png".equals(declaredType)) {
                stored = image(bytes, "png"); type = "image/png";
            } else if ((lower.endsWith(".jpg") || lower.endsWith(".jpeg")) && "image/jpeg".equals(declaredType)) {
                stored = image(bytes, "JPEG"); type = "image/jpeg";
            } else throw new IllegalArgumentException("Choose a PDF, PNG, or JPEG whose file type matches its extension.");
            if (stored.length > MAX_BYTES) throw new IllegalArgumentException("The decoded image exceeds the 2 MiB storage limit.");
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stored));
            return new Validated(clean, type, sha, stored);
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            throw new IllegalArgumentException("The receipt could not be read. Use a valid static PDF, PNG, or JPEG.");
        }
    }

    private byte[] image(byte[] bytes, String expected) throws IOException {
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllegalArgumentException("This file is not a readable image.");
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                if (!reader.getFormatName().equalsIgnoreCase(expected))
                    throw new IllegalArgumentException("The image contents do not match the file type.");
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > 10_000_000)
                    throw new IllegalArgumentException("Receipt images must be no larger than 10 megapixels.");
                var decoded = reader.read(0);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                // Re-encoding drops trailing data and image metadata from the uploaded file.
                if (!ImageIO.write(decoded, expected, output)) throw new IOException("Image writer unavailable.");
                return output.toByteArray();
            } finally { reader.dispose(); }
        }
    }

    private void validatePdf(byte[] bytes) throws IOException {
        try (var pdf = Loader.loadPDF(bytes)) {
            if (pdf.isEncrypted() || pdf.getNumberOfPages() < 1 || pdf.getNumberOfPages() > 20)
                throw new IllegalArgumentException("Receipt PDFs must be unencrypted and contain 1 to 20 pages.");
            Set<String> forbiddenKeys = Set.of("AA", "OpenAction", "JS", "JavaScript", "EmbeddedFiles", "RichMedia", "XFA", "AcroForm");
            Set<String> forbiddenNames = Set.of("JavaScript", "Launch", "GoToR", "SubmitForm", "ImportData", "Rendition", "URI", "EmbeddedFile");
            Set<COSBase> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            var pending = new ArrayDeque<COSBase>();
            pending.add(pdf.getDocument().getTrailer());
            while (!pending.isEmpty()) {
                COSBase node = pending.removeFirst();
                if (!seen.add(node)) continue;
                if (seen.size() > 20_000) throw new IllegalArgumentException("Receipt PDF is too complex.");
                if (node instanceof COSObject object) {
                    if (object.getObject() != null) pending.add(object.getObject());
                } else if (node instanceof COSDictionary dictionary) {
                    for (COSName name : dictionary.keySet()) {
                        if (forbiddenKeys.contains(name.getName())) throw new IllegalArgumentException("Interactive PDFs and embedded files are not accepted.");
                        COSBase value = dictionary.getItem(name);
                        if (value != null) pending.add(value);
                    }
                } else if (node instanceof COSArray array) {
                    for (int i = 0; i < array.size(); i++) pending.add(array.get(i));
                } else if (node instanceof COSName name && forbiddenNames.contains(name.getName())) {
                    throw new IllegalArgumentException("PDF actions and embedded files are not accepted.");
                }
            }
        }
    }
}
