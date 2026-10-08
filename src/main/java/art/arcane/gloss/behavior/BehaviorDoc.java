package art.arcane.gloss.behavior;

import art.arcane.gloss.config.action.CommandActionData;
import art.arcane.gloss.config.action.MenuActionData;
import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.doc.DocumentParsers;
import art.arcane.gloss.enums.MenuActionCommandSource;
import art.arcane.gloss.state.StateSchema;
import art.arcane.gloss.state.StateScope;
import art.arcane.gloss.state.StateType;
import art.arcane.gloss.text.BoundedRegexCompiler;
import com.google.re2j.Pattern;
import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code behaviors/<id>.json}: state declarations and trigger entries. A {@code command} action
 * with {@code source: server} anywhere in the document (nested lists included) needs
 * {@code allowServerCommands: true} at the root, because a timer or a chat line running console
 * commands is console authority handed to whoever can fire the trigger.
 */
public record BehaviorDoc(int schemaVersion, long revision, Boolean enabled, Boolean allowServerCommands,
                          Map<String, StateDeclaration> state, List<BehaviorEntry> on, Matching matching) {
    public static final String KIND = "behaviors";
    public static final int CURRENT_SCHEMA_VERSION = 2;
    public static final int MAX_ENTRIES = 256;

    public BehaviorDoc {
        DocumentEnvelope.requireSchemaVersion(KIND, schemaVersion, CURRENT_SCHEMA_VERSION);
        DocumentEnvelope.requireRevision(KIND, revision);
        enabled = enabled == null || enabled;
        allowServerCommands = allowServerCommands != null && allowServerCommands;
        state = state == null ? Map.of() : copyState(state);
        on = on == null ? List.of() : copyEntries(on, allowServerCommands);
        matching = matching == null ? Matching.DEFAULTS : matching;
        for (int index = 0; index < on.size(); index++) {
            if (on.get(index).pattern() != null) {
                matching.compile(on.get(index).pattern(), "on[" + index + "].pattern");
            }
        }
    }

    public static BehaviorDoc parse(String fileName, String raw) {
        return DocumentParsers.parseJson(fileName, raw, BehaviorDoc.class);
    }

    public List<StateSchema> stateSchemas() {
        List<StateSchema> schemas = new ArrayList<>(state.size());
        for (Map.Entry<String, StateDeclaration> declaration : state.entrySet()) {
            schemas.add(declaration.getValue().schema(declaration.getKey()));
        }
        return schemas;
    }

    private static Map<String, StateDeclaration> copyState(Map<String, StateDeclaration> state) {
        Map<String, StateDeclaration> copy = new LinkedHashMap<>(state.size());
        for (Map.Entry<String, StateDeclaration> declaration : state.entrySet()) {
            if (declaration.getValue() == null) {
                throw new IllegalArgumentException("state." + declaration.getKey() + " must be an object with scope and type");
            }
            declaration.getValue().schema(declaration.getKey());
            copy.put(declaration.getKey(), declaration.getValue());
        }
        return Map.copyOf(copy);
    }

    private static List<BehaviorEntry> copyEntries(List<BehaviorEntry> entries, boolean allowServerCommands) {
        if (entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("a behavior document may declare at most " + MAX_ENTRIES + " entries");
        }
        for (int index = 0; index < entries.size(); index++) {
            BehaviorEntry entry = entries.get(index);
            if (entry == null) {
                throw new IllegalArgumentException("on[" + index + "]: entry must be an object");
            }
            if (!allowServerCommands) {
                refuseServerCommands(entry.actions(), "on[" + index + "].do");
            }
        }
        return List.copyOf(entries);
    }

    private static void refuseServerCommands(List<MenuActionData> actions, String path) {
        for (int index = 0; index < actions.size(); index++) {
            MenuActionData action = actions.get(index);
            if (action == null) {
                continue;
            }
            String actionPath = path + "[" + index + "]";
            if (action instanceof CommandActionData command && command.sourceOrDefault() == MenuActionCommandSource.GLOBAL) {
                throw new IllegalArgumentException(actionPath + " runs a command as the server; set allowServerCommands: true"
                    + " at the document root to allow it");
            }
            List<MenuActionData> nested = action.nestedActions();
            if (!nested.isEmpty()) {
                refuseServerCommands(nested, actionPath);
            }
        }
    }

    public record Matching(String syntax, Integer maxInputCharacters, Integer maxPatternCharacters,
                           Integer maxProgramSize, Integer maxNestingDepth, Integer maxWorkUnits) {
        public static final Matching DEFAULTS = new Matching(null, null, null, null, null, null);

        public Matching {
            syntax = syntax == null ? "re2" : syntax;
            if (!syntax.equals("re2")) {
                throw new IllegalArgumentException("matching.syntax must be re2");
            }
            maxInputCharacters = limit(maxInputCharacters, 1, 32768, 4096, "maxInputCharacters");
            maxPatternCharacters = limit(maxPatternCharacters, 1, 4096, 1024, "maxPatternCharacters");
            maxProgramSize = limit(maxProgramSize, 16, 1000000, 16384, "maxProgramSize");
            maxNestingDepth = limit(maxNestingDepth, 1, 128, 32, "maxNestingDepth");
            maxWorkUnits = limit(maxWorkUnits, 1, 100000000, 2000000, "maxWorkUnits");
        }

        public Pattern compile(String source, String owner) {
            return BoundedRegexCompiler.compile(source,
                new BoundedRegexCompiler.Limits(maxPatternCharacters, maxProgramSize, maxNestingDepth), owner);
        }

        private static int limit(Integer value, int minimum, int maximum, int fallback, String name) {
            int resolved = value == null ? fallback : value;
            if (resolved < minimum || resolved > maximum) {
                throw new IllegalArgumentException("matching." + name + " must be within " + minimum + ".." + maximum);
            }
            return resolved;
        }
    }

    public record StateDeclaration(String scope, String type, @SerializedName("default") Object defaultValue) {
        public StateSchema schema(String key) {
            try {
                if (scope == null) {
                    throw new IllegalArgumentException("scope is required (player, world, or global)");
                }
                if (type == null) {
                    throw new IllegalArgumentException("type is required (number, string, or boolean)");
                }
                return new StateSchema(key, StateScope.parse(scope), StateType.parse(type), defaultValue);
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("state." + key + ": " + invalid.getMessage());
            }
        }
    }
}
