# WagWag

Module 1 implements a pet profile from Expo through a Spring Boot API to PostgreSQL. Module 2 adds posts, a chronological feed, likes, comments, and multi-image uploads. The `dev` profile seeds a development user and pet (both ID 1) for these first product flows, as described in the roadmap.

## Run locally

Requirements: Java 21, Node.js, npm, Docker Desktop, and Expo Go or a simulator.

1. Start PostgreSQL and SeaweedFS from the repository root:

   ~~~sh
   docker compose up -d postgres seaweedfs
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

   Flyway creates the pet and post tables; only the `dev` profile loads the seed migration for user and pet ID 1 and configures the development identity. The default profile creates the schema without seed data. The API listens on port 8080. SeaweedFS creates the `wagwag-avatars` bucket and serves S3-compatible objects on port 8333. The local SeaweedFS gateway runs without authentication; configure credentials and public image access separately outside local development.

3. Run the app in another terminal:

   ~~~sh
   cd mobile
   cp .env.example .env
   npm ci
   npm start
   ~~~

   For a physical phone, replace `localhost` in `mobile/.env` and the API's `S3_ENDPOINT` and `S3_PUBLIC_BASE_URL` with the computer's LAN IP. Both the API and SeaweedFS must be reachable by the phone. Android emulators can use `10.0.2.2` for the host. Restart Expo after editing `.env`.

   Tap **Feed** on the pet profile to create a post, like posts, and comment. The composer can upload up to four photos or use one public HTTP(S) image link. Selected photos are resized and compressed before upload.

## API

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/pets/{id}` | Read a pet |
| POST | `/api/pets` | Create a pet for development user 1 |
| PUT | `/api/pets/{id}` | Update the development user's pet |
| POST | `/api/pets/{id}/avatar-uploads` | Request a 10-minute S3-compatible upload URL |
| PUT | `/api/pets/{id}/avatar` | Verify the uploaded image and save its URL |
| POST | `/api/posts/media-uploads` | Request a 10-minute upload URL for a post image |
| POST | `/api/posts` | Create a text, image-link, or uploaded-photo post as development pet 1 |
| GET | `/api/posts/{id}` | Read a post |
| GET | `/api/feed?limit=20` | Read the first page of posts, newest first |
| GET | `/api/feed?limit=20&cursor=...` | Read the next page using the previous `nextCursor` |
| POST | `/api/posts/{id}/likes` | Like a post |
| DELETE | `/api/posts/{id}/likes` | Unlike a post |
| POST | `/api/posts/{id}/comments` | Add a comment |
| GET | `/api/posts/{id}/comments` | Read comments |

The avatar request body is `{ "contentType": "image/jpeg" }`. Upload the bytes to the returned `uploadUrl` using HTTP PUT and the same `Content-Type`, then send `{ "key": "..." }` to the avatar endpoint. Images must be JPEG, PNG, or WebP and no larger than 5 MB. The database stores only the URL. Set `S3_PUBLIC_BASE_URL` to the public bucket or CDN prefix in a deployed environment. If the client runs in a browser, configure the storage bucket's CORS policy to allow PUT from the web app origin.

A post can still use `{ "body": "Hello!", "imageUrl": "https://example.com/photo.jpg" }`. For direct uploads, request a ticket with `{ "contentType": "image/jpeg" }` at `/api/posts/media-uploads`, PUT each image to its `uploadUrl` using all returned `headers`, then create a post with `{ "body": "Hello!", "imageKeys": ["pets/1/posts/...jpg"] }`. Post-image upload tickets use unique keys and conditional, create-only writes; reusing a ticket cannot overwrite an existing object. Up to four distinct images are allowed; each must be JPEG, PNG, or WebP and no larger than 5 MB. The API verifies object metadata before saving the post. Responses include ordered `imageUrls` and retain `imageUrl` as the first image for existing clients. PostgreSQL stores image URLs, not image bytes. Set `S3_PUBLIC_BASE_URL` to a public bucket or CDN prefix when deploying.

The feed returns `{ "items": [...], "nextCursor": "..." }` in chronological newest-first order, with ID as the tie-breaker. `limit` defaults to 20 and must be between 1 and 50. A null `nextCursor` means there are no more posts. Clients should pass the cursor back unchanged and treat it as opaque.

The fixed development identity only supports local development; it is not an authentication mechanism. Before public deployment, replace it with authenticated user identity and private authorization policy.
For AWS, leave `S3_ACCESS_KEY` and `S3_SECRET_KEY` unset to use the standard AWS credential chain.

## Checks

~~~sh
cd backend && ./mvnw test
cd mobile && npm run typecheck
~~~

The backend suite checks both the default and development configurations, including PostgreSQL/SeaweedFS integration tests for pet profiles and post images. A running Docker daemon is required.
