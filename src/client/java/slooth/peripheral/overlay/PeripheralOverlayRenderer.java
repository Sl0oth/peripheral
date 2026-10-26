package slooth.peripheral.overlay;

import slooth.peripheral.PeripheralClient;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Python-scriptable world-space overlay.
 *
 * <p>Scripts call draw_esp(), draw_world_text(), draw_line(), draw_waypoint(),
 * draw_player_line(), draw_live_esp(), draw_live_tags() and overlay_clear()
 * via the HTTP API. Commands are rendered each frame via
 * {@link LevelRenderEvents#BEFORE_GIZMOS}, using camera-relative coordinates as
 * required by the Fabric rendering API.
 *
 * <p>Command JSON fields:
 * <pre>
 *   type   – "esp_box" | "world_text" | "line" | "waypoint" | "player_line"
 *            | "live_esp" | "billboard" | "health_bar" | "live_tags"
 *   id     – string identifier (replaces any prior command with the same id)
 *   color  – named color, "#RRGGBB" / "#AARRGGBB", or "auto" (health-based)
 *
 *   esp_box:     x, y, z (centre of bottom face), w, h, d (dimensions)
 *   world_text:  x, y, z (world position), text
 *   line:        x1, y1, z1, x2, y2, z2
 *   waypoint:    x, y, z, label
 *   player_line: x, y, z (target) – origin resolved from live player eye each frame
 *   live_esp:    filter ("hostile"|"passive"|"players"|"all"), radius (default 64),
 *                w, h, d (box dims), show_lines (bool), show_labels (bool)
 *                – entity positions resolved from live world each frame
 *   billboard:   x, y, z, lines ([{text, color}, ...]) – camera-facing panel
 *                with optional scale, side_offset, line_spacing and see_through
 *   health_bar:  x, y, z, value, max, color ("auto" for health-tinted),
 *                show_text (bool), optional scale, side_offset, width_chars,
 *                see_through
 *   live_tags:   render-thread entity tags with live billboards / bars.
 *                Supports filter, radius, require_custom_name, include_players,
 *                lines, show_bar, show_box, label_offset_*, bar_offset_*,
 *                side_offset, bar_side_offset, scale, bar_scale, bar_width_chars,
 *                bar_color, bar_show_text and see_through.
 *                Billboard text templates may use tokens like
 *                {name}, {distance:.1f}, {health:.0f}, {max_health:.0f},
 *                {health_percent:.0f}, {type}, {custom_name}.
 * </pre>
 */
public class PeripheralOverlayRenderer {

    private static final float LABEL_TEXT_Z_OFFSET = -0.02f;
    private static final float HEALTH_BAR_FILL_Z_OFFSET = -0.02f;
    private static final CopyOnWriteArrayList<JsonObject> commands = new CopyOnWriteArrayList<>();
    private static final ConcurrentHashMap<String, Float> barValues = new ConcurrentHashMap<>();
    private static final Set<String> loggedRenderErrors = ConcurrentHashMap.newKeySet();
    private static final Pattern TEMPLATE_TOKEN = Pattern.compile("\\{([a-z_]+)(?::([^}]+))?}");

    public static void setCommand(JsonObject cmd, String scriptName) {
        String id = str(cmd, "id", "");
        if (!id.isEmpty()) {
            for (JsonObject existing : new ArrayList<>(commands)) {
                if (id.equals(str(existing, "id", "")) && scriptName.equals(str(existing, "_owner", "")) && commands.remove(existing)) {
                    clearCommandState(existing);
                }
            }
        }
        JsonObject copy = cmd.deepCopy();
        if (scriptName != null && !scriptName.isEmpty()) copy.addProperty("_owner", scriptName);
        commands.add(copy);
    }

    public static void clearIfOwner(String scriptName) {
        if (scriptName == null || scriptName.isEmpty()) return;
        for (JsonObject cmd : new ArrayList<>(commands)) {
            if (scriptName.equals(str(cmd, "_owner", "")) && commands.remove(cmd)) {
                clearCommandState(cmd);
            }
        }
    }

    public static void clearAll() {
        for (JsonObject cmd : new ArrayList<>(commands)) clearCommandState(cmd);
        commands.clear();
    }

    private static void clearCommandState(JsonObject cmd) {
        String id = str(cmd, "id", "");
        if (id.isEmpty()) return;
        barValues.keySet().removeIf(key -> key.equals(id) || key.startsWith(id + "|"));
    }

    public static void register() {
        LevelRenderEvents.BEFORE_GIZMOS.register(context -> {
            if (commands.isEmpty()) return;
            Minecraft client = Minecraft.getInstance();
            if (client.player == null) return;

            PoseStack ps = context.poseStack();
            if (ps == null) return;

            Camera camera = context.gameRenderer().mainCamera();
            Vec3 cam = camera.position();
            float tickProgress = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
            SubmitNodeCollector submit = context.submitNodeCollector();

            List<JsonObject> liveEspCmds = new ArrayList<>();
            List<List<LivingEntity>> liveEspEnts = new ArrayList<>();
            List<JsonObject> liveTagCmds = new ArrayList<>();
            List<List<LivingEntity>> liveTagEnts = new ArrayList<>();
            if (client.level != null) {
                for (JsonObject cmd : commands) {
                    String type = str(cmd, "type", "");
                    if ("live_esp".equals(type)) {
                        liveEspCmds.add(cmd);
                        liveEspEnts.add(getLiveEntities(cmd, client, false));
                    } else if ("live_tags".equals(type)) {
                        liveTagCmds.add(cmd);
                        liveTagEnts.add(getLiveEntities(cmd, client, true));
                    }
                }
            }

            ps.pushPose();
            ps.translate(-cam.x, -cam.y, -cam.z);

            // Lines are submitted as one custom-geometry node; it runs later in the frame, so it
            // works from this frame's snapshot of commands and entities.
            List<JsonObject> geometry = List.copyOf(commands);
            submit.submitCustomGeometry(ps, RenderTypes.LINES, (pose, lines) -> {
                PoseStack local = new PoseStack();
                local.last().set(pose);
                for (JsonObject cmd : geometry) {
                    try { renderGeometry(cmd, local, lines); }
                    catch (Exception e) { logRenderError("geometry", cmd, e); }
                }
                for (int i = 0; i < liveEspCmds.size(); i++) {
                    try { renderLiveEspGeometry(liveEspCmds.get(i), liveEspEnts.get(i), local, lines, camera, cam, tickProgress); }
                    catch (Exception e) { logRenderError("live_esp_geometry", liveEspCmds.get(i), e); }
                }
                for (int i = 0; i < liveTagCmds.size(); i++) {
                    try { renderLiveTagGeometry(liveTagCmds.get(i), liveTagEnts.get(i), local, lines, tickProgress); }
                    catch (Exception e) { logRenderError("live_tag_geometry", liveTagCmds.get(i), e); }
                }
            });

            for (JsonObject cmd : commands) {
                try { renderText(cmd, ps, submit, client, cam); }
                catch (Exception e) { logRenderError("text", cmd, e); }
            }
            for (int i = 0; i < liveEspCmds.size(); i++) {
                try { renderLiveEspText(liveEspCmds.get(i), liveEspEnts.get(i), ps, submit, client, cam, tickProgress); }
                catch (Exception e) { logRenderError("live_esp_text", liveEspCmds.get(i), e); }
            }
            for (int i = 0; i < liveTagCmds.size(); i++) {
                try { renderLiveTagText(liveTagCmds.get(i), liveTagEnts.get(i), ps, submit, client, cam, tickProgress); }
                catch (Exception e) { logRenderError("live_tag_text", liveTagCmds.get(i), e); }
            }

            ps.popPose();
        });
    }

    private static List<LivingEntity> getLiveEntities(JsonObject cmd, Minecraft client, boolean tagMode) {
        if (client.level == null || client.player == null) return List.of();
        String filter = str(cmd, "filter", "hostile");
        double radius = dbl(cmd, "radius", 64);
        double cx = client.player.getX();
        double cy = client.player.getY();
        double cz = client.player.getZ();
        AABB box = new AABB(cx - radius, cy - radius / 2, cz - radius,
                            cx + radius, cy + radius / 2 + 8, cz + radius);
        boolean requireCustomName = tagMode && getBool(cmd, "require_custom_name", false);
        boolean includePlayers = tagMode && getBool(cmd, "include_players", false);
        return client.level.getEntitiesOfClass(LivingEntity.class, box, entity -> {
            if (entity == client.player) return false;
            if (!matchesFilter(entity, filter)) return false;
            if (!tagMode || !requireCustomName) return true;
            if (entity.hasCustomName()) return true;
            return includePlayers && entity instanceof AbstractClientPlayer;
        });
    }

    private static boolean matchesFilter(LivingEntity entity, String filter) {
        return switch (filter) {
            case "hostile" -> entity instanceof Monster;
            case "passive" -> !(entity instanceof Monster);
            case "players" -> entity instanceof AbstractClientPlayer;
            default -> true;
        };
    }

    private static void renderLiveEspGeometry(JsonObject cmd, List<LivingEntity> entities,
            PoseStack ps, VertexConsumer lines, Camera camera, Vec3 cam, float tickProgress) {
        boolean showLines = getBool(cmd, "show_lines", true);
        double w = dbl(cmd, "w", 0.8);
        double h = dbl(cmd, "h", 1.9);
        double d = dbl(cmd, "d", 0.8);
        String colorStr = str(cmd, "color", "auto");

        Vec3 anchor = tracerOrigin(camera, cam);
        float ox = (float) anchor.x;
        float oy = (float) anchor.y;
        float oz = (float) anchor.z;

        for (LivingEntity entity : entities) {
            int color = "auto".equals(colorStr) ? autoColor(entity) : parseColor(cmd, "color", 0xFF00FFAA);
            Vec3 pos = lerpedPosition(entity, tickProgress);
            double ex = pos.x;
            double ey = pos.y;
            double ez = pos.z;
            drawBox(ps, lines, ex - w / 2, ey, ez - d / 2, ex + w / 2, ey + h, ez + d / 2, color);
            if (showLines) {
                drawLine(ps, lines, ox, oy, oz, (float) ex, (float) (ey + h / 2), (float) ez, color);
            }
        }
    }

    private static void renderLiveEspText(JsonObject cmd, List<LivingEntity> entities,
            PoseStack ps, SubmitNodeCollector submit, Minecraft client, Vec3 cam,
            float tickProgress) {
        if (!getBool(cmd, "show_labels", true)) return;
        double h = dbl(cmd, "h", 1.9);
        String colorStr = str(cmd, "color", "auto");
        boolean seeThrough = getBool(cmd, "see_through", true);

        for (LivingEntity entity : entities) {
            int color = "auto".equals(colorStr) ? autoColor(entity) : parseColor(cmd, "color", 0xFFDDDDDD);
            String label = liveEspLabel(entity, client);
            Vec3 pos = lerpedPosition(entity, tickProgress);
            drawWorldText(ps, submit, client.font, cam,
                    pos.x, pos.y + h + 0.3, pos.z, label, color,
                    dbl(cmd, "side_offset", 0), flt(cmd, "scale", 1f), seeThrough,
                    getBool(cmd, "face_pitch", false));
        }
    }

    private static void renderLiveTagGeometry(JsonObject cmd, List<LivingEntity> entities,
            PoseStack ps, VertexConsumer lines, float tickProgress) {
        if (!getBool(cmd, "show_box", false)) return;
        String colorKey = cmd.has("box_color") ? "box_color" : "color";
        for (LivingEntity entity : entities) {
            int color = isAutoHealthColor(str(cmd, colorKey, "auto"))
                ? autoColor(entity)
                : parseColor(cmd, colorKey, 0xFF00FFAA);
            double w = dbl(cmd, "box_w", entity.getBbWidth());
            double h = dbl(cmd, "box_h", entity.getBbHeight());
            double d = dbl(cmd, "box_d", entity.getBbWidth());
            Vec3 pos = lerpedPosition(entity, tickProgress);
            double ex = pos.x;
            double ey = pos.y;
            double ez = pos.z;
            drawBox(ps, lines, ex - w / 2, ey, ez - d / 2, ex + w / 2, ey + h, ez + d / 2, color);
        }
    }

    private static void renderLiveTagText(JsonObject cmd, List<LivingEntity> entities,
            PoseStack ps, SubmitNodeCollector submit, Minecraft client, Vec3 cam,
            float tickProgress) {
        JsonArray lines = cmd.has("lines") && cmd.get("lines").isJsonArray()
            ? cmd.getAsJsonArray("lines")
            : defaultTagLines();
        boolean showBar = getBool(cmd, "show_bar", true);
        double sideOffset = dbl(cmd, "side_offset", 0);
        double barSideOffset = dbl(cmd, "bar_side_offset", sideOffset);
        float scale = flt(cmd, "scale", 1f);
        float barScale = scale * flt(cmd, "bar_scale", 1f);
        boolean barShowText = getBool(cmd, "bar_show_text", false);
        boolean seeThrough = getBool(cmd, "see_through", true);
        boolean facePitch = getBool(cmd, "face_pitch", false);
        int widthChars = Math.max(1, (int) Math.round(dbl(cmd, "bar_width_chars", 10)));

        for (LivingEntity entity : entities) {
            Vec3 pos = lerpedPosition(entity, tickProgress);
            double ex = pos.x;
            double ey = pos.y;
            double ez = pos.z;
            double height = entity.getBbHeight();

            if (showBar) {
                double bx = ex + dbl(cmd, "bar_offset_x", 0);
                double by = ey + height + dbl(cmd, "bar_offset_y", 0.2);
                double bz = ez + dbl(cmd, "bar_offset_z", 0);
                String colorStr = str(cmd, "bar_color", "auto");
                int barColor = isAutoHealthColor(colorStr)
                    ? autoColor(entity)
                    : parseColor(cmd, "bar_color", 0xFF00FFAA);
                drawHealthBarPanel(ps, submit, client.font, cam,
                        bx, by, bz,
                        entity.getHealth(), entity.getMaxHealth(),
                        barColor, barShowText,
                        barStateKey(cmd, entity),
                        barSideOffset, barScale, widthChars, seeThrough,
                        getBool(cmd, "distance_scale", true),
                        expandTemplate(str(cmd, "bar_text", ""), entity, client),
                        facePitch);
            }

            if (lines.size() == 0) continue;
            double lx = ex + dbl(cmd, "label_offset_x", 0);
            double ly = ey + height + dbl(cmd, "label_offset_y", 0.55);
            double lz = ez + dbl(cmd, "label_offset_z", 0);
            drawBillboardLines(ps, submit, client.font, cam,
                    lx, ly, lz, lines, entity, client,
                    sideOffset, scale, flt(cmd, "line_spacing", 1f), seeThrough,
                    getBool(cmd, "distance_scale", true),
                    getBool(cmd, "label_background", true),
                    facePitch);
        }
    }

    private static int autoColor(LivingEntity entity) {
        float ratio = entity.getHealth() / Math.max(1f, entity.getMaxHealth());
        if (ratio > 0.5f) return 0xFF00FFAA;
        if (ratio > 0.25f) return 0xFFFFAA00;
        return 0xFFFF4444;
    }

    private static String liveEspLabel(LivingEntity entity, Minecraft client) {
        String hp = String.format(Locale.ROOT, "%.0f", entity.getHealth()) + "hp";
        if (client.player != null) {
            return prettyEntityName(entity) + "  " + hp + "  "
                + String.format(Locale.ROOT, "%.1f", client.player.distanceTo(entity)) + "m";
        }
        return prettyEntityName(entity) + "  " + hp;
    }

    private static String prettyEntityName(LivingEntity entity) {
        if (entity.hasCustomName() && entity.getCustomName() != null) {
            return entity.getCustomName().getString();
        }
        String raw = entity.getType().toString();
        int colon = raw.lastIndexOf(':');
        int dot = raw.lastIndexOf('.');
        int cut = Math.max(colon, dot);
        if (cut >= 0 && cut + 1 < raw.length()) raw = raw.substring(cut + 1);
        StringBuilder sb = new StringBuilder();
        for (String part : raw.split("_")) {
            if (!part.isEmpty()) sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1)).append(' ');
        }
        String name = sb.toString().trim();
        if (entity.isBaby() && !name.isEmpty()) name = "Baby " + name;
        return name.isEmpty() ? "Entity" : name;
    }

    private static void renderGeometry(JsonObject cmd, PoseStack ps, VertexConsumer lines) {
        String type = str(cmd, "type", "");
        int color = parseColor(cmd, "color", 0xFF00FFAA);
        switch (type) {
            case "esp_box" -> {
                double x = dbl(cmd, "x", 0);
                double y = dbl(cmd, "y", 0);
                double z = dbl(cmd, "z", 0);
                double w = dbl(cmd, "w", 1);
                double h = dbl(cmd, "h", 2);
                double d = dbl(cmd, "d", 1);
                drawBox(ps, lines, x - w / 2, y, z - d / 2, x + w / 2, y + h, z + d / 2, color);
            }
            case "line" -> {
                float x1 = (float) dbl(cmd, "x1", 0);
                float y1 = (float) dbl(cmd, "y1", 0);
                float z1 = (float) dbl(cmd, "z1", 0);
                float x2 = (float) dbl(cmd, "x2", 0);
                float y2 = (float) dbl(cmd, "y2", 0);
                float z2 = (float) dbl(cmd, "z2", 0);
                drawLine(ps, lines, x1, y1, z1, x2, y2, z2, color);
            }
            case "waypoint" -> {
                float x = (float) dbl(cmd, "x", 0);
                float y = (float) dbl(cmd, "y", 0);
                float z = (float) dbl(cmd, "z", 0);
                drawLine(ps, lines, x, y - 0.5f, z, x, y + 3f, z, color);
            }
            case "player_line" -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player == null) break;
                float px = (float) mc.player.getX();
                float py = (float) mc.player.getEyeY();
                float pz = (float) mc.player.getZ();
                float x2 = (float) dbl(cmd, "x", 0);
                float y2 = (float) dbl(cmd, "y", 0);
                float z2 = (float) dbl(cmd, "z", 0);
                drawLine(ps, lines, px, py, pz, x2, y2, z2, color);
            }
        }
    }

    private static void renderText(JsonObject cmd, PoseStack ps,
            SubmitNodeCollector submit, Minecraft client, Vec3 cam) {
        String type = str(cmd, "type", "");
        int color = parseColor(cmd, "color", 0xFFDDDDDD);
        double x;
        double y;
        double z;
        String label;
        switch (type) {
            case "world_text" -> {
                x = dbl(cmd, "x", 0);
                y = dbl(cmd, "y", 0);
                z = dbl(cmd, "z", 0);
                label = str(cmd, "text", "");
            }
            case "waypoint" -> {
                x = dbl(cmd, "x", 0);
                y = dbl(cmd, "y", 0) + 3.5;
                z = dbl(cmd, "z", 0);
                label = str(cmd, "label", "");
            }
            case "billboard" -> {
                renderBillboardPanel(cmd, ps, submit, client, cam);
                return;
            }
            case "health_bar" -> {
                renderHealthBarPanel(cmd, ps, submit, client, cam);
                return;
            }
            default -> {
                return;
            }
        }
        if (label.isEmpty()) return;
        drawWorldText(ps, submit, client.font, cam, x, y, z, label, color,
                dbl(cmd, "side_offset", 0), flt(cmd, "scale", 1f),
                getBool(cmd, "see_through", true), getBool(cmd, "face_pitch", false));
    }

    private static void renderBillboardPanel(JsonObject cmd, PoseStack ps,
            SubmitNodeCollector submit, Minecraft client, Vec3 cam) {
        if (!cmd.has("lines") || !cmd.get("lines").isJsonArray()) return;
        drawBillboardLines(ps, submit, client.font, cam,
                dbl(cmd, "x", 0), dbl(cmd, "y", 0), dbl(cmd, "z", 0),
                cmd.getAsJsonArray("lines"), null, client,
                dbl(cmd, "side_offset", 0), flt(cmd, "scale", 1f),
                flt(cmd, "line_spacing", 1f), getBool(cmd, "see_through", true),
                getBool(cmd, "distance_scale", true), getBool(cmd, "label_background", true),
                getBool(cmd, "face_pitch", false));
    }

    private static void renderHealthBarPanel(JsonObject cmd, PoseStack ps,
            SubmitNodeCollector submit, Minecraft client, Vec3 cam) {
        String colorStr = str(cmd, "color", "green");
        int barColor = isAutoHealthColor(colorStr)
            ? autoColorForRatio(flt(cmd, "value", 0f), flt(cmd, "max", 1f))
            : parseColor(cmd, "color", 0xFF00FFAA);
        drawHealthBarPanel(ps, submit, client.font, cam,
                dbl(cmd, "x", 0), dbl(cmd, "y", 0), dbl(cmd, "z", 0),
                flt(cmd, "value", 0f), flt(cmd, "max", 1f),
                barColor, getBool(cmd, "show_text", true), str(cmd, "id", ""),
                dbl(cmd, "side_offset", 0), flt(cmd, "scale", 1f),
                Math.max(1, (int) Math.round(dbl(cmd, "width_chars", 10))),
                getBool(cmd, "see_through", true), getBool(cmd, "distance_scale", true),
                str(cmd, "text", ""), getBool(cmd, "face_pitch", false));
    }

    private static void drawBillboardLines(PoseStack ps, SubmitNodeCollector submit,
            Font font, Vec3 cam, double bx, double by, double bz, JsonArray linesArr,
            LivingEntity entity, Minecraft client, double sideOffset, float scaleMult,
            float lineSpacing, boolean seeThrough, boolean distanceScale, boolean labelBackground,
            boolean facePitch) {
        if (linesArr == null || linesArr.size() == 0) return;
        float scale = scaledSize(cam, bx, by, bz, scaleMult, distanceScale);
        float yaw = billboardYaw(cam, bx, bz);
        int lineH = font.lineHeight;
        Font.DisplayMode mode = seeThrough ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL;
        int bg = labelBackground ? 0xA0000000 : 0;

        ps.pushPose();
        ps.translate(bx, by, bz);
        ps.mulPose(new Quaternionf().rotationY(yaw));
        if (facePitch) ps.mulPose(new Quaternionf().rotationX(billboardPitch(cam, bx, by, bz)));
        if (sideOffset != 0) ps.translate(sideOffset, 0, 0);
        ps.scale(-scale, -scale, scale);

        for (int i = 0; i < linesArr.size(); i++) {
            JsonElement elem = linesArr.get(i);
            if (!elem.isJsonObject()) continue;
            JsonObject lineObj = elem.getAsJsonObject();
            String raw = str(lineObj, "text", "");
            String text = entity == null ? raw : expandTemplate(raw, entity, client);
            if (text.isEmpty()) continue;
            int color = lineColor(lineObj, entity, 0xFFDDDDDD);
            float halfW = font.width(text) / 2f;
            float y = i * lineH * Math.max(0.5f, lineSpacing);
            if (labelBackground) {
                submit.submitText(ps, -halfW, y, seq(text), false, mode, 0xF000F0, 0x00FFFFFF, bg, 0);
                ps.pushPose();
                ps.translate(0, 0, LABEL_TEXT_Z_OFFSET);
                submit.submitText(ps, -halfW, y, seq(text), false, mode, 0xF000F0, color, 0, 0);
                ps.popPose();
            } else {
                submit.submitText(ps, -halfW, y, seq(text), false, mode, 0xF000F0, color, 0, 0);
            }
        }

        ps.popPose();
    }

    private static void drawHealthBarPanel(PoseStack ps, SubmitNodeCollector submit,
            Font font, Vec3 cam, double bx, double by, double bz, float value, float max,
            int barColor, boolean showText, String idKey, double sideOffset, float scaleMult,
            int widthChars, boolean seeThrough, boolean distanceScale, String labelOverride, boolean facePitch) {
        float target = max > 0 ? Math.max(0f, Math.min(1f, value / max)) : 0f;
        float ratio = target;
        if (idKey != null && !idKey.isEmpty()) {
            float prev = barValues.getOrDefault(idKey, target);
            ratio = prev + (target - prev) * 0.15f;
            barValues.put(idKey, ratio);
        }

        float scale = scaledSize(cam, bx, by, bz, scaleMult, distanceScale);
        float yaw = billboardYaw(cam, bx, bz);
        int safeWidthChars = Math.max(1, widthChars);
        String emptyBar = "█".repeat(safeWidthChars);
        int fillChars = Math.round(ratio * safeWidthChars);
        String fillBar = "█".repeat(Math.max(0, fillChars));
        Font.DisplayMode mode = seeThrough ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL;

        ps.pushPose();
        ps.translate(bx, by, bz);
        ps.mulPose(new Quaternionf().rotationY(yaw));
        if (facePitch) ps.mulPose(new Quaternionf().rotationX(billboardPitch(cam, bx, by, bz)));
        if (sideOffset != 0) ps.translate(sideOffset, 0, 0);
        ps.scale(-scale, -scale, scale);

        float barX = -font.width(emptyBar) / 2f;
        float barY = showText ? font.lineHeight + 1 : 0;

        if (showText) {
            String label = (labelOverride != null && !labelOverride.isEmpty())
                ? labelOverride
                : String.format(Locale.ROOT, "%.0f / %.0f", value, max);
            submit.submitText(ps, -font.width(label) / 2f, 0, seq(label), false, mode, 0xF000F0, 0xFFDDDDDD, 0xA0000000, 0);
        }

        submit.submitText(ps, barX, barY, seq(emptyBar), false, mode, 0xF000F0, 0xFF222222, 0, 0);
        if (fillChars > 0) {
            ps.pushPose();
            ps.translate(0, 0, HEALTH_BAR_FILL_Z_OFFSET);
            submit.submitText(ps, barX, barY, seq(fillBar), false, mode, 0xF000F0, (barColor & 0x00FFFFFF) | 0xFF000000, 0, 0);
            ps.popPose();
        }

        ps.popPose();
    }

    private static int autoColorForRatio(float value, float max) {
        float ratio = max > 0 ? Math.max(0f, Math.min(1f, value / max)) : 0f;
        if (ratio > 0.5f) return 0xFF00FFAA;
        if (ratio > 0.25f) return 0xFFFFAA00;
        return 0xFFFF4444;
    }

    private static int lineColor(JsonObject lineObj, LivingEntity entity, int def) {
        String color = str(lineObj, "color", "");
        if (entity != null && isAutoHealthColor(color)) return autoColor(entity);
        return parseColor(lineObj, "color", def);
    }

    private static boolean isAutoHealthColor(String color) {
        return "auto".equals(color) || "auto_health".equals(color);
    }

    private static JsonArray defaultTagLines() {
        JsonArray arr = new JsonArray();
        JsonObject name = new JsonObject();
        name.addProperty("text", "{name}");
        name.addProperty("color", "auto_health");
        arr.add(name);
        JsonObject distance = new JsonObject();
        distance.addProperty("text", "{distance:.1f} m");
        distance.addProperty("color", "grey");
        arr.add(distance);
        return arr;
    }

    private static String barStateKey(JsonObject cmd, LivingEntity entity) {
        String id = str(cmd, "id", "");
        if (id.isEmpty()) return "";
        return id + "|" + entity.getUUID();
    }

    private static String expandTemplate(String template, LivingEntity entity, Minecraft client) {
        if (template == null || template.isEmpty() || entity == null) return template == null ? "" : template;
        Matcher matcher = TEMPLATE_TOKEN.matcher(template);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String token = matcher.group(1);
            String fmt = matcher.group(2);
            matcher.appendReplacement(out, Matcher.quoteReplacement(tokenValue(token, fmt, entity, client)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String tokenValue(String token, String fmt, LivingEntity entity, Minecraft client) {
        return switch (token) {
            case "name" -> prettyEntityName(entity);
            case "custom_name" -> entity.hasCustomName() && entity.getCustomName() != null
                ? entity.getCustomName().getString()
                : "";
            case "type" -> entity.getType().toString();
            case "distance" -> formatNumber(client.player != null ? client.player.distanceTo(entity) : 0, fmt, 1);
            case "health" -> formatNumber(entity.getHealth(), fmt, 0);
            case "max_health" -> formatNumber(entity.getMaxHealth(), fmt, 0);
            case "health_percent" -> formatNumber(
                entity.getHealth() / Math.max(1f, entity.getMaxHealth()) * 100f, fmt, 0);
            default -> "";
        };
    }

    private static String formatNumber(double value, String fmt, int defaultDigits) {
        try {
            if (fmt != null && !fmt.isEmpty()) return String.format(Locale.ROOT, "%" + fmt, value);
            return String.format(Locale.ROOT, "%." + defaultDigits + "f", value);
        } catch (Exception ignored) {
            return String.format(Locale.ROOT, "%." + defaultDigits + "f", value);
        }
    }

    private static float baseScale(Vec3 cam, double x, double y, double z) {
        double dx = x - cam.x;
        double dy = y - cam.y;
        double dz = z - cam.z;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return Math.min(0.06f, (float) (0.025 * Math.max(2.0, dist) / 8.0));
    }

    private static float scaledSize(Vec3 cam, double x, double y, double z, float scaleMult, boolean distanceScale) {
        float base = distanceScale ? baseScale(cam, x, y, z) : 0.025f;
        return base * Math.max(0.1f, scaleMult);
    }

    private static float billboardYaw(Vec3 cam, double x, double z) {
        float dx = (float) (cam.x - x);
        float dz = (float) (cam.z - z);
        return (float) Math.atan2(dx, dz) + (float) Math.PI;
    }

    private static float billboardPitch(Vec3 cam, double x, double y, double z) {
        double dx = cam.x - x;
        double dy = cam.y - y;
        double dz = cam.z - z;
        double horiz = Math.max(1.0E-4, Math.sqrt(dx * dx + dz * dz));
        return (float) -Math.atan2(dy, horiz);
    }

    private static Vec3 tracerOrigin(Camera camera, Vec3 cam) {
        var forward = camera.forwardVector();
        return cam.add(forward.x() * 0.25, forward.y() * 0.25, forward.z() * 0.25);
    }

    private static Vec3 lerpedPosition(LivingEntity entity, float tickProgress) {
        return entity.getPosition(tickProgress);
    }

    private static void logRenderError(String stage, JsonObject cmd, Exception e) {
        String type = str(cmd, "type", "?");
        String id = str(cmd, "id", "");
        String key = stage + "|" + type + "|" + id + "|" + e.getClass().getName();
        if (loggedRenderErrors.add(key)) {
            PeripheralClient.LOGGER.error("[Peripheral] Overlay render failed during {} for type={} id={}",
                stage, type, id, e);
        }
    }

    private static void drawBox(PoseStack ps, VertexConsumer vc,
            double x0, double y0, double z0, double x1, double y1, double z1, int color) {
        float ax = (float) x0;
        float ay = (float) y0;
        float az = (float) z0;
        float bx = (float) x1;
        float by = (float) y1;
        float bz = (float) z1;
        drawLine(ps, vc, ax, ay, az, bx, ay, az, color);
        drawLine(ps, vc, bx, ay, az, bx, ay, bz, color);
        drawLine(ps, vc, bx, ay, bz, ax, ay, bz, color);
        drawLine(ps, vc, ax, ay, bz, ax, ay, az, color);
        drawLine(ps, vc, ax, by, az, bx, by, az, color);
        drawLine(ps, vc, bx, by, az, bx, by, bz, color);
        drawLine(ps, vc, bx, by, bz, ax, by, bz, color);
        drawLine(ps, vc, ax, by, bz, ax, by, az, color);
        drawLine(ps, vc, ax, ay, az, ax, by, az, color);
        drawLine(ps, vc, bx, ay, az, bx, by, az, color);
        drawLine(ps, vc, bx, ay, bz, bx, by, bz, color);
        drawLine(ps, vc, ax, ay, bz, ax, by, bz, color);
    }

    private static void drawLine(PoseStack ps, VertexConsumer vc,
            float x1, float y1, float z1, float x2, float y2, float z2, int color) {
        int a = (color >>> 24);
        if (a == 0) a = 255;
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        float dx = x2 - x1;
        float dy = y2 - y1;
        float dz = z2 - z1;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len > 0) {
            dx /= len;
            dy /= len;
            dz /= len;
        }
        PoseStack.Pose pose = ps.last();
        vc.addVertex(pose, x1, y1, z1).setColor(r, g, b, a).setNormal(pose, dx, dy, dz).setLineWidth(2.5f);
        vc.addVertex(pose, x2, y2, z2).setColor(r, g, b, a).setNormal(pose, dx, dy, dz).setLineWidth(2.5f);
    }

    private static void drawWorldText(PoseStack ps, SubmitNodeCollector submit,
            Font font, Vec3 cam, double x, double y, double z, String text, int color,
            double sideOffset, float scaleMult, boolean seeThrough, boolean facePitch) {
        float scale = baseScale(cam, x, y, z) * Math.max(0.1f, scaleMult);
        float yaw = billboardYaw(cam, x, z);
        Font.DisplayMode mode = seeThrough ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL;

        ps.pushPose();
        ps.translate(x, y, z);
        ps.mulPose(new Quaternionf().rotationY(yaw));
        if (facePitch) ps.mulPose(new Quaternionf().rotationX(billboardPitch(cam, x, y, z)));
        if (sideOffset != 0) ps.translate(sideOffset, 0, 0);
        ps.scale(-scale, -scale, scale);
        float halfW = font.width(text) / 2f;
        submit.submitText(ps, -halfW, 0, seq(text), false, mode, 0xF000F0, color, 0xA0000000, 0);
        ps.popPose();
    }

    private static FormattedCharSequence seq(String text) {
        return FormattedCharSequence.forward(text, Style.EMPTY);
    }

    private static String str(JsonObject obj, String key, String def) {
        return obj.has(key) ? obj.get(key).getAsString() : def;
    }

    private static double dbl(JsonObject obj, String key, double def) {
        try { return obj.has(key) ? obj.get(key).getAsDouble() : def; }
        catch (Exception ignored) { return def; }
    }

    private static float flt(JsonObject obj, String key, float def) {
        return (float) dbl(obj, key, def);
    }

    private static boolean getBool(JsonObject obj, String key, boolean def) {
        return obj.has(key) ? obj.get(key).getAsBoolean() : def;
    }

    static int parseColor(JsonObject obj, String key, int def) {
        if (!obj.has(key)) return def;
        String s = obj.get(key).getAsString().trim();
        if (s.equals("auto") || s.equals("auto_health")) return def;
        try {
            if (s.startsWith("#")) {
                long v = Long.parseLong(s.substring(1), 16);
                return s.length() > 7 ? (int) v : (int) (0xFF000000L | v);
            }
            return switch (s.toLowerCase(Locale.ROOT)) {
                case "red" -> 0xFFFF4444;
                case "green" -> 0xFF00FFAA;
                case "yellow" -> 0xFFFFAA00;
                case "blue" -> 0xFF00CCFF;
                case "white" -> 0xFFDDDDDD;
                case "grey", "gray" -> 0xFF888888;
                case "dark_grey", "dark_gray" -> 0xFF444444;
                case "orange" -> 0xFFFF8800;
                case "purple" -> 0xFFAA66FF;
                case "pink" -> 0xFFFF88BB;
                case "dark_red" -> 0xFFAA0000;
                case "dark_green" -> 0xFF007755;
                default -> def;
            };
        } catch (Exception ignored) {
            return def;
        }
    }
}
