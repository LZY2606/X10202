package org.petitparser.parser.combinators;

import org.petitparser.context.Context;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A parser that parses a sequence of parsers.
 *
 * <p>The sequential transfer semantics &mdash; activate every child at the
 * position the previous child stopped at and abort on the first failure
 * &mdash; lives in a single place: {@link #run}. The slow path contributes a
 * value-collecting cursor, the fast path contributes the stateless
 * {@link FastCursor} singleton. Position is handed through the cursor on the
 * fast path using primitive integers, so no extra objects are allocated.
 */
public class SequenceParser extends ListParser {

  /**
   * Strategy that performs one transfer step over a single child parser.
   *
   * <p>This is a deliberately restricted template method (a private stateless
   * strategy for the fast path and a per-parse value cursor for the slow one),
   * not a tagged result object: the fast path keeps moving plain integers.
   */
  private abstract static class FastCursor {
    /**
     * Transfers over {@code parser}, starting at {@code position}.
     *
     * @return the resulting position, or {@link Parser#FAST_PARSE_FAILURE} on
     *         failure.
     */
    abstract int next(Parser parser, String buffer, int position);
  }

  /** Shared, allocation-free fast transition. */
  private static final FastCursor FAST_CURSOR = new FastCursor() {
    @Override
    int next(Parser parser, String buffer, int position) {
      int result = parser.fastParseOn(buffer, position);
      return result < 0 ? FAST_PARSE_FAILURE : result;
    }
  };

  /**
   * Slow-path transfer state; allocated once per {@code parseOn} and plays the
   * same role the fast cursor plays with plain integers.
   */
  private static final class SlowCursor {
    Context current;
    final List<Object> elements;
    Result failure;

    SlowCursor(Context start, int size) {
      this.current = start;
      this.elements = new ArrayList<>(size);
    }

    boolean next(Parser parser) {
      Result result = parser.parseOn(current);
      if (result.isFailure()) {
        failure = result;
        return false;
      }
      elements.add(result.get());
      current = result;
      return true;
    }
  }

  public SequenceParser(Parser... parsers) {
    super(parsers);
  }

  /** Fast transfer loop, shared semantics, primitive position only. */
  private int run(FastCursor cursor, String buffer, int position) {
    for (Parser parser : parsers) {
      position = cursor.next(parser, buffer, position);
      if (position < 0) {
        return FAST_PARSE_FAILURE;
      }
    }
    return position;
  }

  /** Slow transfer loop: same ordering and same abort-on-first-failure rule. */
  private SlowCursor run(SlowCursor cursor) {
    for (Parser parser : parsers) {
      if (!cursor.next(parser)) {
        return cursor;
      }
    }
    return cursor;
  }

  @Override
  public Result parseOn(Context context) {
    SlowCursor cursor = run(new SlowCursor(context, parsers.length));
    return cursor.failure != null
        ? cursor.failure
        : cursor.current.success(cursor.elements);
  }

  @Override
  public int fastParseOn(String buffer, int position) {
    return run(FAST_CURSOR, buffer, position);
  }

  @Override
  public SequenceParser seq(Parser... others) {
    Parser[] array = Arrays.copyOf(parsers, parsers.length + others.length);
    System.arraycopy(others, 0, array, parsers.length, others.length);
    return new SequenceParser(array);
  }

  @Override
  public SequenceParser copy() {
    return new SequenceParser(Arrays.copyOf(parsers, parsers.length));
  }
}
