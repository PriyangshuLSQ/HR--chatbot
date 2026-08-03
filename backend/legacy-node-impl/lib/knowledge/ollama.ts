/**
 * Ollama client — the open-source AI backend.
 *
 * Everything here is plain `fetch` against a local Ollama daemon, which is why
 * there is no ML dependency in package.json: Ollama serves both the embedding
 * model (semantic search) and the chat model (grounded answer writing), and
 * both are open-source weights running on the user's own machine. No HR data
 * leaves the host.
 *
 * Every call is written to fail soft. If Ollama is not installed or not
 * running, callers get `{ ok: false }` and fall back to lexical retrieval plus
 * extractive answers — the product degrades, it does not break.
 */

const DEFAULT_URL = 'http://127.0.0.1:11434';

/**
 * `nomic-embed-text` is the default because it is small (274 MB), fast on CPU,
 * and outperforms the MiniLM family on retrieval. `llama3.2` is a 2 GB model
 * that comfortably handles "answer from this passage" grounding.
 */
export const DEFAULT_EMBED_MODEL = 'nomic-embed-text';
export const DEFAULT_CHAT_MODEL = 'llama3.2';

export function ollamaUrl(): string {
  return (process.env.OLLAMA_URL || DEFAULT_URL).replace(/\/+$/, '');
}

export function configuredEmbedModel(): string {
  return process.env.OLLAMA_EMBED_MODEL || DEFAULT_EMBED_MODEL;
}

export function configuredChatModel(): string {
  return process.env.OLLAMA_CHAT_MODEL || DEFAULT_CHAT_MODEL;
}

// ---------------------------------------------------------------------------
// Transport
// ---------------------------------------------------------------------------

async function call<T>(path: string, body: unknown, timeoutMs: number): Promise<T> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const res = await fetch(`${ollamaUrl()}${path}`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(body),
      signal: controller.signal,
      cache: 'no-store',
    });
    if (!res.ok) {
      throw new Error(`Ollama ${path} returned ${res.status}: ${(await res.text()).slice(0, 200)}`);
    }
    return (await res.json()) as T;
  } finally {
    clearTimeout(timer);
  }
}

// ---------------------------------------------------------------------------
// Status
// ---------------------------------------------------------------------------

export interface OllamaStatus {
  ok: boolean;
  url: string;
  /** Models actually pulled on this machine. */
  models: string[];
  /** The embedding model we will use, or null if it is not installed. */
  embedModel: string | null;
  /** The chat model we will use, or null if none is installed. */
  chatModel: string | null;
  /** Human-readable next step when something is missing. */
  hint?: string;
}

/** Model names that only produce embeddings and must never be used for chat. */
const EMBED_ONLY = /(^|[/:-])(embed|bge|gte|e5|minilm|nomic-embed|mxbai-embed|snowflake-arctic-embed)/i;

/**
 * Cached because `ask()` calls this on every employee message, and a probe
 * against a machine with no Ollama installed costs the full connection
 * timeout. Without the cache, every chat turn would stall for seconds on
 * exactly the setup where nothing is going to answer anyway.
 *
 * The negative result is cached for less time so that installing Ollama shows
 * up quickly, and the admin panel passes `fresh` to bypass it entirely.
 */
let statusCache: { status: OllamaStatus; expiresAt: number } | null = null;
const OK_TTL_MS = 30_000;
const FAIL_TTL_MS = 8_000;

export async function getStatus(
  opts: { fresh?: boolean; timeoutMs?: number } = {}
): Promise<OllamaStatus> {
  if (!opts.fresh && statusCache && Date.now() < statusCache.expiresAt) {
    return statusCache.status;
  }

  const status = await probe(opts.timeoutMs ?? 2500);
  statusCache = {
    status,
    expiresAt: Date.now() + (status.ok ? OK_TTL_MS : FAIL_TTL_MS),
  };
  return status;
}

