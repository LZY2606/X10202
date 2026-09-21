package org.petitparser.parser.combinators;

import org.petitparser.context.Context;
import org.petitparser.context.Failure;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;

/**
 * Internal, allocation-free building blocks for the parser state machines.
 *
 * <p>These primitives capture the <em>control-flow semantics</em> shared by the
 * two parser entry points ({@link Parser#parseOn(Context)} returning a full
 * {@link Result} and {@link Parser#fastParseOn(String, int)} returning the next
 * position or {@code -1}): ordered short-circuit sequencing, first-success
 * choice, zero-width lookahead and failure inversion.
 *
 * <p>Each transition is offered as a pair with identical loop structure and
 * branching &mdash; one operating on contexts/results for the slow path, one
 * operating on plain {@code int} positions for the fast path. That keeps the
 * meaning of a combinator (success versus failure, the next position and
 * rollback behavior) in a single place, while the fast path only threads
 * integers through small, inlinable methods and never allocates a wrapper
 * object.
 */
final class Transitions {

  private Transitions() {
  }

  // ------------------------------------------------------------------
  // Sequences: run every child in order, stop at the first failure.
  // ------------------------------------------------------------------

  /**
   * Slow sequential transition. {@code step} parses the child at {@code index}
   * against {@code context}; a failure short-circuits the whole sequence.
   */
  static Result sequence(int count, Context initial, SlowSequenceStep step) {
    Context current = initial;
    for (int index = 0; index < count; index++) {
      Result result = step.apply(index, current);
      if (result.isFailure()) {
        return result;
      }
      current = result;
    }
    return null;
  }

  interface SlowSequenceStep {
    Result apply(int index, Context context);
  }

  /**
   * Fast sequential transition. {@code step} advances from {@code position}
   * using the child at {@code index}; {@code -1} short-circuits. No context,
   * result or list is ever allocated.
   */
  static int sequence(int count, String buffer, int position,
      FastSequenceStep step) {
    for (int index = 0; index < count; index++) {
      position = step.apply(index, buffer, position);
      if (position < 0) {
        return -1;
      }
    }
    return position;
  }

  interface FastSequenceStep {
    int apply(int index, String buffer, int position);
  }

  // ------------------------------------------------------------------
  // Choices: try children in order, first success wins.
  // ------------------------------------------------------------------

  /**
   * Slow ordered choice. {@code attempt} produces the result for the given
   * alternative; the first success is returned, otherwise failures are folded
   * with {@code join} which decides the reported failure.
   */
  static Result choice(int count, ChoiceAttempt attempt, FailureJoin join) {
    Failure failure = null;
    for (int index = 0; index < count; index++) {
      Result result = attempt.apply(index);
      if (result.isFailure()) {
        failure = failure == null ? (Failure) result
            : join.apply(failure, (Failure) result);
      } else {
        return result;
      }
    }
    return failure;
  }

  interface ChoiceAttempt {
    Result apply(int index);
  }

  interface FailureJoin {
    Failure apply(Failure first, Failure second);
  }

  /**
   * Fast ordered choice. Only the winning position is observable, so no
   * failure folding is needed; {@code -1} is returned when every alternative
   * fails.
   */
  static int choice(String buffer, int position, int count,
      FastChoiceAttempt attempt) {
    for (int index = 0; index < count; index++) {
      int result = attempt.apply(index, buffer, position);
      if (result >= 0) {
        return result;
      }
    }
    return -1;
  }

  interface FastChoiceAttempt {
    int apply(int index, String buffer, int position);
  }

  // ------------------------------------------------------------------
  // Lookahead and optional.
  // ------------------------------------------------------------------

  /** Positive lookahead: report the delegate's value but stay put. */
  static Result and(Context context, Result result) {
    return result.isSuccess() ? context.success(result.get()) : result;
  }

  /** Positive lookahead fast path: success keeps the start position. */
  static int and(int start, int result) {
    return result < 0 ? -1 : start;
  }

  /** Negative lookahead: delegate failure becomes zero-width success. */
  static Result not(Context context, Result result, String message) {
    return result.isFailure() ? context.success(null)
        : context.failure(message);
  }

  /** Negative lookahead fast path: delegate success becomes {@code -1}. */
  static int not(int start, int result) {
    return result < 0 ? start : -1;
  }

  /** Optional: delegate failure is swallowed into a zero-width success. */
  static Result optional(Context context, Result result, Object otherwise) {
    return result.isSuccess() ? result : context.success(otherwise);
  }

  /** Optional fast path: failure keeps the start position. */
  static int optional(int start, int result) {
    return result < 0 ? start : result;
  }
}
