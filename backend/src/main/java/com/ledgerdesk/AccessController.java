package com.ledgerdesk;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AccessController {
    private final boolean persistent;
    public AccessController(@Value("${app.accounts.persistent:false}") boolean persistent) { this.persistent=persistent; }
    @GetMapping("/api/access")
    ResponseEntity<Map<String, Object>> access(Authentication user) {
        boolean owner = user.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_OWNER"));
        boolean bookkeeper = user.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_BOOKKEEPER"));
        boolean canWrite = owner || bookkeeper;
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(Map.of("username", user.getName(), "canWrite", canWrite, "role", owner ? "OWNER" : bookkeeper ? "BOOKKEEPER" : "REVIEWER", "persistentAccounts", persistent));
    }
}
