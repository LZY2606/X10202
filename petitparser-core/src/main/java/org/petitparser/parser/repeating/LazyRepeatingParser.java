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
 */
public class LazyRepeatingParser extends LimitedRepeatingParser {

  public LazyRepeatingParser(Parser delegate, Parser limit, int min, int max) {
    super(delegate, limit, min, max);
  }

  @Override
  public Result parseOn(Context context) {
    Context[] current = {context};
    List<Object> elements = new ArrayList<>();

    // Shared mandatory prefix.
    Result failure = repeatMandatory(min, () -> {
      Result result = delegate.parseOn(current[0]);
      if (result.isSuccess()) {
        elements.add(result.get());
        current[0] = result;
      }
      return result;
    });
    if (failure != null) {
      return failure;
    }

    // Probe the limit as early as possible, otherwise consume one more.
    while (true) {
      Result limiter = limit.parseOn(current[0]);
      if (limiter.isSuccess()) {
        return current[0].success(elements);
      }
      if (!canRepeat(elements.size(), max)) {
        return limiter;
      }
      Result result = delegate.parseOn(current[0]);
      if (result.isFailure()) {
        return limiter;
      }
      elements.add(result.get());
      current[0] = result;
    }
  }

  @Override
  public int fastParseOn(String buffer, int position) {
    // Fast counterpart of the slow state machine above, threading plain locals
    // so recognition captures nothing and allocates nothing.
    int current = position;
    int count = 0;
    while (count < min) {
      int result = delegate.fastParseOn(buffer, current);
      if (result < 0) {
        return -1;
      }
      current = result;
      count++;
    }
    while (true) {
      int limiter = limit.fastParseOn(buffer, current);
      if (limiter >= 0) {
        return current;
      }
      if (!canRepeat(count, max)) {
        return -1;
      }
      int result = delegate.fastParseOn(buffer, current);
      if (result < 0) {
        return -1;
      }
      current = result;
      count++;
    }
  }

  @Override
  public LazyRepeatingParser copy() {
    return new LazyRepeatingParser(delegate, limit, min, max);
  }
}
