package org.petitparser.parser.repeating;

import org.petitparser.context.Context;
import org.petitparser.context.Failure;
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
    List<Object> elements = new ArrayList<>();
    Result[] sink = new Result[1];
    int position = transitionLazy(
        context.getBuffer(), context.getPosition(), sink, elements);
    return position < 0 ? sink[0] : context.success(elements, position);
  }

  @Override
  public int fastParseOn(String buffer, int position) {
    return transitionLazy(buffer, position, null, null);
  }

  /**
   * Shared transition semantics of the lazy repeater: checks the {@code
   * limit} parser before each additional repetition and stops as early as
   * possible. In slow mode ({@code sink} and {@code elements} not {@code
   * null}) collects the parsed values and leaves the offending {@link
   * Failure} (of the delegate during the minimum phase, or of the limit
   * afterwards) in {@code sink[0]}.
   */
  private int transitionLazy(
      String buffer, int position, Result[] sink, List<Object> elements) {
    int count = 0;
    while (count < min) {
      int result = transition(delegate, buffer, position, sink);
      if (result < 0) {
        return -1;
      }
      if (elements != null) {
        elements.add(sink[0].get());
      }
      position = result;
      count++;
    }
    while (true) {
      int limiter = transition(limit, buffer, position, sink);
      if (limiter >= 0) {
        return position;
      } else {
        Result limitFailure = sink == null ? null : sink[0];
        if (max != UNBOUNDED && count >= max) {
          return -1;
        }
        int result = transition(delegate, buffer, position, sink);
        if (result < 0) {
          if (sink != null) {
            // The delegate failed, but the reported failure is the one of
            // the limit parser that was rejected before.
            sink[0] = limitFailure;
          }
          return -1;
        }
        if (elements != null) {
          elements.add(sink[0].get());
        }
        position = result;
        count++;
      }
    }
  }

  @Override
  public LazyRepeatingParser copy() {
    return new LazyRepeatingParser(delegate, limit, min, max);
  }
}
