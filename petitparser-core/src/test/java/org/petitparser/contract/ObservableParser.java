package org.petitparser.contract;

import org.petitparser.context.Context;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;

import java.util.ArrayList;
import java.util.List;

/**
 * A configurable leaf parser used by the parseOn / fastParseOn contract
 * tests.
 *
 * <p>Every activation is recorded in a shared event log, so that the tests can
 * observe not just the final outcome but also <em>which</em> children were
 * invoked, at which position, in which order, and through which entry point.
 * The outcome of both entry points is driven from the same
 * {@link Outcome} definition, so that this parser by construction never drifts
 * between the two paths.
 */
public class ObservableParser extends Parser {

  /**
   * The observable outcome of activating the parser at one position.
   */
  public static final class Outcome {

    /** Position reported on success, {@code -1} for failure. */
    final int nextPosition;

    /** Value reported on success through {@code parseOn}. */
    final Object value;

    /** Failure message. */
    final String message;

    private Outcome(int nextPosition, Object value, String message) {
      this.nextPosition = nextPosition;
      this.value = value;
      this.message = message;
    }

    /** Succeeds at {@code nextPosition} with {@code value}. */
    public static Outcome success(int nextPosition, Object value) {
      return new Outcome(nextPosition, value, null);
    }

    /** Fails at {@code failurePosition} with {@code message}. */
    public static Outcome failure(int failurePosition, String message) {
      return new Outcome(-1, null, message + "@" + failurePosition);
    }

    boolean isSuccess() {
      return nextPosition >= 0;
    }

    int failurePosition() {
      return Integer.parseInt(message.substring(message.indexOf('@') + 1));
    }

    String failureMessage() {
      return message.substring(0, message.indexOf('@'));
    }
  }

  /** One recorded activation of an observable parser. */
  public static final class Event {

    final String name;
    final String path;
    final int position;

    Event(String name, String path, int position) {
      this.name = name;
      this.path = path;
      this.position = position;
    }

    @Override
    public String toString() {
      return name + "[" + path + "]@" + position;
    }
  }

  private final String name;
  private final List<Event> log;
  private final java.util.function.BiFunction<String, Integer, Outcome> behavior;

  /**
   * Creates a parser that always answers the fixed {@code outcome}, but
   * records its activations.
   */
  public ObservableParser(String name, List<Event> log, Outcome outcome) {
    this(name, log, (buffer, position) -> outcome);
  }

  /**
   * Creates a parser that computes its outcome from the buffer and position.
   */
  public ObservableParser(String name, List<Event> log,
      java.util.function.BiFunction<String, Integer, Outcome> behavior) {
    this.name = name;
    this.log = log;
    this.behavior = behavior;
  }

  /** Convenience constructor with an isolated event log. */
  public static ObservableParser of(String name, Outcome outcome) {
    return new ObservableParser(name, new ArrayList<>(), outcome);
  }

  public List<Event> getLog() {
    return log;
  }

  public String getName() {
    return name;
  }

  @Override
  public Result parseOn(Context context) {
    log.add(new Event(name, "slow", context.getPosition()));
    Outcome outcome = behavior.apply(context.getBuffer(),
        context.getPosition());
    if (outcome.isSuccess()) {
      return context.success(outcome.value, outcome.nextPosition);
    }
    return context.failure(outcome.failureMessage(),
        outcome.failurePosition());
  }

  @Override
  public int fastParseOn(String buffer, int position) {
    log.add(new Event(name, "fast", position));
    Outcome outcome = behavior.apply(buffer, position);
    return outcome.nextPosition;
  }

  @Override
  public Parser copy() {
    return this;
  }

  @Override
  public String toString() {
    return name;
  }
}
