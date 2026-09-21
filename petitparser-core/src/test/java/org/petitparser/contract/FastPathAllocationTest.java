package org.petitparser.contract;

import org.junit.BeforeClass;
import org.junit.Test;
import org.petitparser.parser.Parser;

import java.lang.reflect.Method;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import static org.petitparser.parser.primitive.CharacterParser.of;

/**
 * Stable allocation baseline for the {@code fastParseOn} hot path.
 *
 * <p>The fast path of a typical {@code sequence}/{@code choice}/{@code repeat}
 * parser must not degrade into building {@link org.petitparser.context.Result}
 * (or other wrapper) objects: it is expected to allocate nothing at all. The
 * slow {@code parseOn} path is measured as a sanity control and must clearly
 * allocate, proving the counter is actually observing the difference.
 *
 * <p>The {@code com.sun.management.ThreadMXBean} is accessed reflectively so
 * that the production {@code module-info} does not need an extra
 * {@code requires} for a test-only concern.
 */
public class FastPathAllocationTest {

  private static Object threadBean;
  private static Method allocatedBytes;

  @BeforeClass
  public static void setUp() throws Exception {
    Class<?> factory = Class.forName("java.lang.management.ManagementFactory");
    Object generic = factory.getMethod("getThreadMXBean").invoke(null);
    Class<?> sunBean = null;
    try {
      sunBean = Class.forName("com.sun.management.ThreadMXBean");
    } catch (ClassNotFoundException ignored) {
      // Unsupported JVM; tests will be skipped.
    }
    if (sunBean != null && sunBean.isInstance(generic)) {
      Method supported = sunBean.getMethod(
          "isThreadAllocatedMemorySupported");
      if ((Boolean) supported.invoke(generic)) {
        Method enable = sunBean.getMethod(
            "setThreadAllocatedMemoryEnabled", boolean.class);
        enable.invoke(generic, true);
        threadBean = generic;
        allocatedBytes = sunBean.getMethod(
            "getThreadAllocatedBytes", long.class);
      }
    }
  }

  private interface Runner {
    long run(String input);
  }

  private long currentAllocatedBytes() throws Exception {
    long threadId = Thread.currentThread().getId();
    return (Long) allocatedBytes.invoke(threadBean, threadId);
  }

  /**
   * Measures the minimum bytes allocated per invocation over several rounds,
   * after warm-up and with a forced GC before every round.
   */
  private long bytesPerIteration(Runner runner, String input,
      int warmup, int iterations, int rounds) throws Exception {
    for (int i = 0; i < warmup; i++) {
      sink(runner.run(input));
    }
    long minimum = Long.MAX_VALUE;
    for (int round = 0; round < rounds; round++) {
      forceGc();
      long before = currentAllocatedBytes();
      long accumulated = 0;
      for (int i = 0; i < iterations; i++) {
        accumulated += runner.run(input);
      }
      sink(accumulated);
      long after = currentAllocatedBytes();
      minimum = Math.min(minimum, (after - before) / iterations);
    }
    return minimum;
  }

  private static void sink(long value) {
    if (value == Long.MIN_VALUE) {
      throw new IllegalStateException("prevent dead code elimination");
    }
  }

  private static void forceGc() {
    System.gc();
    try {
      Thread.sleep(5);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void assertFastPathAllocatesNothing(String name, Parser parser,
      String input) throws Exception {
    assumeTrue("thread allocation measurement unsupported",
        threadBean != null);
    long fast = measureStable(name + " fast",
        buffer -> parser.fastParseOn(buffer, 0), input);
    long slow = measureStable(name + " slow",
        buffer -> {
          org.petitparser.context.Result result =
              parser.parseOn(new org.petitparser.context.Context(buffer, 0));
          return result.getPosition();
        }, input);
    assertTrue(name + ": fast path allocated " + fast
        + " bytes/iter (expected 0)", fast == 0L);
    assertTrue(name + ": slow control must allocate, got " + slow,
        slow > 8L);
  }

  /**
   * Measures with progressively more warmup so that the tiered compiler has
   * scalar-replaced stack-confined hot-path state before sampling; takes the
   * minimum over rounds to suppress measurement noise.
   */
  private long measureStable(String name, Runner runner, String input)
      throws Exception {
    int[] warmups = {20_000, 100_000, 400_000};
    long measured = Long.MAX_VALUE;
    for (int warmup : warmups) {
      measured = bytesPerIteration(runner, input, warmup, 100_000, 7);
      if (measured == 0L) {
        return 0L;
      }
    }
    return measured;
  }

  @Test
  public void sequenceFastPathAllocatesNothing() throws Exception {
    Parser parser = of('a').seq(of('b'), of('c'));
    assertFastPathAllocatesNothing("sequence(abc)", parser, "abc");
  }

  @Test
  public void choiceFastPathAllocatesNothing() throws Exception {
    Parser parser = of('x').or(of('y'), of('a').seq(of('b')));
    assertFastPathAllocatesNothing("choice", parser, "ab");
  }

  @Test
  public void possessiveRepeatFastPathAllocatesNothing() throws Exception {
    Parser parser = of('a').star().seq(of('b'));
    assertFastPathAllocatesNothing("possessive repeat", parser, "aaaab");
  }

  @Test
  public void lookaheadFastPathAllocatesNothing() throws Exception {
    Parser parser = of('a').and().seq(of('a'));
    assertFastPathAllocatesNothing("lookahead", parser, "a");
  }

  private void assertFastPathBudget(String name, Parser parser,
      String input, long maxBytes) throws Exception {
    assumeTrue("thread allocation measurement unsupported",
        threadBean != null);
    long fast = measureStable(name + " fast",
        buffer -> parser.fastParseOn(buffer, 0), input);
    long slow = measureStable(name + " slow",
        buffer -> {
          org.petitparser.context.Result result =
              parser.parseOn(new org.petitparser.context.Context(buffer, 0));
          return result.getPosition();
        }, input);
    assertTrue(name + ": fast path allocated " + fast
        + " bytes/iter (budget " + maxBytes + ")", fast <= maxBytes);
    assertTrue(name + ": slow control must allocate, got " + slow,
        slow > 8L);
  }

  @Test
  public void lazyRepeatFastPathAllocatesNothing() throws Exception {
    Parser parser = of('a').starLazy(of('b')).seq(of('b'));
    assertFastPathAllocatesNothing("lazy repeat", parser, "aaaab");
  }

  @Test
  public void greedyRepeatFastPathUsesOnlyPositionStack() throws Exception {
    Parser parser = of('a').starGreedy(of('b')).seq(of('b'));
    assertFastPathBudget("greedy repeat", parser, "aaaab", 48L);
  }

  @Test
  public void pureActionFastPathAllocatesNothing() throws Exception {
    Parser parser = of('a').map(value -> value).seq(of('b'));
    assertFastPathAllocatesNothing("pure action", parser, "ab");
  }
}
