package com.ledgerdesk;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PersistentAccounts {
    private final JdbcTemplate db;
    public PersistentAccounts(JdbcTemplate db) { this.db = db; }

    @Transactional
    public void bootstrap(String owner, String password, String reviewer, String reviewerPassword, PasswordEncoder encoder) {
        // Serialize first-time setup; restarts must never reset stored passwords or roles.
        db.queryForObject("SELECT id FROM businesses WHERE id=1 FOR UPDATE", Long.class);
        if (db.queryForObject("SELECT COUNT(*) FROM app_users", Integer.class) > 0) return;
        validate(owner, password);
        boolean includeReviewer = !reviewer.isBlank() || !reviewerPassword.isBlank();
        if (includeReviewer) {
            validate(reviewer, reviewerPassword);
            if (reviewer.equals(owner)) throw new IllegalArgumentException("Use a distinct reviewer username.");
        }
        add(owner, password, "OWNER", encoder);
        if (includeReviewer) add(reviewer, reviewerPassword, "REVIEWER", encoder);
    }
    static void validate(String username, String password) {
        if (username == null || username.isBlank() || username.length() > 100 || !username.equals(username.trim()))
            throw new IllegalArgumentException("Use a username of one to 100 characters without surrounding whitespace.");
        if (password == null || password.isBlank() || password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new IllegalArgumentException("Use a password of at least 12 characters and at most 72 UTF-8 bytes.");
    }
    private void add(String username, String password, String role, PasswordEncoder encoder) {
        String id = UUID.randomUUID().toString();
        db.update("INSERT INTO app_users (id,username,password_hash,enabled) VALUES (?,?,?,TRUE)", id, username, encoder.encode(password));
        db.update("INSERT INTO business_memberships VALUES (?,1,?)", id, role);
    }
    @Transactional(readOnly = true)
    public UserDetails load(String username) {
        var users = db.queryForList("""
            SELECT u.username,u.password_hash,u.enabled,m.role
            FROM app_users u JOIN business_memberships m ON m.user_id=u.id
            WHERE u.username=? AND m.business_id=1
            """, username);
        if (users.size() != 1) throw new UsernameNotFoundException("Account not available.");
        var user = users.get(0);
        return User.withUsername(user.get("username").toString()).password(user.get("password_hash").toString())
                .roles(user.get("role").toString()).disabled(!Boolean.TRUE.equals(user.get("enabled"))).build();
    }
}
