package com.leadsquared.hr.knowledge.text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalisation, stemming and tokenisation — ported from {@code lib/nlu.ts} and
 * the tokeniser in {@code lib/knowledge/chunk.ts}.
 *
 * <p>This lives in the backend because lexical retrieval needs it, but the rules
 * originate in the browser-side intent matcher and the two must agree on what a
 * term <i>is</i>. "pay slip", "payslip" and "salary slip" all collapse to one
 * token; without that, a passage titled "Salary Slip" would not retrieve for
 * "payslip" on the keyword half of the search.
 *
 * <p>Both sides of retrieval run through here — the chunk's term frequencies at
 * ingest, and the query at search time — so the only property that actually
 * matters is that it is applied consistently. It is deliberately a faithful port
 * rather than an improved one: changing the rules changes every stored term map.
 */
public final class TextPipeline {

  private TextPipeline() {}

  /** Contractions and common chat shorthand, expanded before punctuation is stripped. */
  private static final Map<String, String> CONTRACTIONS = new LinkedHashMap<>();

  static {
    CONTRACTIONS.put("whats", "what");
    CONTRACTIONS.put("what's", "what");
    CONTRACTIONS.put("hows", "how");
    CONTRACTIONS.put("how's", "how");
    CONTRACTIONS.put("im", "i");
    CONTRACTIONS.put("i'm", "i");
    CONTRACTIONS.put("ive", "i have");
    CONTRACTIONS.put("i've", "i have");
    CONTRACTIONS.put("dont", "do not");
    CONTRACTIONS.put("don't", "do not");
    CONTRACTIONS.put("didnt", "did not");
    CONTRACTIONS.put("didn't", "did not");
    CONTRACTIONS.put("cant", "can not");
    CONTRACTIONS.put("can't", "can not");
    CONTRACTIONS.put("wont", "will not");
    CONTRACTIONS.put("won't", "will not");
    CONTRACTIONS.put("isnt", "is not");
    CONTRACTIONS.put("isn't", "is not");
    CONTRACTIONS.put("hasnt", "has not");
    CONTRACTIONS.put("hasn't", "has not");
    CONTRACTIONS.put("i'd", "i would");
    CONTRACTIONS.put("ill", "i will");
    CONTRACTIONS.put("i'll", "i will");
    CONTRACTIONS.put("u", "you");
    CONTRACTIONS.put("ur", "your");
    CONTRACTIONS.put("r", "are");
    CONTRACTIONS.put("n", "and");
    CONTRACTIONS.put("pls", "please");
    CONTRACTIONS.put("plz", "please");
    CONTRACTIONS.put("info", "information");
    CONTRACTIONS.put("asap", "urgent");
    CONTRACTIONS.put("abt", "about");
    CONTRACTIONS.put("bcz", "because");
    CONTRACTIONS.put("cn", "can");
    CONTRACTIONS.put("tmrw", "tomorrow");
    CONTRACTIONS.put("tmr", "tomorrow");
    CONTRACTIONS.put("2day", "today");
    CONTRACTIONS.put("hw", "how");
    CONTRACTIONS.put("wat", "what");
    CONTRACTIONS.put("wen", "when");
    CONTRACTIONS.put("wher", "where");
    CONTRACTIONS.put("bal", "balance");
    CONTRACTIONS.put("msg", "message");
    CONTRACTIONS.put("doc", "document");
    CONTRACTIONS.put("docs", "documents");
    CONTRACTIONS.put("mgr", "manager");
  }

  /**
   * Multi-word phrases collapsed before tokenising, so "time off" survives as a
   * single concept rather than two stopword-ish tokens. Order is significant.
   */
  private record Phrase(Pattern pattern, String replacement) {}

  private static final List<Phrase> PHRASE_MAP =
      List.of(
          phrase("\\btime[\\s-]?off\\b", "leave"),
          phrase("\\bdays?[\\s-]?off\\b", "leave"),
          phrase("\\bput(ting)?\\s+in\\s+(my\\s+)?papers?\\b", "resignation"),
          phrase("\\bsign(ed)?[\\s-]?off\\b", "approve"),
          phrase("\\bfull\\s+and\\s+final\\b", "fnf"),
          phrase("\\bform\\s*16\\b", "form16"),
          phrase("\\bform\\s*sixteen\\b", "form16"),
          phrase("\\bwork[\\s-]?from[\\s-]?home\\b", "wfh"),
          phrase("\\bsalary[\\s-]?slip\\b", "payslip"),
          phrase("\\bpay[\\s-]?slip\\b", "payslip"),
          phrase("\\bpay[\\s-]?check\\b", "salary"),
          phrase("\\bhealth[\\s-]?insurance\\b", "insurance"),
          phrase("\\bmedical[\\s-]?insurance\\b", "insurance"),
          phrase("\\bnotice[\\s-]?period\\b", "noticeperiod"),
          phrase("\\bprovident[\\s-]?fund\\b", "pf"),
          phrase("\\bhow[\\s-]?many\\b", "howmany"),
          phrase("\\bhow[\\s-]?much\\b", "howmuch"),
          phrase("\\bleft[\\s-]?over\\b", "remaining"),
          phrase("\\bsick[\\s-]?(leave|days?)\\b", "sickleave"),
          phrase("\\bcasual[\\s-]?leave\\b", "casualleave"),
          phrase("\\bmaternity[\\s-]?leave\\b", "maternity"),
          phrase("\\bpaternity[\\s-]?leave\\b", "paternity"),
          phrase("\\blaptop[\\s-]?request\\b", "asset"),
          phrase("\\bexpense[\\s-]?report\\b", "expense"),
          phrase("\\bnot[\\s-]?credited\\b", "notcredited"),
          phrase(
              "\\b(salary|pay|payment)\\s+(has\\s+|is\\s+|have\\s+)?not\\s+"
                  + "(come|arrived?|credited?|received?|reflected?)\\b",
              "salary notcredited"));

