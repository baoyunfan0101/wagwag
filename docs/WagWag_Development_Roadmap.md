# WagWag Development Roadmap

> A pet-first, location-based social app built incrementally by product module.

---

## 1. Development Strategy

WagWag is developed **vertically by product module**, rather than horizontally by infrastructure layer.

Each module follows the same two-step pattern:

### Step A — Minimal Real End-to-End Solution

Build the smallest version that is still a real product flow:

```text
React Native + Expo
        ↓ REST
Spring Boot
        ↓
PostgreSQL
```

The goal is to make the full data path work:

```text
Database
   ↓
Backend API
   ↓
Mobile UI
   ↓
User Action
   ↓
Backend API
   ↓
Database
```

No fake persistence. Mock data may be used temporarily during UI construction, but the module is not considered complete until data is persisted through the backend.

### Step B — Complete Engineering Solution

Only after the core flow works, introduce infrastructure that is justified by the module:

- Redis
- S3 / Cloudflare R2
- CDN
- PostGIS
- WebSocket
- Push Notification
- OpenSearch
- background jobs
- asynchronous processing

The principle is:

> Infrastructure should enter the architecture because a real product requirement needs it, not because it looks good in the stack.

---

# 2. Core Technology Stack

## Mobile

```text
React Native
Expo
TypeScript
Expo Router
TanStack Query
Zustand
```

Use TanStack Query and Zustand only after the project actually needs shared server/client state.

## Backend

```text
Java
Spring Boot
Spring Web
Spring Data JPA
Spring Security
```

Start as a **modular monolith**.

Do not start with microservices.

## Data

```text
PostgreSQL
PostGIS
Redis
```

PostGIS is introduced when location-based modules begin.

## Media

```text
S3 or Cloudflare R2
CDN
```

## Maps

```text
Mapbox
Expo Location
```

## Realtime

```text
WebSocket
Redis Pub/Sub
Expo Push Notifications
```

## Infrastructure

```text
Docker
GitHub Actions
Cloud deployment
```

---

# 3. High-Level Module Roadmap

```text
Pet Profile
    ↓
Post + Feed
    ↓
Follow / Social Graph
    ↓
Community
    ↓
Walking Map
    ↓
Territory
    ↓
Task Marketplace
    ↓
Messaging + Notification
    ↓
Video
    ↓
Shopping / Second-hand
```

There are also cross-module dependencies:

```text
Pet
 ├── Post
 ├── Walking
 ├── Task
 └── Messaging

Post
 ├── Feed
 ├── Community
 └── Search

Walking
 ├── Territory
 └── Nearby Task

Messaging
 └── Notification
```

---

# 4. Module 1 — Pet Profile

## Goal

Establish the central WagWag identity model:

> The pet is the social subject of the app.

---

## Phase 1A — Minimal End-to-End Solution

### Mobile

Build:

```text
Pet Profile Screen
Edit Pet Profile Screen
```

Fields:

```text
name
avatarUrl
species
breed
gender
birthday
bio
```

### Backend

Endpoints:

```http
GET /api/pets/{id}
POST /api/pets
PUT /api/pets/{id}
```

### Database

Initial tables:

```text
users
pets
```

Example `pets` fields:

```text
id
owner_id
name
species
breed
gender
birthday
bio
avatar_url
created_at
updated_at
```

### Data Flow

```text
React Native
    ↓ GET /api/pets/{id}
Spring Boot
    ↓
PostgreSQL
```

Edit flow:

```text
React Native
    ↓ PUT /api/pets/{id}
Spring Boot
    ↓
PostgreSQL
```

### Done Criteria

- [ ] Pet exists in PostgreSQL
- [ ] React Native can load pet from backend
- [ ] Profile page renders real backend data
- [ ] User can edit profile
- [ ] Saving updates PostgreSQL
- [ ] Restarting the app preserves the changes

---

## Phase 1B — Complete Solution

