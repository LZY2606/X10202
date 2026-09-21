package org.petitparser.parser.repeating;

import org.petitparser.context.Context;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;
import org.petitparser.parser.TransitionHandler;

/**
 * A greedy parser that repeatedly parses between 'min' and 'max' instances of
 * its delegate.
 */
public class PossessiveRepeatingParser extends RepeatingParser {

  public PossessiveRepeatingParser(Parser delegate, int min, int max) {
    super(delegate, min, max);
  }

  @Override
  public Result parseOn(Context context) {
    TransitionHandler.Collecting handler = new TransitionHandler.Collecting();
    int position = transition(context.getBuffer(), context.getPosition(),
        handler);
    return position < 0 ? handler.last :
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
    while (max == UNBOUNDED || count < max) {
      int result = handler.move(delegate, buffer, current);
      if (result < 0) {
        return current;
      }
      handler.push();
      current = result;
      count++;
    }
    return current;
  }

  @Override
  public PossessiveRepeatingParser copy() {
    return new PossessiveRepeatingParser(delegate, min, max);
  }
}
