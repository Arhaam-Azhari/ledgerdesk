package com.ledgerdesk;

import java.util.Map;
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
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> invalid(IllegalArgumentException error) { return Map.of("message", error.getMessage()); }
}
