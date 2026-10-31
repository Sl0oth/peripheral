package slooth.peripheral.audit;

import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import slooth.peripheral.PeripheralClient;
import slooth.peripheral.mixin.client.KeyMappingAccess;
import slooth.peripheral.record.Recorder;
import slooth.peripheral.record.Recording;
import slooth.peripheral.script.ScriptActions;

/**
 * Real-game recorder checks in a disposable flat area: record a path with turns, strafing, jumps,
 * sprinting, a hotbar change and an attack; loop it three times from somewhere else; push the
 * player mid-run; and play it shifted by whole blocks. Measures how far each tick lands from the
 * recording and whether loops drift.
 */
public final class RecorderAcceptance {
    static int stage, ticks, failures, scriptGoneAt = -1;
    static double startX, startY, startZ;
    static final String NAME = "audit path", RENAMED = "audit renamed";
    static slooth.peripheral.ui.PeripheralScreen ui;
    static net.minecraft.world.phys.Vec3 serverStart, clientStart;
    static long holdStartMs;

    /** Where the integrated server thinks the player is. */
    static net.minecraft.world.phys.Vec3 serverPos(Minecraft c) {
        var server = c.getSingleplayerServer();
        var player = server.getPlayerList().getPlayer(c.player.getUUID());
        return player.position();
    }

    public static void init() { ClientTickEvents.END_CLIENT_TICK.register(RecorderAcceptance::tick); log("START"); }

