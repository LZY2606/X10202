package org.petitparser.parser.repeating;

import org.petitparser.context.Context;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;
import org.petitparser.parser.mode.ParseMode;
import org.petitparser.parser.mode.PositionMode;
import org.petitparser.parser.mode.ResultMode;

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
   * Shared transition: at least {@code min} successes are mandatory; up to
   * {@code max} further successes are consumed greedily without looking
   * ahead, and a failure after the minimum ends the repetition successfully.
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
    while (max == UNBOUNDED || count < max) {
      if (!mode.accept(delegate)) {
        break;
      }
      mode.push();
      count++;
    }
    mode.succeedList();
  }

  @Override
  public PossessiveRepeatingParser copy() {
    return new PossessiveRepeatingParser(delegate, min, max);
  }
}
