package slooth.peripheral;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;

/**
 * Peripheral — client-side mod entry point.
 *
 * <p>Peripheral exposes Minecraft's game state over a local HTTP API so that
 * external Python scripts can read game data and send actions back into the
 * game.  It also hosts an in-game control panel (press <b>G</b>) and a
 * scriptable GUI screen (press <b>H</b>).
 *
 * <p><b>Architecture overview:</b>
 * <pre>
 *   Python script (*.py)
 *       └─ ScriptRunner   – runs scripts in isolated threads via Jython
 *           └─ HTTP API   – PeripheralHttpServer on localhost:25585
 *               ├─ GET  /state      – full game state snapshot
 *               ├─ POST /action     – send chat, commands, GUI events, …
 *               ├─ GET  /gui        – poll the scriptable GUI screen
 *               └─ …many more endpoints
 *   In-game UI
 *       ├─ PeripheralScreen (G)     – log viewer, script manager, settings
 *       ├─ ScriptEditorScreen       – multi-line in-game script editor
 *       └─ ScriptableScreen (H)     – JSON-driven GUI for scripts to draw on
 * </pre>
 *
 * <p>Key bindings registered here:
 * <ul>
 *   <li><b>G</b> – open/close the Peripheral control panel</li>
 *   <li><b>H</b> – open/close the scriptable GUI (only if a script has loaded
 *       a layout via {@code open_gui()})</li>
 * </ul>
 */
public class PeripheralClient implements ClientModInitializer {

