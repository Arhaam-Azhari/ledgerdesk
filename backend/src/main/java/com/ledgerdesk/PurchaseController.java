package com.ledgerdesk;

import java.io.IOException;
import java.security.Principal;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestController
@RequestMapping("/api")
public class PurchaseController {
    private final PurchaseService purchases;
    private final ReceiptValidator validator;

    public PurchaseController(PurchaseService purchases, ReceiptValidator validator) {
        this.purchases = purchases;
        this.validator = validator;
    }

    @PostMapping("/vendors") Map<String, String> vendor(@RequestBody PurchaseService.Vendor body,
            @RequestHeader("Idempotency-Key") String key, Principal user) {
        return Map.of("id", purchases.addVendor(body, key, user.getName()));
    }
    @PostMapping("/bills") Map<String, String> bill(@RequestBody PurchaseService.Bill body,
            @RequestHeader("Idempotency-Key") String key, Principal user) {
        return Map.of("id", purchases.postBill(body, key, user.getName()));
    }
    @PostMapping("/bills/{id}/payments") Map<String, String> payment(@PathVariable String id,
            @RequestBody LedgerService.Payment body, @RequestHeader("Idempotency-Key") String key, Principal user) {
        return Map.of("id", purchases.payBill(id, body, key, user.getName()));
    }
    @PostMapping("/bills/{id}/void") Map<String, String> voidBill(@PathVariable String id,
            @RequestBody ApiController.Reversal body, @RequestHeader("Idempotency-Key") String key, Principal user) {
        return Map.of("id", purchases.voidBill(id, body.date(), key, user.getName()));
    }
    @PostMapping("/expenses") Map<String, String> expense(@RequestBody PurchaseService.Expense body,
            @RequestHeader("Idempotency-Key") String key, Principal user) {
        return Map.of("id", purchases.postExpense(body, key, user.getName()));
    }
    @PostMapping("/expenses/{id}/reverse") Map<String, String> reverseExpense(@PathVariable String id,
            @RequestBody ApiController.Reversal body, @RequestHeader("Idempotency-Key") String key, Principal user) {
        return Map.of("id", purchases.reverseExpense(id, body.date(), key, user.getName()));
    }
    @PostMapping(value = "/{type:bills|expenses}/{id}/receipts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Map<String, String> upload(@PathVariable String type, @PathVariable String id,
            @RequestParam("file") MultipartFile file, @RequestHeader("Idempotency-Key") String key, Principal user) throws IOException {
        if (file.getSize() > ReceiptValidator.MAX_BYTES) throw new IllegalArgumentException("Receipt exceeds 2 MiB.");
        var checked = validator.validate(file.getOriginalFilename(), file.getContentType(), file.getBytes());
        return Map.of("id", purchases.attachReceipt(type, id, checked, key, user.getName()));
    }
    @GetMapping("/receipts/{id}") ResponseEntity<byte[]> download(@PathVariable String id) {
        var receipt = purchases.receipt(id);
        String extension = switch (receipt.get("media_type").toString()) {
            case "application/pdf" -> ".pdf";
            case "image/png" -> ".png";
            default -> ".jpg";
        };
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Disposition", "attachment; filename=\"receipt-" + java.util.UUID.fromString(id) + extension + "\"")
                .header("X-Content-Type-Options", "nosniff").header("Cache-Control", "no-store")
                .body((byte[]) receipt.get("content"));
    }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> invalid(IllegalArgumentException error) { return Map.of("message", error.getMessage()); }
    @ExceptionHandler(MaxUploadSizeExceededException.class) @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    Map<String, String> oversized() { return Map.of("message", "Receipt exceeds the upload limit. Choose a file no larger than 2 MiB."); }
}
