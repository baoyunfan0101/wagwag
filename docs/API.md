# API contract

[Project overview](../README.md) | [Development guide](DEVELOPMENT.md)

The active pet is configured by the dev API. Client-supplied identities are not authorization. PostgreSQL is authoritative; Redis is optional. The default profile creates no development identities or fixtures. Unknown request fields are rejected.

## Pets and social controls

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

The avatar request body is `{ "contentType": "image/jpeg" }`. Upload the bytes to the returned `uploadUrl` using HTTP PUT and the same `Content-Type`, then send `{ "key": "..." }` to the avatar endpoint. Images must be JPEG, PNG, or WebP and no larger than 5 MB. The database stores only the URL. Set `S3_PUBLIC_BASE_URL` to the public bucket or CDN prefix in a deployed environment. If the client runs in a browser, configure the storage bucket's CORS policy to allow PUT from the web app origin.

Follow and unfollow requests are idempotent. Following a private pet creates a pending request; only approved followers count toward follower totals and can see that pet's posts and social lists. Making a private pet public accepts its pending requests; making a public pet private keeps existing followers. Blocking removes both follow directions, prevents new follows, and hides the pet from discovery and feed; unblocking does not restore follows. Muting only removes the pet's posts from feed pages. Pet discovery and relationship lists return `{ "items": [...], "nextPage": 1 }`; a null `nextPage` means the list is complete. These list endpoints accept `limit` from 1 to 50 and a zero-based `page`. Redis entries expire after five minutes and are invalidated after follow changes; the database remains authoritative.

## Posts and feed

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/api/posts/media-uploads` | Request a 10-minute upload URL for a post image |
| POST | `/api/posts/video-uploads` | Request a 10-minute upload URL for a post video |
| POST | `/api/posts` | Create a text, photo, or video post |
| GET | `/api/posts/{id}` | Read a post |
| GET | `/api/feed?limit=20` | Read the first page of posts, newest first |
| GET | `/api/feed?limit=20&cursor=...` | Read the next page using the previous `nextCursor` |
| GET | `/api/feed?following=true&limit=20` | Read posts by followed pets, newest first |
| POST | `/api/posts/{id}/likes` | Like a post |
| DELETE | `/api/posts/{id}/likes` | Unlike a post |
| POST | `/api/posts/{id}/comments` | Add a comment |
| GET | `/api/posts/{id}/comments` | Read comments |

To add photos, request a ticket with `{ "contentType": "image/jpeg" }` at `/api/posts/media-uploads`, PUT each image to its `uploadUrl` using all returned `headers`, then create a post with `{ "body": "Hello!", "imageKeys": ["pets/1/posts/...jpg"] }`. Text-only posts use an empty `imageKeys` list. Post-image upload tickets use unique keys and conditional, create-only writes; reusing a ticket cannot overwrite an existing object. Up to four distinct images are allowed; each must be JPEG, PNG, or WebP and no larger than 5 MB. The API verifies object metadata before saving the post. Responses include ordered `imageUrls`. PostgreSQL stores image URLs, not image bytes. Set `S3_PUBLIC_BASE_URL` to a public bucket or CDN prefix when deploying.

A post may contain text, up to four photos, or one video, with an optional caption. Photos and video cannot be combined. Request a video ticket with `{ "contentType": "video/mp4" }`, PUT the file using **all** returned `headers`, then publish `{ "body": "A park moment", "videoKey": "pets/1/posts/videos/...mp4" }`. MP4 (`video/mp4`), MOV (`video/quicktime`), and WebM (`video/webm`) are accepted, with a non-zero size of at most 50 MB. Video tickets are create-only: signed `If-None-Match: *` prevents replacing an existing object. Keep a successfully uploaded key when retrying a failed publish; do not repeat its PUT. The backend verifies the pet namespace, object existence, MIME type, and size before persisting a post. Direct public media URLs are not accepted as post input.

Post responses include ordered `imageUrls` and nullable `videoUrl`. A video post has an empty `imageUrls` list. Video posts use the same feed pagination, likes, comments, and social/community visibility rules as photos. Module 9A plays the original uploaded file; it does not transcode, generate thumbnails, or validate codecs. MP4 with H.264/AAC is the most portable choice; actual playback support depends on the device/browser.

The feed returns `{ "items": [...], "nextCursor": "..." }` in chronological newest-first order, with ID as the tie-breaker. `limit` defaults to 20 and must be between 1 and 50. A null `nextCursor` means there are no more posts. Clients should pass the cursor back unchanged and treat it as opaque.

## Communities

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/api/communities` | Create a community and join as its first member |
| GET | `/api/communities?query=dog&sort=recent&limit=20&page=0` | Search communities using PostgreSQL full-text search |
| GET | `/api/communities?sort=hot&limit=20&page=0` | Browse communities by members and recent posts |
| GET | `/api/communities/{id}` | Read community details and membership state |
| PUT | `/api/communities/{id}` | Update description and rules as owner or moderator |
| POST / DELETE | `/api/communities/{id}/members` | Join or leave a community |
| GET | `/api/communities/{id}/members?limit=20&page=0` | Read community members |
| PUT | `/api/communities/{id}/members/{petId}/role` | Set a member or moderator role as owner |
| DELETE | `/api/communities/{id}/members/{petId}` | Remove a member as owner or moderator |
| DELETE | `/api/communities/{id}/posts/{postId}` | Remove a post from the community as owner or moderator |
| GET | `/api/communities/{id}/feed?limit=20&cursor=...` | Read community posts with the feed cursor |