    public static final String MOD_ID = "peripheral";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Override
    public void onInitializeClient() {
        LOGGER.info("[Peripheral] Starting…");

        PeripheralConfig.load();
        BlockTracker.register();
        ScriptRunner.init();
        PeripheralHttpServer.start();

        // ── G → toggle Peripheral GUI ──────────────────────────────────────
        KeyBinding guiKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.peripheral.open_gui", GLFW.GLFW_KEY_G,
            net.minecraft.client.option.KeyBinding.Category.GAMEPLAY));

        // ── H → open/close the scriptable script GUI (if a script has loaded one) ──
        KeyBinding scriptGuiKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.peripheral.open_script_gui", GLFW.GLFW_KEY_H,
            net.minecraft.client.option.KeyBinding.Category.GAMEPLAY));

        // ── Script hotkeys 1-5 (unbound by default — set in Options > Controls) ──
        KeyBinding[] scriptKeys = new KeyBinding[5];
        for (int i = 0; i < 5; i++) {
            scriptKeys[i] = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.peripheral.script_" + (i + 1),
                GLFW.GLFW_KEY_UNKNOWN,
                net.minecraft.client.option.KeyBinding.Category.GAMEPLAY));
        }

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // GUI toggle
            while (guiKey.wasPressed()) {
                if (client.player == null) break;
                if (client.currentScreen instanceof PeripheralScreen) {
                    client.setScreen(null);
                    break;
                }
                // Show terms first if not yet accepted — on accept it opens PeripheralScreen
                if (!PeripheralConfig.termsAccepted) {
                    boolean hasBaritone = net.fabricmc.loader.api.FabricLoader
                        .getInstance().isModLoaded("baritone");
                    net.minecraft.client.gui.screen.Screen target =
                        (!hasBaritone && !PeripheralConfig.baritoneWarnDismissed)
                        ? new BaritoneWarningScreen(new PeripheralScreen())
                        : new PeripheralScreen();
                    client.setScreen(new TermsScreen(target));
                    break;
                }
                boolean hasBaritone = net.fabricmc.loader.api.FabricLoader
                    .getInstance().isModLoaded("baritone");
                if (!hasBaritone && !PeripheralConfig.baritoneWarnDismissed) {
                    client.setScreen(new BaritoneWarningScreen(new PeripheralScreen()));
                } else {
                    client.setScreen(new PeripheralScreen());
                }
            }

            // Script GUI toggle (H)
            while (scriptGuiKey.wasPressed()) {
                if (client.player == null) break;
                if (client.currentScreen instanceof ScriptableScreen) {
                    client.setScreen(null);
                } else if (ScriptableScreen.hasLayout()) {
                    client.setScreen(new ScriptableScreen());
                }
            }

            // Script hotkeys
            for (int i = 0; i < 5; i++) {
                while (scriptKeys[i].wasPressed()) {
                    String filename = PeripheralConfig.getScriptSlot(i + 1);
                    if (filename.isEmpty()) {
                        if (client.player != null)
                            client.player.sendMessage(
                                net.minecraft.text.Text.literal(
                                    "[Peripheral] Script slot " + (i + 1) + " is empty. Assign it in the Scripts tab."),
                                false);
                    } else {
                        ScriptRunner.toggle(filename);
                        PeripheralStateTracker.pushLog(null, null,
                            "SCRIPT " + (ScriptRunner.isRunning(filename) ? "started: " : "stopped: ") + filename, -1);
                    }
                }
            }

            // Drive A* navigator
            PeripheralNavigator.INSTANCE.tick(client);
        });

        // ── Auto-run scripts on world / server join ────────────────────────────
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            // Small delay so the world is fully loaded before scripts start
            Thread autorun = new Thread(() -> {
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                boolean singleplayer = client.isIntegratedServerRunning();
                String targetMode = singleplayer ? "world" : "multi";
                for (String relPath : ScriptRunner.listAllScripts()) {
                    String mode = PeripheralConfig.getAutoRun(relPath);
                    if (mode.equals(targetMode) || mode.equals("both")) {
                        ScriptRunner.runScript(relPath);
                        LOGGER.info("[Peripheral] Auto-started: {} (mode={})", relPath, mode);
                    }
                }
            }, "peripheral-autorun");
            autorun.setDaemon(true);
            autorun.start();
        });

        // ── /peripheral commands ───────────────────────────────────────────────
        // Suggestion provider: lists every .py in the scripts folder (all subfolders)
        SuggestionProvider<FabricClientCommandSource> scriptSuggestions = (ctx, builder) -> {
            String remaining = builder.getRemaining().toLowerCase();
            for (String script : ScriptRunner.listAllScripts()) {
                if (script.toLowerCase().startsWith(remaining)) {
                    builder.suggest(script);
                }
            }
            return builder.buildFuture();
        };

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            var peripheral = ClientCommandManager.literal("peripheral");

            // /peripheral run <path>
            peripheral.then(ClientCommandManager.literal("run")
                .then(ClientCommandManager.argument("path", StringArgumentType.greedyString())
                    .suggests(scriptSuggestions)
                    .executes(ctx -> {
                        String path = StringArgumentType.getString(ctx, "path");
                        MinecraftClient mc = ctx.getSource().getClient();
                        mc.execute(() -> {
                            if (!Files.exists(ScriptRunner.SCRIPTS_DIR.resolve(path))) {
                                ctx.getSource().sendFeedback(Text.literal(
                                    "§c[Peripheral] Script not found: §f" + path));
                                return;
                            }
                            boolean started = ScriptRunner.runScript(path);
                            if (started) {
                                ctx.getSource().sendFeedback(Text.literal(
                                    "§a[Peripheral] Started: §f" + path));
                            } else if (ScriptRunner.isRunning(path)) {
                                ctx.getSource().sendFeedback(Text.literal(
                                    "§e[Peripheral] Already running: §f" + path));
                            } else {
                                ctx.getSource().sendFeedback(Text.literal(
                                    "§c[Peripheral] Failed to start: §f" + path));
                            }
                        });
                        return 1;
                    })));

            // /peripheral stop <path>
            peripheral.then(ClientCommandManager.literal("stop")
                .then(ClientCommandManager.argument("path", StringArgumentType.greedyString())
                    .suggests(scriptSuggestions)
                    .executes(ctx -> {
                        String path = StringArgumentType.getString(ctx, "path");
                        ctx.getSource().getClient().execute(() -> {
                            if (ScriptRunner.isRunning(path)) {
                                ScriptRunner.stopScript(path);
                                ctx.getSource().sendFeedback(Text.literal(
                                    "§e[Peripheral] Stopped: §f" + path));
                            } else {
                                ctx.getSource().sendFeedback(Text.literal(
                                    "§c[Peripheral] Not running: §f" + path));
                            }
                        });
                        return 1;
                    })));

            // /peripheral delete <path>  — shows a clickable confirm prompt
            peripheral.then(ClientCommandManager.literal("delete")
                .then(ClientCommandManager.argument("path", StringArgumentType.greedyString())
                    .suggests(scriptSuggestions)
                    .executes(ctx -> {
                        String path = StringArgumentType.getString(ctx, "path");
                        ctx.getSource().getClient().execute(() -> {
                            if (!Files.exists(ScriptRunner.SCRIPTS_DIR.resolve(path))) {
                                ctx.getSource().sendFeedback(Text.literal(
                                    "§c[Peripheral] Script not found: §f" + path));
                                return;
                            }
                            // Build a message with a clickable [Confirm] button
                            MutableText confirm = Text.literal("§c[Peripheral] Delete §f" + path + " §c?  ")
                                .append(Text.literal("§c§l[Confirm]")
                                    .styled(s -> s.withClickEvent(
                                        new ClickEvent.RunCommand(
                                            "/peripheral delete-confirm " + path))));
                            ctx.getSource().sendFeedback(confirm);
                        });
                        return 1;
                    })));

            // /peripheral delete-confirm <path>  — actually deletes (run by clicking Confirm)
            peripheral.then(ClientCommandManager.literal("delete-confirm")
                .then(ClientCommandManager.argument("path", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        String path = StringArgumentType.getString(ctx, "path");
                        ctx.getSource().getClient().execute(() -> {
                            java.nio.file.Path target = ScriptRunner.SCRIPTS_DIR.resolve(path);
                            if (!Files.exists(target)) {
                                ctx.getSource().sendFeedback(Text.literal(
                                    "§c[Peripheral] Script not found: §f" + path));
                                return;
                            }
                            try {
                                ScriptRunner.stopScript(path); // stop if running
                                BuildSession.deleteChatFor(path);
                                Files.delete(target);
                                ctx.getSource().sendFeedback(Text.literal(
                                    "§a[Peripheral] Deleted: §f" + path));
                            } catch (Exception e) {
                                ctx.getSource().sendFeedback(Text.literal(
                                    "§c[Peripheral] Delete failed: §f" + e.getMessage()));
                            }
                        });
                        return 1;
                    })));

            // /peripheral edit <path>
            peripheral.then(ClientCommandManager.literal("edit")
                .then(ClientCommandManager.argument("path", StringArgumentType.greedyString())
                    .suggests(scriptSuggestions)
                    .executes(ctx -> {
                        String path = StringArgumentType.getString(ctx, "path");
                        MinecraftClient mc = ctx.getSource().getClient();
                        mc.execute(() -> {
                            if (!Files.exists(ScriptRunner.SCRIPTS_DIR.resolve(path))) {
                                ctx.getSource().sendFeedback(Text.literal(
                                    "§c[Peripheral] Script not found: §f" + path));
                                return;
                            }
                            mc.setScreen(new ScriptEditorScreen(new PeripheralScreen(), path));
                        });
                        return 1;
                    })));

            dispatcher.register(peripheral);
        });

        // ── HUD overlay ───────────────────────────────────────────────────────
        PeripheralOverlay.register();
        PeripheralHud.register();

        // ── Incoming chat → ChatLog (so scripts can read it) ──────────────────
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
            String senderName = sender != null ? sender .name() : "";
            ChatLog.add("chat", senderName, message.getString());
        });

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) ChatLog.add("system", "", message.getString());
        });

        // ── Outgoing chat interceptor ─────────────────────────────────────────
        // All sent chat messages are added to ChatLog so scripts can read them
        // via wait_for_chat() / chat_log().  In 1.21+ Minecraft displays the
        // sender's own messages locally without an echo from the server, so the
        // CHAT receive event never fires for outgoing messages — we fill that gap here.
        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            MinecraftClient _mc = MinecraftClient.getInstance();
            String senderName = (_mc.player != null) ? _mc.player.getName().getString() : "";
            ChatLog.add("chat", senderName, message);
            if (!message.startsWith("!")) return true;
            forwardToAgent(message);
            return false;
        });

        LOGGER.info("[Peripheral] Ready. HTTP :{} | G to open GUI", PeripheralHttpServer.PORT);
    }

    // ── Route ! commands to the agent ────────────────────────────────────────

    static void forwardToAgent(String message) {
        if (message.equals("!stop")) {
            agentPost("/stop", "{\"type\":\"stop\"}");
        } else if (message.startsWith("!yes")) {
            agentPost("/skill_response", "{\"type\":\"yes\"}");
        } else if (message.equals("!no")) {
            agentPost("/skill_response", "{\"type\":\"no\"}");
        } else if (message.startsWith("!save ")) {
            agentPost("/skill_response", "{\"type\":\"save\",\"value\":\"" + esc(message.substring(6).trim()) + "\"}");
        } else if (message.startsWith("!retry ")) {
            agentPost("/skill_response", "{\"type\":\"retry\",\"value\":\"" + esc(message.substring(7).trim()) + "\"}");
        } else {
            agentPost("/task", "{\"task\":\"" + esc(message.substring(1).trim()) + "\"}");
        }
    }

    private static void agentPost(String path, String json) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(PeripheralConfig.agentUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            LOGGER.warn("[Peripheral] Agent forward failed: {}", e.getMessage());
        }
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
