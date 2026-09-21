package org.petitparser.parser.repeating;

import org.petitparser.parser.Parser;
import org.petitparser.parser.combinators.DelegateParser;

import java.util.Objects;

/**
 * An abstract parser that repeatedly parses between 'min' and 'max' instances
 * of its delegate.
 *
 * <p>The repetition boundaries are defined once here as pure integer
 * predicates and shared by the slow {@code parseOn} and the allocation-free
 * {@code fastParseOn} transitions of the concrete subclasses, so that the two
 * paths cannot drift on when the mandatory phase ends or when the optional
 * phase is exhausted.
 */
public abstract class RepeatingParser extends DelegateParser {

  public static final int UNBOUNDED = -1;

  protected final int min;
  protected final int max;

  public RepeatingParser(Parser delegate, int min, int max) {
    super(delegate);
    this.min = min;
    this.max = max;
    if (min < 0) {
      throw new IllegalArgumentException(
          "Invalid min repetitions: " + getRange());
    }
    if (max != UNBOUNDED && min > max) {
      throw new IllegalArgumentException(
          "Invalid max repetitions: " + getRange());
    }
  }

  /**
   * Whether another mandatory repetition is required ({@code count < min}).
   * A delegate failure during this phase fails the whole parser.
   */
  protected final boolean needsMandatoryRepetition(int count) {
    return count < min;
  }

  /**
   * Whether another optional repetition is allowed ({@code count < max}, with
   * {@link #UNBOUNDED} unlimited). A delegate failure during this phase ends a
   * successful repetition.
   */
  protected final boolean allowsOptionalRepetition(int count) {
    return max == UNBOUNDED || count < max;
  }

  @Override
  public boolean hasEqualProperties(Parser other) {
    return super.hasEqualProperties(other) &&
        Objects.equals(min, ((RepeatingParser) other).min) &&
        Objects.equals(max, ((RepeatingParser) other).max);
  }

  @Override
  public String toString() {
    return super.toString() + "[" + getRange() + "]";
  }

  private String getRange() {
    return min + ".." + (max == UNBOUNDED ? "*" : max);
  }
}
