/**
 * POST /api/ai/ask — grounded answer from HR's uploaded documents.
 *
 * Called by the chat window only after the curated NLU has declined to answer,
 * so this handles the long tail: whatever HR uploaded that the hand-written
 * intents never covered.
 *
 * A `mode: 'none'` response is a normal, expected outcome — it means the
 * knowledge base does not cover the question, and the caller should fall
 * through to its escalation path rather than surface anything.
 */

import { NextResponse } from 'next/server';
import { ask } from '@/lib/knowledge/rag';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';
/** A local model on CPU can take a while for the first token. */
export const maxDuration = 120;

interface AskBody {
  question?: string;
  history?: { role?: string; text?: string }[];
  extractiveOnly?: boolean;
}

export async function POST(request: Request) {
  let body: AskBody;
  try {
    body = await request.json();
  } catch {
    return NextResponse.json({ error: 'Expected a JSON body.' }, { status: 400 });
  }

  const question = (body.question ?? '').trim();
  if (!question) {
    return NextResponse.json({ error: 'A question is required.' }, { status: 400 });
  }
  if (question.length > 2000) {
    return NextResponse.json({ error: 'That question is too long.' }, { status: 400 });
  }

  const history = (body.history ?? [])
    .filter((m): m is { role: string; text: string } => typeof m?.text === 'string')
    .map((m) => ({
      role: m.role === 'user' ? ('user' as const) : ('bot' as const),
      text: m.text,
    }))
    .slice(-6);

  try {
    const answer = await ask({
      question,
      history,
      extractiveOnly: body.extractiveOnly === true,
    });
    return NextResponse.json(answer);
  } catch (error) {
    console.error('[ai] ask failed', error);
    // Answering is best-effort — a failure here must not break the chat, so
    // report "nothing found" and let the caller escalate to a human.
    return NextResponse.json({ mode: 'none', answer: '', citations: [], confidence: 0 });
  }
}
