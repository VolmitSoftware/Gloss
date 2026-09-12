package art.arcane.gloss.strings;

import art.arcane.gloss.expr.ExprException;
import art.arcane.gloss.expr.ExprFunctionRegistry;
import art.arcane.gloss.expr.ExprFunctions;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.expr.ExprVariableContext;
import art.arcane.gloss.locale.GlossMessages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LangFunctionTest {
    private static final StringsCatalog CATALOG = StringsCatalog.of(List.of(
        new StringsDoc(StringsDoc.CURRENT_SCHEMA_VERSION, 1L, "en_US", "",
            Map.of("shop.title", "Shop", "shop.buy", "Buy {item}"))));

    @AfterEach
    void unregister() {
        ExprFunctionRegistry.global().unregister(LangFunction.NAME);
    }

    @Test
    void anAuthoredStringResolvesThroughTheRegistry() {
        register();

        assertEquals("Shop", ExprFunctionRegistry.global().call(new TestScope(null), "lang",
            List.of("shop.title")));
        assertEquals("Buy Iron Ore", ExprFunctionRegistry.global().call(new TestScope(null), "lang",
            List.of("shop.buy", "Iron Ore")));
    }

    @Test
    void aKeyNoCatalogKnowsRendersAsItself() {
        register();

        assertEquals("shop.not.authored", ExprFunctionRegistry.global().call(new TestScope(null), "lang",
            List.of("shop.not.authored")));
    }

    @Test
    void thePluginCatalogAnswersBeforeTheKeyItself() {
        register();

        assertEquals(GlossMessages.THEME_TITLE_BARREL.english(),
            ExprFunctionRegistry.global().call(new TestScope(null), "lang",
                List.of(GlossMessages.THEME_TITLE_BARREL.id())));
    }

    @Test
    void theViewerComesFromTheScopeVariableContext() {
        UUID viewerId = UUID.randomUUID();
        ExprFunctionRegistry.global().register(LangFunction.spec(
            (id, key, args) -> String.valueOf(id)));

        assertEquals(viewerId.toString(), ExprFunctionRegistry.global()
            .call(new TestScope(viewerId), "lang", List.of("shop.title")));
    }

    @Test
    void aNonStringKeyIsRefused() {
        register();

        assertThrows(ExprException.class, () -> ExprFunctionRegistry.global()
            .call(new TestScope(null), "lang", List.of(1.0D)));
    }

    @Test
    void theSpecIsVariadicOverOneRequiredStringKey() {
        register();
        ExprFunctionRegistry.Spec spec = ExprFunctionRegistry.global().find("lang");

        assertEquals(ExprFunctionRegistry.Kind.STRING, spec.returns());
        assertEquals(ExprFunctionRegistry.Kind.STRING, spec.parameter(0));
        assertEquals(ExprFunctionRegistry.Kind.ANY, spec.parameter(3));
        assertNull(ExprFunctionRegistry.global().find("lang.missing"));
    }

    private static void register() {
        ExprFunctionRegistry.global().register(LangFunction.spec(
            (viewerId, key, args) -> StringsService.resolve(CATALOG, "en_US", viewerId, key, args)));
    }

    /** A scope that knows nothing but the viewer the registry function asks it for. */
    private record TestScope(UUID viewerId) implements ExprScope {
        @Override
        public Object variable(String dottedName) {
            return null;
        }

        @Override
        public Object call(String name, List<Object> args) {
            return ExprFunctions.call(name, args);
        }

        @Override
        public ExprVariableContext variableContext() {
            return viewerId == null ? ExprVariableContext.empty() : new PlayerlessContext(viewerId).context();
        }
    }

    /**
     * The registry function reads {@code viewer().getUniqueId()}; the headless scope hands it a
     * player proxy that answers only that.
     */
    private record PlayerlessContext(UUID viewerId) {
        ExprVariableContext context() {
            org.bukkit.entity.Player viewer = (org.bukkit.entity.Player) java.lang.reflect.Proxy.newProxyInstance(
                LangFunctionTest.class.getClassLoader(),
                new Class<?>[]{org.bukkit.entity.Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> viewerId;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "Player[" + viewerId + "]";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
            return new ExprVariableContext(viewer, null, null, null);
        }
    }
}
