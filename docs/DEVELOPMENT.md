# Development guide

[Project overview](../README.md) | [API contract](API.md) | [Roadmap](WagWag_Development_Roadmap.md)

## Layout and runtime

| Path | Responsibility |
| --- | --- |
| `backend/` | Java 21 / Spring Boot REST, WebSocket, persistence, tests |
| `backend/src/main/resources/db/migration/V1__baseline.sql` | Complete pre-v1 PostgreSQL/PostGIS schema |
| `backend/src/main/java/com/wagwag/api/DevelopmentSeed.java` | Idempotent dev-only fixtures |
| `mobile/src/app/` | Expo Router screens |
| `mobile/src/components/` | Shared cards, maps, and video player |
| `mobile/src/lib/` | Typed API, tracking, realtime/push state, tests |
| `compose.yaml` | Local PostgreSQL/PostGIS, SeaweedFS, Redis |
| `.github/workflows/ci.yml` | Backend integration tests, mobile typecheck and tests |

The project version is `0.0.1` while pre-v1. Native build directories and `.env` files are local and must not be committed. Never put server credentials in an `EXPO_PUBLIC_*` variable; these variables are bundled into the app.

## Local startup

Requirements: Java 21, Node.js 24, npm, Docker Desktop, FFmpeg/FFprobe (with libx264 and AAC), and Make (optional).

Install FFmpeg with `brew install ffmpeg` on macOS or `sudo apt-get install ffmpeg` on Debian/Ubuntu. Keep `ffmpeg` and `ffprobe` on PATH for integration tests. `FFMPEG_BIN` selects the backend executable if needed. GitHub Actions installs FFmpeg before running backend tests.

~~~sh
cp backend/.env.example backend/.env
cp mobile/.env.example mobile/.env
make services
make install
~~~

Run these in separate terminals:

~~~sh
make backend
make mobile
~~~

`make backend` loads `backend/.env` and explicitly activates the `dev` profile. Spring Boot does not load `.env` itself. The equivalent manual backend command is:

~~~sh
cd backend
set -a
. ./.env
set +a
SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run
~~~

Without Make, start services with `docker compose up -d postgres seaweedfs redis`, install mobile dependencies using `cd mobile && npm ci`, and run `npm start` from `mobile`. `make web` starts the browser UI; maps use a web fallback.

The API uses port 8080, PostgreSQL 5432, SeaweedFS 8333, and Redis 6379. The PostGIS image runs under amd64 emulation on Apple Silicon. SeaweedFS automatically creates `wagwag-avatars`; that existing bucket stores avatars, post photos, and videos. Its local gateway is unauthenticated. Configure storage credentials and public-object access separately outside local development. Leave `S3_ACCESS_KEY` and `S3_SECRET_KEY` unset for the standard AWS credential chain.

The copied backend environment enables Redis event relay locally. Relationship reads, popular communities, and territory leaderboards fall back to PostgreSQL if Redis is unavailable. With the relay unavailable/disabled, local WebSockets and REST reconciliation continue working. Configure `REDIS_URL` for a different Redis address; device push is disabled by default.

For a physical phone, replace `localhost` in `mobile/.env`, `backend/.env`'s `S3_ENDPOINT`, and `S3_PUBLIC_BASE_URL` with the computer's LAN IP. Android emulators can use `10.0.2.2`. Both the API and object store must be reachable by the client. Restart Expo after changing `.env`. Browser origins must match `APP_ALLOWED_ORIGINS`; bucket CORS must permit PUT with `Content-Type` and `If-None-Match` as well as media GET from the web app origin. Expo normally runs on port 8081.

## Development identities

Flyway creates schema only. The `dev` profile seeds Mochi (user/pet ID 1), Biscuit (user/pet ID 1000), and Biscuit's open demo task outside Flyway. Repeated startups preserve existing rows and do not reopen a completed demo task. The default profile creates zero users/pets and has no development identity.

