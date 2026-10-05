package com.ledgerdesk;

import java.util.Map;
import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/year-end")
public class YearEndController {
    private final YearEndService service;
    public YearEndController(YearEndService service) { this.service = service; }
    @GetMapping("/preview") ResponseEntity<YearEndService.Preview> preview(@RequestParam int year) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.preview(year));
    }
    @GetMapping ResponseEntity<Map<String, Object>> history() {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.history());
    }
    @PostMapping Map<String, String> close(@RequestBody YearEndService.Close body,
            @RequestHeader("Idempotency-Key") String key, Principal user) {
        return Map.of("id", service.close(body, key, user.getName()));
    }
    @PostMapping("/{id}/reopen") Map<String, String> reopen(@PathVariable String id, @RequestBody YearEndService.Reopen body,
            @RequestHeader("Idempotency-Key") String key, Principal user) {
        return Map.of("id", service.reopen(id, body, key, user.getName()));
    }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> invalid(IllegalArgumentException error) { return Map.of("message", error.getMessage()); }
}
