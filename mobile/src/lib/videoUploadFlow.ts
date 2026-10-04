export type VideoTicket = { id: string; key: string; uploadUrl: string; headers: Record<string, string> };
export type VideoState = {
  id: string; key: string; status: 'UPLOADING' | 'QUEUED' | 'PROCESSING' | 'READY' | 'FAILED';
  error: string | null; videoUrl: string | null; thumbnailUrl: string | null;
};
export type VideoDraft = { ticket?: VideoTicket; uploaded: boolean };
export type VideoProgress = { stage: 'uploading' | 'queued' | 'processing' | 'ready'; percent?: number };
type Dependencies = {
  prepare: () => Promise<VideoTicket>;
  renew: (id: string) => Promise<VideoTicket>;
  get: (id: string) => Promise<VideoState>;
  complete: (id: string) => Promise<VideoState>;
  retry: (id: string) => Promise<VideoState>;
  put: (ticket: VideoTicket, progress: (percent: number) => void) => Promise<void>;
  missingSource: (error: unknown) => boolean;
  wait: () => Promise<void>;
};

// The draft belongs to the selected file and survives upload, processing, and publish retries.
export async function readyVideo(draft: VideoDraft, api: Dependencies,
  progress: (value: VideoProgress) => void, signal: AbortSignal): Promise<string> {
  function check() { if (signal.aborted) throw new Error('Video upload cancelled.'); }
  check();
  const existing = !!draft.ticket;
  if (!draft.ticket) draft.ticket = await api.prepare();
  check();
  const id = draft.ticket.id;
  let state: VideoState | undefined;
  if (existing) {
    state = await api.get(id);
    check();
    if (state.status === 'UPLOADING' && !draft.uploaded) {
      // A lost PUT response may still mean the object was committed. Probe before re-uploading.
      try { state = await api.complete(id); draft.uploaded = true; }
      catch (error) { if (!api.missingSource(error)) throw error; }
      check();
      if (state.status === 'UPLOADING') draft.ticket = await api.renew(id);
    }
  }
  if (!state || state.status === 'UPLOADING') {
    if (!draft.uploaded) {
      progress({ stage: 'uploading', percent: 0 });
      try {
        await api.put(draft.ticket, percent => progress({ stage: 'uploading', percent }));
        draft.uploaded = true;
      } catch (error) {
        check();
        // Resolve an ambiguous network failure without attempting to overwrite a completed upload.
        try { state = await api.complete(id); draft.uploaded = true; }
        catch { throw error; }
      }
    }
    check();
    if (!state || state.status === 'UPLOADING') state = await api.complete(id);
  }
  check();
  if (state.status === 'FAILED') {
    if (state.error === 'INVALID_VIDEO' || state.error === 'VIDEO_TOO_LARGE') {
      throw new Error('This video could not be processed. Please choose another video.');
    }
    state = await api.retry(id);
  }
  // Bounded foreground polling; retry resumes this job instead of uploading the file again.
  for (let poll = 0; poll < 180; poll++) {
    check();
    if (state.status === 'READY') { progress({ stage: 'ready' }); return state.key; }
    if (state.status === 'FAILED') {
      throw new Error(state.error === 'INVALID_VIDEO' || state.error === 'VIDEO_TOO_LARGE'
        ? 'This video could not be processed. Please choose another video.'
        : 'Video processing failed. Please try again.');
    }
    progress({ stage: state.status === 'PROCESSING' ? 'processing' : 'queued' });
    await api.wait();
    check();
    state = await api.get(id);
  }
  throw new Error('The video is still processing. Please retry to check its progress.');
}
