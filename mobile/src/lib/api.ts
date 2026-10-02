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

export type PostInput = { body: string | null; imageUrl: string | null };
export type Post = PostInput & {
  id: number;
  petId: number;
  petName: string;
  petAvatarUrl: string | null;
  createdAt: string;
  likeCount: number;
  commentCount: number;
  likedByMe: boolean;
};
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

export function getFeed(): Promise<Post[]> {
  return request<Post[]>('/api/feed');
}

export function getPost(id: number): Promise<Post> {
  return request<Post>(`/api/posts/${id}`);
}

export function createPost(input: PostInput): Promise<Post> {
  return request<Post>('/api/posts', { method: 'POST', body: JSON.stringify(input) });
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
