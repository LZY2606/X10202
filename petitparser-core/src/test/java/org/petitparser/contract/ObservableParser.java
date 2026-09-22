package org.petitparser.contract;

import org.petitparser.context.Context;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;

import java.util.Objects;

/**
 * An instrumented parser used by the {@code parseOn} / {@code fastParseOn}
 * contract tests.
 *
 * <p>The parser reports <em>every</em> invocation (regardless of which entry
 * point is used) into {@link Trace#events} with the entry position and the
 * resulting transition, so that the tests can pin down the exact order in
 * which a combinator invokes its children, which positions it probes during
 * backtracking, and where each transition ends.
 *
 * <p>Behaviour is purely positional: the {@code move} function maps the entry
 * position to a (possibly zero-width) advance or a failure. A negative return
 * value of {@code -p - 1} encodes a failure at position {@code p}; any
 * non-negative return value is the next position after a success.
 */
public final class ObservableParser extends Parser {

  /** Transition of a single invocation, independent of the entry point. */
  static final class Move {

    /** Successful (possibly zero-width) move to the given position. */
    static Move success(int next) {
      if (next < 0) {
        throw new IllegalArgumentException("Position must not be negative");
      }
      return new Move(next);
    }

    /** Failure reported at the given position, without consuming. */
    static Move failure(int at) {
      if (at < 0) {
        throw new IllegalArgumentException("Position must not be negative");
      }
      return new Move(-at - 1);
    }

    final int code;

    private Move(int code) {
      this.code = code;
    }

    boolean isSuccess() {
      return code >= 0;
    }
  }

  /** Positional behaviour: entry position to a {@link Move}. */
  @FunctionalInterface
  interface Behaviour {
    Move at(int position);
  }

  private final String name;
  private final Trace trace;
  private final Behaviour behaviour;
  private final Object value;

  ObservableParser(String name, Trace trace, Behaviour behaviour, Object value) {
    this.name = Objects.requireNonNull(name);
    this.trace = Objects.requireNonNull(trace);
    this.behaviour = Objects.requireNonNull(behaviour);
    this.value = value;
  }

  /** Records the single shared decision used by both entry points. */
  private Move decide(String buffer, int position) {
    Move move = behaviour.at(position);
    trace.events.add(name + "@" + position
        + (move.isSuccess() ? "->ok:" + move.code
            : "->fail:" + (-move.code - 1)));
    return move;
  }

  @Override
  public Result parseOn(Context context) {
    Move move = decide(context.getBuffer(), context.getPosition());
    if (move.isSuccess()) {
      return context.success(value, move.code);
    }
    return context.failure(name + " failure", -move.code - 1);
  }

  @Override
  public int fastParseOn(String buffer, int position) {
    Move move = decide(buffer, position);
    return move.isSuccess() ? move.code : -1;
  }

  @Override
  public Parser copy() {
    return new ObservableParser(name, trace, behaviour, value);
  }

  @Override
  public String toString() {
    return name;
  }
}
