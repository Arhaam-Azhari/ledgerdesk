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
                            @Value("${app.auth.mode:basic}") String mode,
                            PersistentAccounts accounts,
                            PasswordEncoder encoder) {
        if (persistent) {
            accounts.bootstrap(username, password, reviewer, reviewerPassword, encoder);
            return mode.equals("session") ? name -> new SessionAccount(accounts.load(name)) : accounts::load;
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
    SecurityFilterChain security(HttpSecurity http, PersistentAccounts accounts,
            @Value("${app.auth.mode:basic}") String mode,
            @Value("${app.accounts.persistent:false}") boolean persistent) throws Exception {
        if (!mode.equals("basic") && !mode.equals("session")) throw new IllegalArgumentException("Choose basic or session authentication.");
        if (mode.equals("session") && !persistent) throw new IllegalArgumentException("Session login requires stored accounts.");
        // CSRF remains enabled, including for authenticated API writes.
        http.authorizeHttpRequests(auth -> auth.requestMatchers("/api/csrf", "/api/auth").permitAll()
                .requestMatchers("/api/accounts", "/api/accounts/**").hasRole("OWNER")
                .requestMatchers(HttpMethod.POST, "/api/me/password").hasAnyRole("OWNER", "BOOKKEEPER", "REVIEWER")
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
                .anyRequest().hasRole("OWNER"));
        if (mode.equals("basic")) return http.httpBasic(Customizer.withDefaults()).build();
        http.httpBasic(basic -> basic.disable())
                .requestCache(cache -> cache.disable())
                .sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
                .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, error) -> sessionResponse(response, 401, "Sign in to continue.")))
                .formLogin(login -> login.loginPage("/").loginProcessingUrl("/api/session/login")
                        .successHandler((request, response, user) -> sessionResponse(response, 200, "Signed in."))
                        .failureHandler((request, response, error) -> {
                            var session = request.getSession(false);
                            if (session != null) session.invalidate();
                            sessionResponse(response, 401, "Check your username and password.");
                        })
                        .permitAll())
                .logout(logout -> logout.logoutUrl("/api/session/logout").deleteCookies("JSESSIONID")
                        .logoutSuccessHandler((request, response, user) -> sessionResponse(response, 200, "Signed out.")));
        http.addFilterBefore(new SessionAccountFilter(accounts), org.springframework.security.web.access.intercept.AuthorizationFilter.class);
        return http.build();
    }
    static void sessionResponse(jakarta.servlet.http.HttpServletResponse response, int status, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"message\":\"" + message + "\"}");
    }
}
