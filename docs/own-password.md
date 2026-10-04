# Change your own password

Stored owners, bookkeepers and reviewers can use **Change my password** in the workspace header. Enter the current password, a different new password and its confirmation, then choose **Save my password**. Use at least 12 characters and at most 72 UTF-8 bytes. The workspace locks after a confirmed save; sign in with the new password. Cancel closes the form and clears its fields.

A wrong current password leaves the stored hash unchanged. The form clears secret fields after a submitted request, whether it succeeds or fails. If the connection fails after the server saves, lock the workspace and try the new password. An old-password retry cannot undo or duplicate the change. If neither password works, ask an owner for an account reset or follow the existing offline owner recovery instructions when appropriate.

This changes the authenticated account only. `POST /api/me/password` accepts `currentPassword` and `password`, requires authentication and CSRF, and resolves the account from the authenticated username. It accepts no target account ID, username or role. The business lock serializes password/access changes; an enabled membership in this business and a matching current password are rechecked before updating. Password hashes and the `ACCOUNT_SELF_PASSWORD_CHANGED` activity event commit together. No password, password hash or change-request body is stored in command history or returned by the endpoint. Its response uses `Cache-Control: no-store`.

Reviewers retain read-only accounting access, and bookkeepers retain their existing limits. Neither gets access to account lists or other users' resets. Configured-login mode has no editable stored password; it omits the header control and the endpoint rejects attempts. Owner administration and offline recovery remain available separately.

## Verification checkpoint

The production frontend build and diff checks pass locally. Eight new backend checks cover every stored role, current-password validation, CSRF/authentication, target/role injection, disabled or other-business accounts, activity rollback, Unicode byte limits and configured-login rejection. The isolated Chromium workflow covers all roles, mismatched confirmation, a wrong current password, cancellation, cleared fields, desktop/mobile views, confirmed lock/sign-in and a server save whose response is deliberately lost.

Full H2/PostgreSQL integration, browser regression and database restoration checks are pending. Original captures must be downloaded and reviewed before being presented as screen evidence. Run `npm run test:password` from `frontend` after packaging the backend and installing Chromium; keep ports 8097 and 5189 free. This remains local Basic authentication; changing a password does not establish a hosted session revocation design.
