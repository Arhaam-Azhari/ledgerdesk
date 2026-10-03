# Receipt restoration checks

A restored ledger needs its supporting documents as well as its balances. These checks extend the real H2 file-copy and PostgreSQL native-archive workflows with an unpaid $40 supplies bill and a paid $25 software expense. The bill has a PNG receipt; the expense has a JPEG receipt. Combined with $125.37 owner funding, the fixture has six ledger lines and two receipt metadata rows.

Before backup, each test uploads through the authenticated multipart endpoint and downloads the stored receipt. It uses that stored download as the comparison baseline: image validation deliberately decodes and re-encodes uploads, so the original uploaded bytes can differ from the stored bytes.

After restoring into a separate database and starting a new backend, the tests compare the complete workspace, including receipt IDs, filenames, linked document IDs, media types, sizes and timestamps. They compare downloaded bytes and Content-Type, Content-Disposition, X-Content-Type-Options and Cache-Control headers. Both owners and reviewers can download; anonymous downloads return 401. Reviewer uploads with valid CSRF return 403.

Retrying the original owner upload with its original command key returns the retained receipt ID. The workspace stays unchanged, including accounting and activity; this checks retained upload idempotency rather than merely counting files. These checks add recovery evidence without changing the receipt API or accounting behavior.

The first checkpoint passes the production frontend build, Playwright discovery, Python compilation and all 14 backup-tool unit tests locally. H2/ PostgreSQL process verification is pending CI. Run `npm run test:persistent` for H2 after packaging the backend, or use the dedicated PostgreSQL restore job described in [the PostgreSQL guide](postgres-backups.md). Use the existing [H2 guide](local-backups.md) for operator backup/restore instructions.

This fixture covers a PNG attached to a bill and a JPEG attached to an expense. It does not establish PDF restoration, every possible image, five-receipt limits after restoration, crash recovery or encrypted backup storage. Existing receipt validation tests cover file acceptance separately; recovery evidence is limited to the records exercised here.
