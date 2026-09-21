package org.petitparser.parser.repeating;

import org.petitparser.context.Context;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;
import org.petitparser.parser.TransitionHandler;

import java.util.ArrayList;
import java.util.List;

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
    List<Integer> positions = new ArrayList<>();
    positions.add(current);
    while (max == UNBOUNDED || count < max) {
      int result = handler.move(delegate, buffer, current);
      if (result < 0) {
        break;
      }
      handler.push();
      positions.add(current = result);
      count++;
    }
    while (true) {
      int limiter = handler.move(limit, buffer,
          positions.get(positions.size() - 1));
      if (limiter >= 0) {
        return positions.get(positions.size() - 1);
      }
      if (count == 0) {
        return -1;
      }
      positions.remove(positions.size() - 1);
      handler.pop();
      count--;
      if (positions.isEmpty()) {
        return -1;
      }
    }
  }

  @Override
  public GreedyRepeatingParser copy() {
    return new GreedyRepeatingParser(delegate, limit, min, max);
  }
}
