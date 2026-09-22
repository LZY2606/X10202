package org.petitparser.contract;

import org.petitparser.context.Context;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Runs a combinator through both entry points and captures the shared
 * observable transition sequence.
 */
final class ContractProbe {

  private final boolean success;
  private final int finalPosition;
  private final String message;
  private final Object value;
  private final List<ObservableParser.Event> events;

  private ContractProbe(boolean success, int finalPosition, String message,
      Object value, List<ObservableParser.Event> events) {
    this.success = success;
    this.finalPosition = finalPosition;
    this.message = message;
    this.value = value;
    this.events = events;
  }

  static ContractProbe run(Parser parser, ObservableParser.EventLog log,
      String input, int start) {
    log.clear();
    Result result = parser.parseOn(new Context(input, start));
    List<ObservableParser.Event> parseEvents = log.snapshot();

    log.clear();
    int fast = parser.fastParseOn(input, start);
    List<ObservableParser.Event> fastEvents = log.snapshot();

    boolean success = result.isSuccess();
    assertEquals("path agreement on success at " + start + ": parseOn="
        + result + " fastParseOn=" + fast, success, fast >= 0);
    if (success) {
      assertEquals("final position at " + start, result.getPosition(), fast);
    } else {
      assertEquals("fastParseOn failure marker at " + start, -1, fast);
    }
    assertEquals("transition order at " + start + ": parseOn="
            + describe(parseEvents) + " fastParseOn=" + describe(fastEvents),
        describe(parseEvents), describe(fastEvents));

    return new ContractProbe(success,
        success ? result.getPosition() : result.getPosition(),
        success ? null : result.getMessage(),
        success ? result.get() : null, parseEvents);
  }

  ContractProbe assertSuccess(int position) {
    assertTrue("expected success but got failure: " + message, success);
    assertEquals("success position", position, finalPosition);
    return this;
  }

  ContractProbe assertFailure(int position, String expectedMessage) {
    assertTrue("expected failure but got success: " + value, !success);
    assertEquals("failure position", position, finalPosition);
    assertEquals("failure message", expectedMessage, message);
    return this;
  }

  Object value() {
    return value;
  }

  List<String> events() {
    return describe(events);
  }

  private static List<String> describe(List<ObservableParser.Event> events) {
    List<String> described = new ArrayList<>();
    for (ObservableParser.Event event : events) {
      described.add(event.name + "@" + event.position);
    }
    return described;
  }
}
