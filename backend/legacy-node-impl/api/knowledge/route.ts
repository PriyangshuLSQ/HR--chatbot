/**
 * Knowledge base CRUD.
 *
 *   GET    /api/knowledge          list documents + index stats
 *   POST   /api/knowledge          add a typed-in entry (the FAQ form)
 *   DELETE /api/knowledge?id=...   remove a document and its chunks
 */

import { NextResponse } from 'next/server';
import { ingestText } from '@/lib/knowledge/rag';
import { UnsupportedFileError } from '@/lib/knowledge/parse';
import { getStats, listDocs, removeDoc } from '@/lib/knowledge/store';

// node:fs and node:zlib are used downstream, so this cannot run on the edge.
export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

export async function GET() {
  const [docs, stats] = await Promise.all([listDocs(), getStats()]);
  return NextResponse.json({ docs, stats });
}

export async function POST(request: Request) {
  let body: { title?: string; text?: string; category?: string; uploadedBy?: string };
  try {
    body = await request.json();
  } catch {
    return NextResponse.json({ error: 'Expected a JSON body.' }, { status: 400 });
  }

  const title = (body.title ?? '').trim();
  const text = (body.text ?? '').trim();
  if (!title || !text) {
    return NextResponse.json(
      { error: 'Both a title and body text are required.' },
      { status: 400 }
    );
  }

  try {
    const result = await ingestText({
      title,
      // The title is embedded as a heading so the entry retrieves on its
      // question wording, not just on the answer body.
      text: `# ${title}\n\n${text}`,
      category: body.category?.trim() || 'General',
      uploadedBy: body.uploadedBy?.trim() || 'HR admin',
    });
    return NextResponse.json(result, { status: 201 });
  } catch (error) {
    if (error instanceof UnsupportedFileError) {
      return NextResponse.json({ error: error.message }, { status: 400 });
    }
    console.error('[knowledge] add failed', error);
    return NextResponse.json({ error: 'Could not save that entry.' }, { status: 500 });
  }
}

export async function DELETE(request: Request) {
  const id = new URL(request.url).searchParams.get('id');
  if (!id) return NextResponse.json({ error: 'Missing ?id.' }, { status: 400 });

  const removed = await removeDoc(id);
  if (!removed) return NextResponse.json({ error: 'No such document.' }, { status: 404 });

  return NextResponse.json({ ok: true, stats: await getStats() });
}
