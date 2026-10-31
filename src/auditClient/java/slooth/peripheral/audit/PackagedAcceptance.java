package slooth.peripheral.audit;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import slooth.peripheral.PeripheralClient;

/** Separate audit mod only. Never bundled into Peripheral's upload JAR. */
public final class PackagedAcceptance implements ClientModInitializer {
    public void onInitializeClient() {
        var source=PeripheralClient.class.getProtectionDomain().getCodeSource().getLocation().toString();
        if(!source.endsWith("peripheral-1.3.1.jar"))throw new IllegalStateException("Not testing upload JAR: "+source);
        try {
            var path=java.nio.file.Path.of(PeripheralClient.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            var hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(path)));
            if(!hash.equals(System.getProperty("peripheral.audit.expectedSha")))throw new IllegalStateException("Wrong upload hash: "+hash);
            PeripheralClient.LOGGER.info("[PackagedAudit] REAL UPLOAD JAR SHA256={} source={}",hash,source);
        } catch(Exception e) {throw new IllegalStateException(e);}
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(c->{
            if(c.gui.screen() instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen)c.gui.setScreen(new net.minecraft.client.gui.screens.TitleScreen());
        });
        if(!Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath().toString().contains("2026-10-17-publish"))throw new IllegalStateException("Wrong packaged audit directory");
        try {
            var field=slooth.peripheral.ui.BuildSession.class.getDeclaredField("SYSTEM_PROMPT");field.setAccessible(true);
            String prompt=(String)field.get(null);
            try(var in=PackagedAcceptance.class.getResourceAsStream("/peripheral/AI_PROMPT.md")){
                var guide=new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
                if(!prompt.endsWith(guide)||!prompt.contains("gui_peek")||!prompt.contains("window_scaled_width"))throw new IllegalStateException("Builder guide is stale");
            }
            PeripheralClient.LOGGER.info("[PackagedAudit] PASS builder uses current bundled API guide; no provider request made");
        }catch(Exception e){throw new IllegalStateException(e);}
        if(Boolean.getBoolean("peripheral.audit.mcp"))McpAcceptance.init();else if(Boolean.getBoolean("peripheral.audit.whole"))WholeUiAcceptance.init();else AuthoringAcceptance.init();
    }
}
