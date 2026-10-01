package com.ledgerdesk;

import java.security.Principal;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final LedgerService ledger;
    private final InvoicePdf pdf;
    public ApiController(LedgerService ledger, InvoicePdf pdf) { this.ledger = ledger; this.pdf = pdf; }
    public record Reversal(LocalDate date) {}
    @GetMapping("/csrf") Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
    }
    @GetMapping("/state") Map<String, Object> state() { return ledger.state(); }
    @PostMapping("/customers") Map<String, String> customer(@RequestBody LedgerService.Customer body,
            @RequestHeader("Idempotency-Key") String key, Principal user) {
        return Map.of("id", ledger.addCustomer(body, key, user.getName()));
    }
    @PostMapping("/invoices") Map<String, String> invoice(@RequestBody LedgerService.Invoice body,
            @RequestHeader("Idempotency-Key") String key, Principal user) {
        return Map.of("id", ledger.postInvoice(body, key, user.getName()));
    }
    @PostMapping("/drafts") Map<String, String> draft(@RequestBody LedgerService.Invoice body,
            @RequestHeader("Idempotency-Key") String key, Principal user) {
        return Map.of("id", ledger.createDraft(body, key, user.getName()));
    }
    @PostMapping("/drafts/{id}") Map<String, String> editDraft(@PathVariable String id,
            @RequestBody LedgerService.DraftChanges body, @RequestHeader("Idempotency-Key") String key, Principal user) {
        return Map.of("id", ledger.updateDraft(id, body, key, user.getName()));
    }
    @PostMapping("/drafts/{id}/post") Map<String, String> postDraft(@PathVariable String id,
            @RequestBody LedgerService.DraftVersion body, @RequestHeader("Idempotency-Key") String key, Principal user) {
        return Map.of("id", ledger.postDraft(id, body.version(), key, user.getName()));
    }
    @PostMapping("/drafts/{id}/discard") Map<String, String> discardDraft(@PathVariable String id,
            @RequestBody LedgerService.DraftVersion body, @RequestHeader("Idempotency-Key") String key, Principal user) {
        return Map.of("id", ledger.discardDraft(id, body.version(), key, user.getName()));
    }
    @GetMapping("/invoices/{id}/pdf") org.springframework.http.ResponseEntity<byte[]> invoicePdf(@PathVariable String id) throws java.io.IOException {
        Map<String, Object> document = ledger.invoiceDocument(id);
        @SuppressWarnings("unchecked") var invoice = (Map<String, Object>) document.get("invoice");
        String filename = LedgerService.invoiceNumber(((Number) invoice.get("number_value")).longValue()) + ".pdf";
        return org.springframework.http.ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.APPLICATION_PDF)
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .header("Cache-Control", "no-store")
                .body(pdf.render(document));
    }
    @PostMapping("/invoices/{id}/payments") Map<String, String> payment(@PathVariable String id,
            @RequestBody LedgerService.Payment body, @RequestHeader("Idempotency-Key") String key, Principal user) {
        return Map.of("id", ledger.recordPayment(id, body, key, user.getName()));
    }
    @PostMapping("/invoices/{id}/void") Map<String, String> reverse(@PathVariable String id,
            @RequestBody Reversal body, @RequestHeader("Idempotency-Key") String key, Principal user) {
        return Map.of("id", ledger.voidInvoice(id, body.date(), key, user.getName()));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> invalid(IllegalArgumentException error) { return Map.of("message", error.getMessage()); }
}
