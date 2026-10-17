# Velo cab booking
Run: `mvn spring-boot:run`, then open http://localhost:8080. The admin login is printed on first start.
Env vars: `DB_URL` `DB_USER` `DB_PASS` (PostgreSQL), `JWT_SECRET` (32+ chars, keeps logins valid across restarts)



## Docker
```
docker compose up --build
```
Open http://localhost. This starts PostgreSQL, the app and Nginx. Admin login: `admin@cab.local` with your `ADMIN_PASSWORD` (if left empty, see `docker compose logs app`). Data is kept in the `pgdata` volume. For HTTPS on a real domain use `deploy/nginx.conf`. GitHub Actions (`.github/workflows/ci.yml`) builds the jar and the image on every push.

## Map tiles
Tiles come from OpenStreetMap's public servers, which is fine for development and light use. For real traffic, switch to a keyed provider (MapTiler, Stadia) or host your own tiles.
