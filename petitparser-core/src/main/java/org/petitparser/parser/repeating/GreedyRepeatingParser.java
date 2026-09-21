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

    // Over-consume as far as possible, remembering every context reached.
    List<Context> contexts = new ArrayList<>();
    contexts.add(current[0]);
    while (canRepeat(elements.size(), max)) {
      Result result = delegate.parseOn(current[0]);
      if (result.isFailure()) {
        break;
      }
      elements.add(result.get());
      current[0] = result;
      contexts.add(result);
    }

    // Shared backtracking boundary computation: walk the recorded positions
    // backwards until the limit matches.
    while (true) {
      Context candidate = contexts.get(contexts.size() - 1);
      Result limiter = limit.parseOn(candidate);
      if (limiter.isSuccess()) {
        return candidate.success(elements);
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
    // Fast counterpart of the slow state machine above (mandatory prefix,
    // over-consume, then backtrack to the limit). Plain locals and a primitive
    // int stack keep recognition free of captured lambdas, boxing and context
    // or result allocations.
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

    IntStack positions = new IntStack();
    positions.push(current);
    while (canRepeat(count, max)) {
      int result = delegate.fastParseOn(buffer, current);
      if (result < 0) {
        break;
      }
      current = result;
      positions.push(result);
      count++;
    }

    while (true) {
      int candidate = positions.top();
      if (limit.fastParseOn(buffer, candidate) >= 0) {
        return candidate;
      }
      if (count == 0) {
        return -1;
      }
      positions.pop();
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

  /**
   * Minimal growable stack of primitive positions used by the fast-path
   * backtracking. Backed by a plain {@code int[]} to avoid the boxing and
   * per-element entry allocation of a {@code List<Integer>}.
   */
  private static final class IntStack {

    private int[] data = new int[8];
    private int size;

    void push(int value) {
      if (size == data.length) {
        int[] grown = new int[data.length * 2];
        System.arraycopy(data, 0, grown, 0, size);
        data = grown;
      }
      data[size++] = value;
    }

    int pop() {
      return data[--size];
    }

    int top() {
      return data[size - 1];
    }

    boolean isEmpty() {
      return size == 0;
    }
  }
}