Add:

```text
Expo Image Picker
Object Storage
CDN
Pet ownership
validation
error handling
loading states
```

Avatar flow:

```text
React Native
    ↓ select image
Expo Image Picker
    ↓
S3 / R2
    ↓ URL
Spring Boot
    ↓
PostgreSQL.avatar_url
```

### Done Criteria

- [ ] User can select an avatar from phone
- [ ] Image is uploaded to object storage
- [ ] Database stores only media URL / key
- [ ] CDN/object storage serves the image
- [ ] Pet ownership is validated
- [ ] Input validation and API errors are handled

---

# 5. Module 2 — Post + Feed

## Goal

Build the core social loop:

```text
Create Post
    ↓
View Feed
    ↓
Like / Comment
```

---

## Phase 2A — Minimal End-to-End Solution

### Features

```text
Create text/image post
Post detail
Chronological feed
Like
Comment
```

Images may initially use fixed URLs or a basic upload path.

### Backend

Core endpoints:

```http
POST /api/posts
GET /api/posts/{id}
GET /api/feed
POST /api/posts/{id}/likes
DELETE /api/posts/{id}/likes
POST /api/posts/{id}/comments
GET /api/posts/{id}/comments
```

### Database

Add:

```text
posts
post_media
likes
comments
```

### Feed Strategy

First version:

```sql
ORDER BY created_at DESC
```

No recommendation algorithm.

### Done Criteria

- [ ] Pet can create a post
- [ ] Post persists in PostgreSQL
- [ ] Feed loads posts chronologically
- [ ] Like works
- [ ] Comment works
- [ ] App restart preserves data

---

## Phase 2B — Complete Solution

Add:

```text
multi-image upload
object storage
CDN
pagination
Redis
optimistic UI
media compression
feed caching
```

### Redis Use

Introduce Redis only here if needed for:

```text
feed cache
like count
comment count
hot post metadata
```

### Done Criteria

- [ ] Multi-image post works
- [ ] Images are stored outside PostgreSQL
- [ ] Cursor pagination works
- [ ] Feed supports cache
- [ ] Like/comment UX uses optimistic updates
- [ ] Counters remain consistent

---

# 6. Module 3 — Follow + Social Graph

## Goal

Allow pets to form social relationships.

---

## Phase 3A — Minimal End-to-End Solution

Features:

```text
Follow pet
Unfollow pet
Follower list
Following list
Follower / Following counts
Following Feed
```

Database:

```text
pet_follows
```

Possible schema:

```text
follower_pet_id
following_pet_id
created_at
```

Following feed can initially use a PostgreSQL JOIN.

### Done Criteria

- [ ] Follow/unfollow works
- [ ] Duplicate follows are prevented
- [ ] Followers/following lists work
- [ ] Feed can filter by followed pets

---

## Phase 3B — Complete Solution

Add:

```text
Redis relationship cache
cached follower counts
privacy controls
block
mute
feed optimization
```

Do not introduce complex fan-out architecture yet unless scale requires it.

---

# 7. Module 4 — Community / Topic Groups

## Goal

Support pet-focused communities similar to topic groups / forums.

Examples:

```text
Golden Retriever
Houston Dog Parks
Puppy Training
Pet Travel
Senior Cats
```

---

## Phase 4A — Minimal End-to-End Solution

Features:

```text
Create community
Join / leave community
Community detail page
Community post feed
Community members
```

Database:

```text
communities
community_members
post_communities
```

Search can initially use PostgreSQL.

### Done Criteria

- [ ] Community can be created
- [ ] Pet can join/leave
- [ ] Community feed works
- [ ] Posts can belong to a community

---

## Phase 4B — Complete Solution

Add:

```text
moderators
roles
community rules
hot communities
PostgreSQL FTS
or OpenSearch
Redis hot-community cache
```

Only introduce OpenSearch when PostgreSQL search becomes limiting.

---

