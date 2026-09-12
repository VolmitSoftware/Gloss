package art.arcane.gloss.api;

import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import org.bukkit.util.Vector;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * One end of a world-scope particle geometry: a fixed position, a role the renderer knows
 * ({@code viewer}, {@code subject}) or an entity to follow. Existing local-frame documents write
 * the {@code [x, y, z]} array form, which still reads back as a position, so nothing an operator
 * already authored changes meaning.
 */
@JsonAdapter(ParticleAnchor.Adapter.class)
public record ParticleAnchor(Vector position, String role, UUID entity) {
    public static final Set<String> ROLES = Set.of("viewer", "subject");

    private static final String ENTITY_FIELD = "entity";

    public ParticleAnchor {
        position = position == null ? null : position.clone();
        role = role == null ? null : role.trim().toLowerCase(Locale.ROOT);
        int given = (position == null ? 0 : 1) + (role == null ? 0 : 1) + (entity == null ? 0 : 1);
        if (given != 1) {
            throw new IllegalArgumentException(
                "a particle anchor is exactly one of a position, a role or an entity");
        }
        if (role != null && !ROLES.contains(role)) {
            throw new IllegalArgumentException("a particle anchor role must be one of "
                + String.join(", ", ROLES));
        }
    }

    public static ParticleAnchor of(Vector position) {
        return position == null ? null : new ParticleAnchor(position, null, null);
    }

    public static ParticleAnchor role(String role) {
        return new ParticleAnchor(null, role, null);
    }

    public static ParticleAnchor entity(UUID entity) {
        return new ParticleAnchor(null, null, entity);
    }

    @Override
    public Vector position() {
        return position == null ? null : position.clone();
    }

    public boolean isPosition() {
        return position != null;
    }

    static final class Adapter extends TypeAdapter<ParticleAnchor> {
        @Override
        public ParticleAnchor read(JsonReader reader) throws IOException {
            JsonToken token = reader.peek();
            if (token == JsonToken.NULL) {
                reader.nextNull();
                return null;
            }
            if (token == JsonToken.STRING) {
                return role(reader.nextString());
            }
            if (token == JsonToken.BEGIN_ARRAY) {
                reader.beginArray();
                double x = reader.nextDouble();
                double y = reader.nextDouble();
                double z = reader.nextDouble();
                reader.endArray();
                return of(new Vector(x, y, z));
            }
            if (token != JsonToken.BEGIN_OBJECT) {
                throw new IllegalArgumentException("a particle anchor is [x, y, z], a role name or { entity }");
            }
            reader.beginObject();
            UUID entity = null;
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (name.equals(ENTITY_FIELD)) {
                    entity = UUID.fromString(reader.nextString());
                } else {
                    reader.skipValue();
                }
            }
            reader.endObject();
            if (entity == null) {
                throw new IllegalArgumentException("a particle anchor object requires an entity uuid");
            }
            return entity(entity);
        }

        @Override
        public void write(JsonWriter writer, ParticleAnchor anchor) throws IOException {
            if (anchor == null) {
                writer.nullValue();
                return;
            }
            if (anchor.role() != null) {
                writer.value(anchor.role());
                return;
            }
            if (anchor.entity() != null) {
                writer.beginObject().name(ENTITY_FIELD).value(anchor.entity().toString()).endObject();
                return;
            }
            Vector position = anchor.position();
            writer.beginArray().value(position.getX()).value(position.getY())
                .value(position.getZ()).endArray();
        }
    }
}
