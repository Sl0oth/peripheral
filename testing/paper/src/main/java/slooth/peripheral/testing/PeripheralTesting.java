package slooth.peripheral.testing;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

/**
 * Turns on Peripheral's packet tools for players on this server. Peripheral checks whether the
 * server listens on the {@code peripheral:testing} plugin channel; this plugin listens on it, and
 * the server tells every joining client so. Nothing is ever sent on the channel.
 */
public final class PeripheralTesting extends JavaPlugin implements PluginMessageListener {
    public static final String CHANNEL = "peripheral:testing";

    @Override
    public void onEnable() {
        getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL, this);
        getLogger().info("Peripheral's packet tools are on for players on this server.");
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterIncomingPluginChannel(this, CHANNEL);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {}
}
