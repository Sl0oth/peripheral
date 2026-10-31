package slooth.peripheral.audit;
import java.nio.file.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import slooth.peripheral.PeripheralClient;
import slooth.peripheral.api.*;
import slooth.peripheral.ui.PeripheralScreen;
import slooth.peripheral.util.PeripheralConfig;
import slooth.peripheral.script.*;

/** Disposable production-JAR MCP acceptance, driven by the official Python MCP SDK. */
public final class McpAcceptance {
    static int stage,ticks;static int copyAt=-1;static PeripheralScreen root;
    static final Path SIGNAL=Path.of("mcp-audit");
    static final boolean RESTART=Boolean.getBoolean("peripheral.audit.mcpRestart");
    static void check(boolean ok,String s){PeripheralClient.LOGGER.info("[McpAudit] {} {}",ok?"PASS":"FAIL",s);if(!ok)throw new IllegalStateException(s);}
    public static void init(){ClientTickEvents.END_CLIENT_TICK.register(McpAcceptance::tick);}
    static void tick(Minecraft c){try{
        ticks++;
        if(stage==0&&c.gui.screen() instanceof TitleScreen&&ticks>30){PilotAcceptance.load(c);stage=1;ticks=0;}
        else if(stage==1&&c.player!=null&&ticks>60){
            c.options.pauseOnLostFocus=false;if(c.getWindow().isFullscreen())c.getWindow().toggleFullScreen();c.getWindow().setWindowed(1000,650);c.options.guiScale().set(2);c.resizeGui();
            Files.createDirectories(SIGNAL);Files.deleteIfExists(SIGNAL.resolve("enable"));Files.deleteIfExists(SIGNAL.resolve("done"));Files.deleteIfExists(SIGNAL.resolve("off"));
            check(PeripheralConfig.mcpEnabled==RESTART,"saved enable setting on "+(RESTART?"second":"first")+" launch");
            Files.createDirectories(ScriptPaths.resolve("audit"));Files.writeString(ScriptPaths.resolve("audit/mcp_probe.py"),"import time\nprint('MCP script probe running',flush=True)\ntime.sleep(60)\n");
            root=new PeripheralScreen();c.gui.setScreen(root);WholeUiAcceptance.call(root,"openSettings",2);stage=2;ticks=0;
        }else if(stage==2){
            if(ticks==10)root.mouseScrolled(root.width/2.,root.height/2.,0,-10);
            if(ticks==35){check(root.regionBounds("mcp-toggle").isPresent(),"MCP toggle rendered after Settings scroll");WholeUiAcceptance.capture(c,"mcp-settings-"+(RESTART?"restart":"off"));Files.writeString(SIGNAL.resolve("phase"),RESTART?"enabled":"disabled");stage=3;ticks=0;}
        }else if(stage==3){
            if(!RESTART&&Files.exists(SIGNAL.resolve("enable"))){WholeUiAcceptance.clickPane(root,"mcp-toggle");check(PeripheralMcp.active(),"real Enable MCP button activates endpoint");PeripheralConfig.mcpEnabled=false;PeripheralConfig.load();check(PeripheralConfig.mcpEnabled,"enabled value reloaded from actual config");
                copyAt=ticks+15;Files.delete(SIGNAL.resolve("enable"));}
            if(copyAt==ticks){String clipboard=c.keyboardHandler.getClipboard();WholeUiAcceptance.clickPane(root,"mcp-copy");check(c.keyboardHandler.getClipboard().equals(PeripheralMcp.connectionDetails()),"Copy connection button copies authenticated JSON");c.keyboardHandler.setClipboard(clipboard);Files.writeString(SIGNAL.resolve("phase"),"enabled");WholeUiAcceptance.capture(c,"mcp-settings-on");}
            if(RESTART&&Files.exists(SIGNAL.resolve("off"))){WholeUiAcceptance.clickPane(root,"mcp-toggle");check(!PeripheralMcp.active(),"real Turn off button closes MCP access immediately");check(!(Boolean)WholeUiAcceptance.get(c.player.input,"scriptSneak"),"turning MCP off releases its held sneak control");PeripheralConfig.mcpEnabled=true;PeripheralConfig.load();check(!PeripheralConfig.mcpEnabled,"disabled value reloaded from actual config");Files.delete(SIGNAL.resolve("off"));Files.writeString(SIGNAL.resolve("phase"),"disabled");}
            if(Files.exists(SIGNAL.resolve("done"))){check(PeripheralConfig.mcpEnabled!=RESTART,"correct saved setting at end of launch");PeripheralClient.LOGGER.info("[McpAudit] COMPLETE restart={}",RESTART);stage=4;if(Boolean.getBoolean("peripheral.audit.mcpChain")){WholeUiAcceptance.init();WholeUiAcceptance.stage=1;WholeUiAcceptance.ticks=61;}else c.stop();}
        }
        if(stage<4&&ticks>5000)throw new IllegalStateException("MCP audit timeout");
    }catch(Exception e){PeripheralClient.LOGGER.error("[McpAudit] FAIL exception",e);c.stop();stage=4;}}
}