Create a community with `{ "name": "Houston Dog Parks", "description": "Local walks" }`. Its creator joins automatically; join and leave are idempotent. Community lists use zero-based `page` and `nextPage`. To post in a community, include `"communityId": 123` in the existing post request while joined. A post belongs to at most one community. Community feeds use the same newest-first cursor contract and respect existing private-profile, block, and mute visibility rules.

The creator is the owner and cannot leave. Set community details with `{ "description": "Local walks", "rules": "Be kind" }`. Owners can assign `MODERATOR` or `MEMBER` using `{ "role": "MODERATOR" }`. Moderators can edit rules and remove regular members or community posts; only owners can change roles or remove moderators. Community owners and moderators see the full roster and all attached posts in their community, including pets hidden by normal social controls, so they can moderate them. Ordinary community browsing and all global social endpoints keep their privacy, block, and mute filters. Removing a community post keeps the original post in the general feed. Search matches community name, description, and rules. `sort=hot` ranks by member count plus recent post activity; search results rank by text relevance. Redis caches only the top community IDs for one minute; details and membership state are always read from PostgreSQL.

## Walks and territory

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/api/walks` | Save a completed walk with ordered GPS points |
| GET | `/api/walks?limit=20&page=0` | List the active pet's walks, newest first |
| GET | `/api/walks/{id}` | Read the active pet's saved route |
| POST | `/api/walks/{id}/territory` | Claim a territory from the active pet's saved walk |
| GET | `/api/walks/{id}/territory` | Read the claimed territory and its polygon |
| GET | `/api/territories/history?limit=20&page=0` | List the active pet's claims, newest first |
| GET | `/api/territories/leaderboard?limit=20` | Rank pets by currently controlled area |
| GET | `/api/walks/nearby?latitude=29.76&longitude=-95.37&radiusMeters=1000&limit=20&page=0` | Find the active pet's routes near a location |

To save a walk, send `{ "clientWalkId": "a9ee0f56-095a-4ec8-bf87-e56531fdcdb9", "startedAt": "2026-10-03T10:00:00Z", "endedAt": "2026-10-03T10:10:00Z", "points": [{ "latitude": 29.7604, "longitude": -95.3698, "recordedAt": "2026-10-03T10:00:00Z" }] }` to `POST /api/walks`. Generate one UUID per walk and reuse the exact request body for retries. The first save returns 201; an identical retry returns the same walk with 200. Reusing the UUID for different walk data returns 409. A walk accepts 1-2000 points. The backend applies basic route sanity validation before saving: point timestamps must increase strictly, remain within the walk time, and imply no movement segment above 12 m/s. The API stores coordinates in PostgreSQL for the active development pet. Walk history uses zero-based `page`, `limit` from 1 to 50, and a null `nextPage` at the end.

Walks also store a PostGIS `LineString` in WGS84 (SRID 4326), with a GiST geography index. `distanceMeters` is calculated from the full route using geography; details return original `points` and a GeoJSON `route` simplified for map display with a 0.00003-degree tolerance. A single-point walk has a zero-length route. Nearby search uses `ST_DWithin` against the full route and returns `{ "items": [{ "walk": {...}, "proximityMeters": 0 }], "nextPage": null }`, nearest first. `radiusMeters` defaults to 1000 and must be 1-10000. Walk history, details, and nearby search all remain scoped to the active pet. The **My routes near me** screen searches within 1 km; routes are not publicly shared.

Claiming a territory buffers the saved route by 20 meters in PostGIS and stores one immutable claim polygon per walk. `POST /api/walks/{id}/territory` returns 201 on the first claim and the same territory with 200 on retry. Claim strength starts at 1, increases by 1 for each 200 meters walked up to 5, and decreases by 1 every seven days to a minimum of 0. In overlapping ground, the claim with higher current strength controls the area; ties go to the newer claim. PostGIS calculates exclusive `ownedArea` and `ownedAreaSquareMeters` dynamically using spatial union and difference, so ownership can change as claims decay without rewriting claim history. `area` and `areaSquareMeters` remain the original footprint, while `contestedAreaSquareMeters` measures overlap with other pets' footprints. Only the active pet can read a claim's exact polygons or its history. The leaderboard exposes aggregate controlled area and pet names, caches results in Redis for up to one minute, and falls back to PostgreSQL if Redis is unavailable. This is a simple game rule, not an anti-cheat system.

## Pet-care tasks

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/api/tasks` | Post a pet-care task as the active development pet |
| GET | `/api/tasks?scope=open&limit=20&page=0` | Browse open tasks |
| GET | `/api/tasks?scope=mine&limit=20&page=0` | List tasks created or accepted by the active pet |
| GET | `/api/tasks/nearby?latitude=29.76&longitude=-95.37&radiusMeters=5000&limit=20&page=0` | Browse open tasks nearby, nearest first |
| GET | `/api/tasks/profile` | Read the active pet's availability and received rating summary |
| PUT | `/api/tasks/availability` | Enable or pause new task acceptance |
| GET | `/api/tasks/{id}` | Read task details |
| GET | `/api/tasks/{id}/history` | Read status history as creator or assignee |
| PUT | `/api/tasks/{id}/rating` | Rate a completed task as creator |
| POST | `/api/tasks/{id}/accept` | Accept an open task posted by another pet |
| POST | `/api/tasks/{id}/start` | Start an accepted task as its assignee |
| POST | `/api/tasks/{id}/complete` | Complete an in-progress task as its assignee |
| POST | `/api/tasks/{id}/cancel` | Cancel an open or accepted task as its creator |

