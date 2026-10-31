package slooth.peripheral.audit;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import slooth.peripheral.PeripheralClient;
import slooth.peripheral.net.PacketTools;
import slooth.peripheral.script.ScriptActions;
import slooth.peripheral.script.ScriptPaths;
import slooth.peripheral.script.ScriptRunner;

/**
 * Packet tools on a real multiplayer server: joins {@code peripheral.serverAddress} and checks that
 * cancelling, holding and sending work only when the server runs Peripheral Testing
 * ({@code -Dperipheral.serverAudit=on}) and are refused when it doesn't ({@code =off}), while the
 * log works either way.
 */
public final class ServerAcceptance {
    static int stage, ticks, failures;
    static boolean expectOn;

    public static void init() {
        expectOn = System.getProperty("peripheral.serverAudit", "").equals("on");
        ClientTickEvents.END_CLIENT_TICK.register(ServerAcceptance::tick);
        log("START expecting tools " + (expectOn ? "on" : "off"));
    }

    static void tick(Minecraft c) {
        ticks++;
        try {
            if (stage == 0) {
                if (!(c.gui.screen() instanceof TitleScreen) || ticks < 30) return;
                String address = System.getProperty("peripheral.serverAddress", "127.0.0.1:25599");
                ConnectScreen.startConnecting(c.gui.screen(), c, ServerAddress.parseString(address),
                    new ServerData("audit", address, ServerData.Type.OTHER), false, null);
                next(); return;
            }
            if (stage == 1) {
                if (c.player == null || ticks < 80) { if (ticks > 1200) { check(false, "joined the server"); stage = 9; } return; }
                check(!c.hasSingleplayerServer(), "joined a real multiplayer server");
                check(PacketTools.allowed() == expectOn, expectOn ? "the server with Peripheral Testing turns the tools on" : "a server without Peripheral Testing keeps them off");
                slooth.peripheral.util.PeripheralConfig.termsAccepted = true;
                slooth.peripheral.util.PeripheralConfig.baritoneWarnDismissed = true;
                PacketTools.setLogging(true);
                next(); return;
            }
            if (stage == 2) {
                if (ticks < 20) return;
                // Hold while walking: the server must not kick us, and releasing must catch it up.
                PacketTools.setHolding(true, "");
                check(PacketTools.holding() == expectOn, expectOn ? "holding starts" : "holding is refused");
                next(); return;
            }
            if (stage == 3) {
                if (ticks < 20) { ScriptActions.movement("audit", 1, 0); return; }
                ScriptActions.release("audit");
                if (expectOn) check(PacketTools.heldCount() > 0, "movement is held while holding");
                PacketTools.release(true);
                next(); return;
            }
            if (stage == 4) {
                if (ticks == 40) c.player.connection.sendCommand("help");
                if (ticks < 60) return;
                check(c.player != null && c.getConnection() != null, "still connected after holding and releasing");
                check(PacketTools.recent(2000).stream().anyMatch(e -> e.direction() == PacketTools.Direction.SENT && e.name().startsWith("move_player")),
                    "the log works on a multiplayer server");
                java.nio.file.Files.createDirectories(ScriptPaths.resolve("audit"));
                java.nio.file.Files.writeString(ScriptPaths.resolve("audit/server_packets.py"), String.join("\n",
                    "from mc import *",
                    "import time",
                    "print('ALLOWED', packet.allowed())",
                    "help = [p for p in packet.recent(2000, direction='sent', name='chat_command') if 'help' in p['summary']][-1]",
                    "edited = bytes.fromhex(help['data']).replace(b'help', b'list').hex()",
                    "for name, call in [('CANCEL', lambda: packet.cancel('swing')), ('HOLD', packet.hold), ('SEND', lambda: packet.send(edited))]:",
                    "    try:",
                    "        call()",
                    "        print(name, 'worked')",
                    "    except RuntimeError as e:",
                    "        print(name, 'refused' if 'not_allowed_here' in str(e) else 'error ' + str(e))",
                    "packet.release()",
                    "packet.allow('swing')",
                    "print('LOG', len(packet.recent(50)) > 0)",
                    ""));
                check(ScriptRunner.runScript("audit/server_packets.py"), "the packets script starts");
                next(); return;
            }
            if (stage == 5) {
                if (ScriptRunner.isRunning("audit/server_packets.py") && ticks < 600) return;
                if (ticks < 40) return;
                var tail = String.join("\n", ScriptRunner.logTail("audit/server_packets.py"));
                log("script output: " + tail.replace("\n", " | "));
                String want = expectOn ? "worked" : "refused";
                check(tail.contains("ALLOWED " + (expectOn ? "True" : "False")), "packet.allowed() says " + expectOn);
                check(tail.contains("CANCEL " + want), "packet.cancel() " + want);
                check(tail.contains("HOLD " + want), "packet.hold() " + want);
                check(tail.contains("SEND " + want), "packet.send() " + want);
                check(tail.contains("LOG True"), "packet.recent() works");
                boolean listed = slooth.peripheral.util.ChatLog.recent(60).stream().anyMatch(m -> m.text().contains("players online"));
                check(listed == expectOn, expectOn ? "the server ran the edited /list packet" : "no edited packet reached the server");
                check(!PacketTools.active(), "nothing is left changing traffic");
                next(); return;
            }
            if (stage == 6) {
                log("COMPLETE " + (failures == 0 ? "PASS" : "FAIL") + " failures=" + failures);
                stage = 9;
                c.stop();
            }
        } catch (Throwable e) {
            log("EXCEPTION " + e); e.printStackTrace(); failures++; stage = 6;
        }
    }

    static void next() { stage++; ticks = 0; }
    static void check(boolean ok, String message) { if (!ok) failures++; log((ok ? "PASS " : "FAIL ") + message); }
    static void log(String m) { PeripheralClient.LOGGER.info("[ServerAudit] {}", m); }
}
