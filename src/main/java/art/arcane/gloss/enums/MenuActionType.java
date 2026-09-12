package art.arcane.gloss.enums;

import art.arcane.gloss.config.action.ActionBarActionData;
import art.arcane.gloss.config.action.AddStateActionData;
import art.arcane.gloss.config.action.BookActionData;
import art.arcane.gloss.config.action.BossBarActionData;
import art.arcane.gloss.config.action.BroadcastActionData;
import art.arcane.gloss.config.action.CameraActionData;
import art.arcane.gloss.config.action.ChanceActionData;
import art.arcane.gloss.config.action.ClearStateActionData;
import art.arcane.gloss.config.action.CloseActionData;
import art.arcane.gloss.config.action.CommandActionData;
import art.arcane.gloss.config.action.ConfirmActionData;
import art.arcane.gloss.config.action.ConnectActionData;
import art.arcane.gloss.config.action.CooldownActionData;
import art.arcane.gloss.config.action.DelayActionData;
import art.arcane.gloss.config.action.DialogActionData;
import art.arcane.gloss.config.action.EconomyActionData;
import art.arcane.gloss.config.action.EffectActionData;
import art.arcane.gloss.config.action.EmitActionData;
import art.arcane.gloss.config.action.GiveActionData;
import art.arcane.gloss.config.action.GlowActionData;
import art.arcane.gloss.config.action.IfActionData;
import art.arcane.gloss.config.action.InventoryActionData;
import art.arcane.gloss.config.action.MenuActionData;
import art.arcane.gloss.config.action.MessageActionData;
import art.arcane.gloss.config.action.NavigationActionData;
import art.arcane.gloss.config.action.ParallelActionData;
import art.arcane.gloss.config.action.ParticleActionData;
import art.arcane.gloss.config.action.PromptActionData;
import art.arcane.gloss.config.action.RepeatActionData;
import art.arcane.gloss.config.action.RigStateActionData;
import art.arcane.gloss.config.action.SequenceActionData;
import art.arcane.gloss.config.action.SetRigActionData;
import art.arcane.gloss.config.action.SetSessionActionData;
import art.arcane.gloss.config.action.SetStateActionData;
import art.arcane.gloss.config.action.SkyActionData;
import art.arcane.gloss.config.action.SoundActionData;
import art.arcane.gloss.config.action.StopActionData;
import art.arcane.gloss.config.action.SwitchActionData;
import art.arcane.gloss.config.action.TakeActionData;
import art.arcane.gloss.config.action.TeleportActionData;
import art.arcane.gloss.config.action.TitleActionData;
import art.arcane.volmlib.util.json.EnumType;

public enum MenuActionType implements EnumType.Values<MenuActionData> {

  COMMAND("command", CommandActionData.class),
  SOUND("sound", SoundActionData.class),
  MESSAGE("message", MessageActionData.class),
  TELEPORT("teleport", TeleportActionData.class),
  CONNECT("connect", ConnectActionData.class),
  NAVIGATE("navigate", NavigationActionData.class),
  // --- lane:screen ---
  TITLE("title", TitleActionData.class),
  ACTIONBAR("actionbar", ActionBarActionData.class),
  BOSSBAR("bossbar", BossBarActionData.class),

  // --- lane:forms ---
  DIALOG("dialog", DialogActionData.class),
  CONFIRM("confirm", ConfirmActionData.class),
  CLOSE("close", CloseActionData.class),
  INVENTORY("inventory", InventoryActionData.class),
  SET_SESSION("setSession", SetSessionActionData.class),
  PROMPT("prompt", PromptActionData.class),
  BOOK("book", BookActionData.class),
  GIVE("give", GiveActionData.class),
  TAKE("take", TakeActionData.class),
  ECONOMY("economy", EconomyActionData.class),

  // --- lane:rigs ---
  SET_RIG("setRig", SetRigActionData.class),
  RIG_STATE("rigState", RigStateActionData.class),

  // --- lane:behaviors ---
  DELAY("delay", DelayActionData.class),
  SEQUENCE("sequence", SequenceActionData.class),
  PARALLEL("parallel", ParallelActionData.class),
  REPEAT("repeat", RepeatActionData.class),
  IF("if", IfActionData.class),
  SWITCH("switch", SwitchActionData.class),
  CHANCE("chance", ChanceActionData.class),
  COOLDOWN("cooldown", CooldownActionData.class),
  EMIT("emit", EmitActionData.class),
  BROADCAST("broadcast", BroadcastActionData.class),
  EFFECT("effect", EffectActionData.class),
  PARTICLE("particle", ParticleActionData.class),
  STOP("stop", StopActionData.class),
  SET_STATE("setState", SetStateActionData.class),
  ADD_STATE("addState", AddStateActionData.class),
  CLEAR_STATE("clearState", ClearStateActionData.class),

  // --- lane:world ---
  SKY("sky", SkyActionData.class),
  CAMERA("camera", CameraActionData.class),
  GLOW("glow", GlowActionData.class);

  private final String value;
  private final Class<? extends MenuActionData> type;

  MenuActionType(String value, Class<? extends MenuActionData> type) {
    this.value = value;
    this.type = type;
  }

  public String getSerializedName() {
    return value;
  }

  @Override
  public Class<? extends MenuActionData> getType() {
    return type;
  }
}
