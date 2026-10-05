import { fetch as expoFetch } from 'expo/fetch';
import { File } from 'expo-file-system';
import type { ImagePickerAsset } from 'expo-image-picker';
import { Platform } from 'react-native';
import { validateVideoSize, videoContentType } from './postVideo';
import { readyVideo } from './videoUploadFlow';
import type { VideoDraft, VideoProgress, VideoState, VideoTicket } from './videoUploadFlow';

export type Gender = 'MALE' | 'FEMALE' | 'UNKNOWN';
export type PetInput = {
  name: string;
  species: string;
  breed: string | null;
  gender: Gender;
  birthday: string | null;
  bio: string | null;
};
export type Pet = PetInput & {
  id: number;
  ownerId: number;
  avatarUrl: string | null;
  privateProfile: boolean;
  createdAt: string;
  updatedAt: string;
};
export type PetSummary = {
  id: number;
  name: string;
  species: string;
  avatarUrl: string | null;
  privateProfile: boolean;
  followedByMe: boolean;
  requestedByMe: boolean;
};
export type PetListPage = { items: PetSummary[]; nextPage: number | null };
export type FollowStatus = {
  petId: number;
  followerCount: number;
  followingCount: number;
  followedByMe: boolean;
  requestedByMe: boolean;
  privateProfile: boolean;
  blockedByMe: boolean;
  mutedByMe: boolean;
};

export type PostInput = { body: string | null; imageKeys: string[]; videoKey?: string; communityId?: number };
export type Post = {
  body: string | null;
  imageUrls: string[];
  videoUrl: string | null;
  videoThumbnailUrl: string | null;
  id: number;
  petId: number;
  petName: string;
  petAvatarUrl: string | null;
  communityId: number | null;
  communityName: string | null;
  createdAt: string;
  likeCount: number;
  commentCount: number;
  likedByMe: boolean;
};
export type FeedPage = { items: Post[]; nextCursor: string | null };
export type Community = {
  id: number;
  name: string;
  description: string | null;
  rules: string | null;
  createdByPetId: number;
  createdAt: string;
  memberCount: number;
  joinedByMe: boolean;
  myRole: 'OWNER' | 'MODERATOR' | 'MEMBER' | null;
};
export type CommunityPage = { items: Community[]; nextPage: number | null };
export type CommunityMember = { id: number; name: string; species: string; avatarUrl: string | null;
  role: 'OWNER' | 'MODERATOR' | 'MEMBER' };
export type CommunityMemberPage = { items: CommunityMember[]; nextPage: number | null };
export type WalkPoint = { latitude: number; longitude: number; recordedAt: string };
export type WalkRoute = { type: 'LineString'; coordinates: [number, number][] };
export type Walk = { id: number; petId: number; startedAt: string; endedAt: string; points: WalkPoint[];
  distanceMeters: number; route: WalkRoute };
export type WalkSummary = { id: number; petId: number; startedAt: string; endedAt: string; pointCount: number;
  distanceMeters: number };
export type WalkPage = { items: WalkSummary[]; nextPage: number | null };
export type NearbyWalk = { walk: WalkSummary; proximityMeters: number };
export type NearbyWalkPage = { items: NearbyWalk[]; nextPage: number | null };
export type WalkInput = { clientWalkId: string; startedAt: string; endedAt: string; points: WalkPoint[] };
export type TerritoryArea = { type: 'Polygon'; coordinates: [number, number][][] };
export type OwnedTerritoryArea = { type: 'MultiPolygon'; coordinates: [number, number][][][] };
export type Territory = { id: number; walkId: number; petId: number; createdAt: string;
  areaSquareMeters: number; ownedAreaSquareMeters: number; contestedAreaSquareMeters: number;
  baseStrength: number; effectiveStrength: number; area: TerritoryArea; ownedArea: OwnedTerritoryArea };
export type TerritorySummary = { id: number; walkId: number; createdAt: string;
  baseStrength: number; effectiveStrength: number; areaSquareMeters: number; ownedAreaSquareMeters: number };
export type TerritoryPage = { items: TerritorySummary[]; nextPage: number | null };
export type TerritoryLeader = { petId: number; petName: string; areaSquareMeters: number; claimCount: number };
export type TaskCategory = 'DOG_WALKING' | 'PET_SITTING' | 'FEEDING' | 'CHECK_IN';
export type TaskStatus = 'OPEN' | 'ACCEPTED' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED';
export type TaskInput = { title: string; description: string; category: TaskCategory;
  latitude: number; longitude: number };
