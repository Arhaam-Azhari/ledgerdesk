# Session login (in progress)

This adds an optional cookie-based login mode. The existing local Basic-auth mode remains the default. The browser reads the configured mode, signs in and out using cookies, and resumes valid sessions after a reload. HTTPS deployment and passing real-cookie checks are still needed before hosted access is ready.

Use the `session` Spring profile with stored owner credentials and database settings. It enables persistent accounts, secure/HttpOnly/SameSite session cookies and a 20-minute idle timeout. Secure cookies require HTTPS. The backend stays on its existing loopback address until deployment is configured.

| Request | Purpose |
| --- | --- |
| GET `/api/auth` | Read the configured login mode |
| GET `/api/csrf` | Obtain the token before login or a write |
| POST `/api/session/login` | Submit URL-encoded `username` and `password`, plus CSRF |
| POST `/api/session/logout` | End the session, with CSRF |

Login rotates the session ID. Session mode rejects Basic headers. Login errors return a generic 401, and API reads require a signed-in session. Failed sign-in clears any existing session. Logout invalidates the session and expires its cookie.

Each authenticated request compares the stored account with the identity captured at login. Password changes/resets, disabled accounts and role changes invalidate old sessions on their next request. The session keeps a fingerprint of the account state, without retaining the password hash after authentication. Existing role restrictions and CSRF-protected writes remain in place.

Six integration tests are prepared for these behaviors. They have not run yet; Java compilation and database execution depend on CI. A focused Chromium check is also prepared for sign-in, reload, logout retry, password changes and reviewer restrictions. Run it with `npm run test:session` from `frontend` after packaging the backend. Its loopback HTTP fixture explicitly disables Secure cookies; production must keep them enabled. Screenshots are captured by the check and will be reviewed after a successful run.

The implementation uses Spring Security's [form-login](https://docs.spring.io/spring-security/reference/6.5/servlet/authentication/passwords/form.html) and [session support](https://docs.spring.io/spring-security/reference/6.5/servlet/authentication/session-management.html).