Post a task with `{ "title": "Walk Mochi", "description": "Short afternoon walk", "category": "DOG_WALKING", "latitude": 29.7604, "longitude": -95.3698 }`. Categories are `DOG_WALKING`, `PET_SITTING`, `FEEDING`, and `CHECK_IN`. Task lists return `{ "items": [...], "nextPage": 1 }` with zero-based pages and a 1-50 limit. State moves from `OPEN` to `ACCEPTED` to `IN_PROGRESS` to `COMPLETED`; the creator may cancel an open or accepted task. The task row is locked for state changes, so only one pet can accept it. Each successful transition records an event in the same transaction; retries that return an existing assignment do not duplicate events. History is available only to the creator and assignee.

Blocked pets cannot discover or newly accept each other's tasks in either block direction. Existing creators and assignees retain access after a later block so they can finish or cancel an established task when its state permits. Task responses include `locationExact`: creators and assignees receive their task's exact stored coordinates with `true`; everyone else receives coordinates rounded to two decimal places with `false`. This applies to task detail and lists, including completed or cancelled tasks. It provides coarse location privacy, not anonymization. Mutes and private-profile settings do not hide marketplace entries.

**Find tasks near me** searches within 5 km on mobile. The API accepts a radius of 1-20000 meters (default 5000) and returns `{ "items": [{ "task": {...}, "distanceMeters": 0 }], "nextPage": null }`. PostGIS filters and sorts by distance with spatial indexes, then creation time and ID as tie-breakers. For other pets' tasks, both the search radius and distance are calculated from the rounded public location; the creator can search their own exact location. Distance queries do not expose a hidden exact point.