# 8. Module 5 — Walking Map

## Goal

Build WagWag's first location-native feature.

This is the point where the project transitions from a normal social app into a location-based social network.

---

## Phase 5A — Minimal End-to-End Solution

### Mobile

Use:

```text
Expo Location
Mapbox
```

Flow:

```text
Start Walk
   ↓
collect GPS points
   ↓
send route
   ↓
Spring Boot
   ↓
PostgreSQL
   ↓
display route on Mapbox
```

Initial route representation may be a sequence of latitude/longitude points.

Database:

```text
walks
walk_points
```

### Done Criteria

- [ ] User can start/stop a walk
- [ ] GPS locations are collected
- [ ] Route persists in backend
- [ ] Past walk can be loaded
- [ ] Route renders on map

---

## Phase 5B — Complete Solution

Introduce:

```text
PostGIS
background location
route compression
spatial indexing
LineString
nearby queries
```

Data model evolves toward:

```text
walks.route -> PostGIS LINESTRING
```

Support:

```text
nearby walks
nearby pets
distance calculation
route intersection
park / area detection
```

### Done Criteria

- [ ] Walk survives background mode as intended
- [ ] Route stored as spatial geometry
- [ ] Spatial index exists
- [ ] Nearby queries use PostGIS
- [ ] GPS noise is reasonably filtered

---

# 9. Module 6 — Territory

## Goal

Turn walking activity into a game/social mechanic.

```text
Walk
  ↓
Route
  ↓
Territory
  ↓
Competition / Ownership
```

---

## Phase 6A — Minimal End-to-End Solution

Implement a simple deterministic territory rule.

Possible first version:

```text
walk route
   ↓
buffer around route
   ↓
polygon
   ↓
assign territory to pet
```

Display territory on Mapbox.

Database:

```text
territories
```

### Done Criteria

- [ ] Walk can create a territory area
- [ ] Territory belongs to a pet
- [ ] Territory renders on map
- [ ] Ownership is persisted

---

## Phase 6B — Complete Solution

Use PostGIS for:

```text
ST_Buffer
ST_Intersection
ST_Area
ST_Union
ST_Difference
```

Add:

```text
territory conflict
claim strength
decay
history
leaderboards
hot area cache
```

Redis can be introduced for frequently viewed territory metadata.

---

# 10. Module 7 — Task Marketplace

## Goal

Allow users to post and accept pet-care tasks.

Examples:

```text
Dog walking
Pet sitting
Feed pet
Check-in visit
```

---

## Phase 7A — Minimal End-to-End Solution

Features:

```text
Create task
Browse tasks
Task detail
Accept task
Cancel task
Complete task
```

Basic state machine:

```text
OPEN
 ↓
ACCEPTED
 ↓
IN_PROGRESS
 ↓
COMPLETED
```

Database:

```text
tasks
task_assignments
```

Location may initially be plain latitude/longitude.

### Done Criteria

- [ ] Task can be created
- [ ] Another user can accept it
- [ ] State transitions are valid
- [ ] Task data persists

---

## Phase 7B — Complete Solution

Add:

```text
PostGIS nearby tasks
distance sorting
concurrency control
ratings
task history
push notifications
availability
```

Later:

```text
payment
disputes
identity verification
```

Payment is deliberately postponed.

---

# 11. Module 8 — Messaging + Notifications

## Goal

Support communication and event-driven product feedback.

---

## Phase 8A — Minimal End-to-End Solution

Messaging can initially be REST-based.

Features:

```text
conversation list
message history
send message
notification center
```

Database:

```text
conversations
conversation_members
messages
notifications
```

The client may poll periodically.

### Done Criteria

- [ ] Two users can exchange messages
- [ ] Conversation history persists
- [ ] Basic in-app notifications work

---

## Phase 8B — Complete Solution

Introduce:

```text
WebSocket
Redis Pub/Sub
push notifications
unread counters
delivery status
read status
```

