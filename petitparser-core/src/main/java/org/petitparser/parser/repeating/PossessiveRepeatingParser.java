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
    Context current = context;
    List<Object> elements = new ArrayList<>();
    while (needsMandatoryRepetition(elements.size())) {
      Result result = delegate.parseOn(current);
      if (result.isFailure()) {
        return result;
      }
      elements.add(result.get());
      current = result;
    }
    while (allowsOptionalRepetition(elements.size())) {
      Result result = delegate.parseOn(current);
      if (result.isFailure()) {
        return current.success(elements);
      }
      elements.add(result.get());
      current = result;
    }
    return current.success(elements);
  }

  @Override
  public int fastParseOn(String buffer, int position) {
    int count = 0;
    // Mandatory repetitions: a failure here fails the whole parser.
    while (needsMandatoryRepetition(count)) {
      int next = delegate.fastParseOn(buffer, position);
      if (next < 0) {
        return -1;
      }
      position = next;
      count++;
    }
    // Optional repetitions: a failure here completes a successful parse.
    while (allowsOptionalRepetition(count)) {
      int next = delegate.fastParseOn(buffer, position);
      if (next < 0) {
        return position;
      }
      position = next;
      count++;
    }
    return position;
  }

  @Override
  public PossessiveRepeatingParser copy() {
    return new PossessiveRepeatingParser(delegate, min, max);
  }
}
