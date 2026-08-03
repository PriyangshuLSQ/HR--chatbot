package com.leadsquared.hr.knowledge.parse;

import com.leadsquared.hr.knowledge.model.SourceKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * CSV / TSV to Markdown.
 *
 * <p>A sheet with Question and Answer columns becomes one heading-plus-body block
 * per row, so each question retrieves as a clean unit instead of being glued to
 * its neighbours. Any other sheet becomes one labelled record per row, which
 * keeps every value attached to its column name — a bare cell value is not
 * retrievable on its own.
 */
final class CsvParser {

  private CsvParser() {}

  /** Header names that mark a sheet as a question/answer pair list. */
  private static final Set<String> QUESTION_HEADERS =
      Set.of("question", "questions", "q", "query", "prompt", "issue", "topic");

  private static final Set<String> ANSWER_HEADERS =
      Set.of("answer", "answers", "a", "response", "reply", "resolution", "details");

  private static final Pattern QUOTED_SPAN = Pattern.compile("\"[^\"]*\"");
  private static final Pattern CRLF = Pattern.compile("\\r\\n?");

  static ParsedFile parse(String text, boolean isTsv) {
    List<List<String>> rows = parseDelimited(text, isTsv ? '\t' : detectDelimiter(text));
    List<String> notes = new ArrayList<>();

    if (rows.size() < 2) {
      throw new UnsupportedFileException(
          "That CSV has no data rows — it needs a header row plus at least one row of content.");
    }

    List<String> header = rows.get(0).stream().map(String::trim).toList();
    List<List<String>> body =
        rows.subList(1, rows.size()).stream()
            .filter(row -> row.stream().anyMatch(cell -> !cell.isBlank()))
            .toList();

    int skipped = rows.size() - 1 - body.size();
    if (skipped > 0) notes.add(skipped + " blank row" + (skipped > 1 ? "s" : "") + " skipped.");

    int qIdx = indexOfHeader(header, QUESTION_HEADERS);
    int aIdx = indexOfHeader(header, ANSWER_HEADERS);

    if (qIdx >= 0 && aIdx >= 0 && qIdx != aIdx) {
      notes.add("Detected a Q&A sheet — \"" + header.get(qIdx) + "\" / \"" + header.get(aIdx) + "\".");
      return new ParsedFile(SourceKind.CSV, questionAnswerBlocks(header, body, qIdx, aIdx), notes);
    }

    notes.add(
        "No question/answer columns found — each row was indexed as a labelled record. "
            + "Name your columns \"Question\" and \"Answer\" for better matching.");
    return new ParsedFile(SourceKind.CSV, labelledRecords(header, body), notes);
  }

  private static int indexOfHeader(List<String> header, Set<String> wanted) {
    for (int i = 0; i < header.size(); i++) {
      if (wanted.contains(header.get(i).toLowerCase())) return i;
    }
    return -1;
  }

  private static String questionAnswerBlocks(
      List<String> header, List<List<String>> body, int qIdx, int aIdx) {

    List<String> blocks = new ArrayList<>();
    for (List<String> row : body) {
      String question = cell(row, qIdx);
      String answer = cell(row, aIdx);
      if (question.isEmpty() || answer.isEmpty()) continue;

      // Remaining columns become an italic metadata line, so a "Category" or
      // "Owner" column still contributes retrievable text without competing
      // with the answer itself.
      List<String> extras = new ArrayList<>();
      for (int i = 0; i < header.size(); i++) {
        if (i == qIdx || i == aIdx) continue;
        String value = cell(row, i);
        if (!value.isEmpty()) extras.add("_" + header.get(i) + ": " + value + "_");
      }

      blocks.add(
          "## " + question + "\n\n" + answer + (extras.isEmpty() ? "" : "\n\n" + String.join(" · ", extras)));
    }

    if (blocks.isEmpty()) {
      throw new UnsupportedFileException(
          "Every row in that CSV was missing either a question or an answer.");
    }
    return String.join("\n\n", blocks);
  }

  private static String labelledRecords(List<String> header, List<List<String>> body) {
    List<String> lines = new ArrayList<>();
    for (int n = 0; n < body.size(); n++) {
      List<String> row = body.get(n);
      List<String> pairs = new ArrayList<>();
      for (int i = 0; i < header.size(); i++) {
        String label = header.get(i);
        String value = cell(row, i);
        if (label.isEmpty() || value.isEmpty()) continue;
        pairs.add("**" + label + ":** " + value);
      }
      lines.add("- Row " + (n + 1) + " — " + String.join(" · ", pairs));
    }
    return String.join("\n", lines);
  }

  private static String cell(List<String> row, int index) {
    return index >= 0 && index < row.size() ? row.get(index).trim() : "";
  }

  /** Picks between comma and semicolon by counting them outside quoted spans. */
  private static char detectDelimiter(String text) {
    String sample = QUOTED_SPAN.matcher(text.substring(0, Math.min(4000, text.length()))).replaceAll("");
    long commas = sample.chars().filter(c -> c == ',').count();
    long semis = sample.chars().filter(c -> c == ';').count();
    return semis > commas ? ';' : ',';
  }

  /** RFC 4180: handles quoted fields, escaped quotes and embedded newlines. */
  private static List<List<String>> parseDelimited(String text, char delimiter) {
    List<List<String>> rows = new ArrayList<>();
    List<String> row = new ArrayList<>();
    StringBuilder field = new StringBuilder();
    boolean inQuotes = false;

    String src = CRLF.matcher(text).replaceAll("\n");

    for (int i = 0; i < src.length(); i++) {
      char ch = src.charAt(i);

      if (inQuotes) {
        if (ch == '"') {
          if (i + 1 < src.length() && src.charAt(i + 1) == '"') {
            field.append('"');
            i++; // consume the escape pair
          } else {
            inQuotes = false;
          }
        } else {
          field.append(ch);
        }
        continue;
      }

      if (ch == '"') {
        inQuotes = true;
      } else if (ch == delimiter) {
        row.add(field.toString());
        field.setLength(0);
      } else if (ch == '\n') {
        row.add(field.toString());
        rows.add(row);
        row = new ArrayList<>();
        field.setLength(0);
      } else {
        field.append(ch);
      }
    }

    // Flush the trailing field unless the file ended on a clean newline.
    if (!field.isEmpty() || !row.isEmpty()) {
      row.add(field.toString());
      rows.add(row);
    }
    return rows;
  }
}
