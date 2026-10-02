package com.ledgerdesk;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class PersistentAccountsTest {
    @Autowired PersistentAccounts accounts;
    @Autowired JdbcTemplate db;
    @Autowired PasswordEncoder encoder;
    private final String secret = "owner-test-only-password";
    @BeforeEach void clean() { DatabaseFixture.reset(db); }
    private void seed() { accounts.bootstrap("owner",secret,"reviewer","reviewer-test-password",encoder); }
    @Test void passwordsAndMembershipsSurviveANewServiceInstance() {
        seed();
        var restarted = new PersistentAccounts(db);
        var owner = restarted.load("owner");
        assertThat(owner.getPassword()).startsWith("$2").isNotEqualTo(secret);
        assertThat(encoder.matches(secret,owner.getPassword())).isTrue();
        assertThat(owner.getAuthorities()).extracting(a -> a.getAuthority()).containsExactly("ROLE_OWNER");
        assertThat(restarted.load("reviewer").getAuthorities()).extracting(a -> a.getAuthority()).containsExactly("ROLE_REVIEWER");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM business_memberships",Integer.class)).isEqualTo(2);
    }
    @Test void repeatedBootstrapCannotResetPasswordsRolesOrDisabledState() {
        seed(); var original = accounts.load("owner").getPassword();
        db.update("UPDATE app_users SET enabled=FALSE WHERE username='owner'");
        db.update("UPDATE business_memberships SET role='REVIEWER' WHERE user_id=(SELECT id FROM app_users WHERE username='owner')");
        accounts.bootstrap("owner","replacement-password","","",encoder);
        var owner = accounts.load("owner");
        assertThat(owner.getPassword()).isEqualTo(original); assertThat(owner.isEnabled()).isFalse();
        assertThat(owner.getAuthorities()).extracting(a -> a.getAuthority()).containsExactly("ROLE_REVIEWER");
        assertThat(encoder.matches("replacement-password",owner.getPassword())).isFalse();
    }
    @Test void otherBusinessMembershipDoesNotGrantAccessToThisBusiness() {
        seed(); db.update("INSERT INTO businesses (id,name,currency) VALUES (2,'Other','USD')");
        db.update("UPDATE business_memberships SET business_id=2 WHERE user_id=(SELECT id FROM app_users WHERE username='reviewer')");
        assertThatThrownBy(() -> accounts.load("reviewer")).isInstanceOf(UsernameNotFoundException.class);
        assertThatThrownBy(() -> accounts.load("unknown")).isInstanceOf(UsernameNotFoundException.class);
    }
    @Test void invalidBootstrapLeavesNoPartialUsersOrMemberships() {
        assertThatThrownBy(() -> accounts.bootstrap("owner",secret,"owner",secret,encoder)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> accounts.bootstrap("owner",secret,"reviewer","short",encoder)).isInstanceOf(IllegalArgumentException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM app_users",Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM business_memberships",Integer.class)).isZero();
    }
    @Test void usernameAndUtf8PasswordBoundsAreValidated() {
        for (String username : new String[]{"", " owner", "x".repeat(101)})
            assertThatThrownBy(() -> accounts.bootstrap(username,secret,"","",encoder)).isInstanceOf(IllegalArgumentException.class);
        for (String password : new String[]{"short", "x".repeat(73), "é".repeat(37)})
            assertThatThrownBy(() -> accounts.bootstrap("owner",password,"","",encoder)).isInstanceOf(IllegalArgumentException.class);
        accounts.bootstrap("owner","x".repeat(72),"","",encoder);
        assertThat(encoder.matches("x".repeat(72),accounts.load("owner").getPassword())).isTrue();
    }
    @Test void persistentSecurityProviderUsesStoredHashesAndRoles() {
        var provider = new SecurityConfig().users("owner",secret,"reviewer","reviewer-test-password",true,accounts,encoder);
        assertThat(encoder.matches(secret,provider.loadUserByUsername("owner").getPassword())).isTrue();
        assertThat(provider.loadUserByUsername("reviewer").getAuthorities()).extracting(a -> a.getAuthority()).containsExactly("ROLE_REVIEWER");
        assertThatThrownBy(() -> db.update("UPDATE business_memberships SET role='ADMIN'")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
