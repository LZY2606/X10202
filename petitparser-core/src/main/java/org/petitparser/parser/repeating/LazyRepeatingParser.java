package org.petitparser.parser.repeating;

import org.petitparser.context.Context;
import org.petitparser.context.Failure;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;
import org.petitparser.parser.TransitionHandler;

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
    LazyHandler handler = new LazyHandler();
    int position = transition(context.getBuffer(), context.getPosition(),
        handler);
    return position < 0 ? handler.failure() :
        context.success(handler.values, position);
  }

  @Override
  public int fastParseOn(String buffer, int position) {
    return transition(buffer, position, TransitionHandler.FAST);
  }

  private int transition(String buffer, int position,
      TransitionHandler handler) {
    int count = 0;
    int current = position;
    while (count < min) {
      int result = handler.move(delegate, buffer, current);
      if (result < 0) {
        return -1;
      }
      handler.push();
      current = result;
      count++;
    }
    while (true) {
      int limiter = handler.move(limit, buffer, current);
      if (limiter >= 0) {
        return current;
      } else {
        if (max != UNBOUNDED && count >= max) {
          return -1;
        }
        int result = handler.move(delegate, buffer, current);
        if (result < 0) {
          return -1;
        }
        handler.push();
        current = result;
        count++;
      }
    }
  }

  private class LazyHandler extends TransitionHandler.Collecting {
    Failure limitFailure;

    @Override
    public int move(Parser parser, String buffer, int position) {
      int result = super.move(parser, buffer, position);
      if (result < 0 && parser == limit) {
        limitFailure = (Failure) last;
      }
      return result;
    }

    /**
     * Failures of the main loop report the limit failure, failures of the
     * initial minimum loop report the delegate failure.
     */
    Result failure() {
      return limitFailure != null ? limitFailure : last;
    }
  }

  @Override
  public LazyRepeatingParser copy() {
    return new LazyRepeatingParser(delegate, limit, min, max);
  }
}
