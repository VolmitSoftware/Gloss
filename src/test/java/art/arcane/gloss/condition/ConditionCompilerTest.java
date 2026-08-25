package art.arcane.gloss.condition;

import art.arcane.gloss.expr.ExprFunctions;
import art.arcane.gloss.expr.ExprScope;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class ConditionCompilerTest {

  @Test
  public void compilesAndMatchesBooleanExpression() {
    CompiledCondition condition = ConditionCompiler.compile(new ConditionSource(
        "boards/example.json $.variants[0].when",
        "viewer.health < 5 && viewer.world == 'dungeon'"));
    TestScope matchingScope = new TestScope(Map.of(
        "viewer.health", 4.0D,
        "viewer.world", "dungeon"));
    TestScope failingScope = new TestScope(Map.of(
        "viewer.health", 6.0D,
        "viewer.world", "dungeon"));

    Assert.assertTrue(condition.matches(matchingScope));
    Assert.assertFalse(condition.matches(failingScope));
  }

  @Test
  public void collectsSortedReferencesAndLiteralMetricKeys() {
    CompiledCondition condition = ConditionCompiler.compile(
        "metric('react.tick-ms', 0) > server.limit && hasPermission('viewer', 'gloss.staff')"
            + " && metric(metricName, 0) > 0");

    Assert.assertEquals(Set.of("metricName", "server.limit"), condition.references().variables());
    Assert.assertEquals(Set.of("hasPermission", "metric"), condition.references().functions());
    Assert.assertEquals(Set.of("react.tick-ms"), condition.references().metricKeys());
    Assert.assertEquals(
        List.of("metricName", "server.limit"),
        new ArrayList<String>(condition.references().variables()));
  }

  @Test(expected = UnsupportedOperationException.class)
  public void referencesAreImmutable() {
    CompiledCondition condition = ConditionCompiler.compile("viewer.op");
    condition.references().variables().add("other");
  }

  @Test
  public void validationErrorCarriesPathSourceAndPosition() {
    ConditionSource source = new ConditionSource("boards/example.json $.select.when", "true && )");
    try {
      ConditionCompiler.compile(source);
      Assert.fail();
    } catch (ConditionValidationException exception) {
      Assert.assertEquals(source.path(), exception.path());
      Assert.assertEquals(source.expression(), exception.source());
      Assert.assertTrue(exception.position() >= 8);
      Assert.assertTrue(exception.getMessage().contains(source.path()));
    }
  }

  @Test
  public void rejectsProvablyNonBooleanRoot() {
    assertInvalid("viewer.health + 1", "condition must produce a boolean");
    assertInvalid("contains('abc', 'a') ? 'yes' : 'no'", "condition must produce a boolean");
    assertInvalid("abs(-5)", "condition must produce a boolean");
  }

  @Test
  public void permitsUnknownRootResolvedAsBooleanAtRuntime() {
    CompiledCondition condition = ConditionCompiler.compile("viewer.op");
    Assert.assertTrue(condition.matches(new TestScope(Map.of("viewer.op", true))));
  }

  @Test
  public void rejectsProvablyInvalidOperatorsAndBuiltInCalls() {
    assertInvalid("contains('abc')", "contains expects 2 argument(s)");
    assertInvalid("contains(1, 'a')", "contains argument 1 must be a string");
    assertInvalid("oneOf('a', ['a', 2])", "list entries have incompatible types");
    assertInvalid("metric('key', 'fallback') > 0", "metric argument 2 must be a number");
    assertInvalid("abs('five') > 0", "abs argument 1 must be a number");
    assertInvalid("typo(viewer.health)", "unknown condition function");
    assertInvalid("true && 1", "&& requires boolean");
    assertInvalid("'1' == 1", "cannot compare string and number");
  }

  @Test
  public void stringHelpersAreAvailableOnlyThroughConditionScope() {
    TestScope scope = new TestScope(Map.of("viewer.world", "dungeon_alpha"));

    Assert.assertTrue(ConditionCompiler.compile(
        "oneOf('nether', ['world', 'nether'])").matches(scope));
    Assert.assertTrue(ConditionCompiler.compile(
        "contains('critical-health', 'health')").matches(scope));
    Assert.assertTrue(ConditionCompiler.compile(
        "startsWith(viewer.world, 'dungeon_')").matches(scope));
    Assert.assertTrue(ConditionCompiler.compile(
        "endsWith(viewer.world, '_alpha')").matches(scope));
    Assert.assertTrue(ConditionCompiler.compile(
        "matchesGlob(viewer.world, 'dungeon_*')").matches(scope));
    Assert.assertTrue(ConditionCompiler.compile(
        "matchesGlob('minigame_a1', 'minigame_??')").matches(scope));
    Assert.assertFalse(ConditionCompiler.compile(
        "matchesGlob('dungeon_alpha_extra', 'dungeon_?????')").matches(scope));
    Assert.assertNull(scope.call("contains", List.of("abc", "a")));
  }

  @Test
  public void conditionScopeStillDelegatesStandardFunctions() {
    TestScope scope = new TestScope(Map.of());

    Assert.assertTrue(ConditionCompiler.compile("abs(-5) == 5").matches(scope));
  }

  @Test
  public void matchFailsClosedAndBoundsEvaluationReports() {
    CompiledCondition condition = ConditionCompiler.compile(new ConditionSource(
        "boards/example.json $.variants[2].when", "viewer.missing"));
    List<ConditionEvaluationError> captured = new ArrayList<ConditionEvaluationError>();
    BoundedConditionErrorCallback errors = BoundedConditionErrorCallback.bounded(2, captured::add);
    TestScope scope = new TestScope(Map.of());

    Assert.assertFalse(condition.matches(scope, errors));
    Assert.assertFalse(condition.matches(scope, errors));
    Assert.assertFalse(condition.matches(scope, errors));
    Assert.assertEquals(2, captured.size());
    Assert.assertEquals(2, errors.reportCount());
    Assert.assertEquals(condition.source().path(), captured.getFirst().path());
    Assert.assertEquals(condition.source().expression(), captured.getFirst().source());
    Assert.assertTrue(captured.getFirst().message().contains("viewer.missing"));
  }

  @Test
  public void callbackFailureDoesNotEscapeMatch() {
    CompiledCondition condition = ConditionCompiler.compile("missing");
    BoundedConditionErrorCallback errors = BoundedConditionErrorCallback.bounded(1, error -> {
      throw new IllegalStateException("callback failed");
    });

    Assert.assertFalse(condition.matches(new TestScope(Map.of()), errors));
  }

  @Test
  public void boundedCallbackIsSafeUnderConcurrentReports() throws InterruptedException {
    AtomicInteger callbacks = new AtomicInteger();
    BoundedConditionErrorCallback errors = BoundedConditionErrorCallback.bounded(
        5, error -> callbacks.incrementAndGet());
    CompiledCondition condition = ConditionCompiler.compile("missing");
    TestScope scope = new TestScope(Map.of());
    List<Thread> threads = new ArrayList<Thread>();
    for (int index = 0; index < 20; index++) {
      Thread thread = new Thread(() -> condition.matches(scope, errors));
      threads.add(thread);
      thread.start();
    }
    for (Thread thread : threads) {
      thread.join();
    }

    Assert.assertEquals(5, callbacks.get());
    Assert.assertEquals(5, errors.reportCount());
  }

  private static void assertInvalid(String expression, String expectedMessage) {
    try {
      ConditionCompiler.compile(new ConditionSource("test.when", expression));
      Assert.fail("expected invalid condition: " + expression);
    } catch (ConditionValidationException exception) {
      Assert.assertTrue(
          "expected '" + expectedMessage + "' in '" + exception.getMessage() + "'",
          exception.getMessage().contains(expectedMessage));
    }
  }

  private static final class TestScope implements ExprScope {

    private final Map<String, Object> variables;
    private TestScope(Map<String, Object> variables) {
      this.variables = variables;
    }

    @Override
    public Object variable(String dottedName) {
      return variables.get(dottedName);
    }

    @Override
    public Object call(String name, List<Object> args) {
      return ExprFunctions.call(name, args);
    }
  }
}
