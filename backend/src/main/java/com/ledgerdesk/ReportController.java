package com.ledgerdesk;

import java.time.LocalDate;
import java.io.IOException;
import org.springframework.http.MediaType;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reports")
public class ReportController {
    private final ReportService reports;
    private final CustomerStatementService statements;
    private final CustomerStatementPdf pdf;
    private final AccountActivityService accounts;
    public ReportController(ReportService reports, CustomerStatementService statements, CustomerStatementPdf pdf,
            AccountActivityService accounts) {
        this.reports = reports;
        this.statements = statements;
        this.pdf = pdf;
        this.accounts = accounts;
    }
    @GetMapping ResponseEntity<ReportService.Reports> reports(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startsOn,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endsOn) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(reports.reports(startsOn, endsOn));
    }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> invalid(IllegalArgumentException error) { return Map.of("message", error.getMessage()); }

    @GetMapping("/accounts/{code}/activity") ResponseEntity<AccountActivityService.Activity> accountActivity(
            @PathVariable String code,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startsOn,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endsOn) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(accounts.activity(code, startsOn, endsOn));
    }

    @GetMapping("/customers/{customerId}/statement") ResponseEntity<CustomerStatementService.Statement> statement(
            @PathVariable String customerId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startsOn,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endsOn) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(statements.statement(customerId, startsOn, endsOn));
    }

    @GetMapping("/customers/{customerId}/statement/pdf") ResponseEntity<byte[]> statementPdf(
            @PathVariable String customerId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startsOn,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endsOn) throws IOException {
        var statement = statements.statement(customerId, startsOn, endsOn);
        // Keep contact text out of response headers; the document identifies the customer.
        String filename = "ledgerdesk-customer-statement-" + startsOn + "-" + endsOn + ".pdf";
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header("Cache-Control", "no-store")
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .body(pdf.render(statement));
    }

    @GetMapping("/profit-comparison") ResponseEntity<ReportService.ProfitComparison> comparison(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startsOn,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endsOn,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate previousStartsOn,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate previousEndsOn) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(reports.compareProfit(startsOn, endsOn, previousStartsOn, previousEndsOn));
    }
}
