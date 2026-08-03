/**
 * GET /api/ai/status — is the local AI backend up, and what can it do?
 *
 * Drives the admin console's AI panel. Deliberately reports the *degraded*
 * states distinctly (offline / running but no models / models but nothing
 * embedded yet) because each has a different one-line fix.
 */

import { NextResponse } from 'next/server';
import { getStatus } from '@/lib/knowledge/ollama';
import { getStats } from '@/lib/knowledge/store';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

export type AiCapability = 'generative' | 'semantic' | 'keyword';

export async function GET() {
  // Bypass the cache: this panel is how HR checks whether the model they just
  // installed has been picked up.
  const [ollama, stats] = await Promise.all([getStatus({ fresh: true }), getStats()]);

  // What the employee actually gets right now, worst case first.
  const capability: AiCapability =
    ollama.ok && ollama.chatModel && ollama.embedModel && stats.embeddedChunks > 0
      ? 'generative'
      : ollama.ok && ollama.embedModel && stats.embeddedChunks > 0
        ? 'semantic'
        : 'keyword';

  return NextResponse.json({
    capability,
    ollama,
    stats,
    summary: summarise(capability, ollama.ok, stats.chunkCount),
  });
}

function summarise(capability: AiCapability, online: boolean, chunks: number): string {
  if (!chunks) {
    return 'No documents uploaded yet. Add HR policies to give the assistant something to answer from.';
  }
  switch (capability) {
    case 'generative':
      return 'Full AI: semantic search over your documents, with answers written by the local model and grounded in the source text.';
    case 'semantic':
      return 'Semantic search is live, but no chat model is installed — employees see the matching policy passage rather than a written answer.';
    default:
      return online
        ? 'Keyword search only. Pull an embedding model and rebuild the index to enable semantic matching.'
        : 'Keyword search only — Ollama is offline. Everything still works; answers are quoted from your documents.';
  }
}