Flow:

```text
Client
  ↓ WebSocket
Spring Boot
  ↓
Redis Pub/Sub
  ↓
other server/client sessions
```

Push:

```text
Backend
   ↓
Expo Push Service
   ↓
APNs / FCM
   ↓
Device
```

---

# 12. Module 9 — Video

## Goal

Extend social posts from photo/text into short-form video.

---

## Phase 9A — Minimal End-to-End Solution

Features:

```text
video URL in post
video player
basic upload
```

Do not build a full TikTok-like feed yet.

---

## Phase 9B — Complete Solution

Add:

```text
object storage
CDN
thumbnail
compression
transcoding
async processing
upload progress
retry
```

Potential future architecture:

```text
Upload
  ↓
Object Storage
  ↓
Async Job
  ↓
Transcoding
  ↓
CDN
```

---

# 13. Module 10 — Shopping / Second-hand

## Goal

Support pet-related goods and peer-to-peer listings.

---

## Phase 10A — Minimal End-to-End Solution

Features:

```text
Create listing
Browse listings
Listing detail
Favorite
Contact seller
Mark sold
```

Database:

```text
listings
listing_media
favorites
```

---

## Phase 10B — Complete Solution

Add:

```text
object storage
search
location
recommendation
inventory state
seller rating
order model
```

Payments come only after the marketplace itself is proven useful.

---

# 14. Authentication Roadmap

Authentication should not dominate the beginning of the project.

A practical sequence:

## Early Development

Use a fixed development user/pet identity.

```text
DEV_USER_ID = 1
DEV_PET_ID = 1
```

This allows Pet Profile and early social flows to move quickly.

## Then Add Real Auth

After basic product flows are working:

```text
Register
Login
Access Token
Refresh Token
Spring Security
Authorization
```

Possible later additions:

```text
Apple Login
Google Login
email verification
password reset
```

Authorization must eventually enforce relationships such as:

```text
User owns Pet
Pet owns Post
User can edit own Task
User can access own Conversation
```

---

# 15. Architecture Evolution

## Stage 1 — Core Product

```text
React Native
     ↓
Spring Boot
     ↓
PostgreSQL
```

Used for:

```text
Pet Profile
basic Post
basic Feed
basic Follow
```

---

## Stage 2 — Media + Cache

```text
                ┌── PostgreSQL
React Native
     ↓          ├── Redis
Spring Boot ────┤
                └── S3 / R2 + CDN
```

Used for:

```text
avatars
post images
feed optimization
social counters
```

---

## Stage 3 — Location Platform

```text
React Native
 ├── Mapbox
 └── Expo Location
        ↓
Spring Boot
        ↓
PostgreSQL + PostGIS
        ↓
Redis
```

Used for:

```text
walking
nearby
territory
tasks
```

---

## Stage 4 — Realtime Platform

```text
React Native
     │
 REST + WebSocket
     │
Spring Boot
 ├── PostgreSQL/PostGIS
 ├── Redis
 ├── S3/R2 + CDN
 └── Push Notifications
```

Used for:

```text
messaging
notifications
task status
realtime interactions
```

---

# 16. Technologies Deliberately Postponed

Do **not** introduce these early:

```text
Kafka
Kubernetes
microservices
service mesh
complex event sourcing
complex recommendation system
large-scale feature store
OpenSearch before needed
```

They should only appear when a concrete problem requires them.

For example:

```text
Search too limited
    → OpenSearch

Cross-service event volume becomes large
    → Kafka

Independent deployment boundaries become necessary
    → microservices

Operational scale becomes meaningful
    → Kubernetes
```

---

# 17. Suggested Repository Structure

A monorepo is convenient during early development.