To try both sides of tasks and messaging, set `APP_DEV_PET_ID=1000` in `backend/.env` and `EXPO_PUBLIC_DEV_PET_ID=1000` in `mobile/.env`, then restart both processes. Restore both to `1` for Mochi. The fixed development identity is not authentication; authenticated identity and authorization are required before public deployment.

## Pre-release database policy

Before v1, current code is the source of truth. There is one complete baseline and no database upgrade path for historical local schemas or compatibility guarantee for unreleased API clients. When the baseline changes, explicitly recreate **only PostgreSQL**:

~~~sh
docker compose rm -sf postgres
docker volume rm wagwag_postgres_data
docker compose up -d postgres
~~~

These commands delete local PostgreSQL data, including walks, messages, posts, and profiles. They preserve SeaweedFS data. If you set `COMPOSE_PROJECT_NAME`, replace the volume name with that project's PostgreSQL volume. No application command deletes this data automatically.

The current baseline includes `video_uploads` and `posts.video_thumbnail_url` for Module 9B. Reset an older local PostgreSQL volume before using this code. Starting with v1, the committed schema becomes production history; later schema changes use incremental Flyway migrations, and released API compatibility is evaluated explicitly.

## Video processing and media delivery

`VIDEO_PROCESSING_ENABLED=true` enables the PostgreSQL-backed video worker (the default). The API process needs FFmpeg, writable temporary disk, and access to the S3 origin. Processing runs outside database transactions; short claims use `SKIP LOCKED`, with a ten-minute lease and a token preventing stale workers from replacing the winning output. Worker retries are bounded to three attempts, with processing timeouts. Temporary files are removed after each attempt. Multiple API instances can claim different jobs. The scheduler has two threads so video conversion does not block the push worker.

Set `MEDIA_PUBLIC_BASE_URL` to a public CDN/bucket prefix that resolves the processed keys unchanged. Leave it blank for local SeaweedFS delivery through `S3_PUBLIC_BASE_URL`. The application emits immutable, cacheable video/thumbnail URLs; it does not create a cloud CDN distribution. Configure HTTPS, media GET/HEAD and range requests, and browser CORS on the delivery service. Raw upload PUTs continue using the S3 origin and all returned signed headers. The local SeaweedFS gateway supports the create-only presigned PUT protocol used for both source and output objects.

