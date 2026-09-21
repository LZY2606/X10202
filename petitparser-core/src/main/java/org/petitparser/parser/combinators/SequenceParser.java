package org.petitparser.parser.combinators;

import org.petitparser.context.Context;
import org.petitparser.context.Failure;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A parser that parses a sequence of parsers.
 */
public class SequenceParser extends ListParser {

  public SequenceParser(Parser... parsers) {
    super(parsers);
  }

  @Override
  public Result parseOn(Context context) {
    List<Object> elements = new ArrayList<>(parsers.length);
    Result[] sink = new Result[1];
    int position = transitionSequence(
        context.getBuffer(), context.getPosition(), sink, elements);
    return position < 0 ? sink[0] : context.success(elements, position);
  }

  @Override
  public int fastParseOn(String buffer, int position) {
    return transitionSequence(buffer, position, null, null);
  }

  /**
   * Shared transition semantics of the sequence: parses each child in order,
   * aborting with {@code -1} on the first failure. In slow mode ({@code sink}
   * and {@code elements} not {@code null}) collects the parsed values and
   * leaves the offending {@link Failure} in {@code sink[0]}.
   */
  private int transitionSequence(
      String buffer, int position, Result[] sink, List<Object> elements) {
    for (Parser parser : parsers) {
      position = transition(parser, buffer, position, sink);
      if (position < 0) {
        return position;
      }
      if (elements != null) {
        elements.add(sink[0].get());
      }
    }
    return position;
  }

  @Override
  public SequenceParser seq(Parser... others) {
    Parser[] array = Arrays.copyOf(parsers, parsers.length + others.length);
    System.arraycopy(others, 0, array, parsers.length, others.length);
    return new SequenceParser(array);
  }

  @Override
  public SequenceParser copy() {
    return new SequenceParser(Arrays.copyOf(parsers, parsers.length));
  }
}
