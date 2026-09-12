package art.arcane.gloss.bedrock;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.dialog.DialogRuntime;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Geyser's Cumulus forms, reached entirely by reflection through the Geyser plugin's own
 * classloader. Gloss never compiles against Geyser, so a server without it links nothing and a
 * Geyser that renames something fails soft: {@link #send} returns false and the dialog falls back.
 */
public final class CumulusForms implements BedrockForms {
    private static final String GEYSER_PLUGIN = "Geyser-Spigot";
    private static final String GEYSER_API = "org.geysermc.geyser.api.GeyserApi";
    private static final String SIMPLE_FORM = "org.geysermc.cumulus.form.SimpleForm";
    private static final String CUSTOM_FORM = "org.geysermc.cumulus.form.CustomForm";
    private static final String FORM = "org.geysermc.cumulus.form.Form";

    private final Api api;

    public CumulusForms() {
        this.api = Api.load();
    }

    public boolean available() {
        return api != null;
    }

    @Override
    public boolean send(Player viewer, DialogRuntime.Rendered rendered,
                        Consumer<Map<String, Object>> onSubmit, Runnable onClose) {
        if (api == null || viewer == null) {
            return false;
        }
        FormSpec spec = FormsMapping.map(rendered);
        try {
            Object form = spec instanceof FormSpec.Simple simple
                ? buildSimple(simple, onSubmit, onClose)
                : buildCustom((FormSpec.Custom) spec, onSubmit, onClose);
            if (form == null) {
                return false;
            }
            api.sendForm(viewer, form);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError drift) {
            Gloss.logExceptionStackThrottled(false, "cumulus-forms", drift,
                "The Geyser forms API did not behave as expected; Bedrock dialogs use their fallback.");
            return false;
        }
    }

    private Object buildSimple(FormSpec.Simple spec, Consumer<Map<String, Object>> onSubmit, Runnable onClose)
        throws ReflectiveOperationException {
        Object builder = api.builder(SIMPLE_FORM);
        invoke(builder, "title", spec.title());
        if (!spec.content().isEmpty()) {
            invoke(builder, "content", spec.content());
        }
        for (String button : spec.buttons()) {
            invoke(builder, "button", button);
        }
        List<Integer> indexes = spec.buttonIndexes();
        api.validResultHandler(builder, response -> {
            int chosen = api.simpleResponseIndex(response);
            if (chosen >= 0 && chosen < indexes.size()) {
                onSubmit.accept(Map.of(BUTTON, indexes.get(chosen)));
            }
        });
        api.closedResultHandler(builder, onClose);
        return api.build(builder);
    }

    private Object buildCustom(FormSpec.Custom spec, Consumer<Map<String, Object>> onSubmit, Runnable onClose)
        throws ReflectiveOperationException {
        Object builder = api.builder(CUSTOM_FORM);
        invoke(builder, "title", spec.title());
        List<FormSpec.Control> controls = spec.controls();
        for (FormSpec.Control control : controls) {
            addControl(builder, control);
        }
        List<Integer> indexes = spec.buttonIndexes();
        api.validResultHandler(builder, response -> onSubmit.accept(answers(response, controls, indexes)));
        api.closedResultHandler(builder, onClose);
        return api.build(builder);
    }

    private void addControl(Object builder, FormSpec.Control control) throws ReflectiveOperationException {
        switch (control) {
            case FormSpec.Input input -> invoke(builder, "input", input.label(), "", input.initial());
            case FormSpec.Toggle toggle -> invoke(builder, "toggle", toggle.label(), toggle.initial());
            case FormSpec.Slider slider -> invoke(builder, "slider", slider.label(), slider.min(),
                slider.max(), slider.step(), slider.initial());
            case FormSpec.Dropdown dropdown -> invoke(builder, "dropdown", dropdown.label(),
                dropdown.options(), dropdown.initialIndex());
        }
    }

    private Map<String, Object> answers(Object response, List<FormSpec.Control> controls,
                                        List<Integer> indexes) {
        Map<String, Object> answers = new HashMap<>(controls.size() + 1);
        answers.put(BUTTON, indexes.isEmpty() ? 0 : indexes.getFirst());
        for (int index = 0; index < controls.size(); index++) {
            FormSpec.Control control = controls.get(index);
            Object value = api.customResponseValue(response, index);
            answers.put(control.key(), normalize(control, value));
        }
        return Map.copyOf(answers);
    }

    private static Object normalize(FormSpec.Control control, Object value) {
        return switch (control) {
            case FormSpec.Toggle ignored -> Boolean.TRUE.equals(value);
            case FormSpec.Slider ignored -> value instanceof Number number ? number.doubleValue() : 0D;
            case FormSpec.Dropdown dropdown -> {
                int chosen = value instanceof Number number ? number.intValue() : dropdown.initialIndex();
                yield chosen >= 0 && chosen < dropdown.ids().size() ? dropdown.ids().get(chosen) : "";
            }
            case FormSpec.Input ignored -> value == null ? "" : String.valueOf(value);
        };
    }

    private static void invoke(Object target, String method, Object... args) throws ReflectiveOperationException {
        for (Method candidate : target.getClass().getMethods()) {
            if (!candidate.getName().equals(method) || candidate.getParameterCount() != args.length) {
                continue;
            }
            candidate.setAccessible(true);
            candidate.invoke(target, args);
            return;
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + method + "/" + args.length);
    }

    /** The reflective handle on the Geyser API, or null when Geyser is not installed. */
    private record Api(Object geyser, ClassLoader loader, Method sendForm) {
        private static Api load() {
            Plugin plugin = enabledPlugin();
            if (plugin == null) {
                return null;
            }
            try {
                ClassLoader loader = plugin.getClass().getClassLoader();
                Class<?> apiType = Class.forName(GEYSER_API, true, loader);
                Object geyser = apiType.getMethod("api").invoke(null);
                Class<?> formType = Class.forName(FORM, true, loader);
                Method send = geyser.getClass().getMethod("sendForm", java.util.UUID.class, formType);
                send.setAccessible(true);
                return new Api(geyser, loader, send);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError absent) {
                return null;
            }
        }

        private static Plugin enabledPlugin() {
            if (Bukkit.getServer() == null) {
                return null;
            }
            Plugin plugin = Bukkit.getPluginManager().getPlugin(GEYSER_PLUGIN);
            return plugin == null || !plugin.isEnabled() ? null : plugin;
        }

        private Object builder(String formClass) throws ReflectiveOperationException {
            return Class.forName(formClass, true, loader).getMethod("builder").invoke(null);
        }

        private Object build(Object builder) throws ReflectiveOperationException {
            return builder.getClass().getMethod("build").invoke(builder);
        }

        private void validResultHandler(Object builder, Consumer<Object> handler)
            throws ReflectiveOperationException {
            Class<?> consumerType = Class.forName("java.util.function.Consumer");
            Object proxy = Proxy.newProxyInstance(loader, new Class<?>[]{consumerType},
                (InvocationHandler) (ignored, method, args) -> {
                    if (method.getName().equals("accept")) {
                        handler.accept(args[0]);
                    }
                    return null;
                });
            invoke(builder, "validResultHandler", proxy);
        }

        /** Optional on older Cumulus builds; a form without one simply cannot report a dismissal. */
        private void closedResultHandler(Object builder, Runnable onClose) {
            if (onClose == null) {
                return;
            }
            try {
                Object proxy = Proxy.newProxyInstance(loader, new Class<?>[]{Runnable.class},
                    (InvocationHandler) (ignored, method, args) -> {
                        if (method.getName().equals("run")) {
                            onClose.run();
                        }
                        return null;
                    });
                invoke(builder, "closedResultHandler", proxy);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError absent) {
                return;
            }
        }

        private int simpleResponseIndex(Object response) {
            try {
                return (Integer) response.getClass().getMethod("clickedButtonId").invoke(response);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError drift) {
                return -1;
            }
        }

        private Object customResponseValue(Object response, int index) {
            List<String> candidates = new ArrayList<>(List.of("valueAt", "asInput", "next"));
            for (String name : candidates) {
                try {
                    Method method = response.getClass().getMethod(name, int.class);
                    return method.invoke(response, index);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError missing) {
                    continue;
                }
            }
            return null;
        }

        private void sendForm(Player viewer, Object form) throws ReflectiveOperationException {
            sendForm.invoke(geyser, viewer.getUniqueId(), form);
        }
    }
}
