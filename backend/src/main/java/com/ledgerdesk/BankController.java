package com.ledgerdesk;

import java.security.Principal;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/bank")
public class BankController {
    private final BankService bank;
    private final BankMatching matching;
    public BankController(BankService bank, BankMatching matching) { this.bank = bank; this.matching = matching; }
    @PostMapping("/imports/preview") BankService.Preview preview(@RequestBody BankService.Import body) {
        return bank.preview(body);
    }
    @PostMapping("/imports") Map<String, String> importCsv(@RequestBody BankService.Import body,
            @RequestHeader("Idempotency-Key") String key, Principal user) {
        return Map.of("id", bank.importCsv(body, key, user.getName()));
    }
    @GetMapping("/transactions/{id}/candidates") java.util.List<Map<String, Object>> candidates(@PathVariable String id) {
        return matching.candidates(id);
    }
    @PostMapping("/transactions/{id}/match") Map<String, String> match(@PathVariable String id,
            @RequestBody BankMatching.Match body, @RequestHeader("Idempotency-Key") String key, Principal user) {
        return Map.of("id", matching.match(id, body, key, user.getName()));
    }
    @PostMapping("/transactions/{id}/unmatch") Map<String, String> unmatch(@PathVariable String id,
            @RequestBody BankMatching.Unmatch body, @RequestHeader("Idempotency-Key") String key, Principal user) {
        return Map.of("id", matching.unmatch(id, body, key, user.getName()));
    }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> invalid(IllegalArgumentException error) { return Map.of("message", error.getMessage()); }
}
