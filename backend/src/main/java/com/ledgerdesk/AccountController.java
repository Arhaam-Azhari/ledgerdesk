package com.ledgerdesk;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {
    private final AccountManagement accounts;
    public AccountController(AccountManagement accounts) { this.accounts=accounts; }
    @GetMapping ResponseEntity<List<Map<String,Object>>> list(Principal user) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(accounts.list(user.getName()));
    }
    @PostMapping Map<String,String> create(@RequestBody AccountManagement.Create request,Principal user) {
        return Map.of("id",accounts.create(request,user.getName()));
    }
    @PostMapping("/{id}/access") Map<String,String> access(@PathVariable String id,@RequestBody AccountManagement.Access request,Principal user) {
        accounts.access(id,request,user.getName()); return Map.of("id",id);
    }
    @PostMapping("/{id}/password") Map<String,String> password(@PathVariable String id,@RequestBody AccountManagement.Password request,Principal user) {
        accounts.password(id,request,user.getName()); return Map.of("id",id);
    }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String,String> invalid(IllegalArgumentException error) { return Map.of("message",error.getMessage()); }
}
