package com.github.AaronAA0721.villageragent.client;

import com.github.AaronAA0721.villageragent.config.ModConfig;
import com.github.AaronAA0721.villageragent.network.DebugDataPacket;
import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.AbstractGui;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side debug overlay. Renders:
 *  - a HUD panel with the looked-at villager's identity, inner / psychological
 *    state (mood, activity, planned action, needs, goals, memories, chunk tags)
 *    and spatial perception (live vision scan + environment summary), and
 *  - in-world wireframe boxes around detected buildings (world-space, camera-relative).
 *
 * <p>Every line here is inherently debug-only: the whole overlay only receives
 * data from the server and renders while {@link ModConfig#ENABLE_DEBUG_OVERLAY}
 * is on, and the HUD panel is further gated by {@code DEBUG_SHOW_HUD}. Per-section
 * rendering is additionally gated by the {@code debug_show_*} config flags.
 */
@Mod.EventBusSubscriber(modid = "villageragent", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class DebugOverlay {

    /** Latest snapshot received from the server (set from the packet handler). */
    public static volatile DebugDataPacket latest = null;
    public static long lastReceived = 0L;

    private static final long STALE_MS = 5000;

    private DebugOverlay() {}

    public static void setLatest(DebugDataPacket p) {
        latest = p;
        lastReceived = System.currentTimeMillis();
    }

    private static boolean fresh() {
        return latest != null && (System.currentTimeMillis() - lastReceived) < STALE_MS;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  HUD panel
    // ═══════════════════════════════════════════════════════════════════════

    @SubscribeEvent
    public static void onRenderHud(RenderGameOverlayEvent.Post event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.TEXT) return;
        if (!ModConfig.DEBUG_SHOW_HUD.get()) return;
        if (!fresh()) return;

        Minecraft mc = Minecraft.getInstance();
        DebugDataPacket p = latest;
        MatrixStack ms = event.getMatrixStack();

        List<String> lines = new ArrayList<>();
        lines.add("[VillagerAgent Debug]  agents=" + p.agentCount + "  buildings(near)=" + p.nearbyBuildings);
        if (p.hasTarget) {
            // Identity + psychological state + spatial perception.
            // This whole panel only renders while the debug overlay is enabled
            // (gated by DEBUG_SHOW_HUD / ENABLE_DEBUG_OVERLAY), so the inner state
            // below is effectively a debug-only display — never shown in normal play.
            lines.add(p.targetName + " (" + p.targetProfession + ")  " + p.targetPersonality);
            lines.add("mood=" + p.targetMood + "  activity=" + p.targetActivity
                    + "  planned=" + p.targetScheduled);
            lines.add("action=" + p.targetAction + "  hunger=" + (int) p.hunger
                    + "  fatigue=" + (int) p.fatigue);
            lines.add("chunkMem=" + p.chunkMemoryCount + "  tags=" + String.join("/", p.chunkTags));
            lines.add("goals(" + p.goalsCount + "): " + (p.goals.isEmpty() ? "-" : String.join(" | ", p.goals)));
            lines.add("memories=" + p.memoriesCount + (p.memories.isEmpty() ? "" : "  > " + String.join(" / ", p.memories)));
            lines.add("frustum scan: " + p.frustumBlocks + " blocks, " + p.frustumEntities
                    + " entities  [" + String.join(", ", p.frustumSamples) + "]");
            String env = p.targetEnv.replace("\n", " ");
            int cut = 110;
            lines.add("env: " + (env.length() <= cut ? env : env.substring(0, cut) + "..."));
            if (env.length() > cut) lines.add("     " + env.substring(cut));
        } else {
            lines.add("Look at a villager to inspect its AI state & perception.");
        }

        int x = 6, y = 6, w = 560, lh = 11;
        int h = lines.size() * lh + 8;
        AbstractGui.fill(ms, x - 3, y - 3, x + w, y + h, 0x90000000);
        AbstractGui.fill(ms, x - 3, y - 3, x + w, y - 2, 0xAA33AA33);

        for (int i = 0; i < lines.size(); i++) {
            mc.font.draw(ms, lines.get(i), x, y + i * lh + 2, 0xFFE0E0E0);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  World-space debug geometry
    // ═══════════════════════════════════════════════════════════════════════

    @SubscribeEvent
    public static void onRenderWorldLast(RenderWorldLastEvent event) {
        if (!fresh()) return;
        DebugDataPacket p = latest;

        MatrixStack ms = event.getMatrixStack();
        ActiveRenderInfo cam = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vector3d cp = cam.getPosition();

        ms.pushPose();
        ms.translate(-cp.x, -cp.y, -cp.z);

        // The Tessellator draws using RenderSystem's matrix, which is NOT the
        // event MatrixStack. Sync the camera-relative (world-space) transform so the
        // boxes are projected at their real world positions instead of screen space.
        RenderSystem.pushMatrix();
        RenderSystem.multMatrix(ms.last().pose());

        RenderSystem.enableBlend();
        RenderSystem.disableDepthTest();
        RenderSystem.disableTexture();
        RenderSystem.defaultBlendFunc();
        RenderSystem.lineWidth(2.0F);

        net.minecraft.client.renderer.Tessellator t = net.minecraft.client.renderer.Tessellator.getInstance();
        BufferBuilder b = t.getBuilder();
        b.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);

        if (ModConfig.DEBUG_SHOW_BUILDINGS.get()) {
            for (DebugDataPacket.BuildingBox bb : p.buildings) {
                float[] col = colorForType(bb.type);
                addBox(b, bb.min.getX(), bb.min.getY(), bb.min.getZ(),
                        bb.max.getX() + 1, bb.max.getY() + 1, bb.max.getZ() + 1,
                        col[0], col[1], col[2], 0.9F);
            }
        }

        if (ModConfig.DEBUG_SHOW_SEEDS.get()) {
            // Seed markers: a cross at the centre of the seed block. Interior / room-candidate seeds
            // are yellow; atmosphere (front-start ring) seeds are magenta.
            for (DebugDataPacket.SeedPoint sp : p.seeds) {
                float[] col = sp.interior
                        ? new float[]{1.0F, 0.85F, 0.0F}   // yellow  — room-candidate seed
                        : new float[]{1.0F, 0.20F, 0.80F};  // magenta — atmosphere (front) seed
                double cx = sp.pos.getX() + 0.5, cy = sp.pos.getY() + 0.5, cz = sp.pos.getZ() + 0.5;
                addCross(b, cx, cy, cz, 0.45, col[0], col[1], col[2], 1.0F);
            }
        }

        if (ModConfig.DEBUG_SHOW_FIELD.get()) {
            // Full watershed collection field. Each non-atmospheric collection gets a distinct colour
            // from the palette; the atmospheric collection is uniform cyan; boundaries are white.
            for (DebugDataPacket.FieldCell fc : p.field) {
                float[] col;
                if (fc.boundary())          col = new float[]{1.0F, 1.0F, 1.0F};        // white  — boundary
                else if (fc.atmospheric())   col = new float[]{0.0F, 0.85F, 0.95F};     // cyan   — atmosphere
                else                         col = palette(fc.setId());                 // per-collection hue
                double x0 = fc.wx + 0.08, y0 = fc.wy + 0.08, z0 = fc.wz + 0.08;
                double x1 = fc.wx + 0.92, y1 = fc.wy + 0.92, z1 = fc.wz + 0.92;
                addBox(b, x0, y0, z0, x1, y1, z1, col[0], col[1], col[2], 0.35F);
            }
        }

        t.end();

        RenderSystem.enableDepthTest();
        RenderSystem.enableTexture();
        RenderSystem.disableBlend();
        RenderSystem.popMatrix();
        ms.popPose();
    }

    private static void addBox(BufferBuilder b, double x0, double y0, double z0,
                               double x1, double y1, double z1,
                               float r, float g, float bl, float a) {
        double[] cx = {x0, x1}, cy = {y0, y1}, cz = {z0, z1};
        int[][] edges = {
                {0,0,0, 1,0,0}, {1,0,0, 1,1,0}, {1,1,0, 0,1,0}, {0,1,0, 0,0,0},
                {0,0,1, 1,0,1}, {1,0,1, 1,1,1}, {1,1,1, 0,1,1}, {0,1,1, 0,0,1},
                {0,0,0, 0,0,1}, {1,0,0, 1,0,1}, {1,1,0, 1,1,1}, {0,1,0, 0,1,1}
        };
        for (int[] e : edges) {
            b.vertex(cx[e[0]], cy[e[1]], cz[e[2]]).color(r, g, bl, a).endVertex();
            b.vertex(cx[e[3]], cy[e[4]], cz[e[5]]).color(r, g, bl, a).endVertex();
        }
    }

    /** Draw a small 3-axis cross centred at (cx,cy,cz) — used to mark a seed block. */
    private static void addCross(BufferBuilder b, double cx, double cy, double cz, double r,
                                 float cr, float cg, float cb, float ca) {
        b.vertex(cx - r, cy, cz).color(cr, cg, cb, ca).endVertex();
        b.vertex(cx + r, cy, cz).color(cr, cg, cb, ca).endVertex();
        b.vertex(cx, cy - r, cz).color(cr, cg, cb, ca).endVertex();
        b.vertex(cx, cy + r, cz).color(cr, cg, cb, ca).endVertex();
        b.vertex(cx, cy, cz - r).color(cr, cg, cb, ca).endVertex();
        b.vertex(cx, cy, cz + r).color(cr, cg, cb, ca).endVertex();
    }

    /** Distinct, deterministic colour per collection id (used for watershed sets). */
    private static final float[][] PALETTE = {
            {1.0F, 0.45F, 0.45F}, {0.45F, 1.0F, 0.45F}, {0.45F, 0.65F, 1.0F},
            {1.0F, 1.0F, 0.45F},  {1.0F, 0.45F, 1.0F},  {0.45F, 1.0F, 1.0F},
            {1.0F, 0.70F, 0.40F}, {0.70F, 0.45F, 1.0F}
    };
    private static float[] palette(int id) {
        return PALETTE[Math.abs(id) % PALETTE.length];
    }

    private static float[] colorForType(String type) {
        if (type == null) return new float[]{0.6F, 0.6F, 0.6F};
        switch (type) {
            case "cave_house": return new float[]{0.3F, 0.6F, 1.0F};
            case "barn":       return new float[]{1.0F, 0.6F, 0.2F};
            case "house":      return new float[]{0.4F, 1.0F, 0.4F};
            default:           return new float[]{0.8F, 0.8F, 0.3F};
        }
    }
}
