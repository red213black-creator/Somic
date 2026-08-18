package cc.silk.module.modules.combat;

import cc.silk.event.impl.player.TickEvent;
import cc.silk.event.impl.world.WorldChangeEvent;
import cc.silk.mixin.MinecraftClientAccessor;
import cc.silk.module.Category;
import cc.silk.module.Module;
import cc.silk.module.modules.misc.Teams;
import cc.silk.module.setting.BooleanSetting;
import cc.silk.module.setting.ModeSetting;
import cc.silk.module.setting.RangeSetting;
import cc.silk.utils.friend.FriendManager;
import cc.silk.utils.math.MathUtils;
import cc.silk.utils.math.TimerUtil;
import cc.silk.utils.mc.CombatUtil;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.Tameable;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.WindChargeEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.item.Item;
import net.minecraft.item.SwordItem;
import net.minecraft.util.hit.EntityHitResult;
import org.lwjgl.glfw.GLFW;

public final class TriggerBot extends Module {

    public static final RangeSetting swordThreshold =
            new RangeSetting("Sword Threshold", 0.1, 1, 0.90, 0.95, 0.01);

    public static final RangeSetting axeThreshold =
            new RangeSetting("Axe Threshold", 0.1, 1, 0.90, 0.95, 0.01);

    public static final RangeSetting axePostDelay =
            new RangeSetting("Axe Post Delay", 1, 500, 120, 120, 0.5);

    public static final RangeSetting reactionTime =
            new RangeSetting("Reaction Time", 1, 350, 20, 95, 0.5);

    public static final ModeSetting cooldownMode =
            new ModeSetting("Cooldown Mode", "Smart", "Smart", "Strict", "None");

    /*
     * Off:
     * обычный TriggerBot.
     *
     * Only Crits:
     * атака только когда игрок находится в настоящем критическом состоянии.
     *
     * Smart Crits:
     * если игрок уже находится в критическом состоянии - атакует;
     * если нет - может выполнить обычную атаку после готовности cooldown.
     */
    public static final ModeSetting critMode =
            new ModeSetting(
                    "Crit Mode",
                    "Only Crits",
                    "Off",
                    "Only Crits",
                    "Smart Crits"
            );

    public static final BooleanSetting ignorePassiveMobs =
            new BooleanSetting("No Passive", true);

    public static final BooleanSetting ignoreInvisible =
            new BooleanSetting("No Invisible", true);

    public static final BooleanSetting ignoreCrystals =
            new BooleanSetting("No Crystals", true);

    public static final BooleanSetting respectShields =
            new BooleanSetting("Ignore Shields", false);

    public static final BooleanSetting useOnlySwordOrAxe =
            new BooleanSetting("Only Sword or Axe", true);

    public static final BooleanSetting onlyWhenMouseDown =
            new BooleanSetting("Only Mouse Hold", false);

    public static final BooleanSetting disableOnWorldChange =
            new BooleanSetting("Disable on Load", false);

    public static final BooleanSetting samePlayer =
            new BooleanSetting("Same Player", false);

    private final TimerUtil reactionTimer = new TimerUtil();
    private final TimerUtil axeTimer = new TimerUtil();

    private Entity target;

    private String lastTargetUUID = null;

    private boolean waitingForReaction = false;

    private long currentReactionDelay = 0L;

    /*
     * Threshold выбирается один раз для текущего цикла атаки.
     * Он НЕ меняется каждый тик.
     */
    private float currentSwordThreshold = -1.0f;
    private float currentAxeThreshold = -1.0f;
    private float currentAxePostDelay = -1.0f;

    private boolean axeDelayStarted = false;

    public TriggerBot() {
        super(
                "Trigger Bot",
                "Makes you automatically attack once aimed at a target",
                -1,
                Category.COMBAT
        );

        addSettings(
                swordThreshold,
                axeThreshold,
                axePostDelay,
                reactionTime,
                cooldownMode,
                critMode,
                ignorePassiveMobs,
                ignoreCrystals,
                respectShields,
                ignoreInvisible,
                onlyWhenMouseDown,
                useOnlySwordOrAxe,
                disableOnWorldChange,
                samePlayer
        );
    }

