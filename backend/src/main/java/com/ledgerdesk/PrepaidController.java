package com.ledgerdesk;

import java.security.Principal;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/prepaid")
public class PrepaidController {
    private final PrepaidService prepaid;
    public PrepaidController(PrepaidService prepaid) { this.prepaid = prepaid; }
    @PostMapping
    Map<String, String> create(@RequestBody PrepaidService.Plan body, @RequestHeader("Idempotency-Key") String key, Principal user) {
        return Map.of("id", prepaid.create(body, key, user.getName()));
    }
    @PostMapping("/{id}/recognize")
    Map<String, String> recognize(@PathVariable String id, @RequestBody PrepaidService.Recognition body,
            @RequestHeader("Idempotency-Key") String key, Principal user) {
        return Map.of("id", prepaid.recognize(id, body, key, user.getName()));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> invalid(IllegalArgumentException error) { return Map.of("message", error.getMessage()); }
}
