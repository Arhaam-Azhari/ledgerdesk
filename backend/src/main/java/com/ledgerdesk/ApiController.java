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
    public ApiController(LedgerService ledger) { this.ledger = ledger; }
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
