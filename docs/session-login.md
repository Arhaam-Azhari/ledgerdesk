# Session login (backend in progress)

This adds an optional cookie-based login mode. The existing local Basic-auth mode remains the default. The browser login integration, HTTPS deployment and real-cookie testing are the next steps; this backend change alone does not complete hosted access.

Use the `session` Spring profile with stored owner credentials and database settings. It enables persistent accounts, secure/HttpOnly/SameSite session cookies and a 20-minute idle timeout. Secure cookies require HTTPS. The backend stays on its existing loopback address until deployment is configured.

| Request | Purpose |
| --- | --- |
| GET `/api/auth` | Read the configured login mode |
| GET `/api/csrf` | Obtain the token before login or a write |
| POST `/api/session/login` | Submit URL-encoded `username` and `password`, plus CSRF |
| POST `/api/session/logout` | End the session, with CSRF |

Login rotates the session ID. Session mode rejects Basic headers. Login errors return a generic 401, and API reads require a signed-in session. Failed sign-in clears any existing session. Logout invalidates the session and expires its cookie.

Each authenticated request compares the stored account with the identity captured at login. Password changes/resets, disabled accounts and role changes invalidate old sessions on their next request. The session keeps a fingerprint of the account state, without retaining the password hash after authentication. Existing role restrictions and CSRF-protected writes remain in place.

Six integration tests are prepared for these behaviors. They have not run yet; Java compilation and database execution depend on CI. The frontend still uses local Basic authentication until its session integration is added.

The implementation uses Spring Security's [form-login](https://docs.spring.io/spring-security/reference/6.5/servlet/authentication/passwords/form.html) and [session support](https://docs.spring.io/spring-security/reference/6.5/servlet/authentication/session-management.html).