async function probe(timeoutMs: number): Promise<OllamaStatus> {
  const url = ollamaUrl();
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);

  try {
    const res = await fetch(`${url}/api/tags`, { signal: controller.signal, cache: 'no-store' });
    if (!res.ok) throw new Error(String(res.status));
    const data = (await res.json()) as { models?: { name?: string; model?: string }[] };
    const models = (data.models ?? [])
      .map((m) => m.name || m.model || '')
      .filter(Boolean);

    return {
      ok: true,
      url,
      models,
      embedModel: resolveModel(models, configuredEmbedModel(), (n) => EMBED_ONLY.test(n)),
      chatModel: resolveModel(models, configuredChatModel(), (n) => !EMBED_ONLY.test(n)),
      hint: hintFor(models),
    };
  } catch {
    return {
      ok: false,
      url,
      models: [],
      embedModel: null,
      chatModel: null,
      hint:
        `Ollama is not reachable at ${url}. Install it from ollama.com, then run ` +
        `\`ollama pull ${configuredEmbedModel()}\` and \`ollama pull ${configuredChatModel()}\`.`,
    };
  } finally {
    clearTimeout(timer);
  }
}

/**
 * Resolves a configured model name against what is actually installed.
 *
 * Ollama reports tagged names (`llama3.2:latest`), so an exact match on the
 * untagged name the user configured usually fails — match on the prefix, then
 * fall back to any installed model of the right class so the feature works
 * without the user having to pull our exact defaults.
 */
function resolveModel(
  models: string[],
  wanted: string,
  isRightClass: (name: string) => boolean
): string | null {
  const base = wanted.split(':')[0].toLowerCase();
  const exact = models.find((m) => m.toLowerCase() === wanted.toLowerCase());
  if (exact) return exact;
  const tagged = models.find((m) => m.split(':')[0].toLowerCase() === base);
  if (tagged) return tagged;
  return models.find(isRightClass) ?? null;
}

function hintFor(models: string[]): string | undefined {
  const missing: string[] = [];
  if (!resolveModel(models, configuredEmbedModel(), (n) => EMBED_ONLY.test(n))) {
    missing.push(configuredEmbedModel());
  }
  if (!resolveModel(models, configuredChatModel(), (n) => !EMBED_ONLY.test(n))) {
    missing.push(configuredChatModel());
  }
  if (!missing.length) return undefined;
  return `Ollama is running, but these models are not pulled yet: ${missing
    .map((m) => `\`ollama pull ${m}\``)
    .join(' and ')}.`;
}

// ---------------------------------------------------------------------------
// Embeddings
// ---------------------------------------------------------------------------

interface EmbedBatchResponse {
  embeddings?: number[][];
}
interface EmbedSingleResponse {
  embedding?: number[];
}

/**
 * Embeds a batch of texts. Returns null on any failure, so ingest can store the
 * chunks lexical-only and re-embed later rather than rejecting the upload.
 *
 * Tries the modern batch endpoint first and falls back to the legacy per-item
 * one, because `/api/embed` only exists on Ollama 0.3.4+.
 */
export async function embedBatch(
  texts: string[],
  model: string,
  timeoutMs = 120_000
): Promise<number[][] | null> {
  if (!texts.length) return [];

  try {
    const res = await call<EmbedBatchResponse>(
      '/api/embed',
      { model, input: texts },
      timeoutMs
    );
    if (res.embeddings?.length === texts.length) return res.embeddings;
  } catch {
    // Fall through to the legacy endpoint.
  }

  try {
    const out: number[][] = [];
    for (const text of texts) {
      const res = await call<EmbedSingleResponse>(
        '/api/embeddings',
        { model, prompt: text },
        timeoutMs
      );
      if (!res.embedding?.length) return null;
      out.push(res.embedding);
    }
    return out;
  } catch {
    return null;
  }
}

// ---------------------------------------------------------------------------
// Chat
// ---------------------------------------------------------------------------

export interface ChatMessage {
  role: 'system' | 'user' | 'assistant';
  content: string;
}

interface ChatResponse {
  message?: { content?: string };
}

/**
 * One-shot chat completion. Temperature is pinned low: this model's job is to
 * restate retrieved HR policy faithfully, not to be creative about it.
 */
export async function chat(
  messages: ChatMessage[],
  model: string,
  timeoutMs = 90_000
): Promise<string | null> {
  try {
    const res = await call<ChatResponse>(
      '/api/chat',
      {
        model,
        messages,
        stream: false,
        options: { temperature: 0.1, top_p: 0.9, num_predict: 700 },
      },
      timeoutMs
    );
    const content = res.message?.content?.trim();
    return content || null;
  } catch {
    return null;
  }
}
