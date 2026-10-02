package com.ledgerdesk;

import java.security.Principal;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/assets")
public class FixedAssetController {
    private final FixedAssetService assets;
    public FixedAssetController(FixedAssetService assets) { this.assets = assets; }
    @PostMapping
    Map<String, String> create(@RequestBody FixedAssetService.Asset body, @RequestHeader("Idempotency-Key") String key, Principal user) {
        return Map.of("id", assets.create(body, key, user.getName()));
    }
    @PostMapping("/{id}/depreciate")
    Map<String, String> depreciate(@PathVariable String id, @RequestBody FixedAssetService.Depreciation body,
            @RequestHeader("Idempotency-Key") String key, Principal user) {
        return Map.of("id", assets.depreciate(id, body, key, user.getName()));
    }
    @PostMapping("/{id}/correct")
    Map<String, String> correct(@PathVariable String id, @RequestBody FixedAssetService.Correction body,
            @RequestHeader("Idempotency-Key") String key, Principal user) {
        return Map.of("id", assets.correct(id, body, key, user.getName()));
    }
    @PostMapping("/{id}/retire")
    Map<String, String> retire(@PathVariable String id, @RequestBody FixedAssetService.Retirement body,
            @RequestHeader("Idempotency-Key") String key, Principal user) {
        return Map.of("id", assets.retire(id, body, key, user.getName()));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> invalid(IllegalArgumentException error) { return Map.of("message", error.getMessage()); }
}
