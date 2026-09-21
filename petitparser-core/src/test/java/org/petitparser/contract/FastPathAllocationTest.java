package org.petitparser.contract;

import org.junit.BeforeClass;
import org.junit.Test;
import org.petitparser.context.Context;
import org.petitparser.parser.Parser;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.petitparser.parser.primitive.CharacterParser.of;

/**
 * Allocation baseline for the {@code fastParseOn} recognition path.
 *
 * <p>The structural combinators must recognize input without falling back to
 * the result-building machinery of {@code parseOn}: a successful fast parse is
 * expected to allocate nothing on the hot path (no {@code Context}, {@code
 * Result} or element list). The greedy repeating parser fundamentally needs a
 * backtracking history, so it allocates a small primitive position stack; that
 * allocation is bounded and must be far below the corresponding slow path.
 *
 * <p>The measurements use the JVM's thread-allocation counter after warm-up.
 * Assertions are deliberately loose (well above the measured steady-state
 * values) to stay stable across JIT implementations and machines while still
 * failing loudly if the fast path degenerates into full parsing.
 */
public class FastPathAllocationTest {

  /**
   * Generous upper bounds in bytes per fast invocation. They are multiples of
   * the observed steady state and leave ample headroom for different JITs.
   */
  private static final long ZERO_TOLERANCE = 16L;
  private static final long GREEDY_TOLERANCE = 2048L;

  private static ThreadAllocation allocation;

  @BeforeClass
  public static void setUp() {
    allocation = ThreadAllocation.create();
    assertNotNull(allocation);
  }

  private long bytesPerFastInvocation(Parser parser, String input) {
    return measure(() -> parser.fastParseOn(input, 0));
  }

  private long bytesPerSlowInvocation(Parser parser, String input) {
    return measure(() -> parser.parseOn(new Context(input, 0)));
  }

  private long measure(Runnable runnable) {
    for (int i = 0; i < 20_000; i++) {
      runnable.run();
    }
    long before = allocation.allocatedBytes();
    int iterations = 200_000;
    for (int i = 0; i < iterations; i++) {
      runnable.run();
    }
    long after = allocation.allocatedBytes();
    return (after - before) / iterations;
  }

  @Test
  public void testSequenceFastPathAllocatesNothing() {
    Parser parser = of('a').seq(of('a'), of('a'), of('a'), of('a'));
    assertTrue("sequence fast path allocated "
            + bytesPerFastInvocation(parser, "aaaaa") + " bytes",
        bytesPerFastInvocation(parser, "aaaaa") <= ZERO_TOLERANCE);
  }

  @Test
  public void testChoiceFastPathAllocatesNothing() {
    Parser parser = of('z').or(of('y')).or(of('x')).or(of('a'));
    assertTrue("choice fast path allocated "
            + bytesPerFastInvocation(parser, "aaaaa") + " bytes",
        bytesPerFastInvocation(parser, "aaaaa") <= ZERO_TOLERANCE);
  }

  @Test
  public void testPossessiveRepeatFastPathAllocatesNothing() {
    Parser parser = of('a').star();
    assertTrue("possessive fast path allocated "
            + bytesPerFastInvocation(parser, "aaaaaaaaaa") + " bytes",
        bytesPerFastInvocation(parser, "aaaaaaaaaa") <= ZERO_TOLERANCE);
  }

  @Test
  public void testLazyRepeatFastPathAllocatesNothing() {
    Parser parser = of('a').starLazy(of('b'));
    assertTrue("lazy fast path allocated "
            + bytesPerFastInvocation(parser, "aaaaaaaaaab") + " bytes",
        bytesPerFastInvocation(parser, "aaaaaaaaaab") <= ZERO_TOLERANCE);
  }

  @Test
  public void testFlattenFastPathAllocatesNothing() {
    Parser parser = of('a').star().flatten();
    assertTrue("flatten fast path allocated "
            + bytesPerFastInvocation(parser, "aaaaaaaaaa") + " bytes",
        bytesPerFastInvocation(parser, "aaaaaaaaaa") <= ZERO_TOLERANCE);
  }

  @Test
  public void testLookaheadAndOptionalFastPathAllocateNothing() {
    String input = "aaaaaaaaaa";
    assertTrue(bytesPerFastInvocation(of('a').and(), input)
        <= ZERO_TOLERANCE);
    assertTrue(bytesPerFastInvocation(of('z').not(), input)
        <= ZERO_TOLERANCE);
    assertTrue(bytesPerFastInvocation(of('z').optional(), input)
        <= ZERO_TOLERANCE);
  }

  @Test
  public void testGreedyFastPathBoundedAndFarCheaperThanSlow() {
    Parser parser = of('a').starGreedy(of('b'));
    String input = "aaaaaaaaaaaaaaaaaaaab";
    long fast = bytesPerFastInvocation(parser, input);
    long slow = bytesPerSlowInvocation(parser, input);
    assertTrue("greedy fast path allocated " + fast + " bytes",
        fast <= GREEDY_TOLERANCE);
    assertTrue("greedy fast path (" + fast + ") must stay well below the "
        + "slow path (" + slow + ")", fast * 2 <= slow);
  }
}
