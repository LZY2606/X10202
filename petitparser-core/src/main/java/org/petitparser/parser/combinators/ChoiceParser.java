package org.petitparser.parser.combinators;

import org.petitparser.context.Context;
import org.petitparser.context.Failure;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;
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
    Result[] sink = new Result[1];
    Failure[] failure = new Failure[1];
    int position = transitionChoice(
        context.getBuffer(), context.getPosition(), sink, failure);
    return position < 0 ? failure[0] : sink[0];
  }

  @Override
  public int fastParseOn(String buffer, int position) {
    return transitionChoice(buffer, position, null, null);
  }

  /**
   * Shared transition semantics of the choice: tries each child at the same
   * position in order and returns the position of the first success, or
   * {@code -1} if all children fail. In slow mode ({@code sink} and {@code
   * failure} not {@code null}) leaves the succeeding {@link Result} in
   * {@code sink[0]}, or joins the collected failures into
   * {@code failure[0]} using the {@link #failureJoiner}.
   */
  private int transitionChoice(
      String buffer, int position, Result[] sink, Failure[] failure) {
    for (Parser parser : parsers) {
      int result = transition(parser, buffer, position, sink);
      if (result >= 0) {
        return result;
      }
      if (failure != null) {
        failure[0] = failure[0] == null ? (Failure) sink[0] :
            failureJoiner.apply(failure[0], (Failure) sink[0]);
      }
    }
    return -1;
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
