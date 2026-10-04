export const MAX_POST_VIDEO_BYTES = 50 * 1024 * 1024;
export type PostVideoType = 'video/mp4' | 'video/quicktime' | 'video/webm';
type SelectedVideo = { uri: string; mimeType?: string; fileName?: string | null; fileSize?: number };

export function videoContentType(video: SelectedVideo): PostVideoType {
  const mime = video.mimeType?.toLowerCase();
  if (mime === 'video/mp4' || mime === 'video/quicktime' || mime === 'video/webm') return mime;
  // Some native pickers omit MIME metadata. Infer only from an explicit supported extension.
  if (!mime) {
    const name = (video.fileName || video.uri.split(/[?#]/)[0]).toLowerCase();
    if (name.endsWith('.mp4')) return 'video/mp4';
    if (name.endsWith('.mov')) return 'video/quicktime';
    if (name.endsWith('.webm')) return 'video/webm';
  }
  throw new Error('Choose an MP4, MOV, or WebM video. MP4 works best across devices.');
}

export function validateVideoSize(bytes: number) {
  if (!Number.isFinite(bytes) || bytes <= 0 || bytes > MAX_POST_VIDEO_BYTES) {
    throw new Error('Choose a non-empty video no larger than 50 MB.');
  }
}
