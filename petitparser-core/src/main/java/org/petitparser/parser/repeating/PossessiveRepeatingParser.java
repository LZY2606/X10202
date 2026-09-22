package org.petitparser.parser.repeating;

import org.petitparser.context.Context;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;

import java.util.ArrayList;
import java.util.List;

/**
 * A greedy parser that repeatedly parses between 'min' and 'max' instances of
 * its delegate.
 *
 * <p>The transfer (a mandatory {@code min} phase followed by an optional
 * {@code max}-bounded phase that merely reports its boundary failure) is
 * shared between {@link #parseOn(Context)} and
 * {@link #fastParseOn(String, int)}. The phases and the
 * {@link #hasReachedMax(int)} boundary are defined once; the fast cursor moves
 * plain integers and allocates nothing, the slow cursor additionally collects
 * the parsed values.
 */
public class PossessiveRepeatingParser extends RepeatingParser {

  /** Allocation-free single repetition transition. */
  private abstract static class FastCursor {
    abstract int next(String buffer, int position);
  }

  private final FastCursor fastCursor = new FastCursor() {
    @Override
    int next(String buffer, int position) {
      int result = delegate.fastParseOn(buffer, position);
      return result < 0 ? FAST_PARSE_FAILURE : result;
    }
  };

  /** Slow-path state collecting values and the boundary failure. */
  private final class SlowCursor {
    Context current;
    final List<Object> elements = new ArrayList<>();
    Result boundaryFailure;

    SlowCursor(Context start) {
      this.current = start;
    }

    boolean next() {
      Result result = delegate.parseOn(current);
      if (result.isFailure()) {
        boundaryFailure = result;
        return false;
      }
      elements.add(result.get());
      current = result;
      return true;
    }
  }

  public PossessiveRepeatingParser(Parser delegate, int min, int max) {
    super(delegate, min, max);
  }

  /** Shared boundary condition of the optional phase. */
  private boolean hasReachedMax(int count) {
    return max != UNBOUNDED && count >= max;
  }

  private int runFast(String buffer, int position) {
    int count = 0;
    while (count < min) {
      position = fastCursor.next(buffer, position);
      if (position < 0) {
        return FAST_PARSE_FAILURE;
      }
      count++;
    }
    while (!hasReachedMax(count)) {
      int result = fastCursor.next(buffer, position);
      if (result < 0) {
        return position;
      }
      position = result;
      count++;
    }
    return position;
  }

  private Result runSlow(Context context) {
    SlowCursor cursor = new SlowCursor(context);
    while (cursor.elements.size() < min) {
      if (!cursor.next()) {
        return cursor.boundaryFailure;
      }
    }
    while (!hasReachedMax(cursor.elements.size())) {
      if (!cursor.next()) {
        return cursor.current.success(cursor.elements);
      }
    }
    return cursor.current.success(cursor.elements);
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
  public PossessiveRepeatingParser copy() {
    return new PossessiveRepeatingParser(delegate, min, max);
  }
}