    @EventHandler
    private void onWorldChangeEvent(WorldChangeEvent event) {
        resetState();

        if (disableOnWorldChange.getValue() && this.isEnabled()) {
            this.toggle();
        }
    }

    @EventHandler
    private void tick(TickEvent event) {
        if (isNull()) {
            resetState();
            return;
        }

        if (mc.player == null || mc.world == null) {
            resetState();
            return;
        }

        /*
         * Во время использования предмета не атакуем.
         */
        if (mc.player.isUsingItem()) {
            resetState();
            return;
        }

        /*
         * Во время GUI тоже ничего не делаем.
         */
        if (mc.currentScreen != null) {
            resetState();
            return;
        }

        /*
         * Проверяем оружие.
         */
        if (!isHoldingSwordOrAxe()) {
            resetState();
            return;
        }

        /*
         * Если включён режим "Only Mouse Hold",
         * ЛКМ должен быть зажат.
         */
        if (onlyWhenMouseDown.getValue()
                && GLFW.glfwGetMouseButton(
                mc.getWindow().getHandle(),
                GLFW.GLFW_MOUSE_BUTTON_LEFT
        ) != GLFW.GLFW_PRESS) {
            resetState();
            return;
        }

        /*
         * Получаем сущность непосредственно из текущего raycast.
         */
        if (!(mc.crosshairTarget instanceof EntityHitResult hitResult)) {
            resetState();
            return;
        }

        Entity crosshairEntity = hitResult.getEntity();

        /*
         * Если цель изменилась — начинаем новый цикл.
         */
        if (target != crosshairEntity) {
            target = crosshairEntity;
            startNewTargetCycle(target);
        }

        if (!hasTarget(target)) {
            resetState();
            return;
        }

        /*
         * Защита от щита.
         */
        if (respectShields.getValue()
                && target instanceof PlayerEntity playerTarget
                && CombatUtil.isShieldFacingAway(playerTarget)
                && mc.player.getMainHandStack().getItem() instanceof SwordItem) {
            resetAttackCycle();
            return;
        }

        /*
         * Reaction Time.
         */
        if (!isReactionReady()) {
            return;
        }

        /*
         * Перед атакой ещё раз убеждаемся,
         * что прицел всё ещё на той же цели.
         */
        if (!isCrosshairTarget(target)) {
            resetState();
            return;
        }

        /*
         * Главная логика критов.
         */
        if (!canAttackAccordingToCritMode()) {
            return;
        }

        /*
         * Cooldown / threshold.
         */
        if (!hasElapsedDelay()) {
            return;
        }

        /*
         * Финальная проверка.
         */
        if (!isCrosshairTarget(target) || !hasTarget(target)) {
            resetState();
            return;
        }

        attack();
    }

    /**
     * Запускает новый цикл для новой цели.
     */
    private void startNewTargetCycle(Entity entity) {
        waitingForReaction = true;

        reactionTimer.reset();

        currentReactionDelay = calculateReactionDelay();

        currentSwordThreshold = randomThreshold(
                swordThreshold.getMinValue(),
                swordThreshold.getMaxValue()
        );

        currentAxeThreshold = randomThreshold(
                axeThreshold.getMinValue(),
                axeThreshold.getMaxValue()
        );

        currentAxePostDelay = randomThreshold(
                axePostDelay.getMinValue(),
                axePostDelay.getMaxValue()
        );

        axeDelayStarted = false;

        if (entity != null) {
            lastTargetUUID = entity.getUuidAsString();
        }
    }

    /**
     * Reaction Time рассчитывается один раз при появлении цели.
     */
    private long calculateReactionDelay() {
        double delay = MathUtils.randomDoubleBetween(
                reactionTime.getMinValue(),
                reactionTime.getMaxValue()
        );

        switch (cooldownMode.getMode()) {
            case "Smart" -> {
                if (mc.player != null && target != null) {
                    double distance = mc.player.distanceTo(target);

                    /*
                     * Чем ближе цель, тем меньше дополнительная
                     * реакционная задержка.
                     */
                    if (distance < 1.5) {
                        delay *= 0.66;
                    }
                }
            }

            case "None" -> delay = 0;

            case "Strict" -> {
                /*
                 * Ничего дополнительно не уменьшаем.
                 */
            }
        }

        return Math.max(0L, Math.round(delay));
    }

