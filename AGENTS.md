# WagWag project instructions

## Version control

- Follow the activated global Git workflow when changing branches, committing, pushing, or opening a PR.
- Develop on a task branch from `main` and commit focused changes. Open or merge a PR only when the user requests it.

## Project maintenance

- Keep setup and environment details in `docs/DEVELOPMENT.md`, API contracts in `docs/API.md`, and the module plan in `docs/WagWag_Development_Roadmap.md`. Keep the root README as the entry point.
- Keep the pre-v1 version aligned between `backend/pom.xml` (with its snapshot suffix), `mobile/package.json`, `mobile/package-lock.json`, and `mobile/app.json`.
- `make check` runs backend tests, mobile typechecking, and mobile tests. Keep environment examples and these commands current when changing configuration or dependencies.
- Native bundle export does not verify device playback, GPS/background permissions, or push delivery. Record any hardware checks that still require a configured device.

## Pre-release schema and API policy

- Before the first v1 release, current code is the source of truth. Keep one complete Flyway schema baseline at `backend/src/main/resources/db/migration/V1__baseline.sql`.
- Keep development fixtures outside Flyway and activate them only with the `dev` profile. Do not add versioned seed migrations.
- Pre-v1 local databases are disposable. Document a PostgreSQL-only reset when the baseline changes; do not add upgrade or data-copy migrations for old local schemas.
- Change internal API contracts directly before v1. Do not retain aliases or deprecated fields solely for unreleased clients.
- Do not delete local data automatically from application code or tests outside their isolated test databases.

## First-release boundary

- Starting with v1, treat the committed baseline as production history. Add future database changes as incremental Flyway migrations and evaluate compatibility with released API clients explicitly.
