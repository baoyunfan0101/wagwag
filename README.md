# WagWag

Module 1 implements a pet profile from Expo through a Spring Boot API to PostgreSQL. Module 2 adds posts, a chronological feed, likes, comments, and multi-image uploads. Module 3 adds follows, follow requests for private pets, block/mute controls, and a following feed. PostgreSQL remains the source of truth; Redis caches relationship status and counts. The `dev` profile seeds the development user and pet (both ID 1), plus a demo neighbor pet (ID 1000) for trying social flows.

## Run locally

Requirements: Java 21, Node.js, npm, Docker Desktop, and Expo Go or a simulator.

1. Start PostgreSQL, SeaweedFS, and Redis from the repository root:

   ~~~sh
   docker compose up -d postgres seaweedfs redis
   ~~~

2. Run the API:

   ~~~sh
   cd backend
   export S3_ACCESS_KEY=dev-access
   export S3_SECRET_KEY=dev-secret
   export S3_BUCKET=wagwag-avatars
   export S3_ENDPOINT=http://localhost:8333
   export S3_PUBLIC_BASE_URL=http://localhost:8333/wagwag-avatars
   SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run
   ~~~

   Flyway creates the current schema from one baseline migration. Only the `dev` profile seeds Mochi (user/pet ID 1) and Biscuit (user/pet ID 1000) outside Flyway and configures the development identity. Repeated dev startups preserve existing rows. The default profile creates the schema without seed data. The API listens on port 8080. SeaweedFS creates the `wagwag-avatars` bucket and serves S3-compatible objects on port 8333. Redis listens on port 6379; set `REDIS_URL` if it is elsewhere. Relationship reads fall back to PostgreSQL if Redis is unavailable. The local SeaweedFS gateway runs without authentication; configure credentials and public image access separately outside local development.

3. Run the app in another terminal:

   ~~~sh
   cd mobile
   cp .env.example .env
   npm ci
   npm start
   ~~~

   For a physical phone, replace `localhost` in `mobile/.env` and the API's `S3_ENDPOINT` and `S3_PUBLIC_BASE_URL` with the computer's LAN IP. Both the API and SeaweedFS must be reachable by the phone. Android emulators can use `10.0.2.2` for the host. Restart Expo after editing `.env`.

   Tap **Feed** on the pet profile to create a post, like posts, and comment. The composer can upload up to four photos; selected photos are resized and compressed before upload. Tap **Friends** to find Biscuit, follow or unfollow pets, and view follower/following lists. The feed's **Following** filter shows posts from followed pets. A private pet must approve new follow requests. On a pet profile, you can mute its posts or block it; your own profile has the private setting and request list.

## Pre-release database policy

WagWag has not reached v1. The current schema baseline is intended for an empty PostgreSQL database; old local Flyway histories have no upgrade path. When the baseline changes, recreate only the local PostgreSQL volume. With the default Compose project name from this directory:

~~~sh
docker compose rm -sf postgres
docker volume rm wagwag_postgres_data
docker compose up -d postgres
~~~

This deletes local PostgreSQL data, including posts and profiles, but leaves the SeaweedFS volume intact. If you set `COMPOSE_PROJECT_NAME`, replace `wagwag_postgres_data` with that project's PostgreSQL volume name. Before v1, schema and API contracts may change directly without backwards compatibility. Starting with v1, the committed schema becomes the production baseline, future database changes use incremental Flyway migrations, and compatibility with released clients is considered explicitly.

