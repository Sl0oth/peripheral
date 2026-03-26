package slooth.peripheral;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Registers Peripheral with Mod Menu so a "Configure" button appears
 * in the mod list. Only loaded when Mod Menu is installed.
 */
public class PeripheralModMenuApi implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> new PeripheralScreen();
    }
}
