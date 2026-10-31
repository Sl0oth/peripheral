package slooth.peripheral.audit;

import slooth.peripheral.ui.*;
import slooth.peripheral.script.*;
import slooth.peripheral.api.*;
import slooth.peripheral.hud.PeripheralHud;
import slooth.peripheral.util.PeripheralConfig;
import slooth.peripheral.util.PeripheralStateTracker;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.input.*;
import net.minecraft.network.chat.Component;
import com.google.gson.*;
import org.lwjgl.glfw.GLFW;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;

/** Development-only whole-UI regression; strictly disposable directory, no legal acceptance or remote writes. */
public final class WholeUiAcceptance {
    static int stage,ticks,failures,viewIndex;
    static PeripheralScreen root;
    static String savedKey,savedUrl,savedModel,savedTheme,savedClipboard;
    static byte[] savedConfig;
    static String editedContents;
    static com.sun.net.httpserver.HttpServer agentFixture;
    static final java.util.concurrent.ConcurrentLinkedQueue<String> agentPosts=new java.util.concurrent.ConcurrentLinkedQueue<>();
    static int idlePaints;
    static final int[][] SIZES={{854,480,2},{480,360,3},{1440,900,2},{1920,1200,3}};
    static final String[] VIEWS={"log","files","settings","build","editor","picker","terms-no-accept","warning","hud","scriptable"};
    static final String EDITOR="audit/whole_ui_editor.py",ORIGINAL="# Whole UI fixture\nprint('fixture')\n";
    static void log(String s){slooth.peripheral.PeripheralClient.LOGGER.info("[WholeUiAudit] {}",s);}
    static void check(boolean ok,String s){if(!ok)failures++;log((ok?"PASS ":"FAIL ")+s);}
    public static void init(){
        var c=Minecraft.getInstance();
        if(!c.gameDirectory.toPath().toAbsolutePath().toString().endsWith("run-pilot-audit"))throw new IllegalStateException("Wrong UI audit directory");
        ClientTickEvents.END_CLIENT_TICK.register(WholeUiAcceptance::tick);
    }
    static void tick(Minecraft c){try{
        ticks++;
        if(stage==0){if(c.gui.screen() instanceof TitleScreen&&ticks>30){PilotAcceptance.load(c);next();}return;}
        if(stage==1){if(c.player!=null&&ticks>60){
            c.options.pauseOnLostFocus=false;c.options.renderDistance().set(6);c.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
            if(Boolean.getBoolean("peripheral.wholeUiPreview")) {
                if(c.getWindow().isFullscreen())c.getWindow().toggleFullScreen();
                c.getWindow().setWindowed(1000,650);c.options.guiScale().set(2);c.resizeGui();
                root=new PeripheralScreen();c.gui.setScreen(root);stage=7;ticks=0;return;
            }
            savedKey=PeripheralConfig.apiKey;savedUrl=PeripheralConfig.agentUrl;savedModel=PeripheralConfig.model;savedTheme=PeripheralConfig.theme;
            Path cfg=c.gameDirectory.toPath().resolve("config/peripheral.json");if(Files.exists(cfg))savedConfig=Files.readAllBytes(cfg);
            savedClipboard=c.keyboardHandler.getClipboard();PeripheralConfig.apiKey="";
            agentFixture=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
            agentFixture.createContext("/",exchange->{String body=new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);agentPosts.add(exchange.getRequestURI().getPath()+" "+body);byte[] response="{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();});agentFixture.start();
            Files.createDirectories(ScriptPaths.resolve("audit"));Files.writeString(ScriptPaths.resolve(EDITOR),ORIGINAL);
            root=new PeripheralScreen();next();
            check(Arrays.equals((String[])getStatic(PeripheralScreen.class,"TAB_LABELS"),new String[]{"Scripts","Macros","Log","Build","HUD","Settings"}),"main navigation contains six tabs and no Store");
            boolean storeClass=false;try{Class.forName("slooth.peripheral.api.StoreClient");storeClass=true;}catch(ClassNotFoundException expected){}check(!storeClass,"no Store client class on runtime classpath");
        }return;}
        if(stage==2){
            int total=SIZES.length*VIEWS.length;
            if(viewIndex>=total){c.getWindow().setWindowed(1000,650);if(c.getWindow().isFullscreen())c.getWindow().toggleFullScreen();c.options.guiScale().set(2);c.resizeGui();root=new PeripheralScreen();c.gui.setScreen(root);next();return;}
            int si=viewIndex/VIEWS.length,vi=viewIndex%VIEWS.length;int[] size=SIZES[si];
            if(ticks==1){
                if(c.getWindow().isFullscreen()!=(si==3))c.getWindow().toggleFullScreen();
                if(si!=3)c.getWindow().setWindowed(size[0],size[1]);c.options.guiScale().set(size[2]);c.resizeGui();
                show(c,VIEWS[vi]);
            }
            if(ticks==24){
                capture(c,"whole-"+si+"-"+VIEWS[vi]);
                check(c.gui.screen()!=null,"visible "+VIEWS[vi]+" size="+si);
                log("VIEW "+VIEWS[vi]+" size="+si+" class="+c.gui.screen().getClass().getName()+" framebuffer="+c.getWindow().getWidth()+"x"+c.getWindow().getHeight()+" gui="+c.getWindow().getGuiScale());
                if(c.gui.screen() instanceof dev.pane.ui.UiScreen pane)log("PANE "+pane.performanceSnapshot().summary());
            }
            if(ticks==30){if(c.gui.screen() instanceof ScriptableScreen)ScriptableScreen.clearIfOwner("ui-audit");PeripheralHud.clearIfOwner("ui-audit");viewIndex++;ticks=0;}
            return;
        }
        if(stage==3){
            if(ticks==10){call(root,"switchTab",0);check(c.gui.screen()==root,"main Scripts tab navigation");}
            if(ticks==25){c.gui.setScreen(new ScriptEditorScreen(root,EDITOR));}
            if(ticks==40){c.gui.screen().charTyped(new CharacterEvent('X'));c.gui.screen().keyPressed(key(GLFW.GLFW_KEY_Z,GLFW.GLFW_MOD_CONTROL|GLFW.GLFW_MOD_SUPER));c.gui.screen().keyPressed(key(GLFW.GLFW_KEY_S,GLFW.GLFW_MOD_CONTROL|GLFW.GLFW_MOD_SUPER));check(Files.readString(ScriptPaths.resolve(EDITOR)).equals(ORIGINAL),"editor typing/undo/save preserves actual file");c.gui.screen().charTyped(new CharacterEvent('Y'));}
            if(ticks==45){c.getWindow().setWindowed(480,360);c.options.guiScale().set(3);c.resizeGui();}
            if(ticks==60){c.gui.screen().keyPressed(key(GLFW.GLFW_KEY_S,GLFW.GLFW_MOD_CONTROL|GLFW.GLFW_MOD_SUPER));check(Files.readString(ScriptPaths.resolve(EDITOR)).startsWith("Y"),"unsaved editor content survives resize and saves");capture(c,"whole-editor-after-resize");c.gui.screen().charTyped(new CharacterEvent('Z'));c.gui.screen().onClose();}
            if(ticks==65){check(c.gui.screen() instanceof UnsavedChangesScreen,"editor Back offers save/discard/cancel");capture(c,"whole-unsaved-choice");c.gui.screen().onClose();}
            if(ticks==70){check(c.gui.screen() instanceof ScriptEditorScreen,"Escape in unsaved choice keeps the editor");c.gui.screen().keyPressed(key(GLFW.GLFW_KEY_S,GLFW.GLFW_MOD_CONTROL|GLFW.GLFW_MOD_SUPER));c.gui.screen().onClose();}
            if(ticks==75){check(c.gui.screen()==root,"editor Back returns to parent");String before=Files.readString(ScriptPaths.resolve(EDITOR));Path backup=BuildDraftSafety.snapshot(EDITOR);check(backup!=null&&Files.readString(backup).equals(before),"pre-generation snapshot preserves exact existing file");check(Files.readString(ScriptPaths.resolve(EDITOR)).equals(before),"snapshot does not alter current script");c.getWindow().setWindowed(1000,650);c.options.guiScale().set(2);c.resizeGui();call(root,"openSettings",0);}
            if(ticks==87){typeField(root,"model","ui-audit-model");}
            if(ticks==90){
                press(root,"Save");
                check(PeripheralConfig.model.equals("ui-audit-model"),"settings Save updates actual model");
                check(Files.readString(c.gameDirectory.toPath().resolve("config/peripheral.json")).contains("ui-audit-model"),"settings Save persists in disposable config");
                check(root.regionBounds("agent-url").isEmpty()&&root.regionBounds("mcp-toggle").isEmpty(),"the AI section shows only AI settings");
                capture(c,"whole-settings-ai");call(root,"onAction","settings-section:2");
            }
            if(ticks==95){check(root.regionBounds("mcp-toggle").isPresent()&&root.regionBounds("api-key").isEmpty(),"picking MCP shows the MCP section");capture(c,"whole-settings-mcp");
                clickPane(root,"mcp-toggle");check(PeripheralConfig.mcpEnabled&&slooth.peripheral.api.PeripheralMcp.active(),"the MCP switch turns the local endpoint on");}
            if(ticks==97){clickPane(root,"mcp-toggle");check(!PeripheralConfig.mcpEnabled&&!slooth.peripheral.api.PeripheralMcp.active(),"the MCP switch turns it off again");
                call(root,"onAction","settings-section:3");}
            if(ticks==98){check(root.regionBounds("theme").isPresent(),"Appearance has the theme picker");capture(c,"whole-settings-look");call(root,"switchTab",2);}
            if(ticks==101){clickPane(root,"agent-settings");}
            if(ticks==105){check(root.regionBounds("agent-url").isPresent()&&root.regionBounds("api-key").isEmpty(),"the Log's Change button opens the Agent section");capture(c,"whole-settings-agent");
                typeField(root,"agent-url","http://127.0.0.1:1");check(!root.stringValue("agent-url","").equals(PeripheralConfig.agentUrl),"typing in a field does not save it");}
            if(ticks==107){clickPane(root,"agent-save");check(PeripheralConfig.agentUrl.equals("http://127.0.0.1:1"),"the Agent section saves its own field");
                PeripheralConfig.apiKey="";call(root,"switchTab",3);call(root,"openBuildSession",EDITOR);
            }
            if(ticks==110){typeField(root,"build-chat","Keep this prompt when a key is missing");press(root,"Send");check(root.stringValue("build-chat","").equals("Keep this prompt when a key is missing"),"missing-key Send preserves draft prompt");capture(c,"whole-build-missing-key");}
            if(ticks==125){c.gui.setScreen(new BuildPickerScreen(root,n->{c.gui.setScreen(root);},n->{c.gui.setScreen(root);}));}
            if(ticks==140){press(c.gui.screen(),"Cancel");}
            if(ticks==155){check(c.gui.screen()==root,"picker Cancel restores its parent");typeField(root,"build-chat","modal background draft");ScriptRunner.fileAccessRequestScript=EDITOR;}
            if(ticks==160){capture(c,"whole-file-access-choice");check(root.openDialogId().orElse("").equals("file-access")&&root.focusedId().orElse("").equals("dialog-cancel"),"file-access request opens a dialog focused on Deny");}
            if(ticks==165){c.gui.screen().charTyped(new CharacterEvent('Q'));c.gui.screen().keyPressed(key(GLFW.GLFW_KEY_ENTER,0));check(root.stringValue("build-chat","").equals("modal background draft"),"safety sheet blocks background typing and submit");check(!PeripheralConfig.getFileAccess(EDITOR),"file-access choice defaults to Deny");call(root,"switchTab",0);call(root,"onAction","autoset:world:"+EDITOR);call(root,"onAction","hotkey:3:"+EDITOR);
                check(PeripheralConfig.getAutoRun(EDITOR).equals("world")&&PeripheralConfig.slotOf(EDITOR)==3,"Scripts tab sets auto-run and a hotkey slot");call(get(root,"scriptsPanel"),"select",EDITOR);}
            if(ticks==172){capture(c,"whole-script-settings");call(root,"startRename",EDITOR);}
            if(ticks==180){check(root.focusedId().orElse("").equals("rename-field")&&root.focusScopeId().orElse("").equals("rename-sheet"),"rename sheet opens on its name field");root.keyPressed(key(GLFW.GLFW_KEY_A,GLFW.GLFW_MOD_CONTROL|GLFW.GLFW_MOD_SUPER));for(int ch:"whole_ui_editor_renamed.py".codePoints().toArray())root.charTyped(new CharacterEvent(ch));capture(c,"whole-rename-choice");root.keyPressed(key(GLFW.GLFW_KEY_ENTER,0));}
            if(ticks==185){check(Files.exists(ScriptPaths.resolve("audit/whole_ui_editor_renamed.py"))&&!Files.exists(ScriptPaths.resolve(EDITOR)),"rename commits the actual local file");
                String renamed="audit/whole_ui_editor_renamed.py";check(PeripheralConfig.getAutoRun(renamed).equals("world")&&PeripheralConfig.slotOf(renamed)==3&&PeripheralConfig.slotOf(EDITOR)==0,"rename carries auto-run and hotkey to the new name");call(root,"startRename","audit/whole_ui_editor_renamed.py");}
            if(ticks==195){root.keyPressed(key(GLFW.GLFW_KEY_A,GLFW.GLFW_MOD_CONTROL|GLFW.GLFW_MOD_SUPER));for(int ch:"whole_ui_editor.py".codePoints().toArray())root.charTyped(new CharacterEvent(ch));root.keyPressed(key(GLFW.GLFW_KEY_ENTER,0));editedContents=Files.readString(ScriptPaths.resolve(EDITOR));call(root,"onAction","del:"+EDITOR);}
            if(ticks==205){capture(c,"whole-delete-choice");clickPane(root,"dialog-cancel");check(Files.exists(ScriptPaths.resolve(EDITOR)),"delete Cancel preserves the file");call(root,"onAction","del:"+EDITOR);}
            if(ticks==215){clickPane(root,"dialog-confirm");check(!Files.exists(ScriptPaths.resolve(EDITOR)),"delete confirms only the disposable fixture");Files.writeString(ScriptPaths.resolve(EDITOR),editedContents);PeripheralConfig.setAutoRun(EDITOR,"off");PeripheralConfig.assignHotkey(EDITOR,0);call(root,"switchTab",2);PeripheralConfig.agentUrl="http://127.0.0.1:"+agentFixture.getAddress().getPort();}
            if(ticks==225){typeField(root,"agent-task","Local UI fixture task");clickPane(root,"agent-send");}
            if(ticks==240){check(agentPosts.stream().anyMatch(p->p.startsWith("/task ")&&p.contains("Local UI fixture task")),"Log Send sends actual JSON to the local fixture");check(root.stringValue("agent-task","").isEmpty(),"acknowledged task clears only its sent draft");clickPane(root,"agent-stop");}
            if(ticks==250){check(agentPosts.stream().anyMatch(p->p.startsWith("/stop ")),"Log Stop sends to the local fixture");PeripheralConfig.agentUrl="not a valid URL";typeField(root,"agent-task","Keep this task on URL error");clickPane(root,"agent-send");}
            if(ticks==265){check(root.stringValue("agent-task","").equals("Keep this task on URL error"),"failed Log Send preserves the draft");capture(c,"whole-agent-draft-preserved");
                PeripheralStateTracker.clearLog();check(root.regionBounds("agent-pill").isEmpty(),"no agent pill while the agent is idle");
                PeripheralStateTracker.pushScriptLog("[audit.py] ERROR boom");
                PeripheralStateTracker.pushLog("Mine some iron","working","Walking to https://example.com/peripheral/docs now",2);
                call(root,"switchTab",0);}
            if(ticks==275){check(root.regionBounds("agent-pill").isPresent(),"the header shows an agent pill while it works");clickPane(root,"agent-pill");}
            if(ticks==285){String v=root.stringValue("pane:view:log","");check(v.contains("Walking to https://example.com")&&!v.contains("boom"),"the agent pill opens the Log on the agent's lines");capture(c,"whole-log-agent");clickPane(root,"log-filter:1");}
            if(ticks==295){String v=root.stringValue("pane:view:log","");check(v.contains("[audit.py] ERROR boom")&&!v.contains("Walking"),"the Scripts filter shows only script lines");clickPane(root,"log-filter:0");}
            if(ticks==305){String v=root.stringValue("pane:view:log","");check(v.contains("boom")&&v.contains("Walking"),"All shows both sources");clickPane(root,"log-copy");
                check(c.keyboardHandler.getClipboard().equals(v),"Copy copies exactly the lines shown");capture(c,"whole-log-all");
                var b=root.regionBounds("log").orElseThrow();check(b.height()>100,"the log view fills the tab");}
            if(ticks==315){PeripheralStateTracker.pushLog(null,"idle",null,-1);clickPane(root,"log-clear");check(PeripheralStateTracker.getLog(10).isEmpty(),"Clear empties the log");}
            if(ticks==325){check(root.regionBounds("agent-pill").isEmpty(),"the pill goes when the agent reports idle");next();}
            return;
        }
        if(stage==4){
            if(ticks==10){JsonObject el=JsonParser.parseString("{\"id\":\"audit-label\",\"type\":\"text\",\"text\":\"HUD coordinate fixture\",\"x\":40,\"y\":70,\"color\":\"#ffbbdd88\"}").getAsJsonObject();PeripheralHud.setElements(List.of(el),"ui-audit");PeripheralHud.resetLayout("ui-audit");c.gui.setScreen(root);call(root,"switchTab",4);}
            if(ticks==18){check(root.regionBounds("hud-clear-owner:ui-audit").isPresent(),"the HUD tab lists the script drawing on the HUD");check(root.regionBounds("hud-reset:ui-audit").isEmpty(),"no Reset positions before anything moved");capture(c,"whole-hud-tab");clickPane(root,"hud-edit");}
            if(ticks==28){check(c.gui.screen() instanceof dev.pane.ui.UiHudEditor ed&&ed.names().contains("ui-audit · audit-label"),"Move HUD opens PaneLib's HUD editor with the script's element");
                var ed=c.gui.screen();var down=new MouseButtonEvent(42,72,new MouseButtonInfo(0,0));ed.mouseClicked(down,false);var move=new MouseButtonEvent(66,88,new MouseButtonInfo(0,0));ed.mouseDragged(move,24,16);ed.mouseReleased(move);}
            if(ticks==31){capture(c,"whole-hud-dragged");check(PeripheralHud.savedPlacement("ui-audit","audit-label")==null,"a drag is not saved until the editor closes");c.gui.screen().onClose();}
            if(ticks==35){check(c.gui.screen()==root,"HUD Done returns to parent");var saved=PeripheralHud.savedPlacement("ui-audit","audit-label");check(saved!=null,"closing the editor saves where the element went");
                if(saved!=null){var b=saved.bounds(c.getWindow().getGuiScaledWidth(),c.getWindow().getGuiScaledHeight(),c.font.width("HUD coordinate fixture"),c.font.lineHeight,1);check(Math.round(b.x())==64&&Math.round(b.y())==88,"the element lands where it was dropped, on the 4 px grid, in GUI pixels: "+b);}
                check(Files.readString(c.gameDirectory.toPath().resolve("config/peripheral/hud_layouts.json")).contains("audit-label"),"the move is written to hud_layouts.json");}
            if(ticks==38){check(root.regionBounds("hud-reset:ui-audit").isPresent(),"Reset positions appears once an element moved");clickPane(root,"hud-reset:ui-audit");check(PeripheralHud.savedPlacement("ui-audit","audit-label")==null,"Reset positions forgets the move");}
            if(ticks==40){PeripheralHud.clearIfOwner("ui-audit");showScriptable();}
            if(ticks==55){
                check(ScriptableScreen.ownedBy("ui-audit"),"script-created GUI owner preserved");
                check(((Number)getStatic(ScriptableScreen.class,"sBg")).intValue()==0xFF213549,"script-authored background color preserved");
                int px=((Number)get(c.gui.screen(),"panelX")).intValue(),py=((Number)get(c.gui.screen(),"panelY")).intValue();
                clickAt(c.gui.screen(),px+6+8+45,py+15+8+10);
                check("fixture-button".equals(ScriptableScreen.pollEvent()),"script GUI button queues real event");capture(c,"whole-scriptable-custom-style");
                clickAt(c.gui.screen(),px+6+112+20,py+15+8+10);c.gui.screen().charTyped(new CharacterEvent('A'));
                check("A".equals(ScriptableScreen.getInputValues().get("fixture-input")),"script GUI input accepts real characters");
                ScriptableScreen.update("fixture-input",JsonParser.parseString("{\"value\":\"from update\"}").getAsJsonObject());
            }
            if(ticks==56){int x=((Number)get(c.gui.screen(),"panelX")).intValue(),y=((Number)get(c.gui.screen(),"panelY")).intValue();clickAt(c.gui.screen(),x+6+8+4,y+15+40+4);check("true".equals(ScriptableScreen.getInputValues().get("fixture-check")),"script checkbox raw-coordinate input works");clickAt(c.gui.screen(),x+6+8+30,y+15+68+10);}
            if(ticks==57)check("from update".equals(ScriptableScreen.getInputValues().get("fixture-input")),"GUI update replaces the visible input value");
            if(ticks==63){capture(c,"whole-scriptable-dropdown");int x=((Number)get(c.gui.screen(),"panelX")).intValue(),y=((Number)get(c.gui.screen(),"panelY")).intValue();clickAt(c.gui.screen(),x+6+8+30,y+15+68+20+30);check("1".equals(ScriptableScreen.getInputValues().get("fixture-drop")),"dropdown popup selects the painted row");}
            if(ticks==64){int x=((Number)get(c.gui.screen(),"panelX")).intValue(),y=((Number)get(c.gui.screen(),"panelY")).intValue();clickAt(c.gui.screen(),x+6+20,y+15+100+24);check("1".equals(ScriptableScreen.getInputValues().get("fixture-list")),"script GUI list selects actual row");clickAt(c.gui.screen(),x+6+8+200,y+15+180+7);check("second".equals(ScriptableScreen.getInputValues().get("fixture-tabs")),"script tabs select actual tab");clickAt(c.gui.screen(),x+6+200+35,y+15+68+5);check(Math.abs(Double.parseDouble(ScriptableScreen.getInputValues().get("fixture-slider"))-.5)<.01,"script slider returns actual value");while(ScriptableScreen.pollEvent()!=null){}c.gui.screen().keyPressed(key(GLFW.GLFW_KEY_TAB,0));c.gui.screen().keyPressed(key(GLFW.GLFW_KEY_ENTER,0));check("fixture-button".equals(ScriptableScreen.pollEvent()),"script GUI Tab/Enter activates a real button");c.gui.screen().keyPressed(key(GLFW.GLFW_KEY_TAB,0));c.gui.screen().charTyped(new CharacterEvent('B'));String value=ScriptableScreen.getInputValues().get("fixture-input");check(value.endsWith("B"),"Tab reaches the script input");c.gui.screen().charTyped(new CharacterEvent(0x1F642));c.gui.screen().keyPressed(key(GLFW.GLFW_KEY_BACKSPACE,0));check(value.equals(ScriptableScreen.getInputValues().get("fixture-input")),"Unicode input backspace removes a complete codepoint");}
            if(ticks==65)ScriptableScreen.clearIfOwner("ui-audit");
            if(ticks==70){check(!ScriptableScreen.hasLayout(),"script GUI owner cleanup removes layout");check(ScriptableScreen.getInputValues().isEmpty(),"owner cleanup leaves no stale input values");ScriptableScreen.openWithLayout(JsonParser.parseString("{\"theme\":\"terminal\",\"title\":\"Explicit author theme\",\"w\":300,\"h\":180,\"widgets\":[{\"id\":\"label\",\"type\":\"label\",\"text\":\"Author-selected terminal palette\",\"x\":8,\"y\":8}]}").getAsJsonObject(),"ui-audit");}
            if(ticks==85){check(Boolean.FALSE.equals(get(c.gui.screen(),"modern")),"explicit author theme keeps its palette");capture(c,"whole-scriptable-explicit-theme");ScriptableScreen.clearIfOwner("ui-audit");restore(c);root=new PeripheralScreen();c.gui.setScreen(root);next();}
            return;
        }
        if(stage==7){if(ticks==30){capture(c,"whole-interactive-preview");log("INTERACTIVE PREVIEW READY; no automated actions or config changes");}return;}
        if(stage==5){if(ticks==30){call(root,"switchTab",0);capture(c,"whole-final-preview");}if(ticks==45)idlePaints=root.paintCount();if(ticks==105){check(root.paintCount()==idlePaints,"unchanged main window has zero paints over three seconds");buildChatFixture(c);}
            if(ticks==125){capture(c,"whole-build-chat");check(root.regionBounds("copy:1").isPresent()&&root.regionBounds("copy:0").isEmpty(),"each reply, and only replies, has a Copy button");clickPane(root,"copy:1");
                check(c.keyboardHandler.getClipboard().contains("print('hello')"),"Copy puts the whole reply, code included, on the clipboard");
                check(root.regionBounds("code").isPresent(),"code shows in a selectable text view");
                Files.deleteIfExists(BuildSession.CHATS_DIR.resolve("audit_whole_ui_chat.py.json"));Files.deleteIfExists(ScriptPaths.resolve(CHAT));
                log("WHOLE UI READY failures="+failures+"; no legal acceptance, remote posts or paid AI calls were made");stage=6;}}
    }catch(Throwable e){log("EXCEPTION "+e);e.printStackTrace();failures++;try{restore(c);}catch(Throwable ignored){}c.stop();}}
    static final String CHAT="audit/whole_ui_chat.py";
    /** A local chat file and script, so Build shows real cards; nothing is sent anywhere. */
    static void buildChatFixture(Minecraft c)throws Exception{
        Files.writeString(ScriptPaths.resolve(CHAT),"from mc import *\nprint('hello')\n");
        Files.createDirectories(BuildSession.CHATS_DIR);
        Files.writeString(BuildSession.CHATS_DIR.resolve("audit_whole_ui_chat.py.json"),
            "{\"script\":\""+CHAT+"\",\"messages\":[{\"role\":\"user\",\"content\":\"Say hello in chat when I join.\"},"
            +"{\"role\":\"assistant\",\"content\":\"Here is a script that greets you:\\n```python\\nfrom mc import *\\nprint('hello')\\n```\\nRun it and it prints hello.\"}]}");
        call(root,"openBuildSession",CHAT);call(root,"switchTab",3);
        if(c.getWindow().isFullscreen())c.getWindow().toggleFullScreen();c.getWindow().setWindowed(1440,900);c.options.guiScale().set(2);c.resizeGui();
    }
    static void show(Minecraft c,String view)throws Exception{
        PeripheralConfig.apiKey=view.equals("settings")?"fixture-not-a-real-key":"";
        root=new PeripheralScreen();c.gui.setScreen(root);
        switch(view){
            case "log" -> call(root,"switchTab",2);
            case "files" -> call(root,"switchTab",0);
            case "settings" -> call(root,"openSettings",0);
            case "build" -> {call(root,"switchTab",3);call(root,"openBuildSession",EDITOR);}
            case "editor" -> c.gui.setScreen(new ScriptEditorScreen(root,EDITOR));
            case "picker" -> c.gui.setScreen(new BuildPickerScreen(root,n->c.gui.setScreen(root),n->c.gui.setScreen(root)));
            case "terms-no-accept" -> c.gui.setScreen(new TermsScreen(root));
            case "warning" -> c.gui.setScreen(new BaritoneWarningScreen(root));
            case "hud" -> {PeripheralHud.setElements(List.of(JsonParser.parseString("{\"id\":\"audit-label\",\"type\":\"text\",\"text\":\"HUD coordinate fixture\",\"x\":40,\"y\":70}").getAsJsonObject()),"ui-audit");call(root,"onAction","hud-edit");}
            case "scriptable" -> showScriptable();
        }
    }
    static void showScriptable(){
        ScriptableScreen.openWithLayout(JsonParser.parseString("""
            {"title":"Script-authored layout fixture","w":300,"h":220,"bg_color":"#ff213549","widgets":[
            {"id":"fixture-button","type":"button","label":"Queue event","x":8,"y":8,"w":90,"h":20},
            {"id":"fixture-input","type":"input","placeholder":"Author input","x":112,"y":8,"w":160,"h":20},
            {"id":"fixture-check","type":"checkbox","label":"Author checkbox","x":8,"y":40},
            {"id":"fixture-drop","type":"dropdown","x":8,"y":68,"w":180,"h":20,"options":["One","Two","Three"]},
            {"id":"fixture-slider","type":"slider","x":200,"y":68,"w":70,"h":12,"min":0,"max":1,"step":0.1},
            {"id":"fixture-list","type":"list","x":8,"y":100,"w":260,"h":66,"items":["Alpha","Beta","Gamma"]},
            {"id":"fixture-tabs","type":"tabs","x":8,"y":180,"w":270,"items":[{"id":"first","label":"First"},{"id":"second","label":"Second"}],"selected":"first"},
            {"id":"fixture-item","type":"item","item":"minecraft:diamond","count":3,"x":40,"y":100}]}
            """).getAsJsonObject(),"ui-audit");
    }
    static void restore(Minecraft c)throws Exception{if(agentFixture!=null){agentFixture.stop(0);agentFixture=null;}if(savedKey==null)return;PeripheralConfig.apiKey=savedKey;PeripheralConfig.agentUrl=savedUrl;PeripheralConfig.model=savedModel;PeripheralConfig.theme=savedTheme;if(savedConfig!=null)Files.write(c.gameDirectory.toPath().resolve("config/peripheral.json"),savedConfig);if(savedClipboard!=null)c.keyboardHandler.setClipboard(savedClipboard);PeripheralHud.clearIfOwner("ui-audit");ScriptableScreen.clearIfOwner("ui-audit");}
    static KeyEvent key(int k,int mods){return new KeyEvent(k,0,mods);}
    static void clickAt(Screen s,double x,double y){var e=new MouseButtonEvent(x,y,new MouseButtonInfo(0,0));s.mouseMoved(x,y);s.mouseClicked(e,false);s.mouseReleased(e);}
    static void press(Screen s,String label){
        if(s instanceof dev.pane.ui.UiScreen pane){String id=switch(label){case "Save"->s instanceof PeripheralScreen?"settings-save":"save";case "Send"->"build-send";case "Cancel"->"cancel";default->label.toLowerCase();};clickPane(pane,id);return;}
        for(var ch:s.children())if(ch instanceof Button b&&b.getMessage().getString().equals(label)){check(b.active,"button enabled "+label);if(b.active)b.onPress(key(GLFW.GLFW_KEY_ENTER,0));return;}check(false,"button found "+label);
    }
    static void clickPane(dev.pane.ui.UiScreen pane,String id){var b=pane.regionBounds(id);check(b.isPresent(),"painted control "+id);if(b.isPresent())clickAt(pane,b.get().centerX()/pane.layoutScale(),b.get().centerY()/pane.layoutScale());}
    static void typeField(dev.pane.ui.UiScreen pane,String id,String text){clickPane(pane,id);pane.keyPressed(key(GLFW.GLFW_KEY_A,GLFW.GLFW_MOD_CONTROL|GLFW.GLFW_MOD_SUPER));for(int ch:text.codePoints().toArray())pane.charTyped(new CharacterEvent(ch));}
    static Field field(Class<?> c,String n)throws Exception{for(Class<?> k=c;k!=null;k=k.getSuperclass())try{Field f=k.getDeclaredField(n);f.setAccessible(true);return f;}catch(NoSuchFieldException ignored){}throw new NoSuchFieldException(n);}
    static Object get(Object o,String n)throws Exception{return field(o.getClass(),n).get(o);}
    static Object getStatic(Class<?> c,String n)throws Exception{return field(c,n).get(null);}
    static void set(Object o,String n,Object v)throws Exception{field(o.getClass(),n).set(o,v);}
    static Object call(Object o,String n,Object...args)throws Exception{for(Class<?> k=o.getClass();k!=null;k=k.getSuperclass())for(Method m:k.getDeclaredMethods())if(m.getName().equals(n)&&m.getParameterCount()==args.length){m.setAccessible(true);return m.invoke(o,args);}throw new NoSuchMethodException(n);}
    static void capture(Minecraft c,String name){Screenshot.grab(c.gameDirectory,name+".png",c.gameRenderer.mainRenderTarget(),1,message->{});}
    static void next(){stage++;ticks=0;}
}
