package org.petitparser.parser.repeating;

import org.petitparser.context.Result;
import org.petitparser.parser.Parser;
import org.petitparser.parser.combinators.DelegateParser;

import java.util.Objects;

/**
 * An abstract parser that repeatedly parses between 'min' and 'max' instances
 * of its delegate.
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

  // ------------------------------------------------------------------
  // Shared repetition transitions.
  //
  // The concrete repeating parsers all run the same state machine on both
  // entry points (parseOn and fastParseOn). These primitives define the common
  // boundary computation once: a mandatory prefix of 'min' attempts that abort
  // on failure, and a predicate for the optional up-to-'max' tail. The slow
  // versions thread Result objects and the fast versions thread plain int
  // positions, so the recognition hot path allocates nothing.
  // ------------------------------------------------------------------

  /** One slow-path repetition attempt. */
  @FunctionalInterface
  protected interface SlowRepeatStep {
    Result apply();
  }

  /** One fast-path repetition attempt. */
  @FunctionalInterface
  protected interface FastRepeatStep {
    int apply();
  }

  /**
   * Runs the mandatory {@code min} repetitions on the slow path.
   *
   * @return {@code null} when every mandatory repetition succeeds, otherwise
   *         the first failure which terminates the whole parse.
   */
  protected static Result repeatMandatory(int min, SlowRepeatStep step) {
    for (int count = 0; count < min; count++) {
      Result result = step.apply();
      if (result.isFailure()) {
        return result;
      }
    }
    return null;
  }

  /**
   * Runs the mandatory {@code min} repetitions on the fast path while only
   * threading the current {@code position}.
   *
   * @return the resulting position when every repetition succeeds, {@code -1}
   *         on the first failure.
   */
  protected static int repeatMandatory(int min, int position,
      FastRepeatStep step) {
    for (int count = 0; count < min; count++) {
      position = step.apply();
      if (position < 0) {
        return -1;
      }
    }
    return position;
  }

  /**
   * Whether another optional repetition is allowed at {@code count} given the
   * (possibly {@link #UNBOUNDED}) {@code max}.
   */
  protected static boolean canRepeat(int count, int max) {
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