    /**
     * Проверка Reaction Time.
     */
    private boolean isReactionReady() {
        if (!waitingForReaction) {
            return true;
        }

        if (currentReactionDelay <= 0) {
            waitingForReaction = false;
            return true;
        }

        if (reactionTimer.hasElapsedTime(currentReactionDelay, true)) {
            waitingForReaction = false;
            return true;
        }

        return false;
    }

    /**
     * Проверяет выбранный Crit Mode.
     */
    private boolean canAttackAccordingToCritMode() {
        String mode = critMode.getMode();

        switch (mode) {
            case "Only Crits":
                return canCrit();

            case "Smart Crits":
                /*
                 * Если игрок уже находится в настоящем критическом
                 * состоянии — атакуем как крит.
                 *
                 * Если критического состояния нет, Smart Crits
                 * разрешает обычную атаку при готовом cooldown.
                 */
                return true;

            case "Off":
            default:
                return true;
        }
    }

    /**
     * Проверяет, может ли текущая атака быть критической
     * по обычным игровым условиям.
     */
    private boolean canCrit() {
        if (mc.player == null) {
            return false;
        }

        /*
         * На земле крит невозможен.
         */
        if (mc.player.isOnGround()) {
            return false;
        }

        /*
         * Во время подъёма прыжка крит не делаем.
         */
        if (mc.player.getVelocity().y >= 0.0) {
            return false;
        }

        /*
         * Должно быть фактическое падение.
         */
        if (mc.player.fallDistance <= 0.065f) {
            return false;
        }

        /*
         * Состояния, при которых обычный крит невозможен.
         */
        if (mc.player.isClimbing()) {
            return false;
        }

        if (mc.player.isInLava()) {
            return false;
        }

        if (mc.player.isTouchingWater()) {
            return false;
        }

        if (mc.player.hasStatusEffect(StatusEffects.BLINDNESS)) {
            return false;
        }

        if (mc.player.getVehicle() != null) {
            return false;
        }

        return true;
    }

    /**
     * Проверяет cooldown оружия.
     */
    private boolean hasElapsedDelay() {
        if (mc.player == null) {
            return false;
        }

        Item heldItem = mc.player.getMainHandStack().getItem();

        /*
         * Если cooldown mode = None,
         * cooldown вообще не блокирует атаку.
         */
        if ("None".equals(cooldownMode.getMode())) {
            return true;
        }

        float cooldown = mc.player.getAttackCooldownProgress(0.0f);

        if (heldItem instanceof AxeItem) {
            return checkAxeCooldown(cooldown);
        }

        if (heldItem instanceof SwordItem) {
            return checkSwordCooldown(cooldown);
        }

        return true;
    }

    private boolean checkSwordCooldown(float cooldown) {
        /*
         * Threshold создаётся при начале цикла.
         */
        if (currentSwordThreshold < 0.0f) {
            currentSwordThreshold = randomThreshold(
                    swordThreshold.getMinValue(),
                    swordThreshold.getMaxValue()
            );
        }

        if (cooldown < currentSwordThreshold) {
            return false;
        }

        return true;
    }

    private boolean checkAxeCooldown(float cooldown) {
        if (currentAxeThreshold < 0.0f) {
            currentAxeThreshold = randomThreshold(
                    axeThreshold.getMinValue(),
                    axeThreshold.getMaxValue()
            );
        }

        if (currentAxePostDelay < 0.0f) {
            currentAxePostDelay = randomThreshold(
                    axePostDelay.getMinValue(),
                    axePostDelay.getMaxValue()
            );
        }

        /*
         * Сначала ждём cooldown.
         */
        if (cooldown < currentAxeThreshold) {
            axeDelayStarted = false;
            axeTimer.reset();
            return false;
        }

        /*
         * Cooldown готов — запускаем Post Delay.
         */
        if (!axeDelayStarted) {
            axeDelayStarted = true;
            axeTimer.reset();
            return false;
        }

        /*
         * Теперь ждём заданную задержку.
         */
        if (!axeTimer.hasElapsedTime(
                Math.max(0L, Math.round(currentAxePostDelay)),
                true
        )) {
            return false;
        }

        return true;
    }

