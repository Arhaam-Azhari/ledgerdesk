# Self-changed passwords after restart and restore

The recovery scenarios now use `POST /api/me/password` to change stored owner, reviewer and bookkeeper passwords before accounting work and backup. Old credentials must stop working immediately; replacement credentials must preserve each role. A process restart must retain these changes even when the operator supplies the original or different bootstrap settings.

PostgreSQL restarts the source backend, compares the entire workspace and owner-visible account list, then stops it for the native custom-format backup. A new backend opens a separate restored database. All three self-changed passwords still work, original passwords and changed bootstrap passwords fail, account IDs/roles/enabled states stay unchanged, and the full workspace matches the baseline.

The H2 workflow retains its offline owner-recovery step. All three passwords are changed and checked across the first process restart. Offline recovery deliberately replaces the owner's password; the owner then changes that recovered password again through the self-service endpoint. The final owner password and original self-changed reviewer/bookkeeper passwords are the ones copied in the backup and checked against a separate restored H2 file. Original logins, the earlier owner password and the offline recovery password must fail after restoration.

Retained activity identifies each self-service change without including either password. Workspace JSON checks reject the fixture's secret strings. Existing receipt bytes/headers, opening balances, dated reports, closed statement and period history, request retries and permissions remain part of both scenarios. Owner account resets, bookkeeper demotion/disabling and deliberate post-restore work remain checked separately from the unchanged baseline.

## Verification checkpoint

Python compilation, all 14 backup-tool tests, production frontend build and diff checks pass locally. Full H2/PostgreSQL integration, Chromium process/regression workflows and native PostgreSQL restoration are pending. No application UI source changes are included; the existing reviewed password-form captures remain screen evidence.

These are graceful process and offline backup/restore checks. A backup contains the stored password hashes as of that backup; restoring an older backup can restore an older password. After a real recovery, operators must review account access and password changes since the backup. The fixture does not prove hosted session revocation, crash recovery or live backup.
