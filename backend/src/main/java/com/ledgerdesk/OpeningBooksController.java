package com.ledgerdesk;

import java.util.Map;
import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/opening-books")
public class OpeningBooksController {
    private final OpeningBooksService service;
    public OpeningBooksController(OpeningBooksService service) { this.service = service; }
    @GetMapping ResponseEntity<Map<String, Object>> history() {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.history());
    }
    @PostMapping Map<String, String> post(@RequestBody OpeningBooksService.Request request,
            @RequestHeader("Idempotency-Key") String key, Principal user) {
        return Map.of("id", service.post(request, key, user.getName()));
    }
    @PostMapping("/preview") ResponseEntity<OpeningBooksService.Preview> preview(@RequestBody OpeningBooksService.Request request) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.preview(request));
    }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> invalid(IllegalArgumentException error) { return Map.of("message", error.getMessage()); }
}
