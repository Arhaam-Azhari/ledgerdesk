package com.ledgerdesk;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AccessController {
    @GetMapping("/api/access")
    ResponseEntity<Map<String, Object>> access(Authentication user) {
        boolean canWrite = user.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_OWNER"));
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(Map.of("username", user.getName(), "canWrite", canWrite, "role", canWrite ? "OWNER" : "REVIEWER"));
    }
}
