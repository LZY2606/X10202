package org.petitparser.parser.repeating;

import org.petitparser.context.Context;
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
    Context current = context;
    List<Object> elements = new ArrayList<>();
    while (needsMandatoryRepetition(elements.size())) {
      Result result = delegate.parseOn(current);
      if (result.isFailure()) {
        return result;
      }
      elements.add(result.get());
      current = result;
    }
    List<Context> contexts = new ArrayList<>();
    contexts.add(current);
    while (allowsOptionalRepetition(elements.size())) {
      Result result = delegate.parseOn(current);
      if (result.isFailure()) {
        break;
      }
      elements.add(result.get());
      contexts.add(current = result);
    }
    while (true) {
      Result limiter = limit.parseOn(contexts.get(contexts.size() - 1));
      if (limiter.isSuccess()) {
        return contexts.get(contexts.size() - 1).success(elements);
      }
      if (elements.isEmpty()) {
        return limiter;
      }
      contexts.remove(contexts.size() - 1);
      elements.remove(elements.size() - 1);
      if (contexts.isEmpty()) {
        return limiter;
      }
    }
  }

  @Override
  public int fastParseOn(String buffer, int position) {
    int count = 0;
    while (needsMandatoryRepetition(count)) {
      int next = delegate.fastParseOn(buffer, position);
      if (next < 0) {
        return -1;
      }
      position = next;
      count++;
    }
    // Unboxed stack of candidate limit positions; unlike a List<Integer> it
    // never boxes positions. This buffer is intrinsic to backtracking and is
    // the only allocation on the fast path.
    int[] positions = new int[Math.max(8, count + 8)];
    int size = 0;
    positions[size++] = position;
    while (allowsOptionalRepetition(count)) {
      int next = delegate.fastParseOn(buffer, position);
      if (next < 0) {
        break;
      }
      position = next;
      if (size == positions.length) {
        positions = grow(positions);
      }
      positions[size++] = position;
      count++;
    }
    while (true) {
      int candidate = positions[size - 1];
      if (limit.fastParseOn(buffer, candidate) >= 0) {
        return candidate;
      }
      if (count == 0) {
        return -1;
      }
      size--;
      count--;
      if (size == 0) {
        return -1;
      }
    }
  }

  private static int[] grow(int[] positions) {
    int[] grown = new int[positions.length * 2];
    System.arraycopy(positions, 0, grown, 0, positions.length);
    return grown;
  }

  @Override
  public GreedyRepeatingParser copy() {
    return new GreedyRepeatingParser(delegate, limit, min, max);
  }
}
