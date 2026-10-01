package com.ledgerdesk;

import java.security.Principal;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/equity")
public class EquityController {
    private final EquityService equity;
    public EquityController(EquityService equity) { this.equity = equity; }

    @PostMapping
    Map<String, String> post(@RequestBody EquityService.Transfer body,
            @RequestHeader("Idempotency-Key") String key, Principal user) {
        return Map.of("id", equity.post(body, key, user.getName()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> invalid(IllegalArgumentException error) {
        return Map.of("message", error.getMessage());
    }
}
