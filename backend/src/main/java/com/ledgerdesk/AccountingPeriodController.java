package com.ledgerdesk;

import java.security.Principal;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/accounting-periods")
public class AccountingPeriodController {
    private final AccountingPeriodService service;
    private final org.springframework.jdbc.core.JdbcTemplate db;
    public AccountingPeriodController(AccountingPeriodService service, org.springframework.jdbc.core.JdbcTemplate db) {
        this.service = service; this.db = db;
    }
    @GetMapping Map<String, Object> history() { return AccountingPeriodService.readState(db); }
    @GetMapping("/preview") AccountingPeriodService.Preview preview(@RequestParam LocalDate endsOn) { return service.preview(endsOn); }
    @PostMapping Map<String, String> close(@RequestBody AccountingPeriodService.Close body,
            @RequestHeader("Idempotency-Key") String key, Principal user) {
        return Map.of("id", service.close(body, key, user.getName()));
    }
    @PostMapping("/{id}/reopen") Map<String, String> reopen(@PathVariable String id,
            @RequestBody AccountingPeriodService.Reopen body, @RequestHeader("Idempotency-Key") String key, Principal user) {
        return Map.of("id", service.reopen(id, body, key, user.getName()));
    }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> invalid(IllegalArgumentException error) { return Map.of("message", error.getMessage()); }
}
