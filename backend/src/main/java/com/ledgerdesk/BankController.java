package com.ledgerdesk;

import java.security.Principal;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/bank")
public class BankController {
    private final BankService bank;
    public BankController(BankService bank) { this.bank = bank; }
    @PostMapping("/imports/preview") BankService.Preview preview(@RequestBody BankService.Import body) {
        return bank.preview(body);
    }
    @PostMapping("/imports") Map<String, String> importCsv(@RequestBody BankService.Import body,
            @RequestHeader("Idempotency-Key") String key, Principal user) {
        return Map.of("id", bank.importCsv(body, key, user.getName()));
    }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> invalid(IllegalArgumentException error) { return Map.of("message", error.getMessage()); }
}
