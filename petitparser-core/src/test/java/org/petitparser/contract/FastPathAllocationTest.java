package org.petitparser.contract;

import org.junit.BeforeClass;
import org.junit.Test;
import org.petitparser.context.Context;
import org.petitparser.context.Result;
import org.petitparser.parser.Parser;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import static org.petitparser.parser.primitive.CharacterParser.any;
import static org.petitparser.parser.primitive.CharacterParser.digit;
import static org.petitparser.parser.primitive.CharacterParser.of;

/**
 * Stable allocation baseline for the fast parsing path.
 *
 * <p>{@link Parser#fastParseOn(String, int)} is documented to gain its speed by
 * avoiding allocations. Two complementary checks guard that contract:
 * <ol>
 *   <li>a functional probe asserts the fast path never drives the children
 *       through {@code parseOn} (i.e. it cannot silently degrade to building a
 *       full {@link Result}); and</li>
 *   <li>a thread-allocation measurement compares the bytes allocated by the
 *       fast path against the full parse, for representative sequence, choice
 *       and repeat parsers.</li>
 * </ol>
 *
 * <p>The numeric thresholds are deliberately loose: they only have to prove
 * the fast path stays allocation-free (or, for the inherently stack-using
 * greedy repeat, substantially cheaper than the full parse). They do not pin a
 * JVM-specific absolute number.
 */
public class FastPathAllocationTest {

  /** A child that records which entry point its parent drives it through. */
  static class ProbeParser extends Parser {
    boolean slowCalled;
    boolean fastCalled;
    final Parser delegate;

    ProbeParser(Parser delegate) {
      this.delegate = delegate;
    }

    @Override
    public Result parseOn(Context context) {
      slowCalled = true;
      return delegate.parseOn(context);
    }

    @Override
    public int fastParseOn(String buffer, int position) {
      fastCalled = true;
      return delegate.fastParseOn(buffer, position);
    }

    @Override
    public Parser copy() {
      return new ProbeParser(delegate);
    }

    void reset() {
      slowCalled = false;
      fastCalled = false;
    }
  }

  // The fast path must drive every child through fastParseOn and must never
  // fall back to constructing a Result for an inner parser.

  @Test
  public void sequenceFastPathUsesNoSlowResult() {
    ProbeParser a = new ProbeParser(of('a'));
    ProbeParser b = new ProbeParser(of('b'));
    Parser parser = new org.petitparser.parser.combinators
        .SequenceParser(a, b);
    assertEquals(2, parser.fastParseOn("ab", 0));
    assertTrue(a.fastCalled);
    assertTrue(b.fastCalled);
    assertTrue(!a.slowCalled && !b.slowCalled);
  }

  @Test
  public void choiceFastPathUsesNoSlowResult() {
    ProbeParser a = new ProbeParser(of('a'));
    ProbeParser b = new ProbeParser(of('b'));
    Parser parser = new org.petitparser.parser.combinators
        .ChoiceParser(a, b);
    assertEquals(1, parser.fastParseOn("b", 0));
    assertTrue(b.fastCalled);
    assertTrue(!a.slowCalled && !b.slowCalled);
  }

  @Test
  public void possessiveRepeatFastPathUsesNoSlowResult() {
    ProbeParser a = new ProbeParser(of('a'));
    Parser parser = new org.petitparser.parser.repeating
        .PossessiveRepeatingParser(a, 0,
            org.petitparser.parser.repeating.RepeatingParser.UNBOUNDED);
    assertEquals(4, parser.fastParseOn("aaaab", 0));
    assertTrue(a.fastCalled);
    assertTrue(!a.slowCalled);
  }

  @Test
  public void pureActionFastPathBypassesActionAndResult() {
    ProbeParser a = new ProbeParser(of('a'));
    Parser parser = new org.petitparser.parser.actions
        .ActionParser<>(a, value -> value, false);
    assertEquals(1, parser.fastParseOn("a", 0));
    assertTrue(a.fastCalled && !a.slowCalled);
  }

  @Test
  public void sideEffectActionFastPathFallsBackToSlowPath() {
    ProbeParser a = new ProbeParser(of('a'));
    Parser parser = new org.petitparser.parser.actions
        .ActionParser<>(a, value -> value, true);
    assertEquals(1, parser.fastParseOn("a", 0));
    // The effect must be observed, so the slow path is intentionally taken.
    assertTrue(a.slowCalled);
  }

  // ------------------------------------------------------------------
  // Allocation measurement
  // ------------------------------------------------------------------

  private static Object threadBean;
  private static Method currentAllocated;
  private static boolean allocationSupported;

