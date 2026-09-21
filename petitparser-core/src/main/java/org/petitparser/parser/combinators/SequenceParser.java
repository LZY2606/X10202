package org.petitparser.parser.combinators;

import org.petitparser.context.Context;
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
    // The ordered, fail-fast transition in Transitions is the single source
    // of truth for sequencing and rollback; the slow path additionally threads
    // contexts, collects values and builds the final success.
    List<Object> elements = new ArrayList<>(parsers.length);
    Context[] current = {context};
    Result failure = Transitions.sequence(parsers.length, context,
        (index, currentContext) -> {
          Result result = parsers[index].parseOn(currentContext);
          if (result.isSuccess()) {
            elements.add(result.get());
            current[0] = result;
          }
          return result;
        });
    return failure != null ? failure : current[0].success(elements);
  }

  @Override
  public int fastParseOn(String buffer, int position) {
    // Fast counterpart of the shared ordered, fail-fast sequence transition
    // in Transitions#sequence. Only the integer position is threaded; the loop
    // is inline so the recognition path captures nothing and allocates nothing.
    for (int i = 0; i < parsers.length - 1; i++) {
      position = parsers[i].fastParseOn(buffer, position);
      if (position < 0) {
        return -1;
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
