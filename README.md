# Velo cab booking
Run: `mvn spring-boot:run`, then open http://localhost:8080. The admin login is printed on first start.
Env vars: `DB_URL` `DB_USER` `DB_PASS` (PostgreSQL), `JWT_SECRET` (32+ chars, keeps logins valid across restarts), `ADMIN_NAME`, `ADMIN_EMAIL`, `ADMIN_PASSWORD` (choose your admin login; if an admin exists and `ADMIN_PASSWORD` is set, that admin is updated at start).

## Before launch
1. Fill the `{{...}}` placeholders in `privacy.html` and `terms.html`, and have both reviewed by a lawyer.
2. Custom domain: point your domain's A record at the server, install `deploy/nginx.conf` (replace `{{DOMAIN}}`), run certbot for HTTPS.
3. Run `./check-launch.sh` until it passes.
4. Payments are recorded in the app only. Connect a real payment provider before taking real money.

## Docker
```
cp .env.example .env     # then edit the three values
docker compose up --build
```
Open http://localhost. This starts PostgreSQL, the app and Nginx. Admin login: `admin@cab.local` with your `ADMIN_PASSWORD` (if left empty, see `docker compose logs app`). Data is kept in the `pgdata` volume. For HTTPS on a real domain use `deploy/nginx.conf`. GitHub Actions (`.github/workflows/ci.yml`) builds the jar and the image on every push.

## Map tiles
Tiles come from OpenStreetMap's public servers, which is fine for development and light use. For real traffic, switch to a keyed provider (MapTiler, Stadia) or host your own tiles.
