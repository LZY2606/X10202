package org.petitparser.parser.repeating;

import org.petitparser.context.Context;
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
    Context[] current = {context};
    List<Object> elements = new ArrayList<>();

    // Mandatory repetitions: a failure aborts the whole parse.
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

    // Optional repetitions: a failure terminates the repetition successfully.
    while (canRepeat(elements.size(), max)) {
      Result result = delegate.parseOn(current[0]);
      if (result.isFailure()) {
        return current[0].success(elements);
      }
      elements.add(result.get());
      current[0] = result;
    }
    return current[0].success(elements);
  }

  @Override
  public int fastParseOn(String buffer, int position) {
    // Fast counterpart of the slow state machine above: mandatory prefix that
    // aborts on failure, then an optional tail that ends successfully on
    // failure. The loop is inline and threads plain locals so recognition
    // captures nothing and allocates nothing.
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
    while (canRepeat(count, max)) {
      int result = delegate.fastParseOn(buffer, current);
      if (result < 0) {
        return current;
      }
      current = result;
      count++;
    }
    return current;
  }

  @Override
  public PossessiveRepeatingParser copy() {
    return new PossessiveRepeatingParser(delegate, min, max);
  }
}
