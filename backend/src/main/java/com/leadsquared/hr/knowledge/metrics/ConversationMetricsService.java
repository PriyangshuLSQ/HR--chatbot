package com.leadsquared.hr.knowledge.metrics;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * The numbers behind the admin overview tiles and the volume chart.
 *
 * <p><b>Why this exists.</b> The dashboard used to show a hardcoded weekday fixture — 2,585
 * conversations a week, "↑ 12.5% vs last week" as a string literal — and derived "resolved
 * without HR" by dividing the real all-time ticket count by that invented denominator. Two of
 * the four tiles were real and two were decoration, which is worse than an empty dashboard: an
 * HR lead reading 99% has no way to tell that the 99 came from a number nobody measured.
 *
 * <p><b>Why {@link MongoTemplate} and not {@code ThreadRepository}.</b> That repository
 * deliberately declares no owner-less finder, and its Javadoc says why: "reading threads without
 * an owner filter would hand one employee another's conversation." That guarantee is worth more
 * than the convenience of reusing it here, so it is left untouched and this class goes to the
 * collection directly — the same shape of exception, and for the same reason, as
 * {@code AdminEmployeeQueryService}.
 *
 * <p><b>What keeps that safe.</b> Every pipeline below ends in a {@code $group} that emits counts
 * and nothing else. No stage projects {@code messages.text}, {@code title} or {@code owner}, so no
 * conversation content or authorship can leave this class even by accident — the return type has
 * nowhere to put it. Aggregate volume is not personal data; a transcript is.
 */
@Service
public class ConversationMetricsService {

  private static final Logger log = LoggerFactory.getLogger(ConversationMetricsService.class);

  private static final String THREADS = "threads";
  private static final String TICKETS = "tickets";

  /** Employee questions only. The bot's replies are not "conversations". */
  private static final String USER_ROLE = "user";

  private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;

  private final MongoTemplate mongo;

  /**
   * Which day a 23:40 question belongs to.
   *
   * <p>Bucketing in UTC would file the late-evening half of every Indian working day under
   * tomorrow, which is visible as a chart whose Monday bar holds part of Sunday. Asia/Kolkata is
   * the default because that is where the workforce is; override for another deployment.
   */
  private final ZoneId zone;

  public ConversationMetricsService(
      MongoTemplate mongo, @Value("${knowledge.metrics.timezone:Asia/Kolkata}") String timezone) {
    this.mongo = mongo;
    this.zone = ZoneId.of(timezone);
  }

  /** One day of the volume chart. {@code label} is the weekday, for the x-axis. */
  public record DayBucket(String date, String label, long conversations, long questions) {}

  /**
   * @param conversations distinct threads carrying at least one employee question in the window
   * @param questions employee messages in the window — always >= conversations
   * @param previousConversations the same measure over the immediately preceding window
   * @param deltaPct change against that window, or null when there is nothing to compare to.
   *     Null rather than 0 on purpose: "no prior data" and "flat" are different facts, and the UI
   *     must be able to say so.
   * @param escalations tickets raised IN THIS WINDOW — not all time, which is the bug this
   *     replaces
   * @param resolvedWithoutHr share of conversations that never became a ticket, or null when
   *     there were no conversations to take a share of
   */
  public record Metrics(
      String from,
      String to,
      int days,
      String timezone,
      long conversations,
      long questions,
      long previousConversations,
      Double deltaPct,
      List<DayBucket> daily,
      long escalations,
      long openEscalations,
      Double resolvedWithoutHr) {}

  public Metrics compute(int days) {
    return compute(days, Instant.now());
  }

  /**
   * @param now injectable so the window arithmetic is testable without freezing a clock globally
   */
  public Metrics compute(int days, Instant now) {
    int span = Math.max(1, Math.min(days, 90));

    // Whole days in the local zone, ending with today. Aligning to midnight is what makes the
    // week-on-week comparison honest: an unaligned "trailing 168 hours" window compares a
    // part-day against a whole one and reports the shortfall as a trend.
    LocalDate today = LocalDate.ofInstant(now, zone);
    LocalDate firstDay = today.minusDays(span - 1L);

    long startMs = firstDay.atStartOfDay(zone).toInstant().toEpochMilli();
    long endMs = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();

    LocalDate prevFirstDay = firstDay.minusDays(span);
    long prevStartMs = prevFirstDay.atStartOfDay(zone).toInstant().toEpochMilli();

    Map<String, DayBucket> measured = dailyBuckets(startMs, endMs);

    // Every day in the range gets a bar, including the quiet ones. A chart drawn only from days
    // that have data silently rescales its own x-axis, so a week with two active days looks like
    // a busy week.
    List<DayBucket> daily = new ArrayList<>(span);
    for (LocalDate d = firstDay; !d.isAfter(today); d = d.plusDays(1)) {
      String key = d.format(DAY);
      daily.add(
          measured.getOrDefault(
              key, new DayBucket(key, d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH), 0, 0)));
    }

    Totals current = totals(startMs, endMs);
    Totals previous = totals(prevStartMs, startMs);

    // Null, not zero, when the prior window is empty — see the record's Javadoc.
    Double deltaPct =
        previous.conversations() > 0
            ? ((double) current.conversations() - previous.conversations())
                / previous.conversations()
                * 100.0
            : null;

    long escalations = ticketsRaisedBetween(startMs, endMs);
    long openEscalations = openTicketCount();