```text
wagwag/
├── mobile/
│   ├── app/
│   ├── src/
│   │   ├── components/
│   │   ├── features/
│   │   ├── services/
│   │   ├── stores/
│   │   └── types/
│   └── assets/
│
├── backend/
│   └── src/main/java/com/wagwag/
│       ├── user/
│       ├── pet/
│       ├── post/
│       ├── social/
│       ├── community/
│       ├── walk/
│       ├── territory/
│       ├── task/
│       ├── message/
│       ├── notification/
│       └── common/
│
├── infra/
│   ├── docker/
│   └── compose/
│
└── docs/
    └── roadmap.md
```

Keep the backend as a modular monolith.

---

# 18. Per-Module Execution Template

For every new WagWag module, use this checklist.

## 1. Product Definition

```text
What user problem does this solve?
What is the smallest useful user flow?
What is explicitly out of scope?
```

## 2. Data Model

```text
entities
relationships
constraints
indexes
```

## 3. Backend API

```text
endpoints
request DTOs
response DTOs
validation
errors
```

## 4. Mobile UI

```text
screens
navigation
loading
empty
error
success
```

## 5. End-to-End Integration

Verify:

```text
Mobile
  ↓
API
  ↓
Database
  ↓
API
  ↓
Mobile
```

## 6. Minimal Version Done

Only after this works should the module enter the engineering upgrade phase.

## 7. Engineering Upgrade

Ask:

```text
Does this need cache?
Does this need object storage?
Does this need realtime?
Does this need spatial queries?
Does this need search?
Does this need background processing?
```

Introduce only what is needed.

## 8. Complete Version Done

Verify:

```text
correctness
error handling
performance
persistence
security
mobile UX
observability
```

---

# 19. Development Milestones

## Milestone A — Identity

```text
Pet Profile
User ownership
Avatar
```

Result:

> WagWag has a real pet identity.

---

## Milestone B — Social Core

```text
Post
Feed
Like
Comment
Follow
```

Result:

> WagWag functions as a basic pet social network.

---

## Milestone C — Community

```text
Topic groups
Community feed
Community membership
Search
```

Result:

> Users can form interest-based pet communities.

---

## Milestone D — Location

```text
Walk tracking
Map
PostGIS
Nearby
```

Result:

> WagWag becomes location-aware.

---

## Milestone E — Differentiation

```text
Territory
Territory competition
Location-based discovery
```

Result:

> WagWag gains its most distinctive product mechanic.

---

## Milestone F — Services

```text
Task Marketplace
Nearby tasks
Ratings
Notifications
```

Result:

> WagWag begins supporting real-world pet services.

---

## Milestone G — Realtime

```text
Messaging
WebSocket
Push
Realtime state
```

Result:

> The product supports active user-to-user interaction.

---

## Milestone H — Content Expansion

```text
Video
Media pipeline
Search improvements
```

Result:

> WagWag evolves beyond a simple image/text social app.

---

## Milestone I — Commerce

```text
Second-hand
Shopping
Seller profiles
Order flow
```

Result:

> WagWag becomes a broader pet ecosystem.

---

# 20. Current Starting Point

The immediate task is:

```text
Module 1
Pet Profile
```

Start with:

```text
React Native + Expo
        ↓
Spring Boot
        ↓
PostgreSQL
```

First implementation target:

```text
1. Create users/pets schema
2. Create Pet entity + repository
3. Implement GET /api/pets/{id}
4. Build React Native Pet Profile screen
5. Connect screen to backend
6. Implement Edit Profile
7. Implement PUT /api/pets/{id}
8. Verify persistence
```

Only after this works:

```text
9. Add image picker
10. Add S3/R2
11. Upload avatar
12. Persist avatar URL
13. Add ownership validation
14. Finish Module 1
```

Then proceed to:

```text
Module 2
Post + Feed
```

---

# 21. Guiding Rule

Whenever there is uncertainty about what to build next, use this rule:

> **Finish one real user flow end-to-end before increasing architectural complexity.**

For WagWag, product complexity should grow first.

Infrastructure complexity should follow it.
