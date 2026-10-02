package com.ledgerdesk;

import java.security.Principal;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/adjustments")
public class AdjustmentController {
    private final AdjustmentService adjustments;
    public AdjustmentController(AdjustmentService adjustments) { this.adjustments = adjustments; }
    @PostMapping
    Map<String, String> post(@RequestBody AdjustmentService.Adjustment body,
            @RequestHeader("Idempotency-Key") String key, Principal user) {
        return Map.of("id", adjustments.post(body, key, user.getName()));
    }
    @PostMapping("/{id}/reverse")
    Map<String, String> reverse(@PathVariable String id, @RequestBody AdjustmentService.Reversal body,
            @RequestHeader("Idempotency-Key") String key, Principal user) {
        return Map.of("id", adjustments.reverse(id, body, key, user.getName()));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> invalid(IllegalArgumentException error) { return Map.of("message", error.getMessage()); }
}