    // Both terms now come from the same window. Clamped at 0 because a backlog imported with
    // older conversations can put more tickets in a window than there are threads in it, and a
    // negative percentage on a dashboard reads as a bug rather than as thin data.
    Double resolvedWithoutHr =
        current.conversations() > 0
            ? Math.max(0.0, 1.0 - (double) escalations / current.conversations()) * 100.0
            : null;

    log.debug(
        "metrics: {} day window {} .. {} ({}) -> {} conversations, {} questions, {} escalations",
        span,
        firstDay,
        today,
        zone,
        current.conversations(),
        current.questions(),
        escalations);

    return new Metrics(
        Instant.ofEpochMilli(startMs).toString(),
        Instant.ofEpochMilli(endMs).toString(),
        span,
        zone.getId(),
        current.conversations(),
        current.questions(),
        previous.conversations(),
        deltaPct,
        daily,
        escalations,
        openEscalations,
        resolvedWithoutHr);
  }

  private record Totals(long conversations, long questions) {}

  /**
   * Window totals. Deliberately not the sum of {@link #dailyBuckets}: a thread that gets a
   * question on Monday and another on Thursday is one conversation for the week but appears in
   * two day buckets, so summing the buckets would double-count it.
   */
  private Totals totals(long startMs, long endMs) {
    // Written as literal pipeline stages rather than through the driver's builders: $unwind of a
    // nested array followed by $addToSet of the parent _id reads far more clearly as the pipeline
    // itself, and this is the part a future reader will want to check against mongosh.
    List<Document> stages =
        List.of(
            new Document("$match", windowPrefilterDoc(startMs)),
            new Document("$unwind", "$messages"),
            new Document("$match", messageWindowDoc(startMs, endMs)),
            new Document(
                "$group",
                new Document("_id", null)
                    .append("questions", new Document("$sum", 1))
                    .append("threads", new Document("$addToSet", "$_id"))),
            new Document(
                "$project",
                new Document("questions", 1)
                    .append("conversations", new Document("$size", "$threads"))));

    Document first = mongo.getCollection(THREADS).aggregate(stages).first();
    if (first == null) {
      return new Totals(0, 0);
    }
    return new Totals(asLong(first.get("conversations")), asLong(first.get("questions")));
  }

  /** Per-day counts, keyed by ISO date. Days with no activity are simply absent. */
  private Map<String, DayBucket> dailyBuckets(long startMs, long endMs) {
    List<Document> stages =
        List.of(
            new Document("$match", windowPrefilterDoc(startMs)),
            new Document("$unwind", "$messages"),
            new Document("$match", messageWindowDoc(startMs, endMs)),
            // $toDate on epoch millis, then formatted in the local zone — the bucketing decision
            // this class exists to get right.
            new Document(
                "$group",
                new Document(
                        "_id",
                        new Document(
                            "$dateToString",
                            new Document("format", "%Y-%m-%d")
                                .append("date", new Document("$toDate", "$messages.at"))
                                .append("timezone", zone.getId())))
                    .append("questions", new Document("$sum", 1))
                    .append("threads", new Document("$addToSet", "$_id"))),
            new Document(
                "$project",
                new Document("questions", 1)
                    .append("conversations", new Document("$size", "$threads"))),
            new Document("$sort", new Document("_id", 1)));

    Map<String, DayBucket> out = new HashMap<>();
    for (Document d : mongo.getCollection(THREADS).aggregate(stages)) {
      String date = d.getString("_id");
      if (date == null) {
        continue;
      }
      LocalDate parsed = LocalDate.parse(date, DAY);
      out.put(
          date,
          new DayBucket(
              date,
              parsed.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
              asLong(d.get("conversations")),
              asLong(d.get("questions"))));
    }
    return out;
  }

  /**
   * Cheap prefilter before the {@code $unwind}.
   *
   * <p>Sound because {@code updatedAt} is the timestamp of the thread's most recent message: a
   * thread holding a message at or after {@code startMs} cannot have an {@code updatedAt} before
   * it. Without this, every thread ever created is unwound on every dashboard load.
   */
  private Document windowPrefilterDoc(long startMs) {
    return new Document("updatedAt", new Document("$gte", startMs));
  }

  private Document messageWindowDoc(long startMs, long endMs) {
    return new Document("messages.role", USER_ROLE)
        .append("messages.at", new Document("$gte", startMs).append("$lt", endMs));
  }

  /**
   * Tickets raised in the window.
   *
   * <p>A string range on {@code createdAt}, which is safe because it is written as
   * {@code Instant.now().toString()} — ISO-8601, UTC, fixed-width to the second — and the field is
   * indexed. The Ticket model already documents that the dashboard sorts on it lexicographically;
   * this relies on the same property rather than introducing a second time representation.
   */
  private long ticketsRaisedBetween(long startMs, long endMs) {
    String from = Instant.ofEpochMilli(startMs).toString();
    String to = Instant.ofEpochMilli(endMs).toString();
    return mongo.count(
        new Query(Criteria.where("createdAt").gte(from).lt(to)), TICKETS);
  }

  /** Open means "not resolved" — the same rule the tickets tab uses for its badge. */
  private long openTicketCount() {
    return mongo.count(new Query(Criteria.where("status").ne("resolved")), TICKETS);
  }

  private static long asLong(Object o) {
    return o instanceof Number n ? n.longValue() : 0L;
  }
}
