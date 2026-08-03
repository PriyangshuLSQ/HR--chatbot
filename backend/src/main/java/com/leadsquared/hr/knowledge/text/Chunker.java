package com.leadsquared.hr.knowledge.text;

import com.leadsquared.hr.knowledge.model.KnowledgeChunk;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits parsed documents into retrieval-sized chunks.
 *
 * <p>Two rules drive the design:
 *
 * <ol>
 *   <li><b>Headings are hard boundaries.</b> A chunk spanning "Sick Leave" into
 *       "Maternity Leave" retrieves for both and answers neither correctly.
 *   <li><b>Chunks carry their heading trail.</b> "Up to 30 days" is meaningless
 *       alone; prefixed with "Leave Policy › Carry-forward" it is retrievable
 *       and, just as importantly, citable back to the employee.
 * </ol>
 */
public final class Chunker {

  private Chunker() {}

  /** Target chunk size in characters. Roughly 200-250 tokens of English prose. */
  private static final int TARGET_CHARS = 900;
  /** A chunk is never split below this — tiny fragments retrieve badly. */
  private static final int MIN_CHARS = 220;
  /** Overlap carried into the next chunk so a sentence spanning the seam survives. */
  private static final int OVERLAP_CHARS = 160;
  /** Above this, a single heading section is split further. */
  private static final int MAX_CHARS = 1400;

  private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
  private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\\n{2,}");
  /**
   * Sentence splitter. {@code \z} rather than {@code $} deliberately: Java's
   * {@code $} also matches before a trailing newline, which would drop the final
   * character of a paragraph that ends in one.
   */
  private static final Pattern SENTENCE =
      Pattern.compile("[^.!?]+[.!?]+(?:\\s|\\z)|[^.!?]+\\z");
  /** A sentence boundary inside the overlap window, so overlap reads as prose. */
  private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("[.!?]\\s");

  private record Section(List<String> headings, String body) {}

  public static List<KnowledgeChunk> chunkDocument(String docId, String markdown) {
    List<KnowledgeChunk> chunks = new ArrayList<>();

    for (Section section : splitByHeadings(markdown)) {
      for (String body : splitLongSection(section.body())) {
        String text = body.trim();
        if (text.isEmpty()) continue;

        // The heading trail is part of the embedded and indexed text, not just
        // display metadata — that is what makes a bare "30 days" chunk findable.
        String withContext =
            section.headings().isEmpty()
                ? text
                : String.join(" › ", section.headings()) + "\n" + text;
        if (TextPipeline.tokenize(withContext).isEmpty()) continue;

        chunks.add(
            KnowledgeChunk.of(
                docId + "#" + chunks.size(),
                docId,
                chunks.size(),
                section.headings(),
                text,
                null));
      }
    }

    // A document with no headings and very little text still deserves one chunk.
    if (chunks.isEmpty() && !markdown.isBlank()) {
      chunks.add(KnowledgeChunk.of(docId + "#0", docId, 0, List.of(), markdown.trim(), null));
    }

    return chunks;
  }

  /**
   * Walks the Markdown, maintaining a stack of the active headings.
   *
   * <p>The stack is kept <i>sparse</i> — index {@code i} always means heading
   * level {@code i + 1}, with empty strings for levels a document skipped.
   * Compacting it as we go breaks that correspondence: a run of sibling
   * {@code ##} headings with no {@code #} parent (exactly what a Q&amp;A CSV
   * produces, one heading per row) would see each heading treated as the parent
   * of the next, and the trail would grow "Question 1 › Question 2 › Question 3".
   * Since the trail is prepended to the text before embedding, that does not
   * just look wrong — it poisons every chunk's vector with unrelated questions.
   * Blanks are dropped at emit time.
   */
  private static List<Section> splitByHeadings(String markdown) {
    List<Section> sections = new ArrayList<>();
    List<String> stack = new ArrayList<>();
    List<String> buffer = new ArrayList<>();

    for (String line : markdown.split("\n", -1)) {
      Matcher heading = HEADING.matcher(line);
      if (heading.matches()) {
        flush(sections, stack, buffer);
        int level = heading.group(1).length();
        String title = heading.group(2).trim();
        // Drop deeper levels, pad any the document skipped, then set this one —
        // so jumping ### back up to ## still yields an accurate trail.
        while (stack.size() > level - 1) stack.remove(stack.size() - 1);
        while (stack.size() < level - 1) stack.add("");
        stack.add(title);
        continue;
      }
      buffer.add(line);
    }
    flush(sections, stack, buffer);

    return sections;
  }

  private static void flush(List<Section> sections, List<String> stack, List<String> buffer) {
    String body = String.join("\n", buffer).trim();
    buffer.clear();
    if (body.isEmpty()) return;
    sections.add(new Section(stack.stream().filter(h -> !h.isEmpty()).toList(), body));
  }

  /**
   * Splits an over-long section on paragraph boundaries, with overlap.
   *
   * <p>Overlap matters more than it looks: without it, a rule stated across two
   * paragraphs ("...unless the employee has completed probation." / "In that
   * case, 60 days apply.") lands in different chunks and neither answers the
   * question on its own.
   */
  private static List<String> splitLongSection(String body) {
    if (body.length() <= MAX_CHARS) return List.of(body);

    List<String> out = new ArrayList<>();
    StringBuilder current = new StringBuilder();

    for (String paragraph : PARAGRAPH_BREAK.split(body, -1)) {
      if (paragraph.isBlank()) continue;

      // A single paragraph bigger than the max is split on sentence boundaries.
      List<String> pieces =
          paragraph.length() > MAX_CHARS ? splitOnSentences(paragraph) : List.of(paragraph);

      for (String piece : pieces) {
        if (!current.isEmpty() && current.length() + piece.length() + 2 > TARGET_CHARS) {
          String finished = current.toString();
          out.add(finished);
          current = new StringBuilder(tailOf(finished)).append(piece);
        } else {
          if (!current.isEmpty()) current.append("\n\n");
          current.append(piece);
        }
      }
    }

    if (!current.toString().isBlank()) {
      // Avoid orphaning a scrap: fold it into the previous chunk instead.
      if (!out.isEmpty() && current.length() < MIN_CHARS) {
        out.set(out.size() - 1, out.get(out.size() - 1) + "\n\n" + current);
      } else {
        out.add(current.toString());
      }
    }

    return out;
  }

  private static List<String> splitOnSentences(String paragraph) {
    List<String> sentences = new ArrayList<>();
    Matcher matcher = SENTENCE.matcher(paragraph);
    while (matcher.find()) sentences.add(matcher.group());
    if (sentences.isEmpty()) sentences.add(paragraph);

    List<String> out = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (String sentence : sentences) {
      if (current.length() + sentence.length() > TARGET_CHARS && !current.isEmpty()) {
        out.add(current.toString().trim());
        current = new StringBuilder();
      }
      current.append(sentence);
    }
    if (!current.toString().isBlank()) out.add(current.toString().trim());
    return out;
  }

  /** The trailing sentences of a chunk, carried into the next one as overlap. */
  private static String tailOf(String text) {
    if (text.length() <= OVERLAP_CHARS) return text + "\n\n";

    String tail = text.substring(text.length() - OVERLAP_CHARS);
    Matcher boundary = SENTENCE_BOUNDARY.matcher(tail);
    String clean = boundary.find() ? tail.substring(boundary.start() + 2) : tail;
    return clean.isBlank() ? "" : clean.trim() + "\n\n";
  }
}
