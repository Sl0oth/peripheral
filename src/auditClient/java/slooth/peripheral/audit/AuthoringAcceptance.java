package slooth.peripheral.audit;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.*;
import net.minecraft.core.BlockPos;
import org.lwjgl.glfw.GLFW;
import slooth.peripheral.PeripheralClient;
import slooth.peripheral.hud.PeripheralHud;
import slooth.peripheral.script.*;
import slooth.peripheral.ui.*;

/** Disposable-world example acceptance. Reflection observes widgets, never powers example behavior. */
public final class AuthoringAcceptance {
    static final String TERMINAL="examples/gui/terminal.py",HUD="examples/hud/trail.py";
    static int stage,ticks,failures; static BlockPos origin;
    static final AtomicBoolean prepared=new AtomicBoolean();
    public static void init(){
        if(!Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath().toString().endsWith("run-pilot-audit"))throw new IllegalStateException("Wrong audit directory");
        ClientTickEvents.END_CLIENT_TICK.register(AuthoringAcceptance::tick);
    }
    static void log(String s){PeripheralClient.LOGGER.info("[AuthoringAudit] {}",s);}
    static void check(boolean ok,String s){if(!ok)failures++;log((ok?"PASS ":"FAIL ")+s);}
    static void next(){stage++;ticks=0;}
    static void tick(Minecraft c){try{
        ticks++;
        if(stage<6&&ticks>1000){check(false,"timeout stage="+stage);ScriptRunner.stopAll();c.stop();return;}
        if(stage==0){if(c.gui.screen() instanceof TitleScreen&&ticks>35){PilotAcceptance.load(c);next();}return;}
        if(stage==1){if(c.player!=null&&ticks>60){
            c.options.pauseOnLostFocus=false;c.options.renderDistance().set(6);c.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
            if(c.getWindow().isFullscreen())c.getWindow().toggleFullScreen();c.getWindow().setWindowed(1000,650);c.options.guiScale().set(2);c.resizeGui();
            if(Boolean.getBoolean("peripheral.authoringPreview")){
                Files.writeString(ScriptPaths.resolve("audit/review_json_probe.py"),"""
                    from mc import json_save_file, json_load_file, waypoints_save, waypoints_load, waypoints_upsert, waypoints_remove
                    path='audit/review-json-data.json'
                    data={'unicode':'café','position':[1,64,-2]}
                    json_save_file(path,data)
                    assert json_load_file(path)==data
                    waypoints_save([{'label':'Home','x':1}],path)
                    waypoints_upsert({'label':'Home','x':2},path)
                    waypoints_upsert({'label':'Mine','x':8},path)
                    assert len(waypoints_load(path))==2
                    assert waypoints_remove('Home',path)==[{'label':'Mine','x':8}]
                    print('JSON ROUNDTRIP PASS from installed mc.py',flush=True)
                    """);
                ScriptRunner.runScript("audit/review_json_probe.py");
                ScriptRunner.runScript(HUD);ScriptRunner.runScript(TERMINAL);stage=7;ticks=0;return;
            }
            Files.createDirectories(ScriptPaths.resolve("audit"));
            origin=c.player.blockPosition().above(8);
            var server=c.getSingleplayerServer();var uuid=c.player.getUUID();
            server.execute(()->{var sp=server.getPlayerList().getPlayer(uuid);var source=sp.createCommandSourceStack();
                for(String command:List.of("fill "+xyz(-6,-1,-6)+" "+xyz(6,-1,6)+" minecraft:stone","fill "+xyz(-6,0,-6)+" "+xyz(6,5,6)+" minecraft:air","tp @s "+(origin.getX()+.5)+" "+origin.getY()+" "+(origin.getZ()+.5),"clear @s","give @s minecraft:iron_axe","give @s minecraft:oak_log 3","gamemode survival @s","time set day","weather clear","setblock "+xyz(2,0,0)+" minecraft:oak_log"))server.getCommands().performPrefixedCommand(source,command);
                prepared.set(true);
            });
            c.gui.setScreen(null);next();
        }return;}
        if(stage==2){
            if(ticks==40){check(prepared.get(),"disposable platform/survival/tool fixture prepared");check(Files.exists(ScriptPaths.resolve(TERMINAL))&&Files.exists(ScriptPaths.resolve(HUD)),"new examples installed through normal first-run copy");check(ScriptRunner.runScript(TERMINAL),"terminal launches as a real Python script");}
            if(ticks==70){check(c.gui.screen() instanceof ScriptableScreen,"terminal opens its actual authored GUI");command(c,"echo hello trail");}
            if(ticks==83){check(output().contains("hello trail"),"terminal Run displays entered echo text");check(ScriptableScreen.getInputValues().get("command").isEmpty(),"terminal clears its visible input after Run");command(c,"pos");}
            if(ticks==96){check(output().contains("XYZ"),"terminal pos reads live game coordinates");command(c,"health");}
            if(ticks==109){check(output().contains("Health 20/20; food 20/20"),"terminal health reads live vitals");command(c,"inventory");}
            if(ticks==122){check(output().contains("oak_log: 3"),"terminal inventory reports actual supplied stack once");command(c,"not_a_command");}
            if(ticks==135){check(output().contains("Unknown command"),"unknown terminal command gives visible feedback");capture(c,"authoring-terminal");}
            if(ticks==140){c.gui.screen().onClose();}
            if(ticks==145){check(c.gui.screen()==null&&ScriptRunner.isRunning(TERMINAL),"Escape hides terminal without killing its process");c.gui.setScreen(new ScriptableScreen());}
            if(ticks==155){click(c,"clear");}
            if(ticks==165){check(output().isEmpty(),"terminal Clear empties actual output");c.getWindow().setWindowed(480,360);c.options.guiScale().set(3);c.resizeGui();}
            if(ticks==175){check((int)WholeUiAcceptance.get(c.gui.screen(),"panelX")>=0&&(int)WholeUiAcceptance.get(c.gui.screen(),"panelY")>=0,"terminal fits compact game GUI");capture(c,"authoring-terminal-compact");click(c,"stop");}
            if(ticks==190){check(!ScriptRunner.isRunning(TERMINAL)&&c.gui.screen()==null,"terminal Stop exits Python and clears its owned GUI");c.getWindow().setWindowed(1000,650);c.options.guiScale().set(2);c.resizeGui();check(ScriptRunner.runScript(HUD),"trail HUD launches real Python");next();}
            return;
        }
        if(stage==3){
            if(ticks==25){check(hud("position").contains("XYZ"),"HUD draws live coordinate text");check(hud("distance").contains("0 blocks"),"HUD records start reference");var server=c.getSingleplayerServer();var uuid=c.player.getUUID();server.execute(()->server.getCommands().performPrefixedCommand(server.getPlayerList().getPlayer(uuid).createCommandSourceStack(),"tp @s "+(origin.getX()+3.5)+" "+origin.getY()+" "+(origin.getZ()+.5)));}
            if(ticks==50){check(hud("distance").contains("3 blocks"),"HUD updates distance after actual player movement");check(hud("vitals").contains("HP 20/20"),"HUD shows actual vitals");capture(c,"authoring-trail-hud");ScriptRunner.stopScript(HUD);}
            if(ticks==65){check(!ScriptRunner.isRunning(HUD),"Scripts Stop terminates HUD process");check(PeripheralHud.elements().stream().noneMatch(e->e.has("_owner")&&e.get("_owner").getAsString().equals(HUD)),"Scripts Stop removes only owned HUD");
                Files.writeString(ScriptPaths.resolve("audit/authoring_mine_probe.py"),"from mc import mine, block_at, wait\nmine("+origin.getX()+"+2,"+origin.getY()+","+origin.getZ()+")\nwait(3)\nprint('SURVIVAL MINE PROBE',block_at("+origin.getX()+"+2,"+origin.getY()+","+origin.getZ()+"),flush=True)\n");
                check(ScriptRunner.runScript("audit/authoring_mine_probe.py"),"survival mining probe uses only public mine/block_at/wait");next();}
            return;
        }
        if(stage==4){if(ticks==85){var target=origin.offset(2,0,0);boolean unbroken=!c.level.getBlockState(target).isAir();var tail=ScriptRunner.logTail("audit/authoring_mine_probe.py");log("MINING PROBE target remains="+unbroken+"; "+tail);check(tail.stream().anyMatch(s->s.contains("'block': 'minecraft:oak_log'")),"public block_at returns the documented canonical block ID");capture(c,"authoring-survival-mine-probe");check(ScriptRunner.listRunningScripts().isEmpty(),"examples/probe leave no orphan Python processes");c.gui.setScreen(new PeripheralScreen());next();}return;}
        if(stage==5){
            if(ticks==10){Files.writeString(ScriptPaths.resolve("audit/gui_peek_probe.py"),"""
                from mc import open_gui, wait, gui_is_open, gui_input, gui_state
                open_gui({'title':'GUI event preservation','w':220,'h':100,'widgets':[
                    {'id':'a','type':'button','x':6,'y':6,'w':64,'h':14,'label':'First'},
                    {'id':'b','type':'button','x':80,'y':6,'w':64,'h':14,'label':'Second'},
                    {'id':'value','type':'input','x':6,'y':30,'w':196,'h':16,'value':'seed'}]})
                wait(2)
                print('PEEK CHECK', gui_is_open(), gui_input('value'), gui_state().get('event'), gui_state().get('event'), flush=True)
                """);check(ScriptRunner.runScript("audit/gui_peek_probe.py"),"non-consuming GUI probe launches real Python");}
            if(ticks==35){click(c,"a");click(c,"b");}
            if(ticks==70){var tail=ScriptRunner.logTail("audit/gui_peek_probe.py");check(tail.stream().anyMatch(s->s.contains("PEEK CHECK True seed a b")),"public GUI value/open reads preserve both queued clicks in order");check(!ScriptRunner.isRunning("audit/gui_peek_probe.py"),"GUI read probe exits cleanly");copyExample("gui/control_center.py","audit/control_center_current.py");check(ScriptRunner.runScript("audit/control_center_current.py"),"current bundled control center launches");}
            if(ticks==100){click(c,"time_pick");var w=widget("time_pick");int[] r=(int[])WholeUiAcceptance.call(c.gui.screen(),"popupRect",w);WholeUiAcceptance.clickAt(c.gui.screen(),r[0]+5,r[1]+2*16+5);}
            if(ticks==110)click(c,"set_time");
            if(ticks==125){long time=c.level.getOverworldClockTime()%24000;check(time>=13000&&time<14000,"control center uses selected night option through public non-consuming read");var w=widget("tab");int x=(int)WholeUiAcceptance.get(c.gui.screen(),"panelX")+6+w.get("x").getAsInt()+150,y=(int)WholeUiAcceptance.get(c.gui.screen(),"panelY")+15+w.get("y").getAsInt()+5;WholeUiAcceptance.clickAt(c.gui.screen(),x,y);}
            if(ticks==145){check(widget("tab").get("selected").getAsString().equals("Info"),"control center Info tab is reachable");check(widget("held_lbl").get("text").getAsString().equals("iron_axe"),"control center shows actual equipped item");capture(c,"authoring-control-center");ScriptRunner.stopScript("audit/control_center_current.py");}
            if(ticks==165){copyExample("hud/dashboard.py","audit/dashboard_current.py");check(ScriptRunner.runScript("audit/dashboard_current.py"),"current bundled dashboard launches");}
            if(ticks==190){check(PeripheralHud.elements().stream().anyMatch(e->e.has("id")&&e.get("id").getAsString().equals("hand")&&e.has("item_id")&&e.get("item_id").getAsString().equals("minecraft:iron_axe")),"dashboard reads the actual equipped item without a duplicate inventory slot");capture(c,"authoring-dashboard");ScriptRunner.stopScript("audit/dashboard_current.py");}
            if(ticks==210){check(ScriptRunner.listRunningScripts().isEmpty(),"all examples/probes stop cleanly");c.gui.setScreen(new PeripheralScreen());}
            if(ticks==235){capture(c,"authoring-final-panel");log("READY failures="+failures+"; terminal/HUD live tested; mining probe outcome reported separately");stage=6;}
        }
        if(stage==7){if(ticks==40)command(c,"pos");if(ticks==55)command(c,"help");if(ticks==80){check(output().contains("pos: coordinates"),"interactive terminal help executes its public command handler");check(ScriptRunner.logTail("audit/review_json_probe.py").stream().anyMatch(s->s.contains("JSON ROUNDTRIP PASS from installed mc.py")),"installed helper JSON/waypoint round trips pass");check(!ScriptRunner.isRunning("audit/review_json_probe.py"),"JSON probe exits cleanly");capture(c,"authoring-interactive-examples");log("INTERACTIVE EXAMPLES READY; terminal and read-only HUD running; no further automated actions");stage=8;}}
    }catch(Throwable e){log("EXCEPTION "+e);e.printStackTrace();failures++;ScriptRunner.stopAll();c.stop();}}
    static String xyz(int x,int y,int z){return (origin.getX()+x)+" "+(origin.getY()+y)+" "+(origin.getZ()+z);}
    static void copyExample(String resource,String target)throws Exception{try(var in=AuthoringAcceptance.class.getResourceAsStream("/peripheral/examples/"+resource)){Files.write(ScriptPaths.resolve(target),in.readAllBytes());}}
    @SuppressWarnings("unchecked") static JsonObject widget(String id)throws Exception{return ((Map<String,JsonObject>)WholeUiAcceptance.getStatic(ScriptableScreen.class,"widgetMap")).get(id);}
    static void click(Minecraft c,String id)throws Exception{var w=widget(id);check(c.gui.screen() instanceof ScriptableScreen&&w!=null,"painted authored control "+id);if(w==null)return;int x=(int)WholeUiAcceptance.get(c.gui.screen(),"panelX")+6+w.get("x").getAsInt()+4,y=(int)WholeUiAcceptance.get(c.gui.screen(),"panelY")+15+w.get("y").getAsInt()+5;WholeUiAcceptance.clickAt(c.gui.screen(),x,y);}
    static void command(Minecraft c,String text)throws Exception{click(c,"command");c.gui.screen().keyPressed(new KeyEvent(GLFW.GLFW_KEY_A,0,GLFW.GLFW_MOD_CONTROL|GLFW.GLFW_MOD_SUPER));for(int ch:text.codePoints().toArray())c.gui.screen().charTyped(new CharacterEvent(ch));click(c,"run");}
    static String output()throws Exception{var w=widget("output");return w==null?"":w.getAsJsonArray("lines").asList().stream().map(JsonElement::getAsString).reduce((a,b)->a+"\n"+b).orElse("");}
    static String hud(String id){return PeripheralHud.elements().stream().filter(e->e.has("id")&&e.get("id").getAsString().equals(id)).map(e->e.has("text")?e.get("text").getAsString():"").findFirst().orElse("");}
    static void capture(Minecraft c,String name){WholeUiAcceptance.capture(c,name);}
}
