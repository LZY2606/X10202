package org.petitparser.parser.repeating;

import org.petitparser.context.Context;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;
import org.petitparser.parser.mode.ParseMode;
import org.petitparser.parser.mode.PositionMode;
import org.petitparser.parser.mode.ResultMode;

import java.util.ArrayList;

/**
 * A greedy repeating parser, commonly seen in regular expression
 * implementations. It aggressively consumes as much input as possible and then
 * backtracks to meet the 'limit' condition.
 */
public class GreedyRepeatingParser extends LimitedRepeatingParser {

  public GreedyRepeatingParser(
      Parser delegate, Parser limit, int min, int max) {
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
   * Shared transition: satisfy the minimum, consume as much as possible up
   * to {@code max}, then backtrack in reverse order until the limit matches;
   * the limit is never consumed. A minimum failure propagates; if the limit
   * cannot match anywhere, its failure at the earliest probed position is
   * reported.
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
    List<Integer> marks = new ArrayList<>();
    marks.add(mode.position());
    while (max == UNBOUNDED || count < max) {
      if (!mode.accept(delegate)) {
        break;
      }
      mode.push();
      marks.add(mode.position());
      count++;
    }
    while (true) {
      int mark = marks.get(marks.size() - 1);
      mode.reset(mark);
      if (mode.accept(limit)) {
        mode.reset(mark);
        mode.succeedList();
        return;
      }
      if (count == 0) {
        return;
      }
      marks.remove(marks.size() - 1);
      mode.pop();
      count--;
      if (marks.isEmpty()) {
        return;
      }
    }
  }

  @Override
  public GreedyRepeatingParser copy() {
    return new GreedyRepeatingParser(delegate, limit, min, max);
  }
}
