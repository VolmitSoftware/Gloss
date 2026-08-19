package art.arcane.gloss.preview;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.menu.DisplayEntityManager;
import art.arcane.gloss.preview.doc.CompiledPreviewDocument;
import art.arcane.gloss.preview.doc.PreviewDocumentRegistry;
import art.arcane.gloss.preview.doc.PreviewStateContext;
import art.arcane.gloss.service.GlossTelemetry;
import art.arcane.gloss.util.common.DisplayEntity;
import art.arcane.gloss.util.common.PacketUtils;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.volmlib.util.scheduling.SchedulerUtils;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ContainerPreview {

  private static final double VANILLA_TEXT_BLOCKS_PER_PIXEL = 1.0 / 40.0;
  private static final int LAYOUT_PIXELS_PER_BLOCK = 160;
  private static final double SPACE_BACKGROUND_WIDTH_PX = 5.0;
  private static final double SPACE_BACKGROUND_HEIGHT_PX = 10.0;
  private static final double BACKGROUND_CENTER_X_PX = 0.5;
  private static final double BACKGROUND_HALF_HEIGHT_PX = 5.0;
  private static final double TEXT_CENTER_X_PX = 1.0;
  private static final double TEXT_HALF_HEIGHT_PX = 4.5;
  private static final double LABEL_LINE_PX = 9.0;
  private static final double DEPTH_FRACTION_PER_UNIT = 0.04;
  private static final double MIN_DEPTH_SHRINK = 0.5;
  private static final double ITEM_Z_OFFSET = 1.5;
  private static final double COUNT_Z_OFFSET = 3.0;

  private static final int ITEM_PX = 15;
  private static final byte BILLBOARD_FIXED = 0;
  private static final byte ITEM_DISPLAY_CONTEXT = 8;
  private static final int ITEM_BRIGHTNESS = 0xF000F0;
  private static final byte TEXT_FLAGS = 0;
  private static final byte TEXT_OPACITY_VISIBLE = (byte) 0xFF;
  private static final byte TEXT_OPACITY_HIDDEN = 0;

  private static final double COMFORT_DISTANCE = 1.5;
  private static final double HALF_BLOCK = 0.5;
  private static final double EDGE_MARGIN = 0.12;
  private static final double MIN_DISTANCE = 0.12;
  private static final double MIN_SCALE_FACTOR = 0.08;
  private static final double SCALE_EPSILON = 0.02;
  private static final int REFRESH_INTERVAL = 4;
  private static final int ACCESS_RECHECK_INTERVAL = 10;

  private final Player player;
  private final Block block;
  private final Entity entity;
  private final Vector targetCenter;
  private final List<PreviewElement> elements;
  private final boolean showsContents;
  private final List<Rendered> rendered = new ArrayList<>();

  private Location anchor;
  private Location eye;
  private Vector right = new Vector(1, 0, 0);
  private Vector up = new Vector(0, 1, 0);
  private double anchorDistance = COMFORT_DISTANCE;
  private float planeYaw;
  private float planePitch;
  private double scaleTarget = 1.0;
  private double appliedScale = 1.0;
  private int ticks;
  private final AtomicBoolean refreshScheduled = new AtomicBoolean();
  private volatile boolean accessStateMatches = true;
  private boolean open;
  private boolean visualsShown;

  private int viewerScaleRevision = Integer.MIN_VALUE;
  private float viewerScale = 1F;
  private boolean viewerHidden;

  private boolean posePinned;
  private double poseEyeX;
  private double poseEyeY;
  private double poseEyeZ;
  private float poseYaw;
  private float posePitch;
  private double poseScale;

  private ContainerPreview(Player player, Block block, Entity entity, Vector targetCenter,
                           List<PreviewElement> elements, boolean showsContents) {
    this.player = player;
    this.block = block;
    this.entity = entity;
    this.targetCenter = targetCenter;
    this.elements = elements;
    this.showsContents = showsContents;
    for (PreviewElement element : elements) {
      Rendered r = new Rendered(element);
      seed(r);
      this.rendered.add(r);
    }
  }

  public static ContainerPreview forBlock(Block block, Player player) {
    PreviewDocumentRegistry registry = registry();
    if (registry == null) {
      return null;
    }
    CompiledPreviewDocument.Resolved resolved = registry.forBlock(block.getType());
    if (resolved == null) {
      return null;
    }
    List<PreviewElement> elements = resolved.doc().build(PreviewStateContext.forBlock(block, player, resolved.vars()));
    if (elements.isEmpty()) {
      return null;
    }
    Vector center = block.getLocation().toVector().add(new Vector(0.5, 0.5, 0.5));
    return new ContainerPreview(player, block, null, center, elements, true);
  }

  public static ContainerPreview forEnderChest(Block block, Player player, Vector center) {
    PreviewDocumentRegistry registry = registry();
    if (registry == null) {
      return null;
    }
    CompiledPreviewDocument.Resolved resolved = registry.special(PreviewDocumentRegistry.SPECIAL_ENDER_CHEST);
    if (resolved == null) {
      return null;
    }
    List<PreviewElement> elements = resolved.doc().build(
        PreviewStateContext.forInventory(player.getEnderChest(), resolved.vars()));
    if (elements.isEmpty()) {
      return null;
    }
    return new ContainerPreview(player, block, null, center, elements, true);
  }

  public static ContainerPreview forEntity(Entity entity, Player player) {
    PreviewDocumentRegistry registry = registry();
    if (registry == null) {
      return null;
    }
    CompiledPreviewDocument.Resolved resolved = registry.forEntity(entity);
    if (resolved == null) {
      return null;
    }
    List<PreviewElement> elements = resolved.doc().build(PreviewStateContext.forEntity(entity, player, resolved.vars()));
    if (elements.isEmpty()) {
      return null;
    }
    Vector center = entity.getLocation().toVector().add(new Vector(0, Math.max(0.35, entity.getHeight() * 0.5), 0));
    return new ContainerPreview(player, null, entity, center, elements, true);
  }

  public static ContainerPreview locked(Block block, Player player) {
    List<PreviewElement> elements = lockedElements();
    if (elements.isEmpty()) {
      return null;
    }
    Vector center = block.getLocation().toVector().add(new Vector(0.5D, 0.5D, 0.5D));
    return new ContainerPreview(player, block, null, center, elements, false);
  }

  public static ContainerPreview locked(Entity entity, Player player) {
    List<PreviewElement> elements = lockedElements();
    if (elements.isEmpty()) {
      return null;
    }
    Vector center = entity.getLocation().toVector().add(new Vector(0, Math.max(0.35D, entity.getHeight() * 0.5D), 0));
    return new ContainerPreview(player, null, entity, center, elements, false);
  }

  /** Target-less: the locked document only ever draws its own constants, so the scope is statics. */
  private static List<PreviewElement> lockedElements() {
    PreviewDocumentRegistry registry = registry();
    CompiledPreviewDocument.Resolved resolved = registry == null
        ? null
        : registry.special(PreviewDocumentRegistry.SPECIAL_LOCKED);
    return resolved == null ? List.of() : resolved.doc().build(PreviewStateContext.statics(resolved.vars()));
  }

  /** Null before the plugin has enabled, which is the one window a preview can be requested in. */
  private static PreviewDocumentRegistry registry() {
    Gloss plugin = Gloss.instance;
    return plugin == null ? null : plugin.getPreviewRegistry();
  }

  public boolean canView() {
    return ContainerPreviewAccess.isEnabled()
        && accessStateMatches
        && (!showsContents || ContainerPreviewAccess.canView(player));
  }

  public boolean matchesBlock(Block lookingAt) {
    return block != null && lookingAt != null && lookingAt.equals(block);
  }

  public boolean matchesEntity(Entity lookingAt) {
    return entity != null && lookingAt != null && entity.isValid() && lookingAt.getUniqueId().equals(entity.getUniqueId());
  }

  public void open() {
    refreshViewerScale();
    recomputeAnchor();
    appliedScale = scaleTarget;
    if (!viewerHidden) {
      for (Rendered r : rendered) {
        spawn(r);
      }
      pinPose();
      visualsShown = true;
    }
    open = true;
  }

  public boolean tick() {
    if (!open || !canView()) {
      return false;
    }
    boolean checkAccess = ticks % ACCESS_RECHECK_INTERVAL == 0;
    boolean refreshContents = showsContents && ticks % REFRESH_INTERVAL == 0;
    ticks++;
    if (checkAccess || refreshContents) {
      scheduleRefresh(checkAccess);
    }
    refreshViewerScale();
    recomputeAnchor();
    boolean shouldShow = !viewerHidden;
    if (shouldShow != visualsShown) {
      if (shouldShow) {
        appliedScale = scaleTarget;
        for (Rendered r : rendered) {
          spawn(r);
        }
        pinPose();
      } else {
        despawnVisuals();
      }
      visualsShown = shouldShow;
    }
    if (!visualsShown) {
      return true;
    }
    boolean scaleDirty = Math.abs(scaleTarget - appliedScale) > appliedScale * SCALE_EPSILON;
    if (scaleDirty) {
      appliedScale = scaleTarget;
    }
    boolean poseMoved = poseMoved();
    List<PacketWrapper<?>> teleports = poseMoved ? new ArrayList<>(rendered.size() * 3) : null;
    for (Rendered r : rendered) {
      if (teleports != null) {
        reposition(r, teleports);
      }
      if (scaleDirty) {
        rescale(r);
      }
      applyDynamic(r);
    }
    if (teleports != null && !teleports.isEmpty()) {
      PacketUtils.send(player, teleports);
    }
    return true;
  }

  public void refreshVisuals() {
    if (!open) {
      return;
    }
    close();
    open();
  }

  public void close() {
    open = false;
    visualsShown = false;
    despawnVisuals();
  }

  private void despawnVisuals() {
    for (Rendered r : rendered) {
      despawn(r.background);
      despawn(r.item);
      despawn(r.count);
      r.background = null;
      r.item = null;
      r.count = null;
    }
    posePinned = false;
  }

  /**
   * True when anything {@link #at} reads has moved since the layout was last placed.
   *
   * <p>Every element position is a pure function of the viewer's eye pose and the applied scale —
   * {@code recomputeAnchor} derives the anchor, the basis vectors and the facing from the eye
   * alone, and the target centre is fixed for the life of the preview. A viewer who has not moved
   * therefore gets byte-identical coordinates, so skipping the whole reposition pass sends nothing
   * a client would have seen. Comparisons are exact: an ulp of drift is a real move.
   */
  private boolean poseMoved() {
    if (posePinned && eye.getX() == poseEyeX && eye.getY() == poseEyeY && eye.getZ() == poseEyeZ
        && eye.getYaw() == poseYaw && eye.getPitch() == posePitch && appliedScale == poseScale) {
      return false;
    }
    pinPose();
    return true;
  }

  private void pinPose() {
    posePinned = true;
    poseEyeX = eye.getX();
    poseEyeY = eye.getY();
    poseEyeZ = eye.getZ();
    poseYaw = eye.getYaw();
    posePitch = eye.getPitch();
    poseScale = appliedScale;
  }

  /**
   * The viewer's personal scale, re-read only when someone actually adjusted one. The preview loop
   * asks for it twice a tick and the answer changes only on a scroll gesture.
   */
  private void refreshViewerScale() {
    int revision = PreviewScaleService.revision();
    if (revision == viewerScaleRevision) {
      return;
    }
    viewerScaleRevision = revision;
    viewerScale = PreviewScaleService.factor(player);
    viewerHidden = PreviewScaleService.isHidden(player);
  }

  private void seed(Rendered r) {
    switch (r.element) {
      case PreviewElement.Slot slot -> {
        ItemStack stack = slot.inventory().getItem(slot.slot());
        ItemStack initial = isEmpty(stack) ? null : stack.clone();
        r.appliedItem = initial;
        r.pendingItem = initial;
      }
      case PreviewElement.Cell cell -> {
        int color = cell.color().getAsInt();
        r.appliedColor = color;
        r.pendingColor = color;
      }
      case PreviewElement.Label label -> {
        Component text = safe(label.text().get());
        r.appliedText = text;
        r.pendingText = text;
      }
      case PreviewElement.Panel ignored -> {
      }
    }
  }

  private void spawn(Rendered r) {
    switch (r.element) {
      case PreviewElement.Panel panel -> {
        r.backgroundPx = new double[]{panel.x(), panel.y(), panel.z()};
        r.background = spawnBackground(r.backgroundPx, panel.width(), panel.height(), panel.color());
      }
      case PreviewElement.Cell cell -> {
        r.backgroundPx = new double[]{cell.x(), cell.y(), cell.z()};
        r.background = spawnBackground(r.backgroundPx, cell.size(), cell.size(), r.appliedColor);
      }
      case PreviewElement.Slot slot -> {
        r.backgroundPx = new double[]{slot.x(), slot.y(), slot.z()};
        r.background = spawnBackground(r.backgroundPx, slot.size(), slot.size(), slot.wellColor());
        if (r.appliedItem != null) {
          r.itemPx = new double[]{slot.x(), slot.y(), slot.z() + ITEM_Z_OFFSET};
          r.item = spawnItem(r.itemPx, r.appliedItem);
          spawnCountIfNeeded(r, slot);
        }
      }
      case PreviewElement.Label label -> {
        r.backgroundPx = new double[]{label.x(), label.y(), label.z()};
        r.background = spawnText(r.backgroundPx, r.appliedText, label.backgroundColor());
      }
    }
  }

  /**
   * Collects rather than sends. Every element of a moving preview is teleported in the same tick to
   * the same single viewer, so the whole layout goes out as one batched send instead of up to three
   * send calls per element.
   */
  private void reposition(Rendered r, List<PacketWrapper<?>> teleports) {
    if (r.background != null && r.backgroundPx != null) {
      collect(teleports, DisplayEntityManager.goToPacket(r.background, at(r.backgroundPx)));
    }
    if (r.item != null && r.itemPx != null) {
      collect(teleports, DisplayEntityManager.goToPacket(r.item, at(r.itemPx)));
    }
    if (r.count != null && r.countPx != null) {
      collect(teleports, DisplayEntityManager.goToPacket(r.count, at(r.countPx)));
    }
  }

  private static void collect(List<PacketWrapper<?>> teleports, PacketWrapper<?> teleport) {
    if (teleport == null) {
      return;
    }
    teleports.add(teleport);
  }

  private void applyDynamic(Rendered r) {
    switch (r.element) {
      case PreviewElement.Cell ignored -> {
        if (r.background != null && r.pendingColor != r.appliedColor) {
          r.appliedColor = r.pendingColor;
          DisplayEntityManager.changeTextBackground(r.background, r.appliedColor);
        }
      }
      case PreviewElement.Label ignored -> {
        Component pending = r.pendingText;
        if (r.background != null && pending != null && !pending.equals(r.appliedText)) {
          r.appliedText = pending;
          DisplayEntityManager.changeName(r.background, pending);
        }
      }
      case PreviewElement.Slot slot -> applySlot(r, slot);
      case PreviewElement.Panel ignored -> {
      }
    }
  }

  private void applySlot(Rendered r, PreviewElement.Slot slot) {
    ItemStack pending = r.pendingItem;
    boolean pendingEmpty = isEmpty(pending);
    boolean appliedEmpty = r.appliedItem == null;
    if (pendingEmpty && appliedEmpty) {
      return;
    }
    if (pendingEmpty) {
      despawn(r.item);
      despawn(r.count);
      r.item = null;
      r.count = null;
      r.appliedItem = null;
      return;
    }
    if (!pending.equals(r.appliedItem)) {
      r.appliedItem = pending.clone();
      if (r.item == null) {
        r.itemPx = new double[]{slot.x(), slot.y(), slot.z() + ITEM_Z_OFFSET};
        r.item = spawnItem(r.itemPx, r.appliedItem);
      } else {
        DisplayEntityManager.changeItem(r.item, r.appliedItem);
      }
      updateCount(r, slot, r.appliedItem.getAmount());
    }
  }

  /**
   * The in-flight guard is claimed with a compare-and-set: the tick thread arms it while a region
   * or entity thread is the one that clears it, so a plain write could drop a refresh by landing
   * between another thread's read and its reset.
   */
  private void scheduleRefresh(boolean checkAccess) {
    if (!refreshScheduled.compareAndSet(false, true)) {
      return;
    }
    GlossTelemetry.countPreviewRefresh();
    Runnable read = () -> {
      try {
        for (Rendered r : rendered) {
          readDynamic(r);
        }
      } finally {
        refreshScheduled.set(false);
      }
    };
    ContainerPreviewAccess.ViewerAccess access = checkAccess
        ? ContainerPreviewAccess.capture(player)
        : null;
    if (block != null) {
      Runnable blockRead = () -> {
        if (checkAccess) {
          boolean canOpen = ContainerPreviewAccess.canOpen(player, block, access);
          if (canOpen != showsContents) {
            accessStateMatches = false;
            refreshScheduled.set(false);
            return;
          }
        }
        if (!showsContents) {
          refreshScheduled.set(false);
          return;
        }
        if (block.getType() == Material.ENDER_CHEST) {
          if (!SchedulerUtils.runEntity(Gloss.instance, player, read)) {
            refreshScheduled.set(false);
          }
          return;
        }
        read.run();
      };
      if (!FoliaScheduler.runRegion(Gloss.instance, block.getLocation(), blockRead)) {
        if (FoliaScheduler.isFolia(Gloss.instance.getServer())) {
          refreshScheduled.set(false);
        } else {
          blockRead.run();
        }
      }
      return;
    }
    if (entity != null) {
      Runnable entityRead = () -> {
        if (checkAccess) {
          boolean canOpen = ContainerPreviewAccess.canOpen(player, entity, access);
          if (canOpen != showsContents) {
            accessStateMatches = false;
            refreshScheduled.set(false);
            return;
          }
        }
        if (showsContents) {
          read.run();
        } else {
          refreshScheduled.set(false);
        }
      };
      if (!SchedulerUtils.runEntity(Gloss.instance, entity, entityRead)) {
        refreshScheduled.set(false);
      }
      return;
    }
    read.run();
  }

  private void readDynamic(Rendered r) {
    switch (r.element) {
      case PreviewElement.Slot slot -> readSlot(r, slot);
      case PreviewElement.Cell cell -> r.pendingColor = cell.color().getAsInt();
      case PreviewElement.Label label -> r.pendingText = safe(label.text().get());
      case PreviewElement.Panel ignored -> {
      }
    }
  }

  /**
   * A refresh clones a slot only when its contents actually changed. The comparison is the one
   * {@code ItemStack.equals} makes — same amount, same everything else — so an unchanged slot keeps
   * publishing the stack it already published, and {@link #applySlot} still sees an equal value and
   * sends nothing. The previous pending stack is the reference rather than the applied one because
   * this runs on the target's region thread while the applied side belongs to the viewer's.
   */
  private void readSlot(Rendered r, PreviewElement.Slot slot) {
    ItemStack stack = slot.inventory().getItem(slot.slot());
    if (isEmpty(stack)) {
      r.pendingItem = null;
      return;
    }
    ItemStack previous = r.pendingItem;
    if (previous != null && previous.getAmount() == stack.getAmount() && previous.isSimilar(stack)) {
      return;
    }
    r.pendingItem = stack.clone();
  }

  private UUID spawnBackground(double[] px, int width, int height, int color) {
    float shrink = (float) depthShrink(px[2]);
    float scaleX = bgScaleX(width) * shrink;
    float scaleY = bgScaleY(height) * shrink;
    DisplayEntity displayEntity = DisplayEntity.Builder.textDisplay(
        Component.text(" "), at(px), scaleX, scaleY, 1F, BILLBOARD_FIXED, TEXT_FLAGS, color, TEXT_OPACITY_HIDDEN);
    displayEntity.translation(backgroundCenteringTranslation(scaleX, scaleY));
    UUID uuid = DisplayEntityManager.add(displayEntity);
    DisplayEntityManager.spawn(uuid, player);
    return uuid;
  }

  private void rescale(Rendered r) {
    switch (r.element) {
      case PreviewElement.Panel panel -> retransformBackground(r.background, panel.width(), panel.height(), r.backgroundPx[2]);
      case PreviewElement.Cell cell -> retransformBackground(r.background, cell.size(), cell.size(), r.backgroundPx[2]);
      case PreviewElement.Slot slot -> {
        retransformBackground(r.background, slot.size(), slot.size(), r.backgroundPx[2]);
        if (r.item != null) {
          float scale = itemScale() * (float) depthShrink(r.itemPx[2]);
          DisplayEntityManager.changeScale(r.item, scale, scale, scale);
        }
        if (r.count != null) {
          float scale = baseTextScale() * (float) depthShrink(r.countPx[2]);
          DisplayEntityManager.changeTransform(r.count, scale, scale, 1F, textCenteringTranslation(scale));
        }
      }
      case PreviewElement.Label ignored -> {
        float scale = baseTextScale() * (float) depthShrink(r.backgroundPx[2]);
        DisplayEntityManager.changeTransform(r.background, scale, scale, 1F, textCenteringTranslation(scale));
      }
    }
  }

  private void retransformBackground(UUID uuid, int widthPx, int heightPx, double z) {
    float shrink = (float) depthShrink(z);
    float scaleX = bgScaleX(widthPx) * shrink;
    float scaleY = bgScaleY(heightPx) * shrink;
    DisplayEntityManager.changeTransform(uuid, scaleX, scaleY, 1F, backgroundCenteringTranslation(scaleX, scaleY));
  }

  private Vector3f backgroundCenteringTranslation(float scaleX, float scaleY) {
    return new Vector3f(
        (float) (-BACKGROUND_CENTER_X_PX * scaleX * VANILLA_TEXT_BLOCKS_PER_PIXEL),
        (float) (-BACKGROUND_HALF_HEIGHT_PX * scaleY * VANILLA_TEXT_BLOCKS_PER_PIXEL),
        0F);
  }

  private Vector3f textCenteringTranslation(float scale) {
    return new Vector3f(
        (float) (-TEXT_CENTER_X_PX * scale * VANILLA_TEXT_BLOCKS_PER_PIXEL),
        (float) (-TEXT_HALF_HEIGHT_PX * scale * VANILLA_TEXT_BLOCKS_PER_PIXEL),
        0F);
  }

  private float bgScaleX(int widthPx) {
    return (float) (baseTextScale() * (widthPx / SPACE_BACKGROUND_WIDTH_PX));
  }

  private float bgScaleY(int heightPx) {
    return (float) (baseTextScale() * (heightPx / SPACE_BACKGROUND_HEIGHT_PX));
  }

  private float itemScale() {
    return (float) (ITEM_PX * pixel());
  }

  private UUID spawnText(double[] px, Component text, int backgroundColor) {
    float scale = baseTextScale() * (float) depthShrink(px[2]);
    DisplayEntity displayEntity = DisplayEntity.Builder.textDisplay(
        text, at(px), scale, scale, 1F, BILLBOARD_FIXED, TEXT_FLAGS, backgroundColor, TEXT_OPACITY_VISIBLE);
    displayEntity.translation(textCenteringTranslation(scale));
    UUID uuid = DisplayEntityManager.add(displayEntity);
    DisplayEntityManager.spawn(uuid, player);
    return uuid;
  }

  private UUID spawnItem(double[] px, ItemStack stack) {
    float scale = itemScale() * (float) depthShrink(px[2]);
    DisplayEntity displayEntity = DisplayEntity.Builder.itemDisplay(stack, at(px), scale, BILLBOARD_FIXED, ITEM_DISPLAY_CONTEXT);
    displayEntity.brightness(ITEM_BRIGHTNESS);
    UUID uuid = DisplayEntityManager.add(displayEntity);
    DisplayEntityManager.spawn(uuid, player);
    return uuid;
  }

  private void spawnCountIfNeeded(Rendered r, PreviewElement.Slot slot) {
    if (r.appliedItem == null || r.appliedItem.getAmount() <= 1) {
      return;
    }
    updateCount(r, slot, r.appliedItem.getAmount());
  }

  private void updateCount(Rendered r, PreviewElement.Slot slot, int amount) {
    if (amount <= 1) {
      despawn(r.count);
      r.count = null;
      return;
    }
    Component text = Component.text(amount).color(NamedTextColor.WHITE).decorate(TextDecoration.BOLD);
    if (r.count == null) {
      r.countPx = new double[]{slot.x() + slot.size() / 2.0 - 4.0, slot.y() - slot.size() / 2.0 + LABEL_LINE_PX / 2.0, slot.z() + COUNT_Z_OFFSET};
      float scale = baseTextScale() * (float) depthShrink(r.countPx[2]);
      DisplayEntity displayEntity = DisplayEntity.Builder.textDisplay(
          text, at(r.countPx), scale, scale, 1F, BILLBOARD_FIXED, TEXT_FLAGS, 0, TEXT_OPACITY_VISIBLE);
      displayEntity.translation(textCenteringTranslation(scale));
      r.count = DisplayEntityManager.add(displayEntity);
      DisplayEntityManager.spawn(r.count, player);
    } else {
      DisplayEntityManager.changeName(r.count, text);
    }
  }

  private void despawn(UUID uuid) {
    if (uuid != null) {
      DisplayEntityManager.delete(uuid, player);
    }
  }

  private double pixel() {
    return appliedScale / LAYOUT_PIXELS_PER_BLOCK;
  }

  private float baseTextScale() {
    return (float) (pixel() / VANILLA_TEXT_BLOCKS_PER_PIXEL);
  }

  private Location at(double[] px) {
    return at(px[0], px[1], px[2]);
  }

  /**
   * Layout pixel to world position. Written out in components rather than through {@code Vector}
   * temporaries — the reposition pass runs this three times per element per tick — but the
   * arithmetic and its evaluation order are the ones the vector form produced, so the coordinates
   * are bit-identical.
   */
  private Location at(double pxX, double pxY, double pxZ) {
    double unit = pixel();
    double acrossX = pxX * unit;
    double acrossY = pxY * unit;
    double planeX = anchor.getX() + right.getX() * acrossX + up.getX() * acrossY;
    double planeY = anchor.getY() + right.getY() * acrossX + up.getY() * acrossY;
    double planeZ = anchor.getZ() + right.getZ() * acrossX + up.getZ() * acrossY;
    double shrink = depthShrink(pxZ);
    Location projected = eye.clone();
    projected.setX(eye.getX() + (planeX - eye.getX()) * shrink);
    projected.setY(eye.getY() + (planeY - eye.getY()) * shrink);
    projected.setZ(eye.getZ() + (planeZ - eye.getZ()) * shrink);
    projected.setYaw(planeYaw);
    projected.setPitch(planePitch);
    return projected;
  }

  private double depthShrink(double z) {
    return Math.max(MIN_DEPTH_SHRINK, 1.0 - z * DEPTH_FRACTION_PER_UNIT);
  }

  private void recomputeAnchor() {
    Location eye = player.getEyeLocation();
    Vector look = eye.getDirection();
    if (look.lengthSquared() <= 1.0E-6) {
      look = new Vector(0, 0, 1);
    }
    look.normalize();
    Vector worldUp = new Vector(0, 1, 0);
    Vector computedRight = look.clone().crossProduct(worldUp);
    if (computedRight.lengthSquared() <= 1.0E-6) {
      double yaw = Math.toRadians(eye.getYaw());
      computedRight = new Vector(Math.cos(yaw), 0, Math.sin(yaw));
    }
    computedRight.normalize();
    Vector computedUp = computedRight.clone().crossProduct(look);
    if (computedUp.lengthSquared() <= 1.0E-6) {
      computedUp = worldUp;
    }
    computedUp.normalize();

    double distanceToTarget = eye.toVector().distance(targetCenter);
    double surfaceDistance = Math.max(0.0, distanceToTarget - HALF_BLOCK);
    double anchorDistance = Math.max(MIN_DISTANCE, Math.min(COMFORT_DISTANCE, surfaceDistance - EDGE_MARGIN));
    double distanceFactor = Math.max(MIN_SCALE_FACTOR, Math.min(1.0, anchorDistance / COMFORT_DISTANCE));

    this.scaleTarget = GlossConfig.current().previews().scale() * viewerScale * distanceFactor;
    this.right = computedRight;
    this.up = computedUp;
    this.eye = eye;
    this.anchorDistance = anchorDistance;
    this.planeYaw = eye.getYaw() - 180F;
    this.planePitch = -eye.getPitch();
    this.anchor = eye.clone().add(look.clone().multiply(anchorDistance));
    this.anchor.setYaw(0F);
    this.anchor.setPitch(0F);
  }

  private static Component safe(Component component) {
    return component == null ? Component.empty() : component;
  }

  private static boolean isEmpty(ItemStack stack) {
    return stack == null || stack.getType() == Material.AIR || stack.getAmount() < 1;
  }

  private static final class Rendered {
    private final PreviewElement element;
    private UUID background;
    private UUID item;
    private UUID count;
    private double[] backgroundPx;
    private double[] itemPx;
    private double[] countPx;
    private ItemStack appliedItem;
    private int appliedColor;
    private Component appliedText;
    private volatile ItemStack pendingItem;
    private volatile int pendingColor;
    private volatile Component pendingText;

    private Rendered(PreviewElement element) {
      this.element = element;
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ContainerPreview other)) {
      return false;
    }
    return Objects.equals(block, other.block) && Objects.equals(entity, other.entity) && Objects.equals(player, other.player);
  }

  @Override
  public int hashCode() {
    return Objects.hash(player, block, entity);
  }
}