The [API guide](API.md#posts-and-feed) describes completion, status polling, and retry. Upload polling stops when the composer is left, while a queued backend job can finish independently. No mobile crash recovery, output lifecycle cleanup, or multi-worker fleet provisioning is included.

## Native builds and maps

To use walk maps, set `EXPO_PUBLIC_MAPBOX_ACCESS_TOKEN` in `mobile/.env` to a Mapbox **public** access token. A native build also needs a separate Mapbox **secret** token with `Downloads:Read` scope, configured locally for the platform SDK download: [iOS `.netrc`](https://docs.mapbox.com/ios/maps/guides/install/) or [Android Gradle properties](https://rnmapbox.github.io/docs/RNMapboxMapsDownloadTokenDetails). Never commit that secret token. After configuring it, build and run the native app with `npx expo run:ios` or `npx expo run:android` from `mobile/`; use `npm start` for later JavaScript changes. Rebuild the native app after adding the background-location permissions. A simulator can display saved routes, but recording a real walk needs a device with GPS.

Rebuild the native app after adding or changing native plugins, including `expo-video`, background location, and notifications. For routine JavaScript changes, `npm start` is enough.

The walk screen offers **Record with screen locked**. This requests foreground and background location permission (iOS Always; Android Allow all the time) and uses an Android foreground-service notification while recording. Turn it off for foreground-only recording; web always uses foreground recording. Background batches update a local active-route draft and sync into the screen when it returns to the foreground. Stop/save and explicit discard stop location updates. Terminating the app or OS/vendor restrictions can stop background recording; uninterrupted recording after process death is not guaranteed. Interrupted drafts can be saved or discarded when reopened. GPS sampling requests 5 m / 3 s intervals and keeps at most 2000 points. Fixes with accuracy worse than 35 m, movement under 5 m, non-increasing timestamps, or jumps above 12 m/s are ignored.

## Device push

Device push requires a rebuilt native app on a physical phone. Follow the [Expo push setup](https://docs.expo.dev/push-notifications/push-notifications-setup/) to link an EAS project and configure APNs/FCM credentials. Set `EXPO_PUBLIC_EAS_PROJECT_ID` in `mobile/.env` (or use the linked EAS project's config); this project ID is public. Rebuild after adding the `expo-notifications` plugin. Enable backend delivery with `EXPO_PUSH_ENABLED=true`; optionally set `EXPO_PUSH_ACCESS_TOKEN` locally if Expo enhanced push security is enabled. `EXPO_PUSH_BASE_URL` defaults to Expo's API and is overridable for local provider tests. No EAS project or provider credentials are committed.

Tap **Enable device push** in Notifications to explicitly request permission and register the installation UUID/token. Android creates its notification channel before permission/token acquisition. `PUT /api/notifications/push-devices/{uuid}` accepts `{ "expoPushToken": "ExpoPushToken[...]", "platform": "IOS" }` (`ANDROID` is also supported); GET reports `registered` and `serverEnabled`, and DELETE pauses that actor's device. Opted-in token rotation is refreshed on foregrounding. When switching development identities on one installation, pause push under the old identity first, then enable it under the new identity. An active device/token cannot silently transfer between pets.

Notifications and per-device push jobs commit together. Workers lock due jobs with `SKIP LOCKED`, send generic text without message bodies or task locations, check Expo tickets/receipts, retry transient failures up to five attempts, and disable unregistered tokens. Read or disabled pending deliveries are skipped. An accepted ticket is checked after 15 minutes rather than resent. Provider acceptance does not prove phone delivery; interruption after a send may still produce a duplicate alert. Opening a push resolves its owned notification through REST before navigating to a fixed conversation/task route. Web keeps the in-app notification center without device push.

## Checks

~~~sh
make check
~~~

Equivalent checks (each from its own directory):

~~~sh
cd backend && ./mvnw test
cd mobile && npm run typecheck
cd mobile && npm test
~~~

`make check-backend` and `make check-mobile` run the same jobs as GitHub Actions. Backend integration tests create isolated PostGIS, SeaweedFS, and Redis Testcontainers and require a running Docker daemon plus FFmpeg/FFprobe; they do not use or reset Compose volumes. Mobile tests use the Node.js 24 test runner. Typecheck does not compile or verify native SDKs.

To check JavaScript bundles for all supported platforms:

~~~sh
cd mobile
npx expo export --platform all --output-dir /tmp/wagwag-export
~~~

### Manual device checks

For native verification, start a walk with background recording enabled on a device, lock the screen and move outdoors, then reopen WagWag and confirm the additional route points. Stop/save, reopen the saved route, and check the map and distance. Repeat with foreground-only recording and a navigation/discard attempt. Device permission prompts, background delivery, and Mapbox rendering require this device check; JavaScript bundle export alone does not verify them.


For native verification, enable push on a configured phone, exchange messages with the other development pet, and check live updates, foreground delivery, read status, unread badges, reconnect catch-up, background alerts, and notification-tap navigation. Pause push and verify further alerts stop. Integration tests use real WebSocket/Redis and a local HTTP push provider; APNs/FCM delivery still requires this configured device check.

For video, select small MP4/MOV/WebM files, verify byte-upload progress followed by queued/processing state, publish, and confirm the generated thumbnail in Feed and post detail. Play/pause/seek/fullscreen and verify playback stops when leaving the screen, scrolling the card off screen, or backgrounding. Interrupt upload/API connectivity and retry with the same job; successfully uploaded bytes must not be sent twice. Try an invalid file and a transient processor failure, then retry processing without another PUT. A selected source preview can still depend on device codecs; published files are normalized to H.264/AAC. Native playback/upload progress, GPS/background behavior, and APNs/FCM delivery require configured hardware; bundle export and browser checks do not establish those results.
