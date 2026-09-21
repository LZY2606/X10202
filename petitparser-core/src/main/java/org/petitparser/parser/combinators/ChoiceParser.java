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
    // First-success semantics and the restart position are shared with the
    // fast path; only the slow path folds the collected failures together.
    return Transitions.choice(parsers.length,
        index -> parsers[index].parseOn(context),
        failureJoiner::apply);
  }

  @Override
  public int fastParseOn(String buffer, int position) {
    // Fast counterpart of the shared ordered-choice transition in
    // Transitions#choice. Every alternative is retried from the same start
    // position and the first non-negative result wins; the loop is kept inline
    // so the recognition path captures nothing and allocates nothing.
    for (Parser parser : parsers) {
      int result = parser.fastParseOn(buffer, position);
      if (result >= 0) {
        return result;
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
