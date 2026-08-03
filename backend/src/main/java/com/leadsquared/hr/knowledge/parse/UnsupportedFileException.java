package com.leadsquared.hr.knowledge.parse;

/**
 * A file we cannot index, with a message written for HR rather than for a log.
 *
 * <p>Surfaced verbatim to the uploader, so every message should say what to do
 * next — "open it in Word and Save As .docx", not "parse error".
 */
public class UnsupportedFileException extends RuntimeException {

  public UnsupportedFileException(String message) {
    super(message);
  }
}