  @BeforeClass
  public static void setUp() throws Exception {
    // Accessed reflectively so the production module descriptor does not have
    // to declare a dependency on java.management. All reflective calls go
    // through the exported ThreadMXBean interface type rather than the JVM
    // internal implementation class.
    Class<?> managementFactory =
        Class.forName("java.lang.management.ManagementFactory");
    threadBean = managementFactory
        .getMethod("getThreadMXBean").invoke(null);
    Class<?> threadMxBean = null;
    for (Class<?> candidate : threadBean.getClass().getInterfaces()) {
      if (candidate.getName()
          .equals("com.sun.management.ThreadMXBean")) {
        threadMxBean = candidate;
      }
    }
    if (threadMxBean == null) {
      return;
    }
    if ((Boolean) threadMxBean
        .invoke(threadBean)) {
      threadMxBean
          .getMethod("setThreadAllocatedMemoryEnabled", boolean.class)
          .invoke(threadBean, true);
      allocationSupported = (Boolean) findMethod(threadMxBean,
              "isThreadAllocatedMemoryEnabled").invoke(threadBean);
      currentAllocated =
          findMethod(threadMxBean, "currentThreadAllocatedBytes");
    }
  }

  private static Method findMethod(Class<?> type, String name)
      throws NoSuchMethodException {
    Class<?> current = type;
    while (current != null) {
      try {
        return current.getMethod(name);
      } catch (NoSuchMethodException ignored) {
        // walk up: concrete then declared interfaces transitively
      }
      for (Class<?> iface : current.getInterfaces()) {
        try {
          return findMethod(iface, name);
        } catch (NoSuchMethodException ignored) {
          // continue searching
        }
      }
      current = current.getSuperclass();
    }
    throw new NoSuchMethodException(name);
  }

  private static long currentAllocated() {
    try {
      return (Long) currentAllocated.invoke(threadBean);
    } catch (ReflectiveOperationException error) {
      throw new AssertionError(error);
    }
  }

  private static long measureFast(Parser parser, String input,
      int iterations) {
    long start = currentAllocated();
    long position = 0;
    for (int i = 0; i < iterations; i++) {
      position += parser.fastParseOn(input, 0);
      position -= input.length();
    }
    long end = currentAllocated();
    assertEquals("all parsers must consume the whole input", 0, position);
    return end - start;
  }

  private static long measureSlow(Parser parser, String input,
      int iterations) {
    List<Object> sink = new ArrayList<>();
    long start = currentAllocated();
    long position = 0;
    for (int i = 0; i < iterations; i++) {
      Result result = parser.parseOn(new Context(input, 0));
      sink.add(result);
      position += result.getPosition();
      position -= input.length();
    }
    long end = currentAllocated();
    assertEquals("all parsers must consume the whole input", 0, position);
    if (sink.isEmpty()) {
      throw new AssertionError();
    }
    return end - start;
  }

  private static long medianFast(Parser parser, String input,
      int iterations, int rounds) {
    long best = Long.MAX_VALUE;
    for (int i = 0; i < rounds; i++) {
      best = Math.min(best, measureFast(parser, input, iterations));
    }
    return best / iterations;
  }

  private static long medianSlow(Parser parser, String input,
      int iterations, int rounds) {
    long best = Long.MAX_VALUE;
    for (int i = 0; i < rounds; i++) {
      best = Math.min(best, measureSlow(parser, input, iterations));
    }
    return best / iterations;
  }

  private void assertFastIsCheap(Parser parser, String input,
      long fastBudgetBytes, double slowRatioLimit) {
    assumeTrue("JVM does not report per-thread allocation",
        allocationSupported);
    int iterations = 20_000;
    int rounds = 7;
    // Warm up both paths so JIT and escape-analysis effects are stable.
    for (int i = 0; i < 5_000; i++) {
      parser.fastParseOn(input, 0);
      parser.parseOn(new Context(input, 0));
    }
    long fast = medianFast(parser, input, iterations, rounds);
    long slow = medianSlow(parser, input, iterations, rounds);
    assertTrue("fast path allocated " + fast + " B/iter, budget "
        + fastBudgetBytes + " B/iter (slow=" + slow + " B/iter)",
        fast <= fastBudgetBytes);
    double ratio = (double) fast / (double) Math.max(1L, slow);
    assertTrue("fast/slow allocation ratio " + ratio + " exceeds "
        + slowRatioLimit + " (fast=" + fast + ", slow=" + slow + " B/iter)",
        ratio <= slowRatioLimit);
  }

  @Test
  public void sequenceFastPathAllocatesNoResults() {
    Parser parser = digit().seq(any()).seq(any()).seq(digit());
    assertFastIsCheap(parser, "1abc9", 8L, 0.20);
  }

  @Test
  public void choiceFastPathAllocatesNoResults() {
    Parser parser = of('x').or(of('y')).or(any());
    assertFastIsCheap(parser, "z", 8L, 0.20);
  }

  @Test
  public void possessiveRepeatFastPathAllocatesNoResults() {
    Parser parser = of('a').star();
    assertFastIsCheap(parser, "aaaaaaaaaaaaaaaaaaaa", 8L, 0.10);
  }

  @Test
  public void greedyRepeatFastPathStaysSubstantiallyCheaper() {
    // The greedy parser legitimately needs a backtracking position stack on
    // both paths, but the fast path must avoid per-iteration Integer boxing.
    Parser parser = of('a').starGreedy(of('b'));
    assertFastIsCheap(parser, "aaaaaaaaaaaaaaaaaaab", 24L, 0.40);
  }
}
