package com.ledgerdesk;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {
    @Bean
    UserDetailsService users(@Value("${app.username:${APP_USERNAME}}") String username,
                            @Value("${app.password:${APP_PASSWORD}}") String password,
                            @Value("${app.reviewer.username:}") String reviewer,
                            @Value("${app.reviewer.password:}") String reviewerPassword,
                            @Value("${app.accounts.persistent:false}") boolean persistent,
                            PersistentAccounts accounts,
                            PasswordEncoder encoder) {
        if (persistent) {
            accounts.bootstrap(username, password, reviewer, reviewerPassword, encoder);
            return accounts::load;
        }
        var users = new InMemoryUserDetailsManager(User.withUsername(username)
                .password(encoder.encode(password)).roles("OWNER").build());
        if (!reviewer.isBlank() || !reviewerPassword.isBlank()) {
            if (reviewer.isBlank() || reviewerPassword.isBlank() || reviewer.equals(username))
                throw new IllegalArgumentException("Configure a distinct reviewer username and a nonblank password together.");
            users.createUser(User.withUsername(reviewer).password(encoder.encode(reviewerPassword)).roles("REVIEWER").build());
        }
        return users;
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        // CSRF remains enabled, including for authenticated API writes.
        return http.authorizeHttpRequests(auth -> auth.requestMatchers("/api/csrf").permitAll()
                .requestMatchers("/api/accounts", "/api/accounts/**").hasRole("OWNER")
                .requestMatchers(HttpMethod.GET, "/**").authenticated()
                .requestMatchers(HttpMethod.HEAD, "/**").authenticated()
                // Only listed routine operations are delegated; new writes stay owner-only.
                .requestMatchers(HttpMethod.POST,
                        "/api/customers", "/api/vendors", "/api/invoices", "/api/invoices/*/payments", "/api/invoices/*/void",
                        "/api/drafts", "/api/drafts/*", "/api/drafts/*/post", "/api/drafts/*/discard",
                        "/api/bills", "/api/bills/*/payments", "/api/bills/*/void", "/api/bills/*/receipts",
                        "/api/expenses", "/api/expenses/*/reverse", "/api/expenses/*/receipts",
                        "/api/adjustments", "/api/adjustments/*/reverse",
                        "/api/accruals", "/api/accruals/*/reverse", "/api/accruals/*/bill",
                        "/api/prepaid", "/api/prepaid/*/recognize", "/api/prepaid/*/cancel", "/api/prepaid/*/correct",
                        "/api/assets", "/api/assets/*/depreciate", "/api/assets/*/correct", "/api/assets/*/retire",
                        "/api/bank/imports", "/api/bank/imports/preview",
                        "/api/bank/transactions/*/match", "/api/bank/transactions/*/unmatch",
                        "/api/bank/reconciliations", "/api/bank/reconciliations/preview")
                .hasAnyRole("OWNER", "BOOKKEEPER")
                .anyRequest().hasRole("OWNER")).httpBasic(Customizer.withDefaults()).build();
    }
}
