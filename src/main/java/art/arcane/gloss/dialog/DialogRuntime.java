package art.arcane.gloss.dialog;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.condition.CompiledCondition;
import art.arcane.gloss.condition.ConditionCompiler;
import art.arcane.gloss.condition.ConditionSource;
import art.arcane.gloss.expr.ExprScope;
import art.arcane.gloss.menu.action.MenuAction;
import art.arcane.gloss.menu.icon.IconItems;
import art.arcane.gloss.text.TextPipeline;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * A compiled dialog document: its conditions and action lists resolved once, and one
 * {@link Rendered} produced per open. Rendering is per viewer because every text field runs through
 * the pipeline, so two players can be looking at the same document and different words.
 */
public final class DialogRuntime {

    private final String id;
    private final DialogDoc doc;
    private final CompiledCondition selection;
    private final Presentation base;
    private final List<Variant> variants;
    private final List<MenuAction<?>> exitActions;

    private DialogRuntime(String id, DialogDoc doc, CompiledCondition selection, Presentation base,
                          List<Variant> variants, List<MenuAction<?>> exitActions) {
        this.id = id;
        this.doc = doc;
        this.selection = selection;
        this.base = base;
        this.variants = variants;
        this.exitActions = exitActions;
    }

    public static DialogRuntime compile(String id, DialogDoc doc) {
        List<DialogButton> baseButtons = presentationButtons(doc);
        Presentation base = new Presentation(doc.title(), doc.body(), baseButtons, compile(id, "base", baseButtons));
        List<Variant> variants = new ArrayList<>(doc.variants().size());
        for (DialogDoc.Variant variant : doc.variants()) {
            DialogDoc.Presentation presentation = variant.presentation();
            List<DialogButton> buttons = doc.type() == DialogType.CONFIRMATION ? baseButtons : presentation.buttons();
            variants.add(new Variant(variant.id(), variant.priority(),
                ConditionCompiler.compile(new ConditionSource("dialogs." + id + ".variants." + variant.id(), variant.when())),
                new Presentation(presentation.title(), presentation.body(), buttons,
                    compile(id, "variant:" + variant.id(), buttons))));
        }
        variants.sort(Comparator.comparingInt(Variant::priority).reversed().thenComparing(Variant::id));
        List<MenuAction<?>> exitActions = doc.exit() == null
            ? List.of()
            : MenuAction.resolve(doc.exit().actions(), "dialog:" + id, "button:exit");
        CompiledCondition selection = ConditionCompiler.compile(
            new ConditionSource("dialogs." + id + ".select.when", doc.select().when()));
        return new DialogRuntime(id, doc, selection, base, List.copyOf(variants), exitActions);
    }

    public String id() {
        return id;
    }

    public DialogDoc doc() {
        return doc;
    }

    public int selectionPriority() {
        return doc.select().priority();
    }

    /** True when this document offers itself to the viewer through its {@code select} block. */
    public boolean selects(ExprScope scope) {
        return selection.matches(scope);
    }

    /** True when the document's own {@code show} allows the viewer to see it at all. */
    public boolean shown(Player viewer) {
        return doc.show().matches(Gloss.instance, viewer);
    }

