package com.opencode.ide.board.model;

import com.opencode.ide.fleet.GlobalEventsAggregator.ObservedEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SWT-free row/badge formatting for the Fleet view's global event feed. The aggregator's {@link
 * ObservedEvent}s carry no timestamp, so the feed remembers arrival times by event id (bounded,
 * FIFO eviction — mirroring the aggregator's seen window) while the view relays its listener, and
 * formats rows as {@code HH:mm:ss · connection · type}. Events retained before the feed listened
 * stamp {@code --:--:--}. Also owns the liveness badge text ({@code n connections (m failed)}).
 * Pure formatting: no I/O, no SWT, never throws.
 */
public final class EventsFeed {

  /** How many newest arrivals are remembered (mirrors the aggregator's seen window). */
  public static final int DEFAULT_ARRIVALS = 512;

  /** Tooltips cap: properties JSON longer than this is cut with an ellipsis. */
  public static final int TOOLTIP_MAX = 400;

  private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("HH:mm:ss");
  private static final String UNKNOWN_STAMP = "--:--:--";

  private final Clock clock;

  /** Guards {@link #arrivals} — one lock keeps remember/format consistent. */
  private final Object lock = new Object();

  private final LinkedHashMap<String, Long> arrivals =
      new LinkedHashMap<>(64, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
          return size() > capacity;
        }
      };
  private final int capacity;

  /** Production feed: system clock, default arrival window. */
  public EventsFeed() {
    this(Clock.systemDefaultZone(), DEFAULT_ARRIVALS);
  }

  /** Test seam: a fixed/controllable clock. */
  public EventsFeed(Clock clock) {
    this(clock, DEFAULT_ARRIVALS);
  }

  /** Full injection (test seam): clock plus a small arrival window. */
  public EventsFeed(Clock clock, int arrivalsCapacity) {
    this.clock = clock == null ? Clock.systemDefaultZone() : clock;
    this.capacity = Math.max(1, arrivalsCapacity);
  }

  /**
   * Listener seam (any thread): remembers the event's arrival time by its id; never throws, ignores
   * null and id-less events.
   */
  public void remember(ObservedEvent event) {
    if (event == null || event.id() == null) {
      return;
    }
    synchronized (lock) {
      arrivals.put(event.id(), clock.millis());
    }
  }

  /**
   * One feed row: {@code HH:mm:ss · connection · type} (the stamp in the clock's zone);
   * arrival-unknown events stamp {@code --:--:--}, a null event formats as {@code ""}.
   */
  public String format(ObservedEvent event) {
    if (event == null) {
      return "";
    }
    Long arrived;
    synchronized (lock) {
      arrived = event.id() == null ? null : arrivals.get(event.id());
    }
    String stamp =
        arrived == null
            ? UNKNOWN_STAMP
            : STAMP.format(Instant.ofEpochMilli(arrived).atZone(clock.getZone()));
    return stamp + " · " + safe(event.connectionId()) + " · " + safe(event.type());
  }

  /**
   * The row tooltip: the event's properties JSON, truncated at {@link #TOOLTIP_MAX} with an
   * ellipsis.
   */
  public static String tooltip(ObservedEvent event) {
    if (event == null || event.properties() == null) {
      return "";
    }
    String text = String.valueOf(event.properties());
    return text.length() <= TOOLTIP_MAX ? text : text.substring(0, TOOLTIP_MAX) + "…";
  }

  /**
   * The liveness badge over the aggregator's counts: {@code n connections (m failed)} — the failed
   * suffix only when {@code failed > 0}; {@code ""} when nothing is subscribed and nothing failed.
   */
  public static String liveness(int connections, int failed) {
    if (connections <= 0 && failed <= 0) {
      return "";
    }
    String base = connections + (connections == 1 ? " connection" : " connections");
    return failed > 0 ? base + " (" + failed + " failed)" : base;
  }

  private static String safe(String value) {
    return value == null ? "" : value;
  }
}
