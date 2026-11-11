package com.github.AaronAA0721.villageragent.client;

import com.github.AaronAA0721.villageragent.config.ModConfig;
import com.github.AaronAA0721.villageragent.network.ShowcasePacket;
import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.AbstractGui;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.vector.Matrix4f;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.math.vector.Vector3f;
import net.minecraft.util.math.vector.Vector4f;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.opengl.GL11;

/**
 * Promotional overlay. World space: a fixed-size card above every villager (name · job, task, step and a
 * goal-weight bar) and a request-system tag. Screen space: the current village's notice board, top right.
 * Data comes only from {@link ShowcasePacket}; nothing renders once the server stops sending it.
 */
@Mod.EventBusSubscriber(modid = "villageragent", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShowcaseOverlay {
    private static volatile ShowcasePacket latest;
    private static volatile long received;
    private static final long STALE_MS = 2000;
    private static final int CARD_WIDTH = 150;

    private ShowcaseOverlay() {}

    public static void receive(ShowcasePacket packet) {
        latest = packet;
        received = System.currentTimeMillis();
    }

    private static ShowcasePacket fresh() {
        ShowcasePacket p = latest;
        return p != null && System.currentTimeMillis() - received < STALE_MS ? p : null;
    }

    static int goalColor(String type) {
        if (type == null) return 0xFFB4B2A9;
        if (type.startsWith("acquire") || type.equals("supply_request")) return 0xFFED93B1;
        switch (type) {
            case "farm": case "gather": case "farm_maintain": case "farm_reclaim": case "collect_crops": return 0xFF97C459;
            case "rest": case "sleep": return 0xFF85B7EB;
            case "combat": case "defend": return 0xFFF09595;
            case "socialize": case "converse": return 0xFFAFA9EC;
            case "trade": case "restock": case "deliver_trade": case "meet_trade": case "fulfill_order": return 0xFF5DCAA5;
            case "craft": case "smelt": case "collect_smelt": case "lumberjack_work": case "miner_work":
            case "fish": case "butcher_work": case "shepherd_work": return 0xFFFAC775;
            default: return 0xFFB4B2A9;
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Screen: notice board (top right)
    // ═══════════════════════════════════════════════════════════════════

    @SubscribeEvent
    public static void onRenderHud(RenderGameOverlayEvent.Post event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.TEXT) return;
        ShowcasePacket p = fresh();
        if (p == null || !p.board) return;
        Minecraft mc = Minecraft.getInstance();
        FontRenderer font = mc.font;
        MatrixStack ms = event.getMatrixStack();
        float scale = (float) (double) ModConfig.SHOWCASE_TEXT_SCALE.get();
        int width = 270, lh = 11;
        int lines = 3 + Math.max(1, p.rows.size()) + 1;
        int height = lines * lh + 10;
        // Lay out in unscaled units, anchored to the top-right corner after scaling.
        int screenW = mc.getWindow().getGuiScaledWidth();
        ms.pushPose();
        ms.translate(screenW - (width + 10) * scale, 6, 0);
        ms.scale(scale, scale, 1);
        int x = 4, y = 4;
        AbstractGui.fill(ms, 0, 0, width + 8, height, 0xD0101014);
        AbstractGui.fill(ms, 0, 0, width + 8, 2, 0xFFFFD27A);

        font.drawShadow(ms, p.hasVillage ? p.boardTitle : "Village Notice Board", x, y + 1, 0xFFFFD27A);
        y += lh + 2;
        if (!p.hasVillage) {
            font.drawShadow(ms, "Fly over a village to see its requests.", x, y, 0xFFE0E0E0);
            ms.popPose();
            return;
        }
        font.drawShadow(ms, "Open " + p.open, x, y, bright(0xFFED93B1));
        font.drawShadow(ms, "Claimed " + p.claimed, x + 60, y, bright(0xFF5DCAA5));
        font.drawShadow(ms, "Delivered " + p.delivered, x + 135, y, bright(0xFF97C459));
        y += lh + 2;
        if (p.rows.isEmpty()) {
            font.drawShadow(ms, "No requests posted right now.", x, y, 0xFFCCCCCC);
            y += lh;
        }
        for (ShowcasePacket.Row row : p.rows) {
            String status = fit(font, row.status, 115);
            int statusWidth = font.width(status);
            String left = fit(font, row.item + " x" + row.quantity + "  \u2014 " + row.author, width - statusWidth - 8);
            font.drawShadow(ms, left, x, y, 0xFFFFFFFF);
            font.drawShadow(ms, status, x + width - statusWidth, y, bright(row.color));
            y += lh;
        }
        y += 3;
        int lx = x;
        lx = legend(ms, font, lx, y, "Requested", 0xFFED93B1);
        lx = legend(ms, font, lx, y, "Making", 0xFFFAC775);
        lx = legend(ms, font, lx, y, "Delivering", 0xFF5DCAA5);
        legend(ms, font, lx, y, "Receiving", 0xFF85B7EB);
        ms.popPose();
    }

    /** Lift a palette colour toward white so it reads on a dark panel. */
    static int bright(int argb) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        r = r + (255 - r) * 2 / 5; g = g + (255 - g) * 2 / 5; b = b + (255 - b) * 2 / 5;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int legend(MatrixStack ms, FontRenderer font, int x, int y, String text, int color) {
        AbstractGui.fill(ms, x, y + 1, x + 6, y + 7, color);
        font.drawShadow(ms, text, x + 8, y, 0xFFE6E6E6);
        return x + 8 + font.width(text) + 8;
    }

    private static String fit(FontRenderer font, String s, int maxWidth) {
        if (s == null) return "";
        if (font.width(s) <= maxWidth) return s;
        String ellipsis = "...";
        int end = s.length();
        while (end > 0 && font.width(s.substring(0, end) + ellipsis) > maxWidth) end--;
        return s.substring(0, end) + ellipsis;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  World: per-villager cards and request tags
    // ═══════════════════════════════════════════════════════════════════

    @SubscribeEvent
    public static void onRenderWorldLast(RenderWorldLastEvent event) {
        ShowcasePacket p = fresh();
        if (p == null || (!p.labels && !p.board)) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        FontRenderer font = mc.font;
        ActiveRenderInfo cam = mc.gameRenderer.getMainCamera();
        Vector3d camPos = cam.getPosition();
        float partial = event.getPartialTicks();
        double range = ModConfig.SHOWCASE_LABEL_RANGE.get();
        IRenderTypeBuffer.Impl buffer = mc.renderBuffers().bufferSource();
        buffer.endBatch();
        // Cards are an overlay, never part of the scene: drop the terrain depth so no block can hide them.
        // (The first-person hand pass clears depth again on its own.)
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);

        // Farthest first, so nearer cards are painted over farther ones.
        java.util.List<ShowcasePacket.Card> ordered = new java.util.ArrayList<>(p.cards);
        java.util.Map<ShowcasePacket.Card, Double> dist = new java.util.HashMap<>();
        for (ShowcasePacket.Card card : ordered) {
            Entity e = mc.level.getEntity(card.entityId);
            dist.put(card, e == null ? 0 : e.distanceToSqr(camPos));
        }
        ordered.sort((a, b) -> Double.compare(dist.get(b), dist.get(a)));

        for (ShowcasePacket.Card card : ordered) {
            boolean hasTag = p.board && !card.tag.isEmpty();
            if (!p.labels && !hasTag) continue;
            Entity entity = mc.level.getEntity(card.entityId);
            if (entity == null) continue;
            double x = entity.xOld + (entity.getX() - entity.xOld) * partial;
            double y = entity.yOld + (entity.getY() - entity.yOld) * partial;
            double z = entity.zOld + (entity.getZ() - entity.zOld) * partial;
            double dx = x - camPos.x, dy = y - camPos.y + entity.getBbHeight() + 0.6, dz = z - camPos.z;
            if (dx * dx + dy * dy + dz * dz > range * range) continue;

            // Fixed GUI-pixel size: one font unit = one GUI pixel at any distance/FOV.
            Vector4f anchor = new Vector4f((float) dx, (float) dy, (float) dz, 1);
            anchor.transform(event.getMatrixStack().last().pose());
            anchor.transform(event.getProjectionMatrix());
            if (anchor.w() <= 0.01F) continue;
            Vector4f vertical = new Vector4f(0, 1, 0, 0);
            vertical.transform(event.getProjectionMatrix());
            float projectionY = Math.abs(vertical.y());
            if (projectionY <= 0.0001F) continue;
            float scale = 2.0F * anchor.w() / (projectionY * mc.getWindow().getGuiScaledHeight())
                    * (float) (double) ModConfig.SHOWCASE_TEXT_SCALE.get();

            MatrixStack ms = event.getMatrixStack();
            ms.pushPose();
            ms.translate(dx, dy, dz);
            ms.mulPose(Vector3f.YP.rotationDegrees(-cam.getYRot()));
            ms.mulPose(Vector3f.XP.rotationDegrees(cam.getXRot()));
            ms.scale(-scale, -scale, scale);
            Matrix4f matrix = ms.last().pose();

            float top = 0;
            if (p.labels) {
                int taskColor = bright(goalColor(card.weights.isEmpty() ? null : topType(card)));
                String title = fit(font, card.name + " \u00b7 " + card.job, CARD_WIDTH);
                String task = fit(font, "Task: " + card.task, CARD_WIDTH);
                String step = fit(font, "Step: " + card.step, CARD_WIDTH);
                float w = Math.max(CARD_WIDTH - 20, Math.max(font.width(title), Math.max(font.width(task), font.width(step))));
                float half = w / 2 + 5;
                float barY = -17, barH = 5;
                float panelTop = barY - 37, panelBottom = barY + barH + 9;
                // One solid panel behind the whole card, with an accent stripe in the task colour.
                fillRect(matrix, -half, panelTop, half, panelBottom, 0xD0101014);
                fillRect(matrix, -half, panelTop, -half + 2, panelBottom, taskColor);
                drawBar(font, buffer, matrix, card, barY, barH, w);
                line(font, buffer, matrix, step, barY - 11, 0xFFE6E6E6);
                line(font, buffer, matrix, task, barY - 22, taskColor);
                line(font, buffer, matrix, title, barY - 33, 0xFFFFFFFF);
                top = panelTop;
            }
            if (hasTag) {
                String tag = fit(font, card.tag, CARD_WIDTH + 30);
                int color = bright(card.tagColor);
                float tw = font.width(tag);
                float ty = top - 13;
                fillRect(matrix, -tw / 2 - 5, ty - 3, tw / 2 + 4, ty + 10, 0xE0101014);
                fillRect(matrix, -tw / 2 - 5, ty - 3, -tw / 2 - 2, ty + 10, color);
                font.drawInBatch(tag, -tw / 2, ty, color, true, matrix, buffer, true, 0, 0xF000F0);
            }
            ms.popPose();
            buffer.endBatch(); // flush this card's text before the next (nearer) card's panel
        }
        RenderSystem.disableDepthTest();
        buffer.endBatch();
        RenderSystem.enableDepthTest();
    }

    private static String topType(ShowcasePacket.Card card) {
        // The running task is usually the heaviest weight; colour the task line to match the bar.
        return card.weights.get(0).goalType;
    }

    private static void line(FontRenderer font, IRenderTypeBuffer buffer, Matrix4f matrix, String text, float y, int color) {
        float w = font.width(text);
        font.drawInBatch(text, -w / 2, y, color, true, matrix, buffer, true, 0, 0xF000F0);
    }

    private static void drawBar(FontRenderer font, IRenderTypeBuffer buffer, Matrix4f matrix,
                                ShowcasePacket.Card card, float barY, float barH, float width) {
        float x0 = -width / 2;
        fillRect(matrix, x0 - 1, barY - 1, x0 + width + 1, barY + barH + 1, 0xFF000000);
        float total = 0;
        for (ShowcasePacket.Weight w : card.weights) total += w.score;
        if (total <= 0) return;
        float cx = x0;
        for (ShowcasePacket.Weight w : card.weights) {
            float seg = w.score / total * width;
            if (seg < 0.5F) continue;
            fillRect(matrix, cx, barY, cx + seg, barY + barH, goalColor(w.goalType));
            cx += seg;
        }
        // Label the two heaviest goals with their share.
        cx = x0;
        int labelled = 0;
        for (ShowcasePacket.Weight w : card.weights) {
            float seg = w.score / total * width;
            if (labelled < 2 && seg >= 28) {
                String label = abbreviate(w.goalType) + " " + Math.round(w.score / total * 100) + "%";
                float tw = font.width(label) * 0.75F;
                if (tw <= seg + 6) {
                    Matrix4f m = matrix.copy();
                    m.multiply(Matrix4f.createScaleMatrix(0.75F, 0.75F, 1F));
                    font.drawInBatch(label, (cx + seg / 2) / 0.75F - font.width(label) / 2F, (barY + barH + 1.5F) / 0.75F,
                            0xFFFFFFFF, true, m, buffer, true, 0, 0xF000F0);
                    labelled++;
                }
            }
            cx += seg;
        }
    }

    private static String abbreviate(String type) {
        switch (type) {
            case "lumberjack_work": return "log";
            case "miner_work": return "mine";
            case "supply_request": return "supply";
            case "acquire_item": return "acquire";
            case "find_job_site": return "job";
            default: return type.length() > 7 ? type.substring(0, 7) : type;
        }
    }

    /** Immediate-mode quad in the label's billboard space (same approach as DebugOverlay). */
    private static void fillRect(Matrix4f matrix, float x0, float y0, float x1, float y1, int argb) {
        float a = ((argb >> 24) & 0xFF) / 255.0F, r = ((argb >> 16) & 0xFF) / 255.0F,
                g = ((argb >> 8) & 0xFF) / 255.0F, b = (argb & 0xFF) / 255.0F;
        net.minecraft.client.renderer.Tessellator tes = net.minecraft.client.renderer.Tessellator.getInstance();
        BufferBuilder bb = tes.getBuilder();
        RenderSystem.enableBlend();
        RenderSystem.disableTexture();
        RenderSystem.disableDepthTest();
        RenderSystem.defaultBlendFunc();
        RenderSystem.pushMatrix();
        RenderSystem.multMatrix(matrix);
        bb.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        bb.vertex(x0, y1, 0).color(r, g, b, a).endVertex();
        bb.vertex(x1, y1, 0).color(r, g, b, a).endVertex();
        bb.vertex(x1, y0, 0).color(r, g, b, a).endVertex();
        bb.vertex(x0, y0, 0).color(r, g, b, a).endVertex();
        tes.end();
        RenderSystem.popMatrix();
        RenderSystem.enableDepthTest();
        RenderSystem.enableTexture();
        RenderSystem.disableBlend();
    }
}
