package org.petitparser.parser.combinators;

import org.petitparser.context.Context;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;
import org.petitparser.parser.TransitionHandler;

import java.util.Arrays;

/**
 * A parser that parses a sequence of parsers.
 */
public class SequenceParser extends ListParser {

  public SequenceParser(Parser... parsers) {
    super(parsers);
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
    for (Parser parser : parsers) {
      position = handler.move(parser, buffer, position);
      if (position < 0) {
        return -1;
      }
      handler.push();
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
