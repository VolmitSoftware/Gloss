package art.arcane.gloss.rig;

public interface RigScope {
    String instanceId();

    String state();

    Object var(String name);
}
