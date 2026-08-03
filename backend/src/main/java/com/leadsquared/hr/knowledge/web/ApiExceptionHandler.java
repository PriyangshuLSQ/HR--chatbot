package com.leadsquared.hr.knowledge.web;

import com.leadsquared.hr.knowledge.parse.UnsupportedFileException;
import com.leadsquared.hr.knowledge.security.CurrentUser;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

/** Turns the failures that reach the edge into the {@code {error}} shape the client expects. */
@RestControllerAdvice
public class ApiExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  /** A file we cannot index. The message is already written for HR — pass it through. */
  @ExceptionHandler(UnsupportedFileException.class)
  public ResponseEntity<Map<String, String>> unsupportedFile(UnsupportedFileException e) {
    return ApiErrors.badRequest(e.getMessage());
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<Map<String, String>> malformedBody(HttpMessageNotReadableException e) {
    return ApiErrors.badRequest("Expected a JSON body.");
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<Map<String, String>> tooLarge(MaxUploadSizeExceededException e) {
    return ApiErrors.badRequest(
        "That upload is too large. The limit is " + KnowledgeController.formatBytes(10L * 1024 * 1024) + " per file.");
  }

  @ExceptionHandler(MultipartException.class)
  public ResponseEntity<Map<String, String>> badMultipart(MultipartException e) {
    return ApiErrors.badRequest("Expected a multipart form upload.");
  }

  /** A request that needs an identity and does not have one. */
  @ExceptionHandler(CurrentUser.NotSignedInException.class)
  public ResponseEntity<Map<String, String>> notSignedIn(CurrentUser.NotSignedInException e) {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", e.getMessage()));
  }

  /**
   * A rejected write from the ticket or feedback services. Those messages are
   * written for a human ("Rating must be up or down"), so they pass through.
   */
  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, String>> rejected(IllegalArgumentException e) {
    return ApiErrors.badRequest(e.getMessage());
  }

  /**
   * MongoDB is unreachable — wrong connection string, or this machine's IP is not
   * on the Atlas access list. 503 rather than 500: the knowledge base is still
   * answering questions, it is only the ticket and feedback history that is down,
   * and the distinction tells whoever is on call where to look.
   */
  @ExceptionHandler(DataAccessResourceFailureException.class)
  public ResponseEntity<Map<String, String>> mongoDown(DataAccessResourceFailureException e) {
    log.error("MongoDB unreachable", e);
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(Map.of("error", "The ticket store is unreachable. Check the MongoDB connection."));
  }

  /** Two tickets cannot share a number, and one message cannot hold two ratings. */
  @ExceptionHandler(DuplicateKeyException.class)
  public ResponseEntity<Map<String, String>> duplicate(DuplicateKeyException e) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(Map.of("error", "That record already exists."));
  }

  @ExceptionHandler(RuntimeException.class)
  public ResponseEntity<Map<String, String>> unexpected(RuntimeException e) {
    log.error("Unhandled failure", e);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(Map.of("error", "Something went wrong on the server."));
  }
}