    public Rendered render(Player viewer, Map<String, Object> args) {
        Map<String, Object> arguments = args == null ? Map.of() : Map.copyOf(args);
        ExprScope scope = new DialogScope(viewer, arguments);
        Presentation presentation = select(viewer, scope);
        List<Rendered.Body> body = new ArrayList<>(presentation.body().size());
        for (DialogBody block : presentation.body()) {
            body.add(renderBody(viewer, scope, block));
        }
        List<Rendered.Input> inputs = new ArrayList<>(doc.inputs().size());
        for (DialogInput input : doc.inputs()) {
            inputs.add(new Rendered.Input(input, render(viewer, scope, input.label())));
        }
        List<Rendered.Button> buttons = new ArrayList<>(presentation.buttons().size());
        for (int index = 0; index < presentation.buttons().size(); index++) {
            DialogButton button = presentation.buttons().get(index);
            buttons.add(new Rendered.Button(index, render(viewer, scope, button.label()),
                button.tooltip() == null ? null : render(viewer, scope, button.tooltip()),
                button.width(), presentation.actions().get(index)));
        }
        Rendered.Button exit = doc.exit() == null ? null : new Rendered.Button(buttons.size(),
            render(viewer, scope, doc.exit().label()),
            doc.exit().tooltip() == null ? null : render(viewer, scope, doc.exit().tooltip()),
            doc.exit().width(), exitActions);
        return new Rendered(id, doc.type(), render(viewer, scope, presentation.title()),
            doc.externalTitle() == null ? null : render(viewer, scope, doc.externalTitle()),
            doc.canCloseWithEscape(), doc.pause(), doc.afterAction(),
            List.copyOf(body), List.copyOf(inputs), List.copyOf(buttons), exit,
            doc.columns(), doc.buttonWidth(), doc.dialogs(), doc.fallback(), arguments);
    }

    private Presentation select(Player viewer, ExprScope scope) {
        for (Variant variant : variants) {
            if (variant.when().matches(scope)) {
                return variant.presentation();
            }
        }
        return base;
    }

    private Rendered.Body renderBody(Player viewer, ExprScope scope, DialogBody block) {
        if (!block.isItem()) {
            return new Rendered.Body(false, render(viewer, scope, block.text()), block.width(), 0, null,
                null, true, true);
        }
        ItemStack stack = IconItems.resolve(block.item(), viewer);
        String description = block.description() == null ? null : render(viewer, scope, block.description());
        return new Rendered.Body(true, null, block.width(), block.height(), stack, description,
            block.showTooltipOrDefault(), block.showDecorationsOrDefault());
    }

    private static List<DialogButton> presentationButtons(DialogDoc doc) {
        if (doc.type() == DialogType.CONFIRMATION) {
            return List.of(doc.yes(), doc.no());
        }
        return doc.buttons();
    }

    private static List<List<MenuAction<?>>> compile(String id, String owner, List<DialogButton> buttons) {
        List<List<MenuAction<?>>> compiled = new ArrayList<>(buttons.size());
        for (int index = 0; index < buttons.size(); index++) {
            compiled.add(MenuAction.resolve(buttons.get(index).actions(), "dialog:" + id, owner + "/button:" + index));
        }
        return List.copyOf(compiled);
    }

    private static String render(Player viewer, ExprScope scope, String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        Gloss plugin = Gloss.instance;
        TextPipeline pipeline = plugin == null ? null : plugin.text();
        if (pipeline == null) {
            return raw;
        }
        return pipeline.renderScoped(viewer, raw, scope, UnaryOperator.identity());
    }

    /** One presentation's already-compiled parts; a variant swaps the whole record. */
    private record Presentation(String title, List<DialogBody> body, List<DialogButton> buttons,
                                List<List<MenuAction<?>>> actions) {
    }

    private record Variant(String id, int priority, CompiledCondition when, Presentation presentation) {
    }

    /**
     * One dialog as a viewer will see it: every text already through the pipeline, every button
     * carrying the compiled action list its click index will run.
     */
    public record Rendered(String id, DialogType type, String title, String externalTitle,
                           boolean canCloseWithEscape, boolean pause, String afterAction,
                           List<Body> body, List<Input> inputs, List<Button> buttons, Button exit,
                           int columns, int buttonWidth, List<String> dialogs,
                           DialogDoc.Fallback fallback, Map<String, Object> args) {

        /** The button at a click index, or null when the client sent an index this render has no button for. */
        public Button button(int index) {
            if (exit != null && index == exit.index()) {
                return exit;
            }
            return index >= 0 && index < buttons.size() ? buttons.get(index) : null;
        }

        public record Body(boolean item, String text, int width, int height, ItemStack itemStack,
                           String description, boolean showTooltip, boolean showDecorations) {
        }

        public record Input(DialogInput source, String label) {
        }

        public record Button(int index, String label, String tooltip, int width, List<MenuAction<?>> actions) {
        }
    }
}
