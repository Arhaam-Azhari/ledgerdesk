# Offline owner recovery

This tool is for the person who controls the local database and backend machine. It bypasses login to recover an existing account in business 1 as an enabled owner, sets a new BCrypt password hash, and records the reason and account action. It cannot create a new user or recover an account whose membership belongs only to another business. There is no HTTP recovery endpoint.

Stop the normal backend and take a database backup first. From the backend directory, use the same database configuration as the normal app and run:

```sh
java -jar target/ledgerdesk-0.1.0.jar --spring.main.web-application-type=none --app.accounts.persistent=true --app.recovery.enabled=true
```

For the local H2 demo, add `--spring.profiles.active=demo` and use the same working directory as the normal demo so it opens the existing database. The command prompts for the exact existing username, replacement password and a recovery reason. Password input is hidden with a terminal console. Without a console, it reads three lines from stdin without echoing them; avoid command-line arguments, shell history and saved scripts containing passwords. Database connection secrets stay in the usual external configuration.

A successful command prints a confirmation and closes the application context without serving HTTP. Start the normal backend without recovery flags and sign in with the new password. Verify the owner's membership and account activity. The old password no longer works; the recovered account is enabled and has OWNER membership. If startup or validation fails, inspect the error and do not assume recovery succeeded. Unknown accounts and invalid passwords/reasons leave no changes.

Recovery writes the password hash, enabled state, membership role, recovery reason and audit action in one transaction. Audit failure rolls everything back. Reasons are retained in `account_recoveries`; the Activity page shows the recovery action and account ID. This is application history, not a tamper-proof security log. It is not hosted password-reset email, backup restoration, crash recovery or multi-business administration.

Six integration tests cover password replacement and authentication, restoration of a disabled reviewer, business/unknown-account guards, input validation, atomic rollback and absence of an HTTP recovery route. Source `9904cb9fe4b714323abcde02c4b0c5926e0dc472` passed [Actions run 37075785948](https://github.com/Arhaam-Azhari/ledgerdesk/actions/runs/37075785948): 229 integration tests on each of H2 and PostgreSQL 17, no failures/errors/skips, production frontend build and all 20 Chromium workflows. The extended persistent workflow stops the Java backend, runs the packaged non-web recovery command with three stdin lines, checks a successful exit/confirmation and that the password is absent from output, then starts the normal backend against the same file. The old owner password returns 401, the recovered password authenticates as owner, supplier data remains and account activity includes the recovery action. This is real command-process proof on file-backed H2, not PostgreSQL process recovery, backup restoration or crash recovery.