export type PetTask = TaskInput & { id: number; creatorPetId: number; creatorName: string;
  locationExact: boolean; status: TaskStatus; assigneePetId: number | null; assigneeName: string | null;
  createdAt: string; updatedAt: string; rating: TaskRating | null };
export type TaskPage = { items: PetTask[]; nextPage: number | null };
export type TaskRating = { score: number; comment: string | null; updatedAt: string };
export type TaskProfile = { petId: number; acceptingTasks: boolean; averageRating: number | null; ratingCount: number };
export type TaskEvent = { id: number; actorPetId: number; actorName: string; status: TaskStatus; createdAt: string };
export type ListingInput = { title: string; description: string; priceCents: number; imageUrls: string[] };
export type Listing = ListingInput & {
  id: number; sellerPetId: number; sellerName: string; sellerAvatarUrl: string | null;
  status: 'AVAILABLE' | 'SOLD'; favoritedByMe: boolean; createdAt: string; updatedAt: string;
};
export type ListingPage = { items: Listing[]; nextCursor: string | null };
export type ListingScope = 'available' | 'favorites' | 'mine';
export type NearbyTask = { task: PetTask; distanceMeters: number };
export type NearbyTaskPage = { items: NearbyTask[]; nextPage: number | null };
export type Conversation = { id: number; petId: number; petName: string; petAvatarUrl: string | null;
  lastMessage: string | null; updatedAt: string; canMessage: boolean; unreadCount: number;
  myDeliveredThroughId: number; peerDeliveredThroughId: number; peerReadThroughId: number };
export type ConversationPage = { items: Conversation[]; nextPage: number | null };
export type MessageInput = { clientMessageId: string; body: string };
export type ChatMessage = MessageInput & { id: number; conversationId: number; senderPetId: number; createdAt: string;
  deliveryStatus: 'SENT' | 'DELIVERED' | 'READ' };
export type MessagePage = { items: ChatMessage[]; nextBeforeId: number | null; nextAfterId: number | null };
export type PetNotification = { id: number; type: 'MESSAGE' | 'TASK_STATUS'; actorPetId: number; actorName: string;
  targetId: number; taskTitle: string | null; taskStatus: TaskStatus | null; createdAt: string; readAt: string | null };
export type NotificationPage = { items: PetNotification[]; nextBeforeId: number | null };
export type UnreadCounts = { messages: number; notifications: number };
export type PushDeviceStatus = { registered: boolean; serverEnabled: boolean };
export type Comment = {
  id: number;
  postId: number;
  petId: number;
  petName: string;
  petAvatarUrl: string | null;
  body: string;
  createdAt: string;
};

export const DEV_PET_ID = Number(process.env.EXPO_PUBLIC_DEV_PET_ID || '1');
const baseUrl = (process.env.EXPO_PUBLIC_API_URL || 'http://localhost:8080').replace(/\/$/, '');
export const eventsUrl = `${baseUrl.replace(/^http/, 'ws')}/api/events`;

export class ApiError extends Error {
  constructor(message: string, readonly status: number) { super(message); }
}

