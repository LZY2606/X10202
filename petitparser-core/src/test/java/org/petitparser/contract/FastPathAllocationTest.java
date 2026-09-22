package org.petitparser.contract;

import org.junit.BeforeClass;
import org.junit.Test;
import org.petitparser.parser.Parser;

import java.lang.reflect.Method;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import static org.petitparser.parser.primitive.CharacterParser.digit;
import static org.petitparser.parser.primitive.CharacterParser.letter;
import static org.petitparser.parser.primitive.CharacterParser.of;

/**
 * Stable allocation baseline proving that {@code fastParseOn} does not
 * degenerate into full parsing for the typical sequence, choice and repeat
 * combinators.
 *
 * <p>The measurement uses the JVM's thread-local allocation counter accessed
 * reflectively (so the library module descriptor does not need to read
 * management modules). It asserts a strong, near-zero-byte budget for the
 * allocation-free combinators; greedy repeat, whose fast path inherently needs
 * a bounded scratch stack, is checked against the slow path by ratio instead.
 * The test is skipped on JVMs that do not expose the counters.
 */
public class FastPathAllocationTest {

  /** Loose upper bound for JVM/JIT bookkeeping noise, in bytes per iteration. */
  private static final long NOISE_BUDGET = 2L;

  private static boolean supported;
  private static Object threadMxBean;
  private static Method getAllocatedBytes;

  @BeforeClass
  public static void setUp() throws Exception {
    Object bean = Class.forName("java.lang.management.ManagementFactory")
        .getMethod("getThreadMXBean").invoke(null);
    Method isSupported = bean.getClass().getMethod(
        "isThreadAllocatedMemorySupported");
    if (!Boolean.TRUE.equals(isSupported.invoke(bean))) {
      return;
    }
    Method isEnabled = bean.getClass().getMethod(
        "isThreadAllocatedMemoryEnabled");
    Method setEnabled = bean.getClass().getMethod(
        "setThreadAllocatedMemoryEnabled", boolean.class);
    if (!Boolean.TRUE.equals(isEnabled.invoke(bean))) {
      setEnabled.invoke(bean, true);
    }
    getAllocatedBytes = bean.getClass().getMethod(
        "getThreadAllocatedBytes", long.class);
    threadMxBean = bean;
    supported = true;
  }

  private long allocatedBytes() throws Exception {
    long id = Thread.currentThread().getId();
    return (Long) getAllocatedBytes.invoke(threadMxBean, id);
  }

  private long measureFast(Parser parser, String input, int iterations)
      throws Exception {
    for (int i = 0; i < iterations / 2; i++) {
      assertTrue(parser.fastParseOn(input, 0) >= 0);
    }
    System.gc();
    long before = allocatedBytes();
    for (int i = 0; i < iterations; i++) {
      assertTrue(parser.fastParseOn(input, 0) >= 0);
    }
    return allocatedBytes() - before;
  }

  private long measureSlow(Parser parser, String input, int iterations)
      throws Exception {
    for (int i = 0; i < iterations / 2; i++) {
      assertTrue(parser.parse(input).isSuccess());
    }
    System.gc();
    long before = allocatedBytes();
    for (int i = 0; i < iterations; i++) {
      assertTrue(parser.parse(input).isSuccess());
    }
    return allocatedBytes() - before;
  }

  private void assertFastAllocatesAlmostNothing(String label, Parser parser,
      String input) throws Exception {
    assumeTrue("thread allocation counters unavailable", supported);
    int iterations = 100_000;
    long allocated = measureFast(parser, input, iterations);
    long perIteration = allocated / iterations;
    assertTrue(label + " fast path allocated " + perIteration
            + " bytes/iteration (expected <= " + NOISE_BUDGET + ")",
        perIteration <= NOISE_BUDGET);
  }

  private void assertFastCheaperThanSlow(String label, Parser parser,
      String input) throws Exception {
    assumeTrue("thread allocation counters unavailable", supported);
    int fastIterations = 100_000;
    int slowIterations = 20_000;
    long fastPer = Math.max(1L,
        measureFast(parser, input, fastIterations) / fastIterations);
    long slowPer = Math.max(1L,
        measureSlow(parser, input, slowIterations) / slowIterations);
   assertTrue(label + ": fast=" + fastPer + "B slow=" + slowPer
        + "B per iteration", fastPer * 4 <= slowPer);
  }

  @Test
  public void sequenceFastPathAllocatesNothing() throws Exception {
    Parser parser = letter().seq(letter()).seq(digit()).seq(of('!'));
    assertFastAllocatesAlmostNothing("sequence", parser, "ab9!");
  }

  @Test
  public void choiceFastPathAllocatesNothing() throws Exception {
    Parser parser = of('x').or(of('y')).or(letter().seq(digit()));
    assertFastAllocatesAlmostNothing("choice", parser, "a9");
  }

  @Test
  public void possessiveRepeatFastPathAllocatesNothing() throws Exception {
    Parser parser = letter().star();
    assertFastAllocatesAlmostNothing("possessive-star", parser,
        "abcdefghij");
  }

  @Test
  public void lazyRepeatFastPathAllocatesNothing() throws Exception {
    Parser parser = letter().starLazy(digit());
    assertFastAllocatesAlmostNothing("lazy-repeat", parser, "abcdefghij0");
  }

  @Test
  public void nestedSequenceChoiceRepeatFastPathAllocatesNothing()
      throws Exception {
    Parser parser = letter().plus()
        .seq(of(',').or(of(';')))
        .seq(digit().plus());
    assertFastAllocatesAlmostNothing("nested", parser, "abc,42");
  }

  @Test
  public void flattenWithMessageFastPathAllocatesNothing() throws Exception {
    // flatten(message) runs its delegate on the fast path and only builds the
    // substring on parseOn, so it must stay allocation-free apart from any
    // allocation inherent to the child parsers.
    Parser parser = letter().plus().seq(digit().plus()).flatten("expected");
    assertFastAllocatesAlmostNothing("flatten(message)", parser, "abc42");
  }

  @Test
  public void greedyRepeatFastPathIsCheaperThanSlow() throws Exception {
    // Greedy repeat needs a bounded position stack even on the fast path;
    // assert it stays dramatically cheaper than the full result-building
    // path rather than demanding exactly zero bytes.
    Parser parser = letter().starGreedy(digit());
    assertFastCheaperThanSlow("greedy-repeat", parser, "abcdefghij0");
  }

  @Test
  public void sideEffectingActionFallsBackButPureActionDoesNot()
      throws Exception {
    assumeTrue("thread allocation counters unavailable", supported);
    Parser pure = letter().star().map(value -> value);
    assertFastAllocatesAlmostNothing("pure-action", pure, "abcdef");

    int[] effects = {0};
    Parser withEffects = letter().star()
        .mapWithSideEffects(value -> {
          effects[0]++;
          return value;
        });
    long fast = measureFast(withEffects, "abcdef", 50_000);
    assertTrue("side-effect fallback must still parse", effects[0] > 0);
    // The fallback behaves like the slow path, well above the zero-allocation
    // budget of the plain combinator.
    Parser noAction = letter().star();
    long plain = measureFast(noAction, "abcdef", 50_000);
    assertTrue("side-effecting action is expected to fall back to the slow "
        + "path, fallback=" + fast + " plain=" + plain, fast > plain * 10);
  }
}
