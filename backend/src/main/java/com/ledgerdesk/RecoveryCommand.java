package com.ledgerdesk;

import java.io.BufferedReader;
import java.io.Console;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.context.WebApplicationContext;

@Component
@ConditionalOnProperty(name="app.recovery.enabled",havingValue="true")
public class RecoveryCommand implements CommandLineRunner {
    private final AccountRecovery recovery;
    private final ConfigurableApplicationContext context;
    public RecoveryCommand(AccountRecovery recovery,ConfigurableApplicationContext context) { this.recovery=recovery; this.context=context; }
    @Override public void run(String... args) throws Exception {
        if(context instanceof WebApplicationContext)
            throw new IllegalArgumentException("Recovery must run with spring.main.web-application-type=none. Stop the normal backend first.");
        Console console=System.console();
        BufferedReader input=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8));
        String username, password, reason;
        if(console!=null) {
            username=console.readLine("Existing account username: ");
            char[] secret=console.readPassword("Replacement password: ");
            if(secret==null) throw new IllegalArgumentException("Recovery input was cancelled.");
            password=new String(secret); Arrays.fill(secret,'\0');
            reason=console.readLine("Recovery reason: ");
        } else {
            System.out.println("Reading username, replacement password and recovery reason from three stdin lines. Input is not echoed.");
            username=input.readLine(); password=input.readLine(); reason=input.readLine();
        }
        recovery.recover(username,password,reason);
        System.out.println("Owner access recovered. Start the normal backend and sign in with the replacement password.");
        context.close();
    }
}
