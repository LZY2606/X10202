package org.petitparser.contract;

import org.junit.Test;
import org.petitparser.context.Context;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;

import java.util.Random;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;

/**
 * Randomized differential testing of the two entry points.
 *
 * <p>For a large space of nested combinator shapes (sequence, choice,
 * optional, and/ not lookahead, possessive/greedy/lazy repeats, trim,
 * flatten, token and pure actions) this asserts that
 * {@code parseOn(...).isSuccess()} and the final position agree with
 * {@code fastParseOn(...)}.
 *
 * <p>All repeating sub-parsers consume at least one character on success, so
 * every grammar terminates. Pure (supposedly side-effect-free) actions are
 * included deliberately to exercise the fast-path skip policy in deeply
 * nested structures.
 */
public class FastParseDifferentialTest {

  private static final String ALPHABET = "abc";

  @Test
  public void randomParsersAgreeOnOutcomeAndPosition() {
    Random random = new Random(20260922L);
    for (int seed = 0; seed < 400; seed++) {
      String input = randomInput(random);
      int position = random.nextInt(input.length() + 1);
      Parser slow = generate(new Random(seed), 0);
      Parser fast = generate(new Random(seed), 0);
      Result result = slow.parseOn(new Context(input, position));
      int fastPosition = fast.fastParseOn(input, position);
      assertEquals("seed=" + seed + " input='" + input + "' @" + position,
          result.isSuccess() ? result.getPosition() : -1, fastPosition);
    }
  }

  private static String randomInput(Random random) {
    StringBuilder builder = new StringBuilder();
    int length = random.nextInt(6);
    for (int i = 0; i < length; i++) {
      builder.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
    }
    return builder.toString();
  }

  /**
   * Deterministically builds a parser shape for a given seed, using the
   * supplied random source only for structure (the same character and limit
   * parsers are recreated independently for the slow and fast copies).
   */
  private static Parser generate(Random random, int depth) {
    if (depth >= 4) {
      return literal(random);
    }
    int choice = random.nextInt(12);
    switch (choice) {
      case 0:
        return literal(random);
      case 1:
        return generate(random, depth + 1).optional();
      case 2:
        return generate(random, depth + 1)
            .seq(generate(random, depth + 1));
      case 3:
        return generate(random, depth + 1)
            .or(generate(random, depth + 1));
      case 4:
        return generate(random, depth + 1).and();
      case 5:
        return generate(random, depth + 1).not();
      case 6:
        return consuming(random, depth).star();
      case 7:
        return consuming(random, depth)
            .repeat(random.nextInt(2), 1 + random.nextInt(2));
      case 8:
        return consuming(random, depth).starGreedy(literal(random));
      case 9:
        return consuming(random, depth).starLazy(literal(random));
      case 10:
        return generate(random, depth + 1).flatten().token();
      default: {
        Parser child = generate(random, depth + 1);
        Function<Object, String> pure = value -> "mapped";
        return child.map(pure);
      }
    }
  }

  /**
   * Builds a parser whose every successful iteration consumes at least one
   * character, so that possessive/greedy/lazy repetition always terminates.
   */
  private static Parser consuming(Random random, int depth) {
    Parser first = literal(random);
    if (depth >= 3) {
      return first;
    }
    // Keep generated trees shallow enough for the unit-test stack budget.
    switch (random.nextInt(3)) {
      case 0:
        return first;
      case 1:
        // A consuming sequence: a literal followed by an optional or
        // lookahead-free child that starts with another consuming parser.
        return first.seq(consuming(random, depth + 1));
      default:
        // A consuming choice: every alternative starts with a literal.
        return first.or(consuming(random, depth + 1));
    }
  }

  private static Parser literal(Random random) {
    char wanted = ALPHABET.charAt(random.nextInt(ALPHABET.length()));
    return new LiteralParser(wanted);
  }
}
