# Hosted deployment (prepared, not yet verified)

This Compose setup builds the frontend and Java service, keeps PostgreSQL private, and serves the browser and API from one HTTPS address through Caddy. It is for one business per installation. It has not been run yet; it is not a completed deployment or recovery milestone. The hosted-installation workflow builds both images and checks HTTPS login, cookie flags, private reads, logout and record persistence after an API restart. Its disposable localhost check accepts Caddy's private certificate; public deployments must use a trusted certificate.

## Prepare a Linux host

Install Docker with Compose v2. Point a domain's DNS at the host and allow inbound ports 80 and 443. Copy `deploy/hosted.env.example` to `.env`, then set your domain and owner username. Keep the `session` profile enabled: its cookies require HTTPS.

Create fresh password files before starting:

```sh
mkdir -p deploy/secrets
chmod 700 deploy/secrets
openssl rand -hex 32 > deploy/secrets/database-password
openssl rand -hex 32 > deploy/secrets/owner-password
chmod 444 deploy/secrets/database-password deploy/secrets/owner-password
docker compose -f compose.hosted.yaml config --quiet
docker compose -f compose.hosted.yaml up -d --build
```

The secret directory stays accessible only to its host owner. Its files are readable by the container users when mounted and are granted only to the services that need them. Compose secrets are host files, not an encrypted vault. Store protected copies outside the host; neither `.env` nor `deploy/secrets` belongs in git or a build image.

Retrieve the owner password from its protected file, then sign in at your configured HTTPS address. The first launch creates the owner in the database. Changing the bootstrap file later does not reset an existing account; use account management or the documented recovery command. Changing the database password file also does not change the password in an existing PostgreSQL volume.

## Check the installation

- The login page and assets load without signing in; private API reads return 401.
- Sign in, reload the page, post fictional activity, and check the corresponding ledger entry.
- The session cookie has Secure, HttpOnly and SameSite=Strict. Sign out and confirm private reads return 401 again.
- Restart with `docker compose -f compose.hosted.yaml restart` and confirm records remain and old in-memory sessions require another sign-in.
- Check `docker compose -f compose.hosted.yaml ps` and service logs if startup fails. Only the web service publishes ports.

`docker compose -f compose.hosted.yaml down` retains database and certificate volumes. Do not use `down -v` on an installation whose records you need. A named volume is not a backup. Hosted backup encryption, off-host storage, retention and a populated restore exercise remain to be implemented and tested before release.

The setup follows Caddy's [Docker](https://caddyserver.com/docs/running) and [HTTPS](https://caddyserver.com/docs/quick-starts/https) guidance, Docker's [Compose secrets](https://docs.docker.com/compose/how-tos/use-secrets/), and Spring's [configuration trees](https://docs.spring.io/spring-boot/reference/features/external-config.html). Image tags currently follow supported major versions; record resolved image digests for a release.
