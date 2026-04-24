package slooth.peripheral;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

class SettingsTab {

    private final PeripheralScreen host;
    private int px, contentY;

    private TextFieldWidget apiKeyField;
    private TextFieldWidget agentUrlField;
    private TextFieldWidget modelField;

    private String saveMsg  = "";
    private int    saveTick = 0;

    SettingsTab(PeripheralScreen host) { this.host = host; }

    void build(int px, int py, int contentY, int contentH) {
        this.px = px; this.contentY = contentY;

        int lx = px + 8, fy = contentY + 12, fw = PeripheralScreen.W - 84;

        apiKeyField = field(lx + 62, fy, fw, PeripheralConfig.apiKey);
        host.widgetAdd(apiKeyField);
        host.widgetAdd(PeripheralScreen.btn("Show", px + PeripheralScreen.W - 46, fy - 1, 38, 16, b -> {
            if (apiKeyField != null) {
                boolean showing = b.getMessage().getString().equals("Hide");
                b.setMessage(Text.literal(showing ? "Show" : "Hide"));
                apiKeyField.setPlaceholder(Text.literal(showing ? "" : "hidden"));
            }
        }));

        agentUrlField = field(lx + 80, fy + 26, fw - 18, PeripheralConfig.agentUrl);
        host.widgetAdd(agentUrlField);

        modelField = field(lx + 52, fy + 52, fw - 18, PeripheralConfig.model);
        host.widgetAdd(modelField);

        host.widgetAdd(PeripheralScreen.btn("Save", px + PeripheralScreen.W / 2 - 24, fy + 78, 48, 16,
            b -> saveSettings()));
    }

    void render(DrawContext ctx) {
        int lx = px + 8, fy = contentY + 12;
        ctx.drawText(host.tr(), Text.literal("API Key:"),   lx, fy + 3,  PeripheralScreen.C_ORANGE, false);
        ctx.drawText(host.tr(), Text.literal("Agent URL:"), lx, fy + 29, PeripheralScreen.C_ORANGE, false);
        ctx.drawText(host.tr(), Text.literal("Model:"),     lx, fy + 55, PeripheralScreen.C_ORANGE, false);
        if (saveTick > 0)
            ctx.drawText(host.tr(), Text.literal(saveMsg),
                px + PeripheralScreen.W / 2 - 20, fy + 100,
                saveMsg.startsWith("Error") ? PeripheralScreen.C_RED : PeripheralScreen.C_GREEN, false);
    }

    void tick() { if (saveTick > 0) saveTick--; }

    private void saveSettings() {
        try {
            if (apiKeyField   != null) PeripheralConfig.apiKey   = apiKeyField.getText().trim();
            if (agentUrlField != null) PeripheralConfig.agentUrl = agentUrlField.getText().trim();
            if (modelField    != null) PeripheralConfig.model    = modelField.getText().trim();
            PeripheralConfig.save();
            saveMsg  = "Saved!";
            saveTick = 60;
        } catch (Exception e) {
            saveMsg  = "Error: " + e.getMessage();
            saveTick = 80;
        }
    }

    private TextFieldWidget field(int x, int y, int w, String initial) {
        TextFieldWidget f = new TextFieldWidget(host.tr(), x, y, w, 14, Text.empty());
        f.setMaxLength(512);
        f.setFocusUnlocked(false);
        f.setText(initial);
        return f;
    }
}
