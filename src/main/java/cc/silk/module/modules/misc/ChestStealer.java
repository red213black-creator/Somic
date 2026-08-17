package cc.silk.module.modules.misc;

import cc.silk.event.impl.player.TickEvent;
import cc.silk.module.Category;
import cc.silk.module.Module;
import cc.silk.module.setting.BooleanSetting;
import cc.silk.module.setting.NumberSetting;

import meteordevelopment.orbit.EventHandler;

import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

public final class ChestStealer extends Module {

    private final NumberSetting delay =
            new NumberSetting("Steal Delay", 0, 500, 100, 10);

    private final BooleanSetting closeAfter =
            new BooleanSetting("Close After Steal", true);

    private long lastSteal;

    public ChestStealer() {
        super(
                "Chest Stealer",
                "Automatically takes items from chests",
                -1,
                Category.MISC
        );

        addSettings(delay, closeAfter);
    }

    @EventHandler
    private void onTickEvent(TickEvent event) {

        if (isNull() || mc.currentScreen == null) {
            return;
        }

        if (!(mc.currentScreen instanceof GenericContainerScreen)) {
            return;
        }

        if (!(mc.player.currentScreenHandler
                instanceof GenericContainerScreenHandler handler)) {
            return;
        }

        long now = System.currentTimeMillis();

        if (now - lastSteal < delay.getValueInt()) {
            return;
        }

        int chestSlots = handler.getRows() * 9;

        for (int slot = 0; slot < chestSlots; slot++) {

            if (handler.getSlot(slot).hasStack()) {

                mc.interactionManager.clickSlot(
                        handler.syncId,
                        slot,
                        0,
                        SlotActionType.QUICK_MOVE,
                        mc.player
                );

                lastSteal = now;
                return;
            }
        }

        if (closeAfter.getValue()) {
            mc.player.closeHandledScreen();
        }
    }

    @Override
    public void onEnable() {
        lastSteal = 0;
        super.onEnable();
    }

    @Override
    public void onDisable() {
        lastSteal = 0;
        super.onDisable();
    }
}
