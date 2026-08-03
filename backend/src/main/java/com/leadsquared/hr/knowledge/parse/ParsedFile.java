package com.leadsquared.hr.knowledge.parse;

import com.leadsquared.hr.knowledge.model.SourceKind;
import java.util.List;

/**
 * The Markdown-flavoured text extracted from an upload.
 *
 * @param notes non-fatal things HR should know, e.g. "3 images skipped" — shown
 *     next to the upload result so nobody assumes a diagram-heavy policy was
 *     fully ingested
 */
public record ParsedFile(SourceKind kind, String text, List<String> notes) {}
