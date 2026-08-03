package com.leadsquared.hr.knowledge.model;

/**
 * Index health, shown in the admin console's AI panel.
 *
 * @param pendingChunks chunks that are indexed lexically but have no vector yet —
 *     the number "Rebuild index" exists to drive to zero
 */
public record StoreStats(
    int docCount, int chunkCount, int embeddedChunks, int pendingChunks, long bytes) {}
