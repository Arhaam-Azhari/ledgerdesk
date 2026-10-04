package com.ledgerdesk;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountManagement {
    public record Create(String username, String password, String role) {}
    public record Access(String role, Boolean enabled) {}
    public record Password(String password, String currentPassword) {}
    private final JdbcTemplate db;
    private final PasswordEncoder encoder;
    private final boolean persistent;
    public AccountManagement(JdbcTemplate db, PasswordEncoder encoder, @Value("${app.accounts.persistent:false}") boolean persistent) {
        this.db=db; this.encoder=encoder; this.persistent=persistent;
    }
    private void owner(String actor) {
        if (!persistent) throw new IllegalArgumentException("Account management requires persistent account mode.");
        if (db.queryForObject("SELECT COUNT(*) FROM app_users u JOIN business_memberships m ON m.user_id=u.id WHERE u.username=? AND u.enabled=TRUE AND m.business_id=1 AND m.role='OWNER'",Integer.class,actor)!=1)
            throw new IllegalArgumentException("An enabled owner membership is required.");
    }
    private void lock(String actor) {
        db.queryForObject("SELECT id FROM businesses WHERE id=1 FOR UPDATE",Long.class);
        owner(actor);
    }
    private static void role(String role) {
        if (role==null || !List.of("OWNER","BOOKKEEPER","REVIEWER").contains(role)) throw new IllegalArgumentException("Choose OWNER, BOOKKEEPER or REVIEWER.");
    }
    private Map<String,Object> account(String id) {
        var rows=db.queryForList("SELECT u.id,u.username,u.password_hash,u.enabled,m.role FROM app_users u JOIN business_memberships m ON m.user_id=u.id WHERE u.id=? AND m.business_id=1",id);
        if(rows.size()!=1) throw new IllegalArgumentException("Account not found in this business.");
        return rows.get(0);
    }
    private void audit(String id,String actor,String action) {
        db.update("INSERT INTO audit_events VALUES (?,?,?,?,?)",UUID.randomUUID().toString(),LocalDateTime.now(),actor,action,id);
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> list(String actor) {
        owner(actor);
        return db.queryForList("SELECT u.id,u.username,u.enabled,m.role FROM app_users u JOIN business_memberships m ON m.user_id=u.id WHERE m.business_id=1 ORDER BY u.username,u.id");
    }
    @Transactional
    public String create(Create request,String actor) {
        lock(actor);
        if(request==null) throw new IllegalArgumentException("Enter account details.");
        PersistentAccounts.validate(request.username(),request.password()); role(request.role());
        if(db.queryForObject("SELECT COUNT(*) FROM app_users WHERE username=?",Integer.class,request.username())>0)
            throw new IllegalArgumentException("That username is already in use.");
        String id=UUID.randomUUID().toString();
        db.update("INSERT INTO app_users (id,username,password_hash,enabled) VALUES (?,?,?,TRUE)",id,request.username(),encoder.encode(request.password()));
        db.update("INSERT INTO business_memberships VALUES (?,1,?)",id,request.role());
        audit(id,actor,"ACCOUNT_CREATED"); return id;
    }
    @Transactional
    public void access(String id,Access request,String actor) {
        lock(actor); var previous=account(id);
        if(request==null || request.enabled()==null) throw new IllegalArgumentException("Choose the role and enabled state.");
        role(request.role());
        boolean wasOwner=Boolean.TRUE.equals(previous.get("enabled")) && previous.get("role").equals("OWNER");
        if(wasOwner && (!request.enabled() || !request.role().equals("OWNER")) && db.queryForObject("SELECT COUNT(*) FROM app_users u JOIN business_memberships m ON m.user_id=u.id WHERE u.enabled=TRUE AND m.business_id=1 AND m.role='OWNER'",Integer.class)<=1)
            throw new IllegalArgumentException("Keep at least one enabled owner.");
        if(previous.get("role").equals(request.role()) && previous.get("enabled").equals(request.enabled())) return;
        db.update("UPDATE app_users SET enabled=? WHERE id=?",request.enabled(),id);
        db.update("UPDATE business_memberships SET role=? WHERE user_id=? AND business_id=1",request.role(),id);
        audit(id,actor,"ACCOUNT_ACCESS_CHANGED");
    }
    @Transactional
    public void password(String id,Password request,String actor) {
        lock(actor); var previous=account(id);
        if(request==null) throw new IllegalArgumentException("Enter a new password.");
        PersistentAccounts.validate(previous.get("username").toString(),request.password());
        if(previous.get("username").equals(actor) && (request.currentPassword()==null || !encoder.matches(request.currentPassword(),previous.get("password_hash").toString())))
            throw new IllegalArgumentException("Enter your current password to change your own login.");
        db.update("UPDATE app_users SET password_hash=? WHERE id=?",encoder.encode(request.password()),id);
        audit(id,actor,"ACCOUNT_PASSWORD_CHANGED");
    }
    @Transactional
    public void ownPassword(Password request,String actor) {
        if (!persistent) throw new IllegalArgumentException("Password changes require persistent account mode.");
        db.queryForObject("SELECT id FROM businesses WHERE id=1 FOR UPDATE",Long.class);
        // Resolve the target from the authenticated name, never from client account IDs.
        var rows=db.queryForList("SELECT u.id,u.password_hash FROM app_users u JOIN business_memberships m ON m.user_id=u.id WHERE u.username=? AND u.enabled=TRUE AND m.business_id=1",actor);
        if(rows.size()!=1) throw new IllegalArgumentException("An enabled account in this business is required.");
        if(request==null) throw new IllegalArgumentException("Enter your current and new passwords.");
        var previous=rows.get(0);
        if(request.currentPassword()==null || request.currentPassword().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72
                || !encoder.matches(request.currentPassword(),previous.get("password_hash").toString()))
            throw new IllegalArgumentException("Enter your current password to change your own login.");
        PersistentAccounts.validate(actor,request.password());
        if(encoder.matches(request.password(),previous.get("password_hash").toString()))
            throw new IllegalArgumentException("Choose a different new password.");
        db.update("UPDATE app_users SET password_hash=? WHERE id=?",encoder.encode(request.password()),previous.get("id"));
        audit(previous.get("id").toString(),actor,"ACCOUNT_SELF_PASSWORD_CHANGED");
    }

}
