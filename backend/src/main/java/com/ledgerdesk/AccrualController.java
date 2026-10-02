package com.ledgerdesk;

import java.security.Principal;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/accruals")
public class AccrualController {
    private final AccrualService accruals;
    public AccrualController(AccrualService accruals) { this.accruals = accruals; }
    @PostMapping
    Map<String, String> post(@RequestBody AccrualService.Accrual body,
            @RequestHeader("Idempotency-Key") String key, Principal user) {
        return Map.of("id", accruals.post(body, key, user.getName()));
    }
    @PostMapping("/{id}/reverse")
    Map<String, String> reverse(@PathVariable String id, @RequestBody AccrualService.Reversal body,
            @RequestHeader("Idempotency-Key") String key, Principal user) {
        return Map.of("id", accruals.reverse(id, body, key, user.getName()));
    }
    @PostMapping("/{id}/bill")
    Map<String, String> receiveBill(@PathVariable String id, @RequestBody AccrualService.BillArrival body,
            @RequestHeader("Idempotency-Key") String key, Principal user) {
        return Map.of("id", accruals.receiveBill(id, body, key, user.getName()));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> invalid(IllegalArgumentException error) { return Map.of("message", error.getMessage()); }
}
