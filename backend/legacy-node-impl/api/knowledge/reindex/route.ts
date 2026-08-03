/**
 * POST /api/knowledge/reindex — compute embeddings for chunks that lack them.
 *
 * The recovery path for the common case: HR uploaded documents before
 * installing Ollama, so everything is keyword-searchable but semantically
 * blind. Running this once turns the whole corpus semantic.
 *
 * `?force=1` re-embeds everything, for when the embedding model is changed.
 */

import { NextResponse } from 'next/server';
import { reindex } from '@/lib/knowledge/rag';
import { getStats } from '@/lib/knowledge/store';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';
/** Embedding a large corpus is slow; allow well past the default. */
export const maxDuration = 300;

export async function POST(request: Request) {
  const force = new URL(request.url).searchParams.get('force') === '1';
  try {
    const result = await reindex(force);
    return NextResponse.json({ ...result, stats: await getStats() });
  } catch (error) {
    console.error('[knowledge] reindex failed', error);
    return NextResponse.json({ error: 'Reindex failed.' }, { status: 500 });
  }
}
