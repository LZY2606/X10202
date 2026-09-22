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
 *
 * <p>The transfer has three phases shared in spirit by
 * {@link #parseOn(Context)} and {@link #fastParseOn(String, int)}:
 * <ol>
 *   <li>consume the mandatory {@code min} repetitions (failure is fatal),</li>
 *   <li>consume as many further repetitions as the {@code max} boundary
 *       permits, recording every position,</li>
 *   <li>walk the recorded positions backwards, probing {@code limit}, until it
 *       succeeds; report the limit failure if the start is reached without a
 *       match.</li>
 * </ol>
 * The fast loop moves primitive integers (its position stack is an {@code
 * int[]}, avoiding the boxing of the previous {@code List<Integer>} based
 * implementation) and allocates nothing beyond that stack.
 */
public class GreedyRepeatingParser extends LimitedRepeatingParser {

  public GreedyRepeatingParser(
      Parser delegate, Parser limit, int min, int max) {
    super(delegate, limit, min, max);
  }

  /** Shared boundary condition of the aggressive phase. */
  private boolean hasReachedMax(int count) {
    return max != UNBOUNDED && count >= max;
  }

  @Override
  public Result parseOn(Context context) {
    Context current = context;
    List<Object> elements = new ArrayList<>();
    while (elements.size() < min) {
      Result result = delegate.parseOn(current);
      if (result.isFailure()) {
        return result;
      }
      elements.add(result.get());
      current = result;
    }
    List<Context> contexts = new ArrayList<>();
    contexts.add(current);
    while (!hasReachedMax(elements.size())) {
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
    int current = position;
    while (count < min) {
      int result = delegate.fastParseOn(buffer, current);
      if (result < 0) {
        return FAST_PARSE_FAILURE;
      }
      current = result;
      count++;
    }
    IntStack positions = new IntStack();
    positions.push(current);
    while (!hasReachedMax(count)) {
      int result = delegate.fastParseOn(buffer, current);
      if (result < 0) {
        break;
      }
      positions.push(current = result);
      count++;
    }
    while (true) {
      int candidate = positions.peek();
      int limiter = limit.fastParseOn(buffer, candidate);
      if (limiter >= 0) {
        return candidate;
      }
      if (count == 0) {
        return FAST_PARSE_FAILURE;
      }
      positions.pop();
      count--;
      if (positions.size == 0) {
        return FAST_PARSE_FAILURE;
      }
    }
  }

  /** Minimal grow-only stack of primitive positions, no boxing. */
  private static final class IntStack {
    private int[] items = new int[8];
    private int size;

    void push(int value) {
      if (size == items.length) {
        int[] grown = new int[items.length * 2];
        System.arraycopy(items, 0, grown, 0, size);
        items = grown;
      }
      items[size++] = value;
    }

    int pop() {
      return items[--size];
    }

    int peek() {
      return items[size - 1];
    }
  }

  @Override
  public GreedyRepeatingParser copy() {
    return new GreedyRepeatingParser(delegate, limit, min, max);
  }
}
