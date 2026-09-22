package org.petitparser.contract;

import org.petitparser.context.Context;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A scripted, observable sub-parser used by the {@code parseOn} /
 * {@code fastParseOn} contract tests.
 *
 * <p>Every activation on either path is recorded as an {@link Event} with the
 * parser name, the kind of path taken and the position at which the delegate
 * was activated. This lets the contract tests assert not only success/failure
 * and the resulting position of each path, but also the exact order in which a
 * combinator activates its children (choice roll-back, sequence partial
 * failure, repeat boundary probing and look-ahead zero-width attempts).
 */
public class ObservableParser extends Parser {

  /** The path on which a delegate parser got activated. */
  public enum Path { PARSE_ON, FAST_PARSE_ON }

  /** A single recorded activation of an observable parser. */
  public static final class Event {
    public final String name;
    public final Path path;
    public final int position;

    Event(String name, Path path, int position) {
      this.name = name;
      this.path = path;
      this.position = position;
    }

    @Override
    public String toString() {
      return name + (path == Path.PARSE_ON ? ".parseOn@" : ".fastParseOn@")
          + position;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof Event)) {
        return false;
      }
      Event event = (Event) other;
      return position == event.position && path == event.path
          && Objects.equals(name, event.name);
    }

    @Override
    public int hashCode() {
      return Objects.hash(name, path, position);
    }
  }

  /** Scripted answer of an observable parser at a given input position. */
  public static final class Answer {
    final int nextPosition;
    final String message;
    final Object value;

    private Answer(int nextPosition, String message, Object value) {
      this.nextPosition = nextPosition;
      this.message = message;
      this.value = value;
    }
  }

  private final String name;
  private final Answer[] answers;
  private final List<Event> events;
  private final Runnable sideEffect;
  private int sideEffectCalls;

  private ObservableParser(String name, Answer[] answers, List<Event> events,
      Runnable sideEffect) {
    this.name = name;
    this.answers = answers;
    this.events = events;
    this.sideEffect = sideEffect;
  }

  public static Answer succeed(int nextPosition) {
    if (nextPosition < 0) {
      throw new IllegalArgumentException("success must not move backwards");
    }
    return new Answer(nextPosition, null, null);
  }

  public static Answer succeed(int nextPosition, Object value) {
    return new Answer(nextPosition, null, value);
  }

  public static Answer fail(int failurePosition, String message) {
    if (failurePosition < 0) {
      throw new IllegalArgumentException("failure position must not be negative");
    }
    return new Answer(failurePosition, message, null);
  }

  public static ObservableParser named(String name, int bufferLength,
      List<Event> events) {
    return new ObservableParser(name, emptyAnswers(name, bufferLength),
        events, null);
  }

  public static ObservableParser scripted(String name, int bufferLength,
      List<Event> events, Answer... answers) {
    return new ObservableParser(name, answers.clone(), events, null);
  }

  public static ObservableParser withSideEffect(String name, int bufferLength,
      List<Event> events, Runnable sideEffect) {
    return new ObservableParser(name, emptyAnswers(name, bufferLength),
        events, sideEffect);
  }

  private static Answer[] emptyAnswers(String name, int bufferLength) {
    Answer[] answers = new Answer[bufferLength + 1];
    for (int i = 0; i < answers.length; i++) {
      answers[i] = fail(i, name + " failed");
    }
    return answers;
  }

  private Answer answerAt(int position) {
    if (position < 0 || position >= answers.length) {
      throw new IllegalStateException(
          name + " activated at unexpected position " + position);
    }
    return answers[position];
  }

  @Override
  public Result parseOn(Context context) {
    events.add(new Event(name, Path.PARSE_ON, context.getPosition()));
    Answer answer = answerAt(context.getPosition());
    if (answer.message == null) {
      if (sideEffect != null) {
        sideEffect.run();
        sideEffectCalls++;
      }
      return context.success(answer.value, answer.nextPosition);
    }
    return context.failure(answer.message, answer.nextPosition);
  }

  @Override
  public int fastParseOn(String buffer, int position) {
    events.add(new Event(name, Path.FAST_PARSE_ON, position));
    Answer answer = answerAt(position);
    if (answer.message == null) {
      if (sideEffect != null) {
        sideEffect.run();
        sideEffectCalls++;
      }
      return answer.nextPosition;
    }
    return -1;
  }

  public int getSideEffectCalls() {
    return sideEffectCalls;
  }

  @Override
  public String toString() {
    return "Observable(" + name + ")";
  }

  @Override
  public Parser copy() {
    return new ObservableParser(name, answers.clone(), events, sideEffect);
  }

  /** Convenience builder for the per-position answer table. */
  public static final class Script {
    private final String name;
    private final List<Event> events;
    private final Answer[] answers;

    public Script(String name, int bufferLength, List<Event> events) {
      this.name = name;
      this.events = events;
      this.answers = emptyAnswers(name, bufferLength);
    }

    public Script succeedAt(int position, int nextPosition) {
      answers[position] = ObservableParser.succeed(nextPosition);
      return this;
    }

    public Script succeedAt(int position, int nextPosition, Object value) {
      answers[position] = ObservableParser.succeed(nextPosition, value);
      return this;
    }

    public Script zeroWidthAt(int position) {
      return succeedAt(position, position);
    }

    public Script failAt(int position) {
      return failAt(position, name + " failed");
    }

    public Script failAt(int position, String message) {
      answers[position] = ObservableParser.fail(position, message);
      return this;
    }

    public Script failAt(int position, int failurePosition, String message) {
      answers[position] = ObservableParser.fail(failurePosition, message);
      return this;
    }

    public ObservableParser build() {
      return new ObservableParser(name, answers, events, null);
    }
  }

  /** Collects events and resets between path invocations. */
  public static final class EventLog {
    private final List<Event> events = new ArrayList<>();

    public List<Event> snapshot() {
      return new ArrayList<>(events);
    }

    public void clear() {
      events.clear();
    }

    public List<Event> events() {
      return events;
    }

    @Override
    public String toString() {
      return events.toString();
    }
  }
}
