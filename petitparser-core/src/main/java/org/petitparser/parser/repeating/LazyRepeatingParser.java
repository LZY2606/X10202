package org.petitparser.parser.repeating;

import org.petitparser.context.Context;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;

import java.util.ArrayList;
import java.util.List;

/**
 * A lazy repeating parser, commonly seen in regular expression implementations.
 * It limits its consumption to meet the 'limit' condition as early as
 * possible.
 *
 * <p>The transfer &mdash; consume the mandatory {@code min} repetitions, then
 * probe {@code limit} before every additional repetition and stop as early as
 * possible &mdash; is defined jointly for {@link #parseOn(Context)} and
 * {@link #fastParseOn(String, int)}: both loops share the {@code min} and
 * {@code max} boundary predicates and the limit-first ordering. The fast loop
 * moves plain integers and allocates nothing; the slow loop additionally
 * collects values and produces the terminal {@link Result}.
 */
public class LazyRepeatingParser extends LimitedRepeatingParser {

  public LazyRepeatingParser(Parser delegate, Parser limit, int min, int max) {
    super(delegate, limit, min, max);
  }

  /** Shared boundary condition: no further repetition is allowed. */
  private boolean hasReachedMax(int count) {
    return max != UNBOUNDED && count >= max;
  }

  @Override
  public Result parseOn(Context context) {
    Context current = context;
    List<Object> elements = new ArrayList<>();
    while (elements.size() < min) {
      Result result = delegate.parseOn(current);
      if (result.isFailure()) {
        return result;
      }
      elements.add(result.get());
      current = result;
    }
    while (true) {
      Result limiter = limit.parseOn(current);
      if (limiter.isSuccess()) {
        return current.success(elements);
      } else {
        if (hasReachedMax(elements.size())) {
          return limiter;
        }
        Result result = delegate.parseOn(current);
        if (result.isFailure()) {
          return limiter;
        }
        elements.add(result.get());
        current = result;
      }
    }
  }

  @Override
  public int fastParseOn(String buffer, int position) {
    int count = 0;
    int current = position;
    while (count < min) {
      int result = delegate.fastParseOn(buffer, current);
      if (result < 0) {
        return FAST_PARSE_FAILURE;
      }
      current = result;
      count++;
    }
    while (true) {
      int limiter = limit.fastParseOn(buffer, current);
      if (limiter >= 0) {
        return current;
      } else {
        if (hasReachedMax(count)) {
          return FAST_PARSE_FAILURE;
        }
        int result = delegate.fastParseOn(buffer, current);
        if (result < 0) {
          return FAST_PARSE_FAILURE;
        }
        current = result;
        count++;
      }
    }
  }

  @Override
  public LazyRepeatingParser copy() {
    return new LazyRepeatingParser(delegate, limit, min, max);
  }
}