  private static Phrase phrase(String regex, String replacement) {
    return new Phrase(Pattern.compile(regex), Matcher.quoteReplacement(replacement));
  }

  /** Irregular stems the suffix stripper would get wrong. */
  private static final Map<String, String> IRREGULAR_STEMS =
      Map.ofEntries(
          Map.entry("applied", "apply"),
          Map.entry("applies", "apply"),
          Map.entry("got", "get"),
          Map.entry("taken", "take"),
          Map.entry("took", "take"),
          Map.entry("paid", "pay"),
          Map.entry("gave", "give"),
          Map.entry("went", "go"),
          Map.entry("children", "child"));

  /** Words too common across HR documents to carry retrieval signal. */
  private static final Set<String> STOP =
      Set.of(
          "a", "an", "the", "is", "am", "are", "was", "were", "be", "been", "being",
          "do", "does", "did", "have", "has", "had", "i", "me", "my", "we", "our",
          "you", "your", "it", "its", "to", "of", "in", "on", "at", "for", "with",
          "and", "or", "but", "if", "then", "than", "so", "that", "this", "these",
          "those", "there", "here", "can", "could", "will", "would", "shall", "should",
          "may", "might", "must", "as", "by", "from", "not", "no", "any", "all",
          "please", "kindly", "per", "via", "such", "each", "other", "more", "most");

  private static final String[] SUFFIXES = {
    "ingly", "edly", "ing", "ies", "ied", "ers", "er", "ed", "es", "s"
  };

  private static final Pattern WHITESPACE = Pattern.compile("\\s+");
  private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9\\s]");

  // -------------------------------------------------------------------------
  // Normalisation
  // -------------------------------------------------------------------------

  /** Lowercase, expand contractions, strip punctuation and emoji, collapse phrases. */
  public static String normalize(String text) {
    return applyPhrases(normalizeBase(text));
  }

  private static String normalizeBase(String text) {
    if (text == null) return "";
    String t = text.toLowerCase().trim();
    // Smart quotes first: the contraction table is keyed on the ASCII apostrophe.
    t = t.replace('‘', '\'').replace('’', '\'').replace('“', '"').replace('”', '"');
    t = expandContractions(t);
    // Keep letters, digits and spaces only.
    return WHITESPACE.matcher(NON_ALNUM.matcher(t).replaceAll(" ")).replaceAll(" ").trim();
  }

  private static String expandContractions(String text) {
    String[] words = WHITESPACE.split(text);
    StringBuilder out = new StringBuilder(text.length());
    for (String word : words) {
      if (!out.isEmpty()) out.append(' ');
      out.append(CONTRACTIONS.getOrDefault(word, word));
    }
    return out.toString();
  }

  private static String applyPhrases(String text) {
    String t = text;
    for (Phrase phrase : PHRASE_MAP) {
      t = phrase.pattern().matcher(t).replaceAll(phrase.replacement());
    }
    return WHITESPACE.matcher(t).replaceAll(" ").trim();
  }

  // -------------------------------------------------------------------------
  // Stemming
  // -------------------------------------------------------------------------

  /** Light suffix stripping — deliberately conservative, to avoid false merges. */
  public static String stem(String word) {
    String irregular = IRREGULAR_STEMS.get(word);
    if (irregular != null) return irregular;
    if (word.length() <= 4) return word;

    for (String suffix : SUFFIXES) {
      if (word.endsWith(suffix) && word.length() - suffix.length() >= 3) {
        String base = word.substring(0, word.length() - suffix.length());
        // "applies" -> "appli" -> "apply"
        if (base.endsWith("i")) base = base.substring(0, base.length() - 1) + "y";
        // "submitted" -> "submitt" -> "submit"; keep genuine doubles (ss, ll, ee, oo).
        if (base.length() > 3) {
          char last = base.charAt(base.length() - 1);
          if (last == base.charAt(base.length() - 2) && "slaeo".indexOf(last) < 0) {
            base = base.substring(0, base.length() - 1);
          }
        }
        return base;
      }
    }
    return word;
  }

  // -------------------------------------------------------------------------
  // Tokenisation
  // -------------------------------------------------------------------------

  /**
   * The canonical terms of a piece of text.
   *
   * <p>Stopwords are dropped <i>before</i> stemming, matching the Node
   * implementation — stemming first would turn "does" into "doe" and let it
   * through.
   */
  public static List<String> tokenize(String text) {
    String normalized = normalize(text);
    if (normalized.isEmpty()) return List.of();

    List<String> tokens = new ArrayList<>();
    for (String word : normalized.split(" ")) {
      if (word.isEmpty() || word.length() <= 1 || STOP.contains(word)) continue;
      tokens.add(stem(word));
    }
    return tokens;
  }

  public static Map<String, Integer> termFrequencies(List<String> tokens) {
    Map<String, Integer> tf = new LinkedHashMap<>();
    for (String token : tokens) tf.merge(token, 1, Integer::sum);
    return tf;
  }
}
