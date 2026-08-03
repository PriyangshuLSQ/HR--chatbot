# Superseded Node backend

These are the Next.js route handlers and server-only modules that the Spring Boot
service in `backend/` replaced. They are kept because this project is not under
version control, so deleting them would make the migration one-way.

Nothing imports them. They are excluded from `tsconfig.json` and sit outside
`app/`, so Next.js does not build them and does not route to them.

| Was | Is now |
|---|---|
| `api/knowledge/route.ts` | `web/KnowledgeController.java` — list, add, delete |
| `api/knowledge/upload/route.ts` | `web/KnowledgeController.java#upload` |
| `api/knowledge/reindex/route.ts` | `web/KnowledgeController.java#reindex` |
| `api/ai/ask/route.ts` | `web/AiController.java#ask` |
| `api/ai/status/route.ts` | `web/AiController.java#status` |
| `lib/knowledge/store.ts` | `store/QdrantStore.java` + `store/KnowledgeStore.java` |
| `lib/knowledge/rag.ts` | `rag/RagService.java` |
| `lib/knowledge/retrieve.ts` | `retrieve/Retriever.java` |
| `lib/knowledge/ollama.ts` | `ollama/OllamaClient.java` |
| `lib/knowledge/parse.ts` | `parse/DocumentParser.java`, `DocxParser`, `CsvParser` |
| `lib/knowledge/chunk.ts` | `text/Chunker.java` + `text/TextPipeline.java` |

`lib/knowledge/api.ts` and `lib/knowledge/types.ts` were **not** moved — the
browser still imports both, and neither ever touched the filesystem.

The one behavioural difference: `store.ts` kept documents, passages and vectors
in a single `data/knowledge.json`, scanned linearly on every query. Qdrant now
holds them, `data/knowledge.json` is only read once at first boot by
`LegacyStoreMigration`, and it is left untouched as the rollback path.

## Rolling back

1. Move `api/` back to `app/api` and the six modules back to `lib/knowledge/`.
2. Drop the `rewrites()` block from `next.config.mjs`.
3. Remove `"backend"` from the `exclude` list in `tsconfig.json`.

`data/knowledge.json` still holds the corpus as it stood at migration time.