    /**
     * Проверка, что прицел всё ещё на конкретной сущности.
     */
    private boolean isCrosshairTarget(Entity entity) {
        if (entity == null) {
            return false;
        }

        if (!(mc.crosshairTarget instanceof EntityHitResult hitResult)) {
            return false;
        }

        return hitResult.getEntity() == entity;
    }

    /**
     * Same Player.
     */
    private boolean samePlayerCheck(Entity entity) {
        if (!samePlayer.getValue()) {
            return true;
        }

        if (entity == null) {
            return false;
        }

        String uuid = entity.getUuidAsString();

        if (lastTargetUUID == null) {
            lastTargetUUID = uuid;
            return true;
        }

        return lastTargetUUID.equals(uuid);
    }

    private float randomThreshold(double min, double max) {
        return (float) MathUtils.randomDoubleBetween(min, max);
    }

    private boolean isHoldingSwordOrAxe() {
        if (!useOnlySwordOrAxe.getValue()) {
            return true;
        }

        if (mc.player == null) {
            return false;
        }

        Item item = mc.player.getMainHandStack().getItem();

        return item instanceof AxeItem || item instanceof SwordItem;
    }

    /**
     * Выполняет обычную атаку клиента.
     */
    public void attack() {
        if (mc.player == null || target == null) {
            return;
        }

        if (!isCrosshairTarget(target)) {
            resetState();
            return;
        }

        ((MinecraftClientAccessor) mc).invokeDoAttack();

        /*
         * После атаки начинаем новый attack cycle.
         */
        waitingForReaction = false;

        currentSwordThreshold = -1.0f;
        currentAxeThreshold = -1.0f;
        currentAxePostDelay = -1.0f;

        axeDelayStarted = false;

        axeTimer.reset();
        reactionTimer.reset();

        if (samePlayer.getValue()) {
            lastTargetUUID = target.getUuidAsString();
        }
    }

    /**
     * Проверяет, является ли сущность допустимой целью.
     */
    public boolean hasTarget(Entity entity) {
        if (entity == null) {
            return false;
        }

        if (mc.player == null) {
            return false;
        }

        if (entity == mc.player || entity == mc.cameraEntity) {
            return false;
        }

        if (!entity.isAlive()) {
            return false;
        }

        if (entity instanceof PlayerEntity player
                && FriendManager.isFriend(player.getUuid())) {
            return false;
        }

        if (Teams.isTeammate(entity)) {
            return false;
        }

        if (entity instanceof WindChargeEntity) {
            return false;
        }

        if (entity instanceof EndCrystalEntity
                && ignoreCrystals.getValue()) {
            return false;
        }

        if (entity instanceof Tameable) {
            return false;
        }

        if (entity instanceof PassiveEntity
                && ignorePassiveMobs.getValue()) {
            return false;
        }

        if (ignoreInvisible.getValue() && entity.isInvisible()) {
            return false;
        }

        return true;
    }

    /**
     * Полный сброс состояния.
     */
    private void resetState() {
        target = null;

        waitingForReaction = false;

        currentReactionDelay = 0L;

        currentSwordThreshold = -1.0f;
        currentAxeThreshold = -1.0f;
        currentAxePostDelay = -1.0f;

        axeDelayStarted = false;

        reactionTimer.reset();
        axeTimer.reset();
    }

    /**
     * Сбрасывает только текущий attack cycle,
     * не уничтожая информацию о цели.
     */
    private void resetAttackCycle() {
        waitingForReaction = false;

        currentSwordThreshold = -1.0f;
        currentAxeThreshold = -1.0f;
        currentAxePostDelay = -1.0f;

        axeDelayStarted = false;

        reactionTimer.reset();
        axeTimer.reset();
    }

    @Override
    public void onEnable() {
        resetState();
        lastTargetUUID = null;

        super.onEnable();
    }

    @Override
    public void onDisable() {
        resetState();
        lastTargetUUID = null;

        super.onDisable();
    }
}
