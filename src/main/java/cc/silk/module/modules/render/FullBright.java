package cc.silk.module.modules.render;

import cc.silk.module.Category;
import cc.silk.module.Module;
import cc.silk.module.setting.BooleanSetting;

public class FullBright extends Module {

    public static final BooleanSetting antiBlindness =
            new BooleanSetting("Anti Blindness", false);

    public FullBright() {
        super("Full Bright", "Removes darkness", Category.RENDER);
        addSetting(antiBlindness);
    }
}
