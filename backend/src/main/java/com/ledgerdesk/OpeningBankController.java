package com.ledgerdesk;

import java.security.Principal;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/opening-bank-balance")
public class OpeningBankController {
    private final OpeningBankBalance opening;
    private final org.springframework.jdbc.core.JdbcTemplate db;
    public OpeningBankController(OpeningBankBalance opening, org.springframework.jdbc.core.JdbcTemplate db) { this.opening = opening; this.db = db; }
    @GetMapping Map<String, Object> read() { return OpeningBankBalance.readState(db); }
    @PostMapping Map<String, String> post(@RequestBody OpeningBankBalance.Opening body,
            @RequestHeader("Idempotency-Key") String key, Principal user) {
        return Map.of("id", opening.post(body, key, user.getName()));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> invalid(IllegalArgumentException error) { return Map.of("message", error.getMessage()); }
}
