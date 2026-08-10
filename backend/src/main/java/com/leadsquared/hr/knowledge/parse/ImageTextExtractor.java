package com.leadsquared.hr.knowledge.parse;

import java.util.List;

/**
 * Reads the text out of images embedded in an upload.
 *
 * <p>An interface rather than a direct call so the parsers stay pure functions of their
 * bytes: the tests run the real .docx and PDF paths against a stub returning fixed
 * strings, with no network and no bill. The production implementation is
 * {@code ClaudeVisionClient}.
 *
 * <p><b>Every method fails soft.</b> A scanned policy that will not OCR is a document
 * with less text in it than HR hoped for, which is disappointing; an upload that returns
 * 500 because one embedded logo could not be decoded is broken. Implementations return
 * what they managed and account for the rest in {@link Extraction}, which the parsers
 * turn into {@link ParsedFile#notes()}.
 *
 * <p>Implementations also own the limits — how many images are worth reading and how
 * small is too small — because those are cost decisions and the cost belongs to whoever
 * is making the calls. Parsers hand over every image they find.
 */
public interface ImageTextExtractor {

  /** An extractor that reads nothing — the state when no API key is configured. */
  ImageTextExtractor DISABLED =
      new ImageTextExtractor() {
        @Override
        public Extraction extractText(List<Image> images) {
          return new Extraction(List.of(), 0, 0, images.size(), 0);
        }

        @Override
        public boolean isAvailable() {
          return false;
        }
      };

  /**
   * One image pulled out of a document.
   *
   * @param mediaType an IANA type — {@code image/png}, {@code image/jpeg},
   *     {@code image/gif} or {@code image/webp}. Anything else is skipped.
   * @param label where it came from, for the log line only ({@code "page 3"},
   *     {@code "word/media/image2.png"}). Never indexed: a filename is not something an
   *     employee asked about.
   */
  record Image(byte[] bytes, String mediaType, String label) {}

  /**
   * What came back, and what did not.
   *
   * <p>The counts exist so HR can be told the difference between "this document had no
   * images" and "this document had eight and I read two of them" — the second is a
   * corpus with holes in it, and silently indexing it is how an assistant ends up
   * confidently answering from half a policy.
   *
   * @param texts the transcriptions, in document order, one per image that had text
   * @param transcribed images that yielded text ({@code texts.size()})
   * @param withoutText images read successfully that contained no legible text — logos,
   *     photographs, decorative rules. Expected, not a problem.
   * @param skipped images never read: below the size threshold, an unsupported format,
   *     past the per-document cap, or OCR being unavailable
   * @param failed images whose transcription errored — a timeout, a rate limit, a
   *     refusal. The only count that indicates something went wrong.
   */
  record Extraction(
      List<String> texts, int transcribed, int withoutText, int skipped, int failed) {

    public static final Extraction NONE = new Extraction(List.of(), 0, 0, 0, 0);

    public boolean isEmpty() {
      return texts.isEmpty();
    }
  }

  /** Transcribes what is worth transcribing, in document order. */
  Extraction extractText(List<Image> images);

  /** Whether OCR can run at all — false with no API key, or when disabled by config. */
  boolean isAvailable();
}
