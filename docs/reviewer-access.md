# Reviewer access checkpoint

An optional configured reviewer can read authenticated GET/HEAD routes but cannot post, edit, delete or call any future write route. The owner remains the only role allowed to use other HTTP methods, and CSRF protection still applies. The security filter enforces this before a controller runs; hiding a button is not the authorization boundary.

Configure `app.reviewer.username` and `app.reviewer.password` together using an external configuration file or Spring environment variables `APP_REVIEWER_USERNAME` and `APP_REVIEWER_PASSWORD`. The reviewer username must differ from the owner. Leave both unset to retain an owner-only installation. Do not commit real passwords. Passwords are BCrypt encoded in the in-memory user store at startup.

`GET /api/access` returns username, role and `canWrite` with `Cache-Control: no-store`. The frontend has not yet adopted this identity response: write controls remain visible, but reviewer writes receive 403. Reviewer-specific navigation and browser proof are the next checkpoint. Demo reviewer credentials are not enabled by default.

Five integration tests check real reviewer credentials, report/state reads, valid-CSRF blocked writes across accounting modules, PATCH/DELETE/future-route denial, owner writes with CSRF, identity response and invalid/anonymous access. CI verification is pending. Persistent users, account management, bookkeeper roles, business memberships and hosted sessions remain future work. This is still the single-business local Basic-auth development setup.
