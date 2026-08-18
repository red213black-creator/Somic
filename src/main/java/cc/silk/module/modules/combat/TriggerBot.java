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
            new ModeSetting(
                    "Cooldown Mode",
                    "Smart",
                    "Smart",
                    "Strict",
                    "None"
            );

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

    private final TimerUtil timer = new TimerUtil();
    private final TimerUtil reactionTimer = new TimerUtil();
    private final TimerUtil axeTimer = new TimerUtil();

    public boolean waitingForDelay = false;

    private boolean waitingForReaction = false;

    private long currentReactionDelay = 0L;

    private float currentSwordThreshold = -1.0f;
    private float currentAxeThreshold = -1.0f;
    private float currentAxePostDelay = -1.0f;

    private Entity target;

    private String lastTargetUUID = null;

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
         * Не атакуем во время использования предмета.
         */
        if (mc.player.isUsingItem()) {
            resetAttackState();
            return;
        }

        /*
         * Не работаем поверх GUI.
         */
        if (mc.currentScreen != null) {
            resetAttackState();
            return;
        }

        /*
         * Проверяем оружие.
         */
        if (!isHoldingSwordOrAxe()) {
            resetAttackState();
            return;
        }

        /*
         * Only Mouse Hold.
         */
        if (onlyWhenMouseDown.getValue()
                && GLFW.glfwGetMouseButton(
                mc.getWindow().getHandle(),
                GLFW.GLFW_MOUSE_BUTTON_LEFT
        ) != GLFW.GLFW_PRESS) {

            resetAttackState();
            return;
        }

        /*
         * Получаем именно сущность под прицелом.
         */
        if (!(mc.crosshairTarget instanceof EntityHitResult hitResult)) {
            resetAttackState();
            return;
        }

        Entity newTarget = hitResult.getEntity();

        /*
         * Если цель изменилась — начинаем новый цикл.
         */
        if (target != newTarget) {
            target = newTarget;
            startTargetCycle(target);
        }

        if (!hasTarget(target)) {
            resetAttackState();
            return;
        }

        /*
         * Если включена проверка щита.
         */
        if (respectShields.getValue()
                && target instanceof PlayerEntity playerTarget
                && mc.player.getMainHandStack().getItem() instanceof SwordItem
                && CombatUtil.isShieldFacingAway(playerTarget)) {

            return;
        }

        /*
         * Reaction Time применяется только к обычному режиму.
         *
         * Для Only Crits не блокируем критическое окно лишней
         * задержкой, иначе можно просто пропустить момент крита.
         */
        if (!"Only Crits".equals(critMode.getMode())) {
            if (!isReactionReady()) {
                return;
            }
        }

        /*
         * Проверяем, что мы всё ещё смотрим именно на эту цель.
         */
        if (!isCrosshairTarget(target)) {
            resetAttackState();
            return;
        }

        /*
         * Проверяем режим критов.
         */
        if (!canAttackByCritMode()) {
            return;
        }

        /*
         * Проверяем cooldown оружия.
         */
        if (!hasElapsedDelay()) {
            return;
        }

        /*
         * Финальная проверка перед атакой.
         */
        if (!isCrosshairTarget(target)) {
            resetAttackState();
            return;
        }

        if (!hasTarget(target)) {
            resetAttackState();
            return;
        }

        if (!samePlayerCheck(target)) {
            return;
        }

        attack();
    }

    /**
     * Начинает новый цикл при появлении цели.
     */
    private void startTargetCycle(Entity entity) {
        waitingForReaction = true;

        reactionTimer.reset();

        currentReactionDelay = calculateReactionDelay();

        /*
         * Threshold выбирается ОДИН раз.
         * Он больше не меняется каждый тик.
         */
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

        waitingForDelay = false;

        axeTimer.reset();

        if (entity != null) {
            lastTargetUUID = entity.getUuidAsString();
        }
    }

    /**
     * Вычисляет Reaction Time.
     */
    private long calculateReactionDelay() {
        if ("None".equals(cooldownMode.getMode())) {
            return 0L;
        }

        double delay = MathUtils.randomDoubleBetween(
                reactionTime.getMinValue(),
                reactionTime.getMaxValue()
        );

        /*
         * Smart:
         * на близкой дистанции уменьшаем задержку.
         *
         * Важно:
         * здесь НЕ используется (long) multiplier,
         * потому что (long) 0.66 == 0.
         */
        if ("Smart".equals(cooldownMode.getMode())
                && mc.player != null
                && target != null) {

            double distance = mc.player.distanceTo(target);

            if (distance < 1.5) {
                delay *= 0.66;
            }
        }

        return Math.max(0L, Math.round(delay));
    }

    /**
     * Проверяет Reaction Time.
     */
    private boolean isReactionReady() {
        if (!waitingForReaction) {
            return true;
        }

        if (currentReactionDelay <= 0L) {
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
     * Логика Crit Mode.
     */
    private boolean canAttackByCritMode() {

        return switch (critMode.getMode()) {

            /*
             * Обычный TriggerBot.
             */
            case "Off" -> true;

            /*
             * Только настоящий крит.
             */
            case "Only Crits" -> canCrit();

            /*
             * Smart Crits:
             *
             * если игрок сейчас падает и крит возможен —
             * разрешаем атаку.
             *
             * если критического состояния нет —
             * разрешаем обычную атаку после cooldown.
             */
            case "Smart Crits" -> true;

            default -> true;
        };
    }

    /**
     * Проверка настоящего критического состояния.
     *
     * Важный момент:
     * игрок должен НЕ быть на земле и уже ДВИГАТЬСЯ ВНИЗ.
     *
     * Поэтому работает:
     *
     * jump
     * ↓
     * подъём — нет
     * ↓
     * вершина — нет
     * ↓
     * падение — ДА
     *
     * И также:
     *
     * стоял на блоке
     * ↓
     * сошёл с края
     * ↓
     * падение — ДА
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
         * Игрок должен именно падать.
         */
        if (mc.player.getVelocity().y >= 0.0D) {
            return false;
        }

        /*
         * Нужна минимальная высота падения.
         */
        if (mc.player.fallDistance <= 0.065F) {
            return false;
        }

        /*
         * Нельзя критовать при лазании.
         */
        if (mc.player.isClimbing()) {
            return false;
        }

        /*
         * В воде крит невозможен.
         */
        if (mc.player.isTouchingWater()) {
            return false;
        }

        /*
         * В лаве крит невозможен.
         */
        if (mc.player.isInLava()) {
            return false;
        }

        /*
         * Blindness блокирует крит.
         */
        if (mc.player.hasStatusEffect(StatusEffects.BLINDNESS)) {
            return false;
        }

        /*
         * На транспорте крит невозможен.
         */
        if (mc.player.getVehicle() != null) {
            return false;
        }

        return true;
    }

    /**
     * Проверка cooldown.
     */
    private boolean hasElapsedDelay() {

        if (mc.player == null) {
            return false;
        }

        Item heldItem = mc.player.getMainHandStack().getItem();

        /*
         * None = не проверяем cooldown.
         */
        if ("None".equals(cooldownMode.getMode())) {
            return true;
        }

        float cooldown = mc.player.getAttackCooldownProgress(0.0F);

        /*
         * Топор.
         */
        if (heldItem instanceof AxeItem) {

            if (currentAxeThreshold < 0.0F) {
                currentAxeThreshold = randomThreshold(
                        axeThreshold.getMinValue(),
                        axeThreshold.getMaxValue()
                );
            }

            if (currentAxePostDelay < 0.0F) {
                currentAxePostDelay = randomThreshold(
                        axePostDelay.getMinValue(),
                        axePostDelay.getMaxValue()
                );
            }

            /*
             * Сначала ждём cooldown.
             */
            if (cooldown < currentAxeThreshold) {
                waitingForDelay = false;
                axeTimer.reset();
                return false;
            }

            /*
             * Cooldown готов.
             * Запускаем Post Delay.
             */
            if (!waitingForDelay) {
                waitingForDelay = true;
                axeTimer.reset();
                return false;
            }

            /*
             * Ждём Axe Post Delay.
             */
            if (!axeTimer.hasElapsedTime(
                    Math.max(0L, Math.round(currentAxePostDelay)),
                    true
            )) {
                return false;
            }

            waitingForDelay = false;

            /*
             * После атаки threshold будет создан заново.
             */
            currentAxeThreshold = -1.0F;
            currentAxePostDelay = -1.0F;

            return true;
        }

        /*
         * Меч.
         */
        if (heldItem instanceof SwordItem) {

            if (currentSwordThreshold < 0.0F) {
                currentSwordThreshold = randomThreshold(
                        swordThreshold.getMinValue(),
                        swordThreshold.getMaxValue()
                );
            }

            return cooldown >= currentSwordThreshold;
        }

        /*
         * Если Only Sword or Axe выключен,
         * разрешаем остальные предметы.
         */
        return true;
    }

    /**
     * Случайное значение в заданном диапазоне.
     */
    private float randomThreshold(double min, double max) {
        return (float) MathUtils.randomDoubleBetween(min, max);
    }

    /**
     * Проверка оружия.
     */
    private boolean isHoldingSwordOrAxe() {

        if (!useOnlySwordOrAxe.getValue()) {
            return true;
        }

        if (mc.player == null) {
            return false;
        }

        Item item = mc.player.getMainHandStack().getItem();

        return item instanceof SwordItem || item instanceof AxeItem;
    }

    /**
     * Проверяет, что прицел всё ещё на нужной сущности.
     *
     * Здесь НЕ имеет значения, смотрит сама модель игрока
     * вперёд, назад или боком.
     *
     * Важно только, что твой raycast попал в его хитбокс.
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

    /**
     * Атака.
     */
    public void attack() {

        if (mc.player == null || target == null) {
            return;
        }

        /*
         * Перед атакой ещё раз проверяем прицел.
         */
        if (!isCrosshairTarget(target)) {
            return;
        }

        ((MinecraftClientAccessor) mc).invokeDoAttack();

        /*
         * Сбрасываем состояние атаки.
         */
        waitingForReaction = false;
        waitingForDelay = false;

        reactionTimer.reset();
        timer.reset();
        axeTimer.reset();

        /*
         * Следующий удар получит новый threshold.
         */
        currentSwordThreshold = -1.0F;
        currentAxeThreshold = -1.0F;
        currentAxePostDelay = -1.0F;

        if (samePlayer.getValue()) {
            lastTargetUUID = target.getUuidAsString();
        }
    }

    /**
     * Проверка цели.
     */
    public boolean hasTarget(Entity en) {

        if (en == null) {
            return false;
        }

        if (mc.player == null) {
            return false;
        }

        if (en == mc.player || en == mc.cameraEntity) {
            return false;
        }

        if (!en.isAlive()) {
            return false;
        }

        /*
         * Friends.
         */
        if (en instanceof PlayerEntity player
                && FriendManager.isFriend(player.getUuid())) {
            return false;
        }

        /*
         * Teams.
         */
        if (Teams.isTeammate(en)) {
            return false;
        }

        /*
         * Wind Charge.
         */
        if (en instanceof WindChargeEntity) {
            return false;
        }

        /*
         * Crystals.
         */
        if (en instanceof EndCrystalEntity
                && ignoreCrystals.getValue()) {
            return false;
        }

        /*
         * Tamed entities.
         */
        if (en instanceof Tameable) {
            return false;
        }

        /*
         * Passive mobs.
         */
        if (en instanceof PassiveEntity
                && ignorePassiveMobs.getValue()) {
            return false;
        }

        /*
         * Invisible.
         */
        if (ignoreInvisible.getValue() && en.isInvisible()) {
            return false;
        }

        return true;
    }

    /**
     * Полный сброс.
     */
    private void resetState() {

        target = null;

        waitingForReaction = false;
        waitingForDelay = false;

        currentReactionDelay = 0L;

        currentSwordThreshold = -1.0F;
        currentAxeThreshold = -1.0F;
        currentAxePostDelay = -1.0F;

        reactionTimer.reset();
        timer.reset();
        axeTimer.reset();
    }

    /**
     * Сброс текущего цикла без удаления lastTargetUUID.
     */
    private void resetAttackState() {

        waitingForReaction = false;
        waitingForDelay = false;

        currentReactionDelay = 0L;

        currentSwordThreshold = -1.0F;
        currentAxeThreshold = -1.0F;
        currentAxePostDelay = -1.0F;

        reactionTimer.reset();
        timer.reset();
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