async function request<T>(path: string, options?: RequestInit): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`${baseUrl}${path}`, {
      ...options,
      headers: { 'Content-Type': 'application/json', ...options?.headers },
    });
  } catch {
    throw new Error(`Cannot reach the API at ${baseUrl}. Check EXPO_PUBLIC_API_URL and your network.`);
  }
  if (!response.ok) {
    const problem = await response.json().catch(() => ({}));
    throw new ApiError(problem.detail || problem.message || `Request failed (${response.status})`, response.status);
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

export function getPet(id: number): Promise<Pet> {
  return request<Pet>(`/api/pets/${id}`);
}

export function openConversation(petId: number): Promise<Conversation> {
  return request<Conversation>('/api/conversations', { method: 'POST', body: JSON.stringify({ petId }) });
}

export function getConversations(limit = 20, page = 0): Promise<ConversationPage> {
  return request<ConversationPage>(`/api/conversations?limit=${limit}&page=${page}`);
}

export function getConversation(id: number): Promise<Conversation> {
  return request<Conversation>(`/api/conversations/${id}`);
}

export function getMessages(id: number, options: { beforeId?: number; afterId?: number; limit?: number } = {}): Promise<MessagePage> {
  const query = new URLSearchParams({ limit: String(options.limit ?? 30) });
  if (options.beforeId !== undefined) query.set('beforeId', String(options.beforeId));
  if (options.afterId !== undefined) query.set('afterId', String(options.afterId));
  return request<MessagePage>(`/api/conversations/${id}/messages?${query}`);
}

export function sendMessage(id: number, input: MessageInput): Promise<ChatMessage> {
  return request<ChatMessage>(`/api/conversations/${id}/messages`, { method: 'POST', body: JSON.stringify(input) });
}

export function acknowledgeConversation(id: number, throughMessageId: number, read: boolean): Promise<Conversation> {
  return request<Conversation>(`/api/conversations/${id}/receipt`, {
    method: 'PUT', body: JSON.stringify({ throughMessageId, read }),
  });
}

export function getUnreadCounts(): Promise<UnreadCounts> {
  return request<UnreadCounts>('/api/notifications/unread');
}

export function getPushDevice(id: string): Promise<PushDeviceStatus> {
  return request<PushDeviceStatus>(`/api/notifications/push-devices/${id}`);
}

export function registerPushDevice(id: string, expoPushToken: string, platform: 'IOS' | 'ANDROID'): Promise<PushDeviceStatus> {
  return request<PushDeviceStatus>(`/api/notifications/push-devices/${id}`, {
    method: 'PUT', body: JSON.stringify({ expoPushToken, platform }),
  });
}

export function disablePushDevice(id: string): Promise<void> {
  return request<void>(`/api/notifications/push-devices/${id}`, { method: 'DELETE' });
}

export function getNotifications(limit = 20, beforeId?: number): Promise<NotificationPage> {
  return request<NotificationPage>(`/api/notifications?limit=${limit}${beforeId === undefined ? '' : `&beforeId=${beforeId}`}`);
}

export function readNotification(id: number): Promise<PetNotification> {
  return request<PetNotification>(`/api/notifications/${id}/read`, { method: 'PUT' });
}

export function savePet(id: number, input: PetInput): Promise<Pet> {
  return request<Pet>(`/api/pets/${id}`, { method: 'PUT', body: JSON.stringify(input) });
}

export function setPetPrivacy(id: number, privateProfile: boolean): Promise<Pet> {
  return request<Pet>(`/api/pets/${id}/privacy`, {
    method: 'PUT', body: JSON.stringify({ privateProfile }),
  });
}

export function discoverPets(limit = 20, page = 0): Promise<PetListPage> {
  return request<PetListPage>(`/api/pets/discover?limit=${limit}&page=${page}`);
}

export function getFollowers(id: number, limit = 20, page = 0): Promise<PetListPage> {
  return request<PetListPage>(`/api/pets/${id}/followers?limit=${limit}&page=${page}`);
}

export function getFollowing(id: number, limit = 20, page = 0): Promise<PetListPage> {
  return request<PetListPage>(`/api/pets/${id}/following?limit=${limit}&page=${page}`);
}

export function getFollowRequests(id: number, limit = 20, page = 0): Promise<PetListPage> {
  return request<PetListPage>(`/api/pets/${id}/follow-requests?limit=${limit}&page=${page}`);
}

export function approveFollowRequest(id: number, followerId: number): Promise<FollowStatus> {
  return request<FollowStatus>(`/api/pets/${id}/follow-requests/${followerId}`, { method: 'POST' });
}

export function declineFollowRequest(id: number, followerId: number): Promise<FollowStatus> {
  return request<FollowStatus>(`/api/pets/${id}/follow-requests/${followerId}`, { method: 'DELETE' });
}

export function getFollowStatus(id: number): Promise<FollowStatus> {
  return request<FollowStatus>(`/api/pets/${id}/social`);
}

export function followPet(id: number): Promise<FollowStatus> {
  return request<FollowStatus>(`/api/pets/${id}/follow`, { method: 'POST' });
}

export function unfollowPet(id: number): Promise<FollowStatus> {
  return request<FollowStatus>(`/api/pets/${id}/follow`, { method: 'DELETE' });
}

export function blockPet(id: number): Promise<FollowStatus> {
  return request<FollowStatus>(`/api/pets/${id}/block`, { method: 'POST' });
}

export function unblockPet(id: number): Promise<FollowStatus> {
  return request<FollowStatus>(`/api/pets/${id}/block`, { method: 'DELETE' });
}

export function mutePet(id: number): Promise<FollowStatus> {
  return request<FollowStatus>(`/api/pets/${id}/mute`, { method: 'POST' });
}

export function unmutePet(id: number): Promise<FollowStatus> {
  return request<FollowStatus>(`/api/pets/${id}/mute`, { method: 'DELETE' });
}

export function getFeed(limit = 20, cursor?: string, following = false): Promise<FeedPage> {
  const query = `limit=${limit}${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}${following ? '&following=true' : ''}`;
  return request<FeedPage>(`/api/feed?${query}`);
}

export function getCommunities(query = '', limit = 20, page = 0, sort: 'recent' | 'hot' = 'recent'): Promise<CommunityPage> {
  return request<CommunityPage>(`/api/communities?query=${encodeURIComponent(query)}&sort=${sort}&limit=${limit}&page=${page}`);
}

export function createCommunity(name: string, description: string): Promise<Community> {
  return request<Community>('/api/communities', {
    method: 'POST', body: JSON.stringify({ name, description: description.trim() || null }),
  });
}

export function getCommunity(id: number): Promise<Community> {
  return request<Community>(`/api/communities/${id}`);
}

export function updateCommunity(id: number, description: string, rules: string): Promise<Community> {
  return request<Community>(`/api/communities/${id}`, {
    method: 'PUT', body: JSON.stringify({ description: description.trim() || null, rules: rules.trim() || null }),
  });
}

export function setCommunityMemberRole(id: number, petId: number, role: 'MEMBER' | 'MODERATOR'): Promise<void> {
  return request<void>(`/api/communities/${id}/members/${petId}/role`, {
    method: 'PUT', body: JSON.stringify({ role }),
  });
}

export function removeCommunityMember(id: number, petId: number): Promise<void> {
  return request<void>(`/api/communities/${id}/members/${petId}`, { method: 'DELETE' });
}

export function removeCommunityPost(id: number, postId: number): Promise<void> {
  return request<void>(`/api/communities/${id}/posts/${postId}`, { method: 'DELETE' });
}

export function joinCommunity(id: number): Promise<Community> {
  return request<Community>(`/api/communities/${id}/members`, { method: 'POST' });
}

export function leaveCommunity(id: number): Promise<Community> {
  return request<Community>(`/api/communities/${id}/members`, { method: 'DELETE' });
}

export function getCommunityMembers(id: number, limit = 20, page = 0): Promise<CommunityMemberPage> {
  return request<CommunityMemberPage>(`/api/communities/${id}/members?limit=${limit}&page=${page}`);
}

export function getCommunityFeed(id: number, limit = 20, cursor?: string): Promise<FeedPage> {
  const query = `limit=${limit}${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`;
  return request<FeedPage>(`/api/communities/${id}/feed?${query}`);
}

export function createWalk(input: WalkInput): Promise<Walk> {
  return request<Walk>('/api/walks', { method: 'POST', body: JSON.stringify(input) });
}

export function getWalk(id: number): Promise<Walk> {
  return request<Walk>(`/api/walks/${id}`);
}

export function getWalkTerritory(walkId: number): Promise<Territory> {
  return request<Territory>(`/api/walks/${walkId}/territory`);
}

export function claimWalkTerritory(walkId: number): Promise<Territory> {
  return request<Territory>(`/api/walks/${walkId}/territory`, { method: 'POST' });
}

export function getTerritoryHistory(limit = 20, page = 0): Promise<TerritoryPage> {
  return request<TerritoryPage>(`/api/territories/history?limit=${limit}&page=${page}`);
}

export function getTerritoryLeaderboard(limit = 20): Promise<TerritoryLeader[]> {
  return request<TerritoryLeader[]>(`/api/territories/leaderboard?limit=${limit}`);
}

export function getListings(scope: ListingScope = 'available', limit = 20, cursor?: string): Promise<ListingPage> {
  const query = `scope=${scope}&limit=${limit}${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`;
  return request<ListingPage>(`/api/listings?${query}`);
}

export function getListing(id: number): Promise<Listing> {
  return request<Listing>(`/api/listings/${id}`);
}

export function createListing(input: ListingInput): Promise<Listing> {
  return request<Listing>('/api/listings', { method: 'POST', body: JSON.stringify(input) });
}

export function favoriteListing(id: number): Promise<Listing> {
  return request<Listing>(`/api/listings/${id}/favorites`, { method: 'POST' });
}

export function unfavoriteListing(id: number): Promise<Listing> {
  return request<Listing>(`/api/listings/${id}/favorites`, { method: 'DELETE' });
}

export function markListingSold(id: number): Promise<Listing> {
  return request<Listing>(`/api/listings/${id}/sold`, { method: 'POST' });
}

export function getTasks(scope: 'open' | 'mine' = 'open', limit = 20, page = 0): Promise<TaskPage> {
  return request<TaskPage>(`/api/tasks?scope=${scope}&limit=${limit}&page=${page}`);
}

export function getTask(id: number): Promise<PetTask> {
  return request<PetTask>(`/api/tasks/${id}`);
}

export function getNearbyTasks(latitude: number, longitude: number, page = 0): Promise<NearbyTaskPage> {
  return request<NearbyTaskPage>(`/api/tasks/nearby?latitude=${latitude}&longitude=${longitude}&radiusMeters=5000&limit=20&page=${page}`);
}

export function getTaskProfile(): Promise<TaskProfile> {
  return request<TaskProfile>('/api/tasks/profile');
}

export function setTaskAvailability(acceptingTasks: boolean): Promise<TaskProfile> {
  return request<TaskProfile>('/api/tasks/availability', { method: 'PUT', body: JSON.stringify({ acceptingTasks }) });
}

export function getTaskHistory(id: number): Promise<TaskEvent[]> {
  return request<TaskEvent[]>(`/api/tasks/${id}/history`);
}

export function rateTask(id: number, score: number, comment: string): Promise<PetTask> {
  return request<PetTask>(`/api/tasks/${id}/rating`, {
    method: 'PUT', body: JSON.stringify({ score, comment: comment.trim() || null }),
  });
}

export function createTask(input: TaskInput): Promise<PetTask> {
  return request<PetTask>('/api/tasks', { method: 'POST', body: JSON.stringify(input) });
}

export function changeTask(id: number, action: 'accept' | 'start' | 'complete' | 'cancel'): Promise<PetTask> {
  return request<PetTask>(`/api/tasks/${id}/${action}`, { method: 'POST' });
}

export function getWalks(limit = 20, page = 0): Promise<WalkPage> {
  return request<WalkPage>(`/api/walks?limit=${limit}&page=${page}`);
}

export function getNearbyWalks(latitude: number, longitude: number, page = 0): Promise<NearbyWalkPage> {
  return request<NearbyWalkPage>(`/api/walks/nearby?latitude=${latitude}&longitude=${longitude}&radiusMeters=1000&limit=20&page=${page}`);
}

export function getPost(id: number): Promise<Post> {
  return request<Post>(`/api/posts/${id}`);
}

export function createPost(input: PostInput): Promise<Post> {
  return request<Post>('/api/posts', { method: 'POST', body: JSON.stringify(input) });
}

export async function uploadPostImage(uri: string): Promise<string> {
  const image = Platform.OS === 'web'
    ? await fetch(uri).then((result) => result.blob())
    : new File(uri);
  if (image.size === 0 || image.size > 5 * 1024 * 1024) {
    throw new Error('Choose an image smaller than 5 MB.');
  }
  const ticket = await request<{ key: string; uploadUrl: string; headers: Record<string, string> }>(
    '/api/posts/media-uploads',
    { method: 'POST', body: JSON.stringify({ contentType: 'image/jpeg' }) },
  );
  try {
    const uploaded = await expoFetch(ticket.uploadUrl, {
      method: 'PUT',
      headers: ticket.headers,
      body: image,
    });
    if (!uploaded.ok) throw new Error('Image upload failed. Please try again.');
  } catch (cause) {
    if (cause instanceof Error && cause.message.startsWith('Image upload failed')) throw cause;
    throw new Error('Cannot reach object storage. Check its network address and CORS settings.');
  }
  return ticket.key;
}

export async function uploadPostVideo(asset: ImagePickerAsset, draft: VideoDraft,
  progress: (value: VideoProgress) => void, signal: AbortSignal): Promise<string> {
  const contentType = videoContentType(asset);
  const video = Platform.OS === 'web' ? (asset.file || await fetch(asset.uri, { signal }).then(result => result.blob()))
    : new File(asset.uri);
  if (!video) throw new Error('Could not read the selected video.');
  validateVideoSize(video.size);
  const path = '/api/posts/video-uploads';
  const action = (id: string, suffix: string) => request<VideoState>(`${path}/${id}/${suffix}`, { method: 'POST', signal });
  return readyVideo(draft, {
    prepare: () => request<VideoTicket>(path, { method: 'POST', body: JSON.stringify({ contentType }), signal }),
    renew: id => request<VideoTicket>(`${path}/${id}/ticket`, { method: 'POST', signal }),
    get: id => request<VideoState>(`${path}/${id}`, { signal }),
    complete: id => action(id, 'complete'), retry: id => action(id, 'retry'),
    missingSource: error => error instanceof ApiError && error.status === 400,
    put: async (ticket, onProgress) => {
      const bytes = Platform.OS === 'web' ? video as Blob : await (video as File).arrayBuffer();
      if (signal.aborted) throw new Error('Video upload cancelled.');
      await new Promise<void>((resolve, reject) => {
        const xhr = new XMLHttpRequest();
        const abort = () => xhr.abort();
        const cleanup = () => signal.removeEventListener('abort', abort);
        xhr.open('PUT', ticket.uploadUrl);
        xhr.timeout = 120_000;
        Object.entries(ticket.headers).forEach(([name, value]) => xhr.setRequestHeader(name, value));
        xhr.upload.onprogress = event => {
          if (event.lengthComputable) onProgress(Math.min(100, Math.round(event.loaded / event.total * 100)));
        };
        xhr.onload = () => {
          cleanup();
          if (xhr.status >= 200 && xhr.status < 300) { onProgress(100); resolve(); }
          else reject(new Error('Video upload failed. Please try again.'));
        };
        xhr.onerror = xhr.ontimeout = () => { cleanup(); reject(new Error('Video upload failed. Check your network and try again.')); };
        xhr.onabort = () => { cleanup(); reject(new Error('Video upload cancelled.')); };
        signal.addEventListener('abort', abort, { once: true });
        xhr.send(bytes);
      });
    },
    wait: () => new Promise<void>((resolve, reject) => {
      const abort = () => { clearTimeout(timer); reject(new Error('Video upload cancelled.')); };
      const timer = setTimeout(() => { signal.removeEventListener('abort', abort); resolve(); }, 1000);
      signal.addEventListener('abort', abort, { once: true });
      if (signal.aborted) abort();
    }),
  }, progress, signal);
}

export function likePost(id: number): Promise<Post> {
  return request<Post>(`/api/posts/${id}/likes`, { method: 'POST' });
}

export function unlikePost(id: number): Promise<Post> {
  return request<Post>(`/api/posts/${id}/likes`, { method: 'DELETE' });
}

export function getComments(id: number): Promise<Comment[]> {
  return request<Comment[]>(`/api/posts/${id}/comments`);
}

export function createComment(id: number, body: string): Promise<Comment> {
  return request<Comment>(`/api/posts/${id}/comments`, {
    method: 'POST', body: JSON.stringify({ body }),
  });
}

export async function uploadAvatar(id: number, asset: ImagePickerAsset): Promise<Pet> {
  const contentType = asset.mimeType || 'image/jpeg';
  if (!['image/jpeg', 'image/png', 'image/webp'].includes(contentType)) {
    throw new Error('Choose a JPEG, PNG, or WebP image.');
  }
  if (asset.fileSize && asset.fileSize > 5 * 1024 * 1024) {
    throw new Error('Choose an image smaller than 5 MB.');
  }
  const ticket = await request<{ key: string; uploadUrl: string }>(
    `/api/pets/${id}/avatar-uploads`,
    { method: 'POST', body: JSON.stringify({ contentType }) },
  );
  const image = Platform.OS === 'web'
    ? await fetch(asset.uri).then((result) => result.blob())
    : new File(asset.uri);
  if (image.size > 5 * 1024 * 1024) {
    throw new Error('Choose an image smaller than 5 MB.');
  }
  try {
    const upload = await expoFetch(ticket.uploadUrl, {
      method: 'PUT',
      headers: { 'Content-Type': contentType },
      body: image,
    });
    if (!upload.ok) {
      throw new Error('Image upload failed. Check object storage and try again.');
    }
  } catch (cause) {
    if (cause instanceof Error && cause.message.startsWith('Image upload failed')) throw cause;
    throw new Error('Cannot reach object storage. Check its network address and CORS settings.');
  }
  return request<Pet>(`/api/pets/${id}/avatar`, {
    method: 'PUT',
    body: JSON.stringify({ key: ticket.key }),
  });
}
