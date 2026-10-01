package com.ledgerdesk;

import java.time.LocalDate;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reports")
public class ReportController {
    private final ReportService reports;
    public ReportController(ReportService reports) { this.reports = reports; }
    @GetMapping ResponseEntity<ReportService.Reports> reports(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startsOn,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endsOn) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(reports.reports(startsOn, endsOn));
    }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> invalid(IllegalArgumentException error) { return Map.of("message", error.getMessage()); }
}
