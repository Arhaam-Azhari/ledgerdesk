package com.ledgerdesk;

import java.security.Principal;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class OwnPasswordController {
    private final AccountManagement accounts;
    public OwnPasswordController(AccountManagement accounts) { this.accounts=accounts; }
    @PostMapping("/api/me/password")
    ResponseEntity<Map<String,String>> change(@RequestBody AccountManagement.Password request,Principal user) {
        accounts.ownPassword(request,user.getName());
        return ResponseEntity.ok().header("Cache-Control","no-store")
                .body(Map.of("message","Password changed. Sign in again."));
    }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String,String> invalid(IllegalArgumentException error) { return Map.of("message",error.getMessage()); }
}
