package org.petitparser.parser.repeating;

import org.petitparser.context.Context;
import org.petitparser.context.Failure;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;

import java.util.ArrayList;
import java.util.List;

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
    List<Object> elements = new ArrayList<>();
    Result[] sink = new Result[1];
    int position = transitionPossessive(
        context.getBuffer(), context.getPosition(), sink, elements);
    return position < 0 ? sink[0] : context.success(elements, position);
  }

  @Override
  public int fastParseOn(String buffer, int position) {
    return transitionPossessive(buffer, position, null, null);
  }

  /**
   * Shared transition semantics of the possessive repeater: consumes the
   * delegate greedily between {@code min} and {@code max} times. Fails with
   * {@code -1} if the minimum cannot be reached, otherwise stops at the
   * first delegate failure or at {@code max} repetitions. In slow mode
   * ({@code sink} and {@code elements} not {@code null}) collects the
   * parsed values and leaves the offending {@link Failure} in
   * {@code sink[0]}.
   */
  private int transitionPossessive(
      String buffer, int position, Result[] sink, List<Object> elements) {
    int count = 0;
    while (count < min) {
      int result = transition(delegate, buffer, position, sink);
      if (result < 0) {
        return result;
      }
      if (elements != null) {
        elements.add(sink[0].get());
      }
      position = result;
      count++;
    }
    while (max == UNBOUNDED || count < max) {
      int result = transition(delegate, buffer, position, sink);
      if (result < 0) {
        return position;
      }
      if (elements != null) {
        elements.add(sink[0].get());
      }
      position = result;
      count++;
    }
    return position;
  }

  @Override
  public PossessiveRepeatingParser copy() {
    return new PossessiveRepeatingParser(delegate, min, max);
  }
}
