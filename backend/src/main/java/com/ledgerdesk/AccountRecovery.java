package com.ledgerdesk;

import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountRecovery {
    private final JdbcTemplate db;
    private final PasswordEncoder encoder;
    private final boolean persistent;
    public AccountRecovery(JdbcTemplate db, PasswordEncoder encoder, @Value("${app.accounts.persistent:false}") boolean persistent) {
        this.db=db; this.encoder=encoder; this.persistent=persistent;
    }
    @Transactional
    public void recover(String username,String password,String reason) {
        if(!persistent) throw new IllegalArgumentException("Recovery requires persistent account mode.");
        PersistentAccounts.validate(username,password);
        String explanation=LedgerService.text(reason,240,"Recovery reason");
        db.queryForObject("SELECT id FROM businesses WHERE id=1 FOR UPDATE",Long.class);
        var rows=db.queryForList("SELECT u.id FROM app_users u JOIN business_memberships m ON m.user_id=u.id WHERE u.username=? AND m.business_id=1",username);
        if(rows.size()!=1) throw new IllegalArgumentException("Choose an existing account in this business.");
        String userId=rows.get(0).get("id").toString(), id=UUID.randomUUID().toString();
        LocalDateTime now=LocalDateTime.now();
        db.update("UPDATE app_users SET enabled=TRUE,password_hash=? WHERE id=?",encoder.encode(password),userId);
        db.update("UPDATE business_memberships SET role='OWNER' WHERE user_id=? AND business_id=1",userId);
        db.update("INSERT INTO account_recoveries VALUES (?,?,1,?,?)",id,userId,now,explanation);
        db.update("INSERT INTO audit_events VALUES (?,?,?,?,?)",UUID.randomUUID().toString(),now,"offline-recovery","ACCOUNT_OWNER_RECOVERED",userId);
    }
}
