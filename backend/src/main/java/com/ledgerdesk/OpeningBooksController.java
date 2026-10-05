package com.ledgerdesk;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/opening-books")
public class OpeningBooksController {
    private final OpeningBooksService service;
    public OpeningBooksController(OpeningBooksService service) { this.service = service; }
    @PostMapping("/preview") ResponseEntity<OpeningBooksService.Preview> preview(@RequestBody OpeningBooksService.Request request) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.preview(request));
    }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> invalid(IllegalArgumentException error) { return Map.of("message", error.getMessage()); }
}