    static void tick(Minecraft c) {
        ticks++;
        try {
            if (stage == 0) { if (c.gui.screen() instanceof TitleScreen && ticks > 30) { PilotAcceptance.load(c); next(); } return; }
            if (stage == 1) {
                if (c.player == null || ticks < 100) return;
                c.player.connection.sendCommand("gamemode survival");
                c.player.connection.sendCommand("time set day");
                c.player.connection.sendCommand("gamerule doDaylightCycle false");
                c.player.connection.sendCommand("fill ~-24 ~-1 ~-24 ~24 ~-1 ~24 minecraft:stone");
                c.player.connection.sendCommand("fill ~-24 ~ ~-24 ~24 ~6 ~24 minecraft:air");
                next(); return;
            }
            if (stage == 2) {
                if (ticks < 60) return;
                // Stand still before recording, so the start is clean.
                if (ticks == 60) { try { Recording.delete(NAME); Recording.delete(RENAMED); } catch (Exception ignored) {} }
                if (ticks < 80) return;
                startX = c.player.getX(); startY = c.player.getY(); startZ = c.player.getZ();
                check(Recorder.startRecording(NAME, false) == null, "recording starts");
                next(); return;
            }
            if (stage == 3) {
                int t = ticks - 1;
                var p = c.player;
                if (t < 40) ScriptActions.movement("audit", 1, 0);
                else if (t < 60) { ScriptActions.movement("audit", 1, 0); p.setYRot(p.getYRot() + 4.5F); }
                else if (t < 80) { ScriptActions.movement("audit", 0, 1); if (t == 65) ScriptActions.jump("audit"); }
                else if (t < 100) { ScriptActions.movement("audit", 1, 0); ScriptActions.sprint("audit", true); }
                else { ScriptActions.release("audit"); }
                if (t == 50) press(c.options.keyHotbarSlots[2]);
                if (t == 70) press(c.options.keyAttack);
                if (t == 90) capture(c, "recorder-recording");
                if (t == 130) {
                    var rec = Recorder.stopRecording();
                    check(rec != null && Recording.exists(NAME), "the recording is saved");
                    if (rec != null) {
                        log("recorded " + rec.ticks() + " ticks, " + String.format("%.2f", rec.distance()) + " blocks, events " + rec.actions());
                        check(rec.ticks() >= 128, "every tick is recorded");
                        check(rec.frames.stream().anyMatch(f -> f.events().contains("hotbar3")), "a hotbar key press is recorded");
                        check(rec.frames.stream().anyMatch(f -> f.events().contains("attack")), "an attack click is recorded");
                        check(rec.frames.stream().anyMatch(f -> f.has(Recording.SPRINT)) && rec.frames.stream().anyMatch(f -> f.has(Recording.JUMP)), "sprint and jump are recorded");
                        check(rec.distance() > 8, "the path covers ground");
                        try { var back = Recording.load(NAME); check(back.ticks() == rec.ticks() && back.frames.get(77).equals(rec.frames.get(77)), "a saved recording loads back identically"); }
                        catch (Exception e) { check(false, "load: " + e); }
                    }
                    next();
                }
                return;
            }
            if (stage == 4) {
                // Walk somewhere else, so playback has to come back to the start first.
                if (ticks < 25) { ScriptActions.movement("audit", -1, 1); return; }
                ScriptActions.release("audit");
                if (ticks < 45) return;
                check(Math.hypot(c.player.getX() - startX, c.player.getZ() - startZ) > 2, "the player is away from the start");
                check(Recorder.play(NAME, 3, Recorder.Mode.WORLD) == null, "looping three times starts");
                next(); return;
            }
            if (stage == 5) {
                if (ticks == 200) capture(c, "recorder-playing");
                if (Recorder.playing() && ticks < 3000) return;
                log("loops: " + Recorder.message() + " errors " + Recorder.loopErrors() + " corrections " + Recorder.corrections());
                check(Recorder.message().startsWith("Finished"), "three loops finish: " + Recorder.message());
                List<Double> errors = Recorder.loopErrors();
                check(errors.size() == 3, "three loops ran");
                for (int i = 0; i < errors.size(); i++) check(errors.get(i) < .05, "loop " + (i + 1) + " stays within 0.05 blocks of the recording every tick: " + String.format("%.4f", errors.get(i)));
                var ends = Recorder.loopEnds();
                double spread = 0;
                for (var a : ends) for (var b : ends) spread = Math.max(spread, Math.sqrt(sq(a[0] - b[0]) + sq(a[1] - b[1]) + sq(a[2] - b[2])));
                check(spread < .03, "loops don't drift: the three end points are within 0.03 blocks: " + String.format("%.4f", spread));
                check(Recorder.play(NAME, 1, Recorder.Mode.WORLD) == null, "playing once starts");
                next(); return;
            }
            if (stage == 6) {
                // A shove mid-run: playback walks back to the block it should be on and carries on.
                if (Recorder.playing() && !Recorder.returning() && Recorder.playIndex() == 45 && Recorder.corrections() == 0) {
                    c.player.setDeltaMovement(1.4, .35, -.8);
                    log("pushed at tick 45");
                }
                if (Recorder.playing() && ticks < 3000) return;
                log("push: " + Recorder.message() + " corrections " + Recorder.corrections());
                check(Recorder.corrections() >= 1, "the push knocked the player off the path and playback noticed");
                check(Recorder.message().startsWith("Finished"), "playback still finishes after the push: " + Recorder.message());
                var rec = load();
                var last = rec.frames.getLast();
                double off = Math.sqrt(sq(last.x() - c.player.getX()) + sq(last.y() - c.player.getY()) + sq(last.z() - c.player.getZ()));
                check(off < .06, "after the push it still ends on the recorded spot: " + String.format("%.4f", off));
                // Move five blocks over for a relative run.
                c.player.connection.sendCommand("tp @s " + (startX + 5) + " " + startY + " " + startZ);
                next(); return;
            }
            if (stage == 7) {
                if (ticks < 30) return;
                if (ticks == 30) { check(Recorder.play(NAME, 1, Recorder.Mode.RELATIVE) == null, "relative playback starts"); return; }
                if (Recorder.playing() && ticks < 3000) return;
                var rec = load();
                var last = rec.frames.getLast();
                double off = Math.sqrt(sq(last.x() + 5 - c.player.getX()) + sq(last.y() - c.player.getY()) + sq(last.z() - c.player.getZ()));
                check(Recorder.message().startsWith("Finished"), "relative playback finishes: " + Recorder.message());
                check(off < .06, "relative playback ends five blocks over, on the same spot in its block: " + String.format("%.4f", off));
                next(); return;
            }
            if (stage == 8) {
                // The Macros tab: listed, make a script, trim, rename, and Play from the button.
                if (ticks == 1) { ui = new slooth.peripheral.ui.PeripheralScreen(); c.gui.setScreen(ui); WholeUiAcceptance.call(ui, "switchTab", 1); return; }
                if (ticks == 30) {
                    check(ui.regionBounds("macro-play:" + NAME).isPresent() && ui.regionBounds("macro-loop:" + NAME).isPresent(), "the Macros tab lists the macro with Play and Loop");
                    check(ui.regionBounds("macro-record").isPresent(), "the Macros tab has a Record button");
                    capture(c, "macros-tab");
                    WholeUiAcceptance.call(ui, "onAction", "macro-script:" + NAME);
                    var script = slooth.peripheral.script.ScriptPaths.resolve("macros/audit_path.py");
                    check(java.nio.file.Files.exists(script) && java.nio.file.Files.readString(script).contains("play_recording(\"audit path\", loops=0"), "Make a script writes a script that loops the macro");
                    WholeUiAcceptance.call(ui, "onAction", "macro-trim:" + NAME);
                    return;
                }
                if (ticks == 40) {
                    capture(c, "macros-trim");
                    check(ui.focusScopeId().orElse("").equals("trim-sheet"), "Trim opens its sheet");
                    ui.setValue("trim-start", 10d); ui.setValue("trim-end", 100d);
                    WholeUiAcceptance.call(ui, "onAction", "trim-ok");
                    check(load().ticks() == 90, "Trim keeps exactly the chosen ticks: " + load().ticks());
                    WholeUiAcceptance.call(ui, "renameMacro", NAME, RENAMED);
                    check(Recording.exists(RENAMED) && !Recording.exists(NAME), "Rename renames the macro file");
                    WholeUiAcceptance.call(ui, "switchTab", 1);
                    return;
                }
                if (ticks == 60) {
                    WholeUiAcceptance.clickPane(ui, "macro-play:" + RENAMED);
                    check(c.gui.screen() == null && Recorder.playing(), "Play closes the panel and plays the macro");
                    return;
                }
                if (ticks < 61 || Recorder.playing() && ticks < 2000) return;
                check(Recorder.message().startsWith("Finished"), "the trimmed, renamed macro plays to the end: " + Recorder.message());
                // A real Python script drives the recorder through mc.py.
                try {
                    java.nio.file.Files.createDirectories(slooth.peripheral.script.ScriptPaths.resolve("audit"));
                    java.nio.file.Files.writeString(slooth.peripheral.script.ScriptPaths.resolve("audit/macro_api.py"),
                        "from mc import *\nprint('LIST', recordings())\nprint('RESULT', play_recording('" + RENAMED + "', loops=2))\n");
                } catch (Exception e) { check(false, "write script: " + e); }
                check(slooth.peripheral.script.ScriptRunner.runScript("audit/macro_api.py"), "a Python script starts");
                next(); return;
            }
            if (stage == 9) {
                if (slooth.peripheral.script.ScriptRunner.isRunning("audit/macro_api.py") && ticks < 3000) return;
                var tail = String.join("\n", slooth.peripheral.script.ScriptRunner.logTail("audit/macro_api.py"));
                log("script output: " + tail.replace("\n", " | "));
                check(tail.contains("LIST") && tail.contains(RENAMED), "recordings() lists the macro from Python");
                check(tail.contains("RESULT Finished " + RENAMED + " (2 loops)"), "play_recording() from Python plays two loops and reports it");
                next(); return;
            }
            if (stage == 10) {
                // A Python script drives real screens through Minecraft's own mouse and keyboard path.
                if (ticks == 1) {
                    c.gui.setScreen(null);
                    c.player.connection.sendCommand("clear @s");
                    c.player.connection.sendCommand("give @s minecraft:dirt 5");
                    // G opens the panel directly, as it does once a player has accepted the terms.
                    slooth.peripheral.util.PeripheralConfig.termsAccepted = true;
                    slooth.peripheral.util.PeripheralConfig.baritoneWarnDismissed = true;
                    return;
                }
                if (ticks < 30) return;
                try {
                    java.nio.file.Files.writeString(slooth.peripheral.script.ScriptPaths.resolve("audit/ui_control.py"), String.join("\n",
                        "from mc import *",
                        "import time",
                        "def wait_for(kind, open_=True):",
                        "    for _ in range(40):",
                        "        s = screen()",
                        "        if (s.get('kind') == kind) if open_ else (not s['open']): return s",
                        "        time.sleep(0.1)",
                        "    return screen()",
                        "slot_select = int(3)",
                        "press_key('e')",
                        "s = wait_for('InventoryScreen')",
                        "print('INVENTORY', s.get('kind'), len(s.get('slots', [])), s['slots'][36]['item'])",
                        "click_slot(36); time.sleep(0.2); click_slot(9); time.sleep(0.3)",
                        "s = screen(); print('MOVED', s['slots'][9]['item'], s['slots'][9]['count'], s['slots'][36]['item'] or 'empty')",
                        "press_key('escape'); s = wait_for(None, False); print('CLOSED', s['open'])",
                        "press_key('escape'); s = wait_for('PauseScreen')",
                        "print('PAUSE', s.get('kind'), click_button('Back to Game'))",
                        "s = wait_for(None, False); print('RESUMED', s['open'])",
                        "press_key('t'); wait_for('ChatScreen'); type_text('hello from ui control'); press_key('enter')",
                        "s = wait_for(None, False); print('CHAT', s['open'])",
                        "press_key('g'); s = wait_for('PeripheralScreen')",
                        "print('PANEL', s.get('kind'), click_button('Settings'))",
                        "time.sleep(0.4); s = screen(); print('SETTINGS', any(ctl['label'] == 'Appearance' for ctl in s['controls']))",
                        "press_key('escape')",
                        ""));
                } catch (Exception e) { check(false, "write ui script: " + e); }
                check(slooth.peripheral.script.ScriptRunner.runScript("audit/ui_control.py"), "the UI control script starts");
                next(); return;
            }
            if (stage == 11) {
                if (slooth.peripheral.script.ScriptRunner.isRunning("audit/ui_control.py") && ticks < 1200) return;
                var tail = String.join("\n", slooth.peripheral.script.ScriptRunner.logTail("audit/ui_control.py"));
                log("ui script output: " + tail.replace("\n", " | "));
                check(tail.contains("INVENTORY InventoryScreen 46 minecraft:dirt"), "press_key('e') opens the inventory the real way, and screen() reads its slots");
                check(tail.contains("MOVED minecraft:dirt 5 empty"), "click_slot moves an item like real mouse clicks");
                check(tail.contains("CLOSED False"), "press_key('escape') closes the inventory");
                check(tail.contains("PAUSE PauseScreen Back to Game"), "click_button finds and clicks a vanilla button by its text");
                check(tail.contains("RESUMED False"), "the pause menu's own button closed it");
                check(slooth.peripheral.util.ChatLog.recent(40).stream().anyMatch(m -> m.text().contains("hello from ui control")), "typing in chat and pressing Enter sends the message");
                check(tail.contains("PANEL PeripheralScreen Settings"), "click_button works on PaneLib screens too");
                check(tail.contains("SETTINGS True"), "clicking a PaneLib tab by its label switches to it");
                next(); return;
            }
            if (stage == 12) {
                // Packet tools (scripts only, no UI): blocking a received packet, holding sent ones.
                if (ticks == 20) {
                    check(!slooth.peripheral.net.PacketTools.active() && !slooth.peripheral.net.PacketTools.logging(), "packet tools start off");
                    check(slooth.peripheral.net.PacketTools.allowed(), "blocking and holding are allowed in singleplayer");
                    slooth.peripheral.net.PacketTools.setLogging(true);
                    return;
                }
                if (ticks < 30) return;
                if (ticks < 50) { ScriptActions.movement("audit", 1, 0); return; }
                if (ticks == 50) {
                    ScriptActions.release("audit");
                    check(slooth.peripheral.net.PacketTools.recent(500).stream().anyMatch(e -> e.direction() == slooth.peripheral.net.PacketTools.Direction.SENT && e.name().startsWith("move_player")),
                        "walking logs sent movement packets by name");
                    check(slooth.peripheral.net.PacketTools.seen().stream().anyMatch(t -> t.direction() == slooth.peripheral.net.PacketTools.Direction.RECEIVED),
                        "received packets are counted by type");
                    check(slooth.peripheral.net.PacketTools.block(slooth.peripheral.net.PacketTools.Direction.RECEIVED, "system_chat", ""), "blocking a received packet type");
                    check(!slooth.peripheral.net.PacketTools.block(slooth.peripheral.net.PacketTools.Direction.SENT, "keep_alive", ""), "keep_alive can't be blocked");
                    c.player.connection.sendCommand("tellraw @s \"packet test hidden\"");
                    return;
                }
                if (ticks == 70) {
                    check(slooth.peripheral.util.ChatLog.recent(40).stream().noneMatch(m -> m.text().contains("packet test hidden")), "a blocked chat packet never reaches the game");
                    check(slooth.peripheral.net.PacketTools.recent(2000).stream().anyMatch(e -> e.name().equals("system_chat") && e.blocked()), "the log marks the blocked packet");
                    return;
                }
                if (ticks == 85) {
                    slooth.peripheral.net.PacketTools.unblock(slooth.peripheral.net.PacketTools.Direction.RECEIVED, "system_chat");
                    check(!slooth.peripheral.net.PacketTools.blocked(slooth.peripheral.net.PacketTools.Direction.RECEIVED, "system_chat"), "unblocking lets it through again");
                    c.gui.setScreen(null);
                    c.player.connection.sendCommand("tellraw @s \"packet test shown\"");
                    return;
                }
                if (ticks == 100) {
                    check(slooth.peripheral.util.ChatLog.recent(40).stream().anyMatch(m -> m.text().contains("packet test shown")), "unblocked chat arrives again");
                    serverStart = serverPos(c);
                    clientStart = c.player.position();
                    slooth.peripheral.net.PacketTools.setHolding(true, "");
                    holdStartMs = System.currentTimeMillis();
                    return;
                }
                if (ticks < 120) { ScriptActions.movement("audit", 1, 0); return; }
                if (ticks == 120) {
                    ScriptActions.release("audit");
                    // The server may still be one movement behind when holding starts; it must not get past where the client was then.
                    double clientMoved = c.player.position().distanceTo(clientStart), serverMoved = serverPos(c).distanceTo(clientStart);
                    log(String.format("holding: client moved %.2f, server saw %.2f, held %d; server %s -> %s, client %s -> %s; held kinds %s", clientMoved, serverMoved,
                        slooth.peripheral.net.PacketTools.heldCount(), serverStart, serverPos(c), clientStart, c.player.position(),
                        slooth.peripheral.net.PacketTools.recent(2000).stream().filter(e -> e.timeMs() > holdStartMs).map(e -> e.direction().name().charAt(0) + e.name() + (e.held() ? "*" : "")).distinct().toList()));
                    check(clientMoved > .5 && serverMoved < .05 && slooth.peripheral.net.PacketTools.heldCount() > 0, "while holding, the server doesn't see the player move");
                    capture(c, "packets-holding");
                    slooth.peripheral.net.PacketTools.release(true);
                    return;
                }
                if (ticks == 135) {
                    double gap = serverPos(c).distanceTo(c.player.position());
                    log(String.format("after release: server is %.3f from the client", gap));
                    check(gap < .3 && slooth.peripheral.net.PacketTools.heldCount() == 0 && !slooth.peripheral.net.PacketTools.holding(), "releasing sends the held packets and the server catches up");
                    try {
                        java.nio.file.Files.writeString(slooth.peripheral.script.ScriptPaths.resolve("audit/packets.py"), String.join("\n",
                            "from mc import *",
                            "import time",
                            "packet.log(True)",
                            "packet.cancel('swing')",
                            "print('BLOCKED', packet.cancelled())",
                            "try:",
                            "    packet.cancel('keep_alive')",
                            "except RuntimeError as e:",
                            "    print('PROTECTED', 'keeps the connection alive' in str(e))",
                            "time.sleep(0.6)",
                            "print('MOVES', len(packet.recent(200, direction='sent', name='move_player')) > 0)",
                            "print('ALLOWED', packet.allowed())",
                            "cmd = []",
                            "for _ in range(30):",
                            "    cmd = [p for p in packet.recent(2000, direction='sent', name='chat_command') if 'packet test shown' in p['summary']]",
                            "    if cmd: break",
                            "    time.sleep(0.1)",
                            "data = cmd[-1].get('data', '') if cmd else ''",
                            "print('HASDATA', len(data) > 0)",
                            "edited = bytes.fromhex(data).replace(b'packet test shown', b'packet test again').hex()",
                            "print('SENT', packet.send(edited).get('name'))",
                            "try:",
                            "    packet.send('ff00')",
                            "except RuntimeError as e:",
                            "    print('BAD', 'bad_packet' in str(e))",
                            ""));
                    } catch (Exception e) { check(false, "write packets script: " + e); }
                    c.player.connection.send(new net.minecraft.network.protocol.game.ServerboundChatCommandPacket("tellraw @s \"packet test shown\""));
                    check(slooth.peripheral.script.ScriptRunner.runScript("audit/packets.py"), "the packets script starts");
                    next();
                }
                return;
            }
            if (stage == 13) {
                if (slooth.peripheral.script.ScriptRunner.isRunning("audit/packets.py") && ticks < 600) return;
                if (ticks < 10) return;
                // A script's cleanup runs on the render thread just after its process exits, so give it a second.
                if (scriptGoneAt < 0) scriptGoneAt = ticks;
                if (ticks < scriptGoneAt + 20) return;
                var tail = String.join("\n", slooth.peripheral.script.ScriptRunner.logTail("audit/packets.py"));
                log("packets script output: " + tail.replace("\n", " | "));
                check(tail.contains("BLOCKED {'sent': ['swing'], 'received': []}"), "packet.cancel() from Python cancels a sent packet");
                check(tail.contains("PROTECTED True"), "Python gets a clear error for keep_alive");
                check(tail.contains("MOVES True"), "packet.recent() reads the log from Python");
                check(tail.contains("ALLOWED True"), "packet.allowed() is true in singleplayer");
                check(tail.contains("HASDATA True"), "the log keeps a sent packet's bytes");
                check(tail.contains("SENT chat_command"), "packet.send() sends an edited packet from its bytes");
                check(slooth.peripheral.util.ChatLog.recent(40).stream().anyMatch(m -> m.text().contains("packet test again")), "the server ran the edited command");
                check(tail.contains("BAD True"), "packet.send() rejects bytes that aren't a packet");
                check(slooth.peripheral.net.PacketTools.blockedNames(slooth.peripheral.net.PacketTools.Direction.SENT).isEmpty(), "a script's blocks are removed when it stops");
                slooth.peripheral.net.PacketTools.allOff();
                next(); return;
            }
            if (stage == 14) {
                log("COMPLETE " + (failures == 0 ? "PASS" : "FAIL") + " failures=" + failures);
                stage = 15;
                c.stop();
            }
        } catch (Throwable e) {
            log("EXCEPTION " + e); e.printStackTrace(); failures++; stage = 14;
        }
    }

    static Recording load() { try { return Recording.load(NAME); } catch (Exception e) { throw new IllegalStateException(e); } }
    static void press(net.minecraft.client.KeyMapping key) { var a = (KeyMappingAccess) key; a.peripheral$setClickCount(a.peripheral$getClickCount() + 1); }
    static double sq(double v) { return v * v; }
    static void next() { stage++; ticks = 0; }
    static void capture(Minecraft c, String name) { Screenshot.grab(c.gameDirectory, name + ".png", c.gameRenderer.mainRenderTarget(), 1, m -> {}); }
    static void check(boolean ok, String message) { if (!ok) failures++; log((ok ? "PASS " : "FAIL ") + message); }
    static void log(String m) { PeripheralClient.LOGGER.info("[RecorderAudit] {}", m); }
}
