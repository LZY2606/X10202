package org.petitparser.parser.repeating;

import org.petitparser.context.Context;
import org.petitparser.context.Failure;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;

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
    List<Object> elements = new ArrayList<>();
    Result[] sink = new Result[1];
    int position = transitionGreedy(
        context.getBuffer(), context.getPosition(), sink, elements);
    return position < 0 ? sink[0] : context.success(elements, position);
  }

  @Override
  public int fastParseOn(String buffer, int position) {
    return transitionGreedy(buffer, position, null, null);
  }

  /**
   * Shared transition semantics of the greedy repeater: consumes the
   * delegate as often as possible, then backtracks one repetition at a time
   * until the {@code limit} parser succeeds. In slow mode ({@code sink} and
   * {@code elements} not {@code null}) collects the parsed values and
   * leaves the offending {@link Failure} (of the delegate during the
   * minimum phase, or of the limit while backtracking) in {@code sink[0]}.
   */
  private int transitionGreedy(
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
    List<Integer> positions = new ArrayList<>();
    positions.add(position);
    while (max == UNBOUNDED || count < max) {
      int result = transition(delegate, buffer, position, sink);
      if (result < 0) {
        break;
      }
      if (elements != null) {
        elements.add(sink[0].get());
      }
      positions.add(position = result);
      count++;
    }
    while (true) {
      int limiter = transition(
          limit, buffer, positions.get(positions.size() - 1), sink);
      if (limiter >= 0) {
        return positions.get(positions.size() - 1);
      }
      if (count == 0) {
        return -1;
      }
      positions.remove(positions.size() - 1);
      count--;
      if (elements != null) {
        elements.remove(elements.size() - 1);
      }
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