## API

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/pets/{id}` | Read a pet |
| POST | `/api/pets` | Create a pet for development user 1 |
| PUT | `/api/pets/{id}` | Update the development user's pet |
| PUT | `/api/pets/{id}/privacy` | Set the active development pet's private profile state |
| POST | `/api/pets/{id}/avatar-uploads` | Request a 10-minute S3-compatible upload URL |
| PUT | `/api/pets/{id}/avatar` | Verify the uploaded image and save its URL |
| GET | `/api/pets/discover?limit=20&page=0` | Discover other pets |
| GET | `/api/pets/{id}/social` | Read follower/following counts and my follow state |
| POST | `/api/pets/{id}/follow` | Follow a pet as development pet 1 |
| DELETE | `/api/pets/{id}/follow` | Unfollow a pet |
| GET | `/api/pets/{id}/follow-requests?limit=20&page=0` | List the active pet's pending requests |
| POST | `/api/pets/{id}/follow-requests/{followerId}` | Approve a request |
| DELETE | `/api/pets/{id}/follow-requests/{followerId}` | Decline a request |
| POST / DELETE | `/api/pets/{id}/block` | Block or unblock a pet |
| POST / DELETE | `/api/pets/{id}/mute` | Mute or unmute a pet's posts |
| GET | `/api/pets/{id}/followers?limit=20&page=0` | Read a page of followers |
| GET | `/api/pets/{id}/following?limit=20&page=0` | Read a page of followed pets |
| POST | `/api/posts/media-uploads` | Request a 10-minute upload URL for a post image |
| POST | `/api/posts` | Create a text and/or uploaded-photo post as development pet 1 |
| GET | `/api/posts/{id}` | Read a post |
| GET | `/api/feed?limit=20` | Read the first page of posts, newest first |
| GET | `/api/feed?limit=20&cursor=...` | Read the next page using the previous `nextCursor` |
| GET | `/api/feed?following=true&limit=20` | Read posts by followed pets, newest first |
| POST | `/api/posts/{id}/likes` | Like a post |
| DELETE | `/api/posts/{id}/likes` | Unlike a post |
| POST | `/api/posts/{id}/comments` | Add a comment |
| GET | `/api/posts/{id}/comments` | Read comments |

The avatar request body is `{ "contentType": "image/jpeg" }`. Upload the bytes to the returned `uploadUrl` using HTTP PUT and the same `Content-Type`, then send `{ "key": "..." }` to the avatar endpoint. Images must be JPEG, PNG, or WebP and no larger than 5 MB. The database stores only the URL. Set `S3_PUBLIC_BASE_URL` to the public bucket or CDN prefix in a deployed environment. If the client runs in a browser, configure the storage bucket's CORS policy to allow PUT from the web app origin.

To add photos, request a ticket with `{ "contentType": "image/jpeg" }` at `/api/posts/media-uploads`, PUT each image to its `uploadUrl` using all returned `headers`, then create a post with `{ "body": "Hello!", "imageKeys": ["pets/1/posts/...jpg"] }`. Text-only posts use an empty `imageKeys` list. Post-image upload tickets use unique keys and conditional, create-only writes; reusing a ticket cannot overwrite an existing object. Up to four distinct images are allowed; each must be JPEG, PNG, or WebP and no larger than 5 MB. The API verifies object metadata before saving the post. Responses include ordered `imageUrls`. PostgreSQL stores image URLs, not image bytes. Set `S3_PUBLIC_BASE_URL` to a public bucket or CDN prefix when deploying.

The feed returns `{ "items": [...], "nextCursor": "..." }` in chronological newest-first order, with ID as the tie-breaker. `limit` defaults to 20 and must be between 1 and 50. A null `nextCursor` means there are no more posts. Clients should pass the cursor back unchanged and treat it as opaque.

Follow and unfollow requests are idempotent. Following a private pet creates a pending request; only approved followers count toward follower totals and can see that pet's posts and social lists. Making a private pet public accepts its pending requests; making a public pet private keeps existing followers. Blocking removes both follow directions, prevents new follows, and hides the pet from discovery and feed; unblocking does not restore follows. Muting only removes the pet's posts from feed pages. Pet discovery and relationship lists return `{ "items": [...], "nextPage": 1 }`; a null `nextPage` means the list is complete. These list endpoints accept `limit` from 1 to 50 and a zero-based `page`. Redis entries expire after five minutes and are invalidated after follow changes; the database remains authoritative.

The fixed development identity only supports local development; it is not an authentication mechanism. Before public deployment, replace it with authenticated user identity and private authorization policy.
For AWS, leave `S3_ACCESS_KEY` and `S3_SECRET_KEY` unset to use the standard AWS credential chain.

## Checks

~~~sh
cd backend && ./mvnw test
cd mobile && npm run typecheck
~~~

The backend suite checks both the default and development configurations, including PostgreSQL, SeaweedFS, and Redis integration tests. A running Docker daemon is required.
