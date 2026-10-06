package com.ledgerdesk;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.stream.Collectors;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

final class SessionAccount extends User {
    private static final long serialVersionUID = 1L;
    private final String stamp;

    SessionAccount(UserDetails user) {
        super(user.getUsername(), user.getPassword(), user.isEnabled(),
                user.isAccountNonExpired(), user.isCredentialsNonExpired(),
                user.isAccountNonLocked(), user.getAuthorities());
        stamp = stamp(user);
    }

    boolean stillMatches(UserDetails current) { return stamp.equals(stamp(current)); }

    private static String stamp(UserDetails user) {
        // Keep a fingerprint after Spring erases credentials from the session principal.
        String roles = user.getAuthorities().stream().map(a -> a.getAuthority()).sorted().collect(Collectors.joining(","));
        String value = user.getUsername() + "\0" + user.getPassword() + "\0" + user.isEnabled() + "\0" + roles;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
