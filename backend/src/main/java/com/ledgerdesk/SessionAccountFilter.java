package com.ledgerdesk;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.filter.OncePerRequestFilter;

final class SessionAccountFilter extends OncePerRequestFilter {
    private final PersistentAccounts accounts;
    SessionAccountFilter(PersistentAccounts accounts) { this.accounts = accounts; }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof SessionAccount account) {
            boolean valid;
            try { valid = account.stillMatches(accounts.load(account.getUsername())); }
            catch (UsernameNotFoundException error) { valid = false; }
            if (!valid) {
                // Password resets, role changes and disabled accounts take effect on the next request.
                var session = request.getSession(false);
                if (session != null) session.invalidate();
                SecurityContextHolder.clearContext();
                SecurityConfig.sessionResponse(response, 401, "Sign in again.");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
