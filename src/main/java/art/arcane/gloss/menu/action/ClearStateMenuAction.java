package art.arcane.gloss.menu.action;

import art.arcane.gloss.config.action.ClearStateActionData;
import art.arcane.gloss.state.StateSchema;
import art.arcane.gloss.state.StateStore;

import java.util.UUID;

public final class ClearStateMenuAction extends MenuAction<ClearStateActionData> {
  public ClearStateMenuAction(ClearStateActionData data) {
    super(data);
  }

  @Override
  public ActionOutcome execute(ActionContext context) {
    StateStore store = StateWrites.store(data.key(), context);
    if (store == null) {
      return ActionOutcome.CONTINUE;
    }
    StateSchema schema = store.declarations().get(data.key());
    UUID owner = StateWrites.owner(store, data.key(), data.target(), context);
    if (StateWrites.missingOwner(schema, owner)) {
      return ActionOutcome.CONTINUE;
    }
    store.clear(schema.scope(), owner, data.key());
    return ActionOutcome.CONTINUE;
  }

  @Override
  protected boolean requiresPlayer() {
    return false;
  }
}
