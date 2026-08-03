/**
 * POST /api/knowledge/upload — multipart upload of .txt / .md / .csv / .docx.
 *
 * Files are processed independently and the response reports per-file outcomes,
 * so one bad document in a batch of ten does not throw away the other nine.
 */

import { NextResponse } from 'next/server';
import { UnsupportedFileError } from '@/lib/knowledge/parse';
import { ingestFile } from '@/lib/knowledge/rag';
import { getStats } from '@/lib/knowledge/store';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

/** Per-file cap. A 10 MB .docx is already thousands of chunks to embed. */
const MAX_BYTES = 10 * 1024 * 1024;
const MAX_FILES = 20;

/**
 * Not exported: Next.js validates the export surface of route modules, and the
 * client-facing copy of this shape lives in `lib/knowledge/api.ts`.
 */
interface UploadOutcome {
  filename: string;
  ok: boolean;
  error?: string;
  chunkCount?: number;
  embedded?: boolean;
  notes?: string[];
  docId?: string;
}

export async function POST(request: Request) {
  let form: FormData;
  try {
    form = await request.formData();
  } catch {
    return NextResponse.json(
      { error: 'Expected a multipart form upload.' },
      { status: 400 }
    );
  }

  const files = form.getAll('files').filter((f): f is File => f instanceof File);
  if (!files.length) {
    return NextResponse.json({ error: 'No files were attached.' }, { status: 400 });
  }
  if (files.length > MAX_FILES) {
    return NextResponse.json(
      { error: `Too many files at once — upload ${MAX_FILES} or fewer.` },
      { status: 400 }
    );
  }

  const category = (form.get('category') as string | null)?.trim() || 'General';
  const uploadedBy = (form.get('uploadedBy') as string | null)?.trim() || 'HR admin';

  const results: UploadOutcome[] = [];

  // Sequential, not parallel: embedding is CPU-bound on a local Ollama, and
  // firing ten batches at once makes every one of them slower.
  for (const file of files) {
    if (file.size > MAX_BYTES) {
      results.push({
        filename: file.name,
        ok: false,
        error: `Too large (${formatBytes(file.size)}). The limit is ${formatBytes(MAX_BYTES)}.`,
      });
      continue;
    }
    if (file.size === 0) {
      results.push({ filename: file.name, ok: false, error: 'The file is empty.' });
      continue;
    }

    try {
      const buffer = Buffer.from(await file.arrayBuffer());
      const result = await ingestFile({
        buffer,
        filename: file.name,
        mimeType: file.type || undefined,
        category,
        uploadedBy,
      });
      results.push({
        filename: file.name,
        ok: true,
        docId: result.doc.id,
        chunkCount: result.chunkCount,
        embedded: result.embedded,
        notes: result.notes,
      });
    } catch (error) {
      results.push({
        filename: file.name,
        ok: false,
        error:
          error instanceof UnsupportedFileError
            ? error.message
            : 'Could not read that file. It may be corrupt.',
      });
      if (!(error instanceof UnsupportedFileError)) {
        console.error(`[knowledge] upload failed for ${file.name}`, error);
      }
    }
  }

  return NextResponse.json({ results, stats: await getStats() });
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}
