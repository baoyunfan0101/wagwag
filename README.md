# WagWag

A pre-v1 pet social app built with Expo / React Native and a Java 21 Spring Boot API. PostgreSQL/PostGIS owns application and spatial state; SeaweedFS provides local S3-compatible media storage. Redis is optional for caches and live-event relay.

| Module | Current features |
| --- | --- |
| 1 | Pet profiles and avatars |
| 2 | Posts, multi-image upload, paginated feed, likes, comments |
| 3 | Follows, private-profile requests, block, mute, Following Feed |
| 4 | Communities, moderation, rules, search, popular communities |
| 5 | Walk recording, background drafts, Mapbox, distance, nearby routes |
| 6 | Territory claims, overlap/decay, area leaderboard |
| 7 | Pet-care tasks, coarse public location, assignment, ratings, availability |
| 8 | Direct messages, notifications, WebSocket updates, receipts, optional device push |
| 9A / 9B | Video posts, async transcoding/compression, thumbnails, upload progress/retry, configurable CDN delivery |
| 10A | Second-hand listings, browse, favorites, seller messaging, mark sold |

## Run locally

Install Java 21, Node.js 24, npm, Docker Desktop, and FFmpeg/FFprobe. From the repository root:

~~~sh
cp backend/.env.example backend/.env
cp mobile/.env.example mobile/.env
make services
make install
~~~

Run `make backend` and `make mobile` in separate terminals; `make web` opens the web app. The backend explicitly runs with the `dev` profile and seeds Mochi (user/pet ID 1), Biscuit (ID 1000), a demo task, and a demo second-hand listing. The default profile creates schema without development data.

Native maps/background recording need a development build and local Mapbox configuration. New native plugins such as `expo-video` require rebuilding. Device push also needs an EAS project and APNs/FCM credentials. See the [development guide](docs/DEVELOPMENT.md) for manual commands, phone networking, native setup, and verification.

## Checks

~~~sh
make check
~~~

This runs `cd backend && ./mvnw test`, mobile `npm run typecheck`, and `npm test`. Docker must be running. The same checks run in GitHub Actions.

## Project guides

- [API contract](docs/API.md): routes, cursor pagination, media upload, visibility, tasks, listings, messaging.
- [Development guide](docs/DEVELOPMENT.md): layout, environment, startup, native builds, test commands.
- [Development roadmap](docs/WagWag_Development_Roadmap.md): module phases and remaining work.
- [Project instructions](AGENTS.md): branch workflow and release policy.

Before v1, schema and internal API contracts may change directly. The current baseline includes second-hand listings; older local PostgreSQL volumes must be recreated using the [PostgreSQL-only reset](docs/DEVELOPMENT.md#pre-release-database-policy). No historical database upgrade path is maintained. Compatibility and incremental database migrations begin with the first release.
