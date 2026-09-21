package org.petitparser.parser.repeating;

import org.petitparser.context.Context;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;
import org.petitparser.parser.mode.ParseMode;
import org.petitparser.parser.mode.PositionMode;
import org.petitparser.parser.mode.ResultMode;

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
    ResultMode mode = new ResultMode(context);
    transition(mode);
    return mode.toResult();
  }

  @Override
  public int fastParseOn(String buffer, int position) {
    PositionMode mode = new PositionMode(buffer, position);
    transition(mode);
    return mode.result();
  }

  /**
   * Shared transition: satisfy the minimum, then alternate between probing
   * the limit (never consumed) at the current position and consuming one
   * delegate. If the limit fails at {@code max}, or the delegate fails after
   * the limit failed, the reported failure is the limit failure.
   */
  private void transition(ParseMode mode) {
    int count = 0;
    while (count < min) {
      if (!mode.accept(delegate)) {
        return;
      }
      mode.push();
      count++;
    }
    while (true) {
      int mark = mode.position();
      if (mode.accept(limit)) {
        mode.reset(mark);
        mode.succeedList();
        return;
      }
      Object limitFailure = mode.failure();
      if (max != UNBOUNDED && count >= max) {
        mode.failWith(limitFailure);
        return;
      }
      if (!mode.accept(delegate)) {
        mode.failWith(limitFailure);
        return;
      }
      mode.push();
      count++;
    }
  }

  @Override
  public LazyRepeatingParser copy() {
    return new LazyRepeatingParser(delegate, limit, min, max);
  }
}
