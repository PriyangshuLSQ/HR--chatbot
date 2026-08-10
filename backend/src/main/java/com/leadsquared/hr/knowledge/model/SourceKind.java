package com.leadsquared.hr.knowledge.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Where a document came from. Drives how it is parsed and how it is displayed. */
public enum SourceKind {
  TXT("txt"),
  MD("md"),
  CSV("csv"),
  DOCX("docx"),
  PDF("pdf"),
  /** Typed straight into the admin console rather than uploaded. */
  MANUAL("manual");

  private final String wire;

  SourceKind(String wire) {
    this.wire = wire;
  }

  /** The admin console switches on these exact lowercase strings. */
  @JsonValue
  public String wire() {
    return wire;
  }

  @JsonCreator
  public static SourceKind fromWire(String value) {
    if (value != null) {
      for (SourceKind kind : values()) {
        if (kind.wire.equalsIgnoreCase(value)) return kind;
      }
    }
    return TXT;
  }
}
