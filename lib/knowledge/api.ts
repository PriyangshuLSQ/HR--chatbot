/**
 * Browser-side client for the knowledge-base API.
 *
 * Deliberately separate from the server modules: importing `store.ts` or
 * `rag.ts` from a client component would pull `node:fs` and `node:zlib` into
 * the bundle and fail the build. This file imports nothing but types.
 */

import type { KnowledgeDoc } from './types';

export interface StoreStats {
  docCount: number;
  chunkCount: number;
  embeddedChunks: number;
  pendingChunks: number;
  bytes: number;
}

export type AiCapability = 'generative' | 'semantic' | 'keyword';

export interface OllamaStatus {
  ok: boolean;
  url: string;
  models: string[];
  embedModel: string | null;
  chatModel: string | null;
  hint?: string;
}

export interface AiStatus {
  capability: AiCapability;
  ollama: OllamaStatus;
  stats: StoreStats;
  summary: string;
}

export interface UploadOutcome {
  filename: string;
  ok: boolean;
  error?: string;
  chunkCount?: number;
  embedded?: boolean;
  notes?: string[];
  docId?: string;
}

export interface KnowledgeSnapshot {
  docs: KnowledgeDoc[];
  stats: StoreStats;
}

export interface ReindexResult {
  embedded: number;
  skipped: number;
  model: string | null;
  message: string;
  stats?: StoreStats;
}

async function json<T>(res: Response): Promise<T> {
  const body = (await res.json().catch(() => null)) as (T & { error?: string }) | null;
  if (!res.ok) throw new Error(body?.error || `Request failed (${res.status})`);
  if (!body) throw new Error('Empty response from the server.');
  return body;
}

export async function fetchKnowledge(): Promise<KnowledgeSnapshot> {
  return json<KnowledgeSnapshot>(await fetch('/api/knowledge', { cache: 'no-store' }));
}

export async function fetchAiStatus(): Promise<AiStatus> {
  return json<AiStatus>(await fetch('/api/ai/status', { cache: 'no-store' }));
}

export async function uploadDocuments(
  files: File[],
  category: string
): Promise<{ results: UploadOutcome[]; stats: StoreStats }> {
  const form = new FormData();
  for (const file of files) form.append('files', file);
  form.append('category', category);

  return json<{ results: UploadOutcome[]; stats: StoreStats }>(
    await fetch('/api/knowledge/upload', { method: 'POST', body: form })
  );
}

export async function addEntry(input: {
  title: string;
  text: string;
  category: string;
}): Promise<{ doc: KnowledgeDoc; chunkCount: number; embedded: boolean; notes: string[] }> {
  return json(
    await fetch('/api/knowledge', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(input),
    })
  );
}

export async function deleteDocument(id: string): Promise<void> {
  await json(await fetch(`/api/knowledge?id=${encodeURIComponent(id)}`, { method: 'DELETE' }));
}

export async function rebuildIndex(force = false): Promise<ReindexResult> {
  return json<ReindexResult>(
    await fetch(`/api/knowledge/reindex${force ? '?force=1' : ''}`, { method: 'POST' })
  );
}

/** Accepted upload formats, shared by the file input and the drop handler. */
export const ACCEPTED_EXTENSIONS = ['.txt', '.md', '.markdown', '.csv', '.tsv', '.docx'];

export function isAcceptedFile(name: string): boolean {
  const lower = name.toLowerCase();
  return ACCEPTED_EXTENSIONS.some((ext) => lower.endsWith(ext));
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}
