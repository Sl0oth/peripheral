package slooth.peripheral.audit;

import slooth.peripheral.*;
import slooth.peripheral.api.*;
import slooth.peripheral.script.*;
import slooth.peripheral.ui.*;
import dev.pane.ui.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.*;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.*;
import net.minecraft.world.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import java.nio.file.*;
import java.util.*;
import org.lwjgl.glfw.GLFW;

/** Opt-in acceptance only; runs solely in run-pilot-audit and its reserved scratch world. */
public final class PilotAcceptance {
    static int stage,ticks,failures,idlePaints;static PeripheralScreen screen;static String token;static long childPid;static double logBeforeScroll;static Process security;
    static final boolean PREVIEW=Boolean.getBoolean("peripheral.pilotPreview");
    static final boolean VISUAL_MATRIX=Boolean.getBoolean("peripheral.visualMatrix");
    static final boolean FIGMA_REVIEW=Boolean.getBoolean("peripheral.figmaReview");
    static final int[][] VIEWS={{854,480,1},{854,480,2},{854,480,3},{1440,900,2},{1440,900,3},{480,360,3},{1728,1080,2,1},{1728,1080,3,1},{1000,650,2}};
    static final String WORLD="peripheral_pilot_acceptance_"+System.currentTimeMillis();
    static final ArrayList<Long> frames=new ArrayList<>();static long previous;
    public static void init() {
        if(!Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath().toString().endsWith("run-pilot-audit"))throw new IllegalStateException("Wrong acceptance directory");
        if(Boolean.getBoolean("peripheral.authoringAudit")){AuthoringAcceptance.init();return;}
        if(Boolean.getBoolean("peripheral.wholeUiAudit")){WholeUiAcceptance.init();return;}
        if(Boolean.getBoolean("peripheral.recorderAudit")){RecorderAcceptance.init();return;}
        if(System.getProperty("peripheral.serverAudit")!=null){ServerAcceptance.init();return;}
        if(!PREVIEW)prepare();
        if(VISUAL_MATRIX)try { Files.createDirectories(ScriptPaths.resolve("audit"));Files.writeString(ScriptPaths.resolve("audit/readability.py"),"import time\nfor i in range(80): print('Line '+str(i)+' | actual Python output | '+('readable wrapped text '*12),flush=True)\ntime.sleep(120)\n"); }catch(Exception e){throw new RuntimeException(e);}
        if(FIGMA_REVIEW)try {
            Files.writeString(ScriptPaths.resolve("audit/review_finished.py"),"print('Visual review completed successfully',flush=True)\n");
            Files.writeString(ScriptPaths.resolve("audit/review_failed.py"),"print('Testing the failed-state banner',flush=True)\nraise RuntimeError('Deliberate visual review failure')\n");
        }catch(Exception e){throw new RuntimeException(e);}
        ClientTickEvents.END_CLIENT_TICK.register(PilotAcceptance::tick);
    }
    static void prepare(){try{
        Path dir=ScriptPaths.resolve("audit");Files.createDirectories(dir);
        Files.writeString(ScriptPaths.resolve("audit/move.py"),"from mc import *\nprint('move started',flush=True)\nmove(1)\nwait(60)\n");
        Files.writeString(ScriptPaths.resolve("audit/sneak.py"),"from mc import *\nprint('sneak started',flush=True)\nsneak(True)\nwait(60)\n");
        Files.writeString(ScriptPaths.resolve("audit/crash.py"),"from mc import *\nmove(1)\nraise RuntimeError('deliberate acceptance failure')\n");
        Files.writeString(ScriptPaths.resolve("audit/jump.py"),"from mc import *\njump()\nwait(1)\n");
        Files.writeString(ScriptPaths.resolve("audit/nav.py"),"from mc import *\np=pos()\ngoto(p[0]+20,p[2])\nwait(60)\n");
        Files.writeString(ScriptPaths.resolve("audit/tree.py"),"import subprocess,sys,time\np=subprocess.Popen([sys.executable,'-c','import time; time.sleep(60)'])\nprint('CHILD '+str(p.pid),flush=True)\ntime.sleep(60)\n");
        Files.writeString(ScriptPaths.resolve("audit/stdout.py"),"import os,time\nos.close(1)\nos.close(2)\ntime.sleep(60)\n");
        Files.writeString(ScriptPaths.resolve("audit/log.py"),"from mc import *\nfor i in range(60): print('line '+str(i)+' '+('long-output '*24),flush=True)\nwait(60)\n");
        for(int i=0;i<70;i++)Files.writeString(ScriptPaths.resolve("audit/list_"+String.format("%02d",i)+".py"),"print('list fixture')\n");
    }catch(Exception e){throw new RuntimeException(e);}}
    static void tick(Minecraft c){try{
        if(PREVIEW && stage==2) {
            ++ticks;
            if(VISUAL_MATRIX && ticks<=VIEWS.length*100) {
                int i=(ticks-1)/100;int[] view=VIEWS[i];
                if(ticks%100==1) {
                    boolean full=view.length>3;
                    if(c.getWindow().isFullscreen()!=full)c.getWindow().toggleFullScreen();
                    if(!full)c.getWindow().setWindowed(view[0],view[1]);
                    c.options.guiScale().set(view[2]);c.resizeGui();c.gui.setScreen(screen=scripts());
                }
                if(ticks%100==40) {
                    capture(c,"visual-"+i+"-"+view[0]+"x"+view[1]+"-g"+view[2]);
                    log("VISUAL "+i+" window="+c.getWindow().getScreenWidth()+"x"+c.getWindow().getScreenHeight()+" framebuffer="+c.getWindow().getWidth()+"x"+c.getWindow().getHeight()+" gui="+c.getWindow().getGuiScale()+" layoutScale="+screen.layoutScale()+" surface="+screen.surfacePixelWidth()+"x"+screen.surfacePixelHeight()+" "+screen.performanceSnapshot().summary());
                    check(screen.regionBounds("close").isPresent(),"visual close reachable "+i);
                    var close=screen.regionBounds("close").orElseThrow();
                    log("VISUAL SIZE "+i+" fullscreen="+c.getWindow().isFullscreen()+" closePixels="+close.width()/screen.layoutScale()*c.getWindow().getGuiScale());
                    check(c.getWindow().isFullscreen()==(view.length>3),"visual fullscreen state "+i);
                }
                if(ticks%100==45)ScriptRunner.runScript("audit/readability.py");
                if(ticks%100==58)click("session:audit/readability.py");
                if(ticks%100==70) {
                    capture(c,"visual-detail-"+i+"-"+view[0]+"x"+view[1]+"-g"+view[2]);
                    check(ScriptRunner.isRunning("audit/readability.py"),"visual real Python running "+i);
                    check(!ScriptRunner.logTail("audit/readability.py").isEmpty(),"visual real stdout "+i);
                    var b=screen.regionBounds("run");
                    check(b.isPresent() && b.get().x()>=0 && b.get().right()<=screen.width*screen.layoutScale()+1 && b.get().bottom()<=screen.height*screen.layoutScale()+1,"visual run bounds "+i);
                    click("run");
                }
                if(ticks%100==85)check(!ScriptRunner.isRunning("audit/readability.py"),"visual Stop works "+i);
                return;
            }
            if(VISUAL_MATRIX) {
                int end=VIEWS.length*100;
                if(ticks==end+1)click("tab:2");
                if(ticks==end+10) { check(screen.regionBounds("search").isEmpty(),"visual Log tab replaces the Scripts library");click("tab:0"); }
                if(ticks==end+30) { check(screen.regionBounds("search").isPresent(),"visual Scripts tab returns");c.getWindow().setWindowed(1440,900);c.resizeGui(); }
                if(ticks==end+60 && !FIGMA_REVIEW) { capture(c,"pilot-interactive-preview");log("PREVIEW READY: interactive Scripts screen; visual failures="+failures); }
                if(FIGMA_REVIEW) {
                    if(ticks==end+70)search("review_finished");
                    if(ticks==end+90)click("row:audit/review_finished.py");
                    if(ticks==end+100) { capture(c,"figma-ready");click("run"); }
                    if(ticks==end+120) { check(ScriptRunner.outcome("audit/review_finished.py").equals("Finished"),"Figma finished banner uses real exit");capture(c,"figma-finished"); }
                    if(ticks==end+125)search("review_failed");
                    if(ticks==end+145) { click("row:audit/review_failed.py");click("run"); }
                    if(ticks==end+165) { check(ScriptRunner.outcome("audit/review_failed.py").startsWith("Exited with error"),"Figma failed banner uses real exit");capture(c,"figma-failed"); }
                    if(ticks==end+185)search("review_finished");
                    if(ticks==end+205)click("row:audit/review_finished.py");
                    if(ticks==end+220) { check(ScriptRunner.listRunningScripts().isEmpty(),"Figma preview leaves no scripts running");capture(c,"pilot-interactive-preview");log("PREVIEW READY: Figma Scripts screen; visual failures="+failures); }
                }
            } else if(ticks==25) { capture(c,"pilot-interactive-preview");log("PREVIEW READY: interactive Scripts screen; no automated actions or auto-exit"); }
            return;
        }
        ticks++;long now=System.nanoTime();if(stage==2 && previous!=0)frames.add(now-previous);previous=now;
        if(ticks>2400){check(false,"stage timeout "+stage);c.stop();return;}
        if(stage==0){if(ticks==40 && c.gui.screen()!=null && c.gui.screen().getClass().getSimpleName().equals("AccessibilityOnboardingScreen")){log("Disposable startup screen: "+(c.gui.screen()==null?"none":c.gui.screen().getClass().getSimpleName()));c.gui.setScreen(new TitleScreen());}if(ticks>45 && c.gui.screen() instanceof TitleScreen){next();load(c);}return;}
        if(stage==1){if(ticks==150){log("World-loading screen: "+(c.gui.screen()==null?"none":c.gui.screen().getClass().getSimpleName()));capture(c,"acceptance-startup");}if(c.player!=null && ticks>60){c.options.pauseOnLostFocus=false;c.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);c.options.renderDistance().set(6);c.getWindow().setWindowed(854,480);c.options.guiScale().set(2);c.resizeGui();if(PREVIEW){c.gui.setScreen(screen=scripts());next();return;}security=new ProcessBuilder("python3",c.gameDirectory.toPath().getParent().resolve("audit/2026-10-16-implementation/security_checks.py").toString()).redirectErrorStream(true).redirectOutput(c.gameDirectory.toPath().resolve("security-results.txt").toFile()).start();c.gui.setScreen(screen=scripts());next();}return;}
        if(stage==2){
            if(ticks==25){capture(c,"pilot-root");click("row:audit");}
            if(ticks==31)capture(c,"pilot-audit-folder");
            if(ticks==32){var b=screen.regionBounds("row:audit/crash.py").orElseThrow();screen.mouseScrolled(b.centerX()/screen.layoutScale(),b.centerY()/screen.layoutScale(),0,-6);}
            if(ticks==33){boolean found=false;for(int i=0;i<70;i++){String id="audit/list_"+String.format("%02d",i)+".py";var b=screen.regionBounds("row:"+id);if(b.isPresent() && b.get().centerY()>170 && b.get().centerY()<320){click("row:"+id);check(screen.stringValue("selected-script","").equals(id),"click during scrolling selects the painted row");found=true;break;}}check(found,"scrolling exposes a selectable virtual row");}
            if(ticks==45){check(screen.regionBounds("row:audit/move.py").isEmpty(),"long-list rows are virtualized");click("search");screen.charTyped(new CharacterEvent('m'));screen.charTyped(new CharacterEvent('o'));screen.charTyped(new CharacterEvent('v'));screen.charTyped(new CharacterEvent('e'));}
            if(ticks==65){screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_G,0,0));check(c.gui.screen()==screen,"typing G in search does not close the screen");check(screen.stringValue("query","").equals("move"),"search accepts real character input");capture(c,"pilot-search");click("row:audit/move.py");}
            if(ticks==75)click("run");
            if(ticks==95){check(ScriptRunner.isRunning("audit/move.py"),"pilot Run starts a real Python process");check(input(c)!=null && input(c).scriptForward==1,"real Python controls forward");check(ScriptRunner.logTail("audit/move.py").stream().anyMatch(s->s.contains("move started")),"real stdout reaches pilot logs");click("run");}
            if(ticks==115){check(!ScriptRunner.isRunning("audit/move.py"),"pilot Stop ends process");check(input(c)==null || input(c).scriptForward==0,"Stop releases forward");click("run");}
            if(ticks==135){check(ScriptRunner.isRunning("audit/move.py"),"pilot restart works");ScriptRunner.runScript("audit/sneak.py");}
            if(ticks==155)ScriptRunner.stopScript("audit/move.py");
            if(ticks==175){check(input(c)!=null && input(c).scriptSneak && input(c).scriptForward==0,"stopping move preserves other script sneak");ScriptRunner.stopScript("audit/sneak.py");ScriptRunner.runScript("audit/crash.py");}
            if(ticks==200){check(!ScriptRunner.isRunning("audit/crash.py"),"crashed process exits");check(input(c)==null || !input(c).scriptOverriding,"crash releases controls");check(ScriptRunner.outcome("audit/crash.py").contains("error"),"crash gets actionable error status");ScriptRunner.runScript("audit/jump.py");}
            if(ticks==205)ScriptRunner.runScript("audit/log.py");
            if(ticks==215){click("session:audit/log.py");check(screen.stringValue("selected-script","").equals("audit/log.py"),"running-session chip selects a script outside search results");}
            if(ticks==220){logBeforeScroll=screen.scrollSnapshot("output").map(v->v.offset()).orElse(0d);scrollLog(4);}
            if(ticks==225){check(screen.scrollSnapshot("output").map(v->v.offset()).orElse(0d)<logBeforeScroll-1,"wrapped output can scroll away from the tail");capture(c,"pilot-scrolled-log");scrollLog(-100);}
            if(ticks==228){check(screen.scrollSnapshot("output").map(v->v.offset()).orElse(0d)>=logBeforeScroll-1,"log returns to newest lines");ScriptRunner.stopScript("audit/log.py");}
            if(ticks==229)scrollLog(6);
            if(ticks==231){check(screen.regionBounds("latest").isPresent(),"scrolling up exposes Jump to latest");capture(c,"figma-follow-paused");click("latest");}
            if(ticks==233)check(screen.regionBounds("latest").isEmpty(),"Jump to latest resumes following");
            if(ticks==230){check(input(c)==null || !input(c).scriptOverriding,"jump override expires");ScriptRunner.runScript("audit/tree.py");ScriptRunner.runScript("audit/stdout.py");}
            if(ticks==250){check(ScriptRunner.isRunning("audit/stdout.py"),"closing stdout does not lose process tracking");for(String l:ScriptRunner.logTail("audit/tree.py"))if(l.startsWith("CHILD "))childPid=Long.parseLong(l.substring(6));check(childPid>0,"descendant PID recorded");ScriptRunner.stopScript("audit/tree.py");ScriptRunner.stopScript("audit/stdout.py");}
            if(ticks==275){check(childPid>0 && ProcessHandle.of(childPid).map(p->!p.isAlive()).orElse(true),"Stop terminates descendant");screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_TAB,0,0));check(screen.focusedId().isPresent(),"Tab focuses pilot controls");screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_TAB,0,GLFW.GLFW_MOD_SHIFT));capture(c,"pilot-log");click("tab:2");}
            if(ticks==285){check(c.gui.screen()==screen&&screen.regionBounds("search").isEmpty(),"Log tab opens in the same window");capture(c,"pilot-log-tab");}
            if(ticks==300){click("tab:0");}
            if(ticks==315){check(screen.regionBounds("search").isPresent()&&screen.stringValue("selected-script","").equals("audit/log.py"),"Scripts tab keeps its selection across tabs");c.getWindow().setWindowed(480,360);c.options.guiScale().set(3);c.resizeGui();}
            if(ticks==317){check(screen.regionBounds("library").isPresent()&&screen.regionBounds("search").isEmpty(),"small window opens on the remembered script");click("library");}
            if(ticks==320){capture(c,"pilot-small-browser");check(screen.regionBounds("search").isPresent()&&screen.stringValue("query","").equals("move"),"Library button returns to the library with its search kept");}
            if(ticks==323)search("crash"); // Fixture growth can move this row below the compact viewport.
            if(ticks==328){click("row:audit/crash.py");}
            if(ticks==335){capture(c,"pilot-small");check(screen.regionBounds("close").isPresent(),"small viewport retains Close");check(screen.regionBounds("run").isPresent(),"compact selected script retains real Run/Stop control");screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_ESCAPE,0,0));check(c.gui.screen()==null,"Escape returns to gameplay");c.options.keyUp.setDown(true);c.getWindow().setWindowed(1000,650);c.options.guiScale().set(2);c.resizeGui();}
            if(ticks==345){check(c.player.input.keyPresses.forward(),"normal keyboard forward works after script stop/jump");c.options.keyUp.setDown(false);c.gui.setScreen(screen=scripts());}
            if(ticks==350){screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_TAB,0,0));check(screen.focusedId().orElse("").equals("close"),"keyboard focus reaches Close first");screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_ENTER,0,0));check(c.gui.screen()==null,"Enter activates focused Close");c.gui.setScreen(screen=scripts());}
            if(ticks==365){idlePaints=screen.paintCount();}
            if(ticks==425){check(screen.paintCount()==idlePaints,"unchanged pilot has zero paints over 3s");capture(c,"pilot-large");ScriptRunner.runScript("audit/nav.py");}
            if(ticks==430){click("search");for(char ch:"zzzz".toCharArray())screen.charTyped(new CharacterEvent(ch));}
            if(ticks==440){check(screen.regionBounds("row:audit").isEmpty(),"no-result search clears stale row targets");capture(c,"pilot-no-results");}
            if(ticks==445){ScriptRunner.stopScript("audit/nav.py");}
            if(ticks==465){check(PeripheralNavigator.INSTANCE.getNavStatus()==PeripheralNavigator.NavStatus.IDLE,"stop releases owned navigation");ScriptRunner.runScript("audit/move.py");}
            if(ticks==485){c.disconnect(new TitleScreen(),false);next();}
            return;
        }
        if(stage==3){if(ticks==35){check(ScriptRunner.listRunningScripts().isEmpty(),"disconnect stops all scripts");next();load(c);}return;}
        if(stage==4 && c.player!=null && ticks>60){check(ScriptRunner.listRunningScripts().isEmpty(),"reconnect does not revive stopped script");c.gui.setScreen(screen=scripts());next();return;}
        if(stage==5 && ticks==40){
            check(!ScriptRunner.runScript("audit/missing-file.py") && ScriptRunner.outcome("audit/missing-file.py").contains("existing"),"missing script has actionable error");
            var pythonField=ScriptRunner.class.getDeclaredField("cachedPython");pythonField.setAccessible(true);Object oldPython=pythonField.get(null);
            try {pythonField.set(null,"peripheral-acceptance-no-python");check(!ScriptRunner.runScript("audit/list_00.py") && ScriptRunner.outcome("audit/list_00.py").contains("Install Python 3"),"missing interpreter has actionable error (injected fixture)");} finally {pythonField.set(null,oldPython);}
            String before=Files.readString(Path.of("config/peripheral/local-api.token"));PeripheralHttpServer.stop();
            try(var occupied=new java.net.ServerSocket(25585,1,java.net.InetAddress.getByName("127.0.0.1"))) {PeripheralHttpServer.start();check(!PeripheralHttpServer.status.equals("Ready") && before.equals(Files.readString(Path.of("config/peripheral/local-api.token"))),"port conflict fails without rotating another listener capability");}
            PeripheralHttpServer.start();check(PeripheralHttpServer.status.equals("Ready"),"local API can bind again after port is free");
            PeripheralSocketServer.stop();
            try(var occupied=new java.net.ServerSocket(25587,1,java.net.InetAddress.getByName("127.0.0.1"))) {
                PeripheralSocketServer.start();check(!PeripheralSocketServer.status.equals("Ready"),"socket port conflict is reported");
                Files.writeString(ScriptPaths.resolve("audit/http_fallback.py"),"from mc import *\nprint('HTTP_FALLBACK_OK',health(),flush=True)\n");
                ScriptRunner.runScript("audit/http_fallback.py");
            }
            PeripheralSocketServer.start();
        }
        if(stage==5 && ticks==65){
            check(ScriptRunner.logTail("audit/http_fallback.py").stream().anyMatch(line->line.startsWith("HTTP_FALLBACK_OK")),"scripts use authorized HTTP when socket startup failed");
            check(security!=null && !security.isAlive() && security.exitValue()==0,"external security/protocol acceptance passed");capture(c,"pilot-reconnected");var perf=screen.performanceSnapshot();log("PERF "+perf.summary());log("END "+(failures==0?"PASS":"FAIL")+" failures="+failures);c.stop();}
    }catch(Throwable e){log("EXCEPTION "+e);e.printStackTrace();failures++;Minecraft.getInstance().stop();}}
    static NavigatorInput input(Minecraft c){return c.player!=null && c.player.input instanceof NavigatorInput n?n:null;}
    static void load(Minecraft c){if(c.getLevelSource().levelExists(WORLD))c.createWorldOpenFlows().openWorld(WORLD,()->{});else c.createWorldOpenFlows().createFreshLevel(WORLD,new LevelSettings("Peripheral disposable acceptance",GameType.CREATIVE,new LevelSettings.DifficultySettings(Difficulty.PEACEFUL,false,false),true,WorldDataConfiguration.DEFAULT),new WorldOptions(2847L,false,false),WorldPresets::createNormalWorldDimensions,null);}
    static void next(){stage++;ticks=0;}
    static void click(String id){var b=screen.regionBounds(id);check(b.isPresent(),"target "+id);if(b.isEmpty())return;double x=b.get().centerX()/screen.layoutScale(),y=b.get().centerY()/screen.layoutScale();var ev=new MouseButtonEvent(x,y,new MouseButtonInfo(0,0));screen.mouseMoved(x,y);screen.mouseClicked(ev,false);screen.mouseReleased(ev);}
    static void scrollLog(double amount){var v=screen.scrollViewport("output");if(v.isEmpty()){check(false,"output viewport");return;}screen.mouseScrolled(v.get().centerX()/screen.layoutScale(),v.get().centerY()/screen.layoutScale(),0,amount);}
    static void search(String query){click("search");screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_END,0,0));for(int i=0;i<80;i++)screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_BACKSPACE,0,0));for(char ch:query.toCharArray())screen.charTyped(new CharacterEvent(ch));}
    /** The control panel on its Scripts tab, as G opens it. */
    static PeripheralScreen scripts()throws Exception{var p=new PeripheralScreen();WholeUiAcceptance.call(p,"switchTab",0);return p;}
    static void capture(Minecraft c,String name){Screenshot.grab(c.gameDirectory,name+".png",c.gameRenderer.mainRenderTarget(),1,message->{});}
    static void check(boolean ok,String message){if(!ok)failures++;log((ok?"PASS ":"FAIL ")+message);}
    static void log(String m){PeripheralClient.LOGGER.info("[PilotAcceptance] {}",m);}
}
