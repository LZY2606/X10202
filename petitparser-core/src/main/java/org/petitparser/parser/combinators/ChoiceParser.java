package org.petitparser.parser.combinators;

import org.petitparser.context.Context;
import org.petitparser.context.Failure;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;
import org.petitparser.parser.TransitionHandler;
import org.petitparser.utils.FailureJoiner;

import java.util.Arrays;

/**
 * A parser that uses the first parser that succeeds.
 */
public class ChoiceParser extends ListParser {

  protected final FailureJoiner failureJoiner;

  public ChoiceParser(Parser... parsers) {
    this(new FailureJoiner.SelectLast(), parsers);
  }

  public ChoiceParser(FailureJoiner failureJoiner, Parser... parsers) {
    super(parsers);
    this.failureJoiner = failureJoiner;
    if (parsers.length == 0) {
      throw new IllegalArgumentException("Choice parser cannot be empty.");
    }
  }

  @Override
  public Result parseOn(Context context) {
    ChoiceHandler handler = new ChoiceHandler();
    int position = transition(context.getBuffer(), context.getPosition(),
        handler);
    return position < 0 ? handler.failure : handler.last;
  }

  @Override
  public int fastParseOn(String buffer, int position) {
    return transition(buffer, position, TransitionHandler.FAST);
  }

  private int transition(String buffer, int position,
      TransitionHandler handler) {
    for (Parser parser : parsers) {
      int result = handler.move(parser, buffer, position);
      if (result >= 0) {
        return result;
      }
    }
    return -1;
  }

  private class ChoiceHandler extends TransitionHandler.Collecting {
    Failure failure;

    @Override
    public int move(Parser parser, String buffer, int position) {
      int result = super.move(parser, buffer, position);
      if (result < 0) {
        failure = failure == null ? (Failure) last :
            failureJoiner.apply(failure, (Failure) last);
      }
      return result;
    }
  }

  @Override
  public ChoiceParser or(FailureJoiner failureJoiner, Parser... others) {
    Parser[] array = Arrays.copyOf(parsers, parsers.length + others.length);
    System.arraycopy(others, 0, array, parsers.length, others.length);
    return new ChoiceParser(failureJoiner, array);
  }

  @Override
  public ChoiceParser copy() {
    return new ChoiceParser(failureJoiner, Arrays.copyOf(parsers,
        parsers.length));
  }
}
