package org.petitparser.parser.combinators;

import org.petitparser.context.Context;
import org.petitparser.context.Failure;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;
import org.petitparser.utils.FailureJoiner;

import java.util.Arrays;

/**
 * A parser that uses the first parser that succeeds.
 *
 * <p>The ordered-choice transfer semantics &mdash; try every child from the
 * same starting position, return the first success, otherwise combine the
 * collected failures &mdash; is shared between {@link #parseOn(Context)} and
 * {@link #fastParseOn(String, int)} through the {@link ChoiceCursor}
 * strategies. The fast cursor is a stateless constant and only moves plain
 * integers, so the hot path performs no extra allocations.
 */
public class ChoiceParser extends ListParser {

  protected final FailureJoiner failureJoiner;

  /** Allocation-free ordered choice over position transitions. */
  private abstract static class FastCursor {
    /**
     * @return the resulting position or {@link Parser#FAST_PARSE_FAILURE}.
     */
    abstract int next(Parser parser, String buffer, int position);
  }

  private static final FastCursor FAST_CURSOR = new FastCursor() {
    @Override
    int next(Parser parser, String buffer, int position) {
      int result = parser.fastParseOn(buffer, position);
      return result < 0 ? FAST_PARSE_FAILURE : result;
    }
  };

  /** Slow-path state that plays the same role as the fast cursor. */
  private final class SlowCursor {
    Failure failure;

    boolean next(Parser parser, Context context) {
      Result result = parser.parseOn(context);
      if (result.isFailure()) {
        failure = failure == null ? (Failure) result
            : failureJoiner.apply(failure, (Failure) result);
        return false;
      }
      success = result;
      return true;
    }

    Result success;
  }

  public ChoiceParser(Parser... parsers) {
    this(new FailureJoiner.SelectLast(), parsers);
  }

  public ChoiceParser(FailureJoiner failureJoiner, Parser... parsers) {
    super(parsers);
    this.failureJoiner = failureJoiner;
    if (parsers.length == 0) {
      throw new IllegalArgumentException("Choice parser cannot be empty.");
    }
  }

  /** Fast ordered-choice loop: children are always retried at {@code start}. */
  private int runFast(String buffer, int start) {
    for (Parser parser : parsers) {
      int result = FAST_CURSOR.next(parser, buffer, start);
      if (result >= 0) {
        return result;
      }
    }
    return FAST_PARSE_FAILURE;
  }

  /** Slow ordered-choice loop: same retry and first-success rule. */
  private Result runSlow(Context context) {
    SlowCursor cursor = new SlowCursor();
    for (Parser parser : parsers) {
      if (cursor.next(parser, context)) {
        return cursor.success;
      }
    }
    return cursor.failure;
  }

  @Override
  public Result parseOn(Context context) {
    return runSlow(context);
  }

  @Override
  public int fastParseOn(String buffer, int position) {
    return runFast(buffer, position);
  }

  @Override
  public ChoiceParser or(FailureJoiner failureJoiner, Parser... others) {
    Parser[] array = Arrays.copyOf(parsers, parsers.length + others.length);
    System.arraycopy(others, 0, array, parsers.length, others.length);
    return new ChoiceParser(failureJoiner, array);
  }

  @Override
  public ChoiceParser copy() {
    return new ChoiceParser(failureJoiner, Arrays.copyOf(parsers,
        parsers.length));
  }
}