Use **Available to accept tasks** to pause new acceptance with `{ "acceptingTasks": false }`; existing assignments can still be started and completed. Creators can rate completed tasks with `{ "score": 5, "comment": "Great walk" }`, using 1-5 stars and an optional comment of up to 500 characters. Saving again updates the same rating. The assignee's **Pet-care tasks** screen shows their average and rating count. Task details show participant status history and the creator's rating. Payments and disputes remain future work; optional device push is described in the [development guide](DEVELOPMENT.md#device-push).

## Messaging and notifications

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/api/conversations` | Open or reuse a direct conversation with another pet |
| GET | `/api/conversations?limit=20&page=0` | List the active pet's conversations, recent activity first |
| GET | `/api/conversations/{id}` | Read conversation details as a member |
| GET | `/api/conversations/{id}/messages?limit=30` | Read the most recent messages |
| GET | `/api/conversations/{id}/messages?limit=30&beforeId=123` | Load earlier history |
| GET | `/api/conversations/{id}/messages?limit=50&afterId=123` | Poll for newer messages |
| POST | `/api/conversations/{id}/messages` | Send text as a conversation member |
| PUT | `/api/conversations/{id}/receipt` | Advance member delivery/read positions |
| GET | `/api/notifications/unread` | Read actor-scoped unread message and notification counts |
| GET / PUT / DELETE | `/api/notifications/push-devices/{uuid}` | Inspect, register, or pause an owned push device |
| GET | `/api/notifications?limit=20&beforeId=123` | Read the active pet's notification center |
| PUT | `/api/notifications/{id}/read` | Mark an owned notification read idempotently |

Tap **Messages** on the profile, or **Message pet** on another pet's profile. `POST /api/conversations` accepts `{ "petId": 1000 }`; the unordered pet pair has one persisted conversation. Only its two members can list, read, or send messages. Send `{ "clientMessageId": "a9ee0f56-095a-4ec8-bf87-e56531fdcdb9", "body": "Hello Biscuit!" }`. Text must be non-blank and at most 2000 characters. The client generates one UUID per send and reuses the same payload on retry. Identical retries return the original message; different text with the same UUID returns 409. Message creation and the recipient's notification commit together.

Message pages return `{ "items": [...], "nextBeforeId": 123, "nextAfterId": null }`, with each page ordered oldest first. Without a cursor, the API returns the latest page. Use `nextBeforeId` to load older history. Poll with `afterId` equal to the last received message ID (or 0 for an empty conversation); if `nextAfterId` is present, continue from it before the next polling interval. Limits are 1-50. The chat screen refreshes from WebSocket hints while focused and foregrounded, with REST polling every five seconds when disconnected and every 30 seconds when connected. It stops refreshing when hidden/backgrounded, merges messages by ID, and retains failed sends for explicit retry. It does not advance the receive cursor from a send response, so incoming messages between polls are not skipped. Conversation lists use zero-based pages with `nextPage`; refresh to reload recent activity.

Blocks in either direction prevent opening conversations and sending new messages. These writes use the existing PostgreSQL pet-pair locks, serializing them with block changes. Existing conversation members retain access to previously exchanged history; an exact retry can resolve an already committed message after a later block. Mute remains feed-only, and private profiles do not prevent direct messaging. No conversation content is exposed to non-members.

Tap **Notifications** for incoming-message and task-status events. Each actual task transition notifies the other established participant, including after a later block; an unassigned cancellation has no recipient. Message/transition retries do not duplicate notifications. The center is recipient-scoped, newest first, and uses `nextBeforeId` for older pages. Opening an item marks it read and navigates to its conversation or task. Lists refresh on focus or pull-to-refresh; notifications persist across application restarts. Use the [development identity switch](DEVELOPMENT.md#development-identities) to exchange messages as Mochi and Biscuit. The profile and conversation list show PostgreSQL-backed unread counts, and incoming events refresh the notification center.

### Live updates and receipts

`/api/events` is a foreground WebSocket with metadata-only `SYNC`, `MESSAGE`, `RECEIPT`, and `NOTIFICATIONS` hints. Clients fetch content through the existing member/recipient-restricted REST endpoints. The handshake uses the same server-configured development pet as REST, rejects client-selected identity/subscriptions, and uses `APP_ALLOWED_ORIGINS` for browser origins. `ping` receives `PONG`; backgrounding closes the socket, and reconnecting resynchronizes REST state. Reconnect delay grows from one to 30 seconds. A periodic REST reconciliation also handles lost hints.

Set `REALTIME_REDIS_ENABLED=true` to relay committed events across API instances using Redis Pub/Sub. The originating instance delivers locally and suppresses its own Redis echo. Events are published after commit; rolled-back messages emit no hints. With the relay disabled or unavailable, local sockets and REST still work. PostgreSQL remains authoritative for membership, blocks, unread counts, and receipts; Redis stores no messaging authorization state.

`GET /api/notifications/unread` returns `{ "messages": 2, "notifications": 3 }`. Conversation responses include `unreadCount`, `myDeliveredThroughId`, `peerDeliveredThroughId`, and `peerReadThroughId`. Send `PUT /api/conversations/{id}/receipt` with `{ "throughMessageId": 123, "read": false }` after fetching messages, or `read: true` after viewing them in the focused foreground chat. Positions only increase and the message must belong to the conversation. Reading also marks the corresponding incoming message notifications read. Message `deliveryStatus` is `SENT`, `DELIVERED`, or `READ`; sender bubbles update from peer receipts. Neither a WebSocket hint nor an Expo receipt counts as client delivery or reading.

Push devices register through REST; native setup, provider delivery behavior, and manual checks are in the [development guide](DEVELOPMENT.md#device-push).
