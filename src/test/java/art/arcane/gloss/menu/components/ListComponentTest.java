package art.arcane.gloss.menu.components;

import art.arcane.gloss.config.MenuComponentData;
import art.arcane.gloss.config.components.ButtonComponentData;
import art.arcane.gloss.config.components.ListComponentData;
import art.arcane.gloss.config.icon.TextIconData;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.menu.ExpandedMenuComponent;
import art.arcane.gloss.menu.MenuExpressions;
import art.arcane.gloss.expr.ExprParser;
import art.arcane.gloss.expr.ExprEvaluator;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a list turns one template into a grid of real components. Every entry that appears is an
 * entity spawn on the viewer's screen, so the cap and the page size are what keep a bad source
 * expression from becoming a bad tick.
 */
class ListComponentTest {

    @Test
    void entriesFlowLeftToRightThenDown() {
        List<ExpandedMenuComponent> expanded = expand(entries("a", "b", "c", "d", "e"),
            new ListComponentData.Flow(2, 1.0F, 0.5F, -0.5F, 1.0F), 6, 0, 64);

        assertEquals(5, expanded.size());
        assertEquals(new Vector(-0.5D, 1.0D, 0.0D), expanded.get(0).data().offset());
        assertEquals(new Vector(0.5D, 1.0D, 0.0D), expanded.get(1).data().offset());
        assertEquals(new Vector(-0.5D, 0.5D, 0.0D), expanded.get(2).data().offset());
        assertEquals(new Vector(0.5D, 0.5D, 0.0D), expanded.get(3).data().offset());
        assertEquals(new Vector(-0.5D, 0.0D, 0.0D), expanded.get(4).data().offset());
    }

    @Test
    void everyExpandedComponentHasItsOwnId() {
        List<ExpandedMenuComponent> expanded = expand(entries("a", "b", "c"), flow(), 6, 0, 64);
        List<String> ids = expanded.stream().map(entry -> entry.data().id()).toList();
        assertEquals(3, ids.size());
        assertEquals(3, ids.stream().distinct().count());
        assertTrue(ids.getFirst().startsWith("items"), ids.getFirst());
    }

    @Test
    void aPageShowsOnlyItsOwnSlice() {
        List<Object> source = entries("a", "b", "c", "d", "e");
        assertEquals(2, expand(source, flow(), 2, 0, 64).size());
        assertEquals(2, expand(source, flow(), 2, 1, 64).size());
        assertEquals(1, expand(source, flow(), 2, 2, 64).size());
        assertEquals(0, expand(source, flow(), 2, 9, 64).size());
    }

    @Test
    void theHardCapWinsOverThePageSize() {
        List<Object> source = new ArrayList<>();
        for (int index = 0; index < 200; index++) {
            source.add("entry-" + index);
        }
        assertEquals(4, expand(source, flow(), 128, 0, 4).size());
    }

    @Test
    void aSourceThatIsNotAListExpandsToNothing() {
        ListComponentData data = listData(flow(), 6);
        assertEquals(List.of(), ListComponent.expand(component(data), scopeOf("not a list"), 0, 64));
    }

    @Test
    void entryTextAndActionExpressionsKeepTheirOwnContext() {
        List<ExpandedMenuComponent> expanded = expand(entries("Oak", "Birch", "Spruce"), flow(), 2, 0, 64);
        assertEquals("Oak", MenuExpressions.substitute("{{item}}", expanded.get(0).scope()));
        assertEquals("Birch", MenuExpressions.substitute("{{item}}", expanded.get(1).scope()));
        assertEquals("buy Birch", MenuExpressions.substitute("buy {{item}}", expanded.get(1).scope()));
        assertEquals(true, ExprEvaluator.eval(ExprParser.parse("item == 'Birch'"), expanded.get(1).scope()));
        assertEquals(false, ExprEvaluator.eval(ExprParser.parse("item == 'Birch'"), expanded.get(0).scope()));
        List<ExpandedMenuComponent> secondPage = expand(entries("Oak", "Birch", "Spruce"), flow(), 2, 1, 64);
        assertEquals("Spruce", MenuExpressions.substitute("{{item}}", secondPage.getFirst().scope()));
        assertEquals("items[2]", secondPage.getFirst().data().id());
    }

    private static List<ExpandedMenuComponent> expand(List<Object> source, ListComponentData.Flow flow,
                                                  int pageSize, int page, int cap) {
        return ListComponent.expand(component(listData(flow, pageSize)), scopeOf(source), page, cap);
    }

    private static List<Object> entries(String... values) {
        return List.of((Object[]) values);
    }

    private static ListComponentData.Flow flow() {
        return new ListComponentData.Flow(3, 1.0F, 0.5F, 0.0F, 0.0F);
    }

    private static ListComponentData listData(ListComponentData.Flow flow, int pageSize) {
        ButtonComponentData template = new ButtonComponentData(0F, List.of(),
            new TextIconData("&f{{ item }}", null, null, null), null, null, null, null);
        return new ListComponentData("item", "entries", pageSize, flow, template);
    }

    private static MenuComponentData component(ListComponentData data) {
        return new MenuComponentData("items", new Vector(), data, null);
    }

    private static ExprScope scopeOf(Object entries) {
        return new ExprScope() {
            @Override
            public Object variable(String dottedName) {
                return "entries".equals(dottedName) ? entries : null;
            }

            @Override
            public Object call(String name, List<Object> args) {
                return null;
            }
        };
    }
}
