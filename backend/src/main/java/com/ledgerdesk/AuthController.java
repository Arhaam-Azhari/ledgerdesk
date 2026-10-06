package com.ledgerdesk;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {
    private final String mode;
    public AuthController(@Value("${app.auth.mode:basic}") String mode) { this.mode = mode; }
    @GetMapping("/api/auth")
    ResponseEntity<Map<String, String>> mode() {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(Map.of("mode", mode));
    }
}
