import { fetch as expoFetch } from 'expo/fetch';
import { File } from 'expo-file-system';
import type { ImagePickerAsset } from 'expo-image-picker';
import { Platform } from 'react-native';

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
  createdAt: string;
  updatedAt: string;
};
export type PetSummary = {
  id: number;
  name: string;
  species: string;
  avatarUrl: string | null;
  followedByMe: boolean;
};
export type PetListPage = { items: PetSummary[]; nextPage: number | null };
export type FollowStatus = {
  petId: number;
  followerCount: number;
  followingCount: number;
  followedByMe: boolean;
};

export type PostInput = { body: string | null; imageKeys: string[] };
export type Post = {
  body: string | null;
  imageUrls: string[];
  id: number;
  petId: number;
  petName: string;
  petAvatarUrl: string | null;
  createdAt: string;
  likeCount: number;
  commentCount: number;
  likedByMe: boolean;
};
export type FeedPage = { items: Post[]; nextCursor: string | null };
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
    throw new Error(problem.detail || problem.message || `Request failed (${response.status})`);
  }
  return response.json() as Promise<T>;
}

export function getPet(id: number): Promise<Pet> {
  return request<Pet>(`/api/pets/${id}`);
}

export function savePet(id: number, input: PetInput): Promise<Pet> {
  return request<Pet>(`/api/pets/${id}`, { method: 'PUT', body: JSON.stringify(input) });
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

export function getFollowStatus(id: number): Promise<FollowStatus> {
  return request<FollowStatus>(`/api/pets/${id}/social`);
}

export function followPet(id: number): Promise<FollowStatus> {
  return request<FollowStatus>(`/api/pets/${id}/follow`, { method: 'POST' });
}

export function unfollowPet(id: number): Promise<FollowStatus> {
  return request<FollowStatus>(`/api/pets/${id}/follow`, { method: 'DELETE' });
}

export function getFeed(limit = 20, cursor?: string, following = false): Promise<FeedPage> {
  const query = `limit=${limit}${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}${following ? '&following=true' : ''}`;
  return request<FeedPage>(`/api/feed?${query}`);
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
