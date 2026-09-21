package org.petitparser.parser.mode;

import org.petitparser.context.Token;
import org.petitparser.parser.Parser;
import org.petitparser.utils.FailureJoiner;

import java.util.function.Function;

/**
 * Internal, allocation-aware execution mode of a combinator's single shared
 * transition.
 *
 * <p>Each combinator that historically implemented both {@code parseOn} and
 * {@code fastParseOn} now implements its state transition exactly once, in
 * terms of this interface. Two concrete modes drive the very same transition:
 * <dl>
 *   <dt>{@link ResultMode}</dt>
 *   <dd>builds the full {@code Result} (value and failure data);</dd>
 *   <dt>{@link PositionMode}</dt>
 *   <dd>tracks only the position and the success flag, and performs no result,
 *   value, token or action allocations.</dd>
 * </dl>
 *
 * <p>The mode is a mutable state machine, not a tagged result object: a
 * combinator never inspects a mode tag, it only performs primitive moves
 * (accept a child, mark/reset the position, push/pop collected values). This
 * keeps the hot path free of {@code Result}/{@code Context} allocations and
 * avoids two copies of the same control flow.
 *
 * <p>This class is internal and not exported from the module.
 */
public abstract class ParseMode {

  protected final String buffer;
  protected int position;

  protected ParseMode(String buffer, int position) {
    this.buffer = buffer;
    this.position = position;
  }

  /** Returns the current position. */
  public final int position() {
    return position;
  }

  /** Restores a previously marked position (backtracking). */
  public final void reset(int position) {
    this.position = position;
  }

  /** Returns whether the current position is at the end of the buffer. */
  public final boolean atEnd() {
    return position >= buffer.length();
  }

  /**
   * Tries the {@code parser} with the full semantics of the receiver mode:
   * {@code parseOn} in result mode, {@code fastParseOn} in position mode.
   * On success the mode advances to the child position and remembers the
   * child value; on failure the position is left untouched and the failure
   * is remembered.
   */
  public abstract boolean accept(Parser parser);

  /**
   * Tries the {@code parser} with the fast semantics of the child, from both
   * driving modes. Used by combinators that deliberately run a child through
   * the allocation free path even while building a full result
   * ({@code flatten(message)} and trimming). Never records a failure.
   */
  public abstract boolean acceptFast(Parser parser);

  /** Repeatedly consumes the {@code parser} while it succeeds. */
  public final void consume(Parser parser) {
    while (acceptFast(parser)) {
      // continue consuming
    }
  }

  /** Records the value of the latest accepted child (result mode only). */
  public abstract void push();

  /** Removes the latest recorded value (result mode only). */
  public abstract void pop();

  /** Succeeds at the current position with the given value. */
  public abstract void succeedValue(Object value);

  /** Succeeds at the current position with the collected list of values. */
  public abstract void succeedList();

  /** Succeeds carrying a {@link Token} spanning {@code start}..current. */
  public abstract void succeedToken(int start);

  /** Succeeds carrying the flattened input spanning {@code start}..current. */
  public abstract void succeedFlatten(int start);

  /**
   * Applies the action {@code function} to the latest child value. Only the
   * result mode invokes the function; the position mode returns {@code null}
   * without calling it, locking the side-effect policy of the fast path.
   */
  public abstract <T, R> R apply(Function<T, R> function);

  /** Returns the failure of the latest failed {@link #accept}, if any. */
  public abstract Object failure();

  /** Fails at the current position with a new {@code message}. */
  public abstract void fail(String message);

  /** Fails with an earlier recorded failure object. */
  public abstract void failWith(Object failure);

  /**
   * Joins the latest failed child failure into this choice's accumulated
   * failure (result mode only).
   */
  public abstract void joinFailure(FailureJoiner joiner);

  /** Fails with the failure accumulated by {@link #joinFailure}. */
  public abstract void failJoined();
}
