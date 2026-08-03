package com.leadsquared.hr.knowledge.model;

import java.util.List;

/**
 * The passage an answer was grounded in, rendered under every answer.
 *
 * <p>An AI-written answer about someone's leave entitlement is only trustworthy
 * if they can read the policy text it came from.
 *
 * @param snippet the exact passage, whitespace-collapsed and capped at 300 chars
 * @param score blended retrieval score, rounded to 2 decimal places
 */
public record Citation(
    String docId, String title, String filename, List<String> headings, String snippet, double score) {}
