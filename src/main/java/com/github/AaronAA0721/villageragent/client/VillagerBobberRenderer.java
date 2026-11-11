package com.github.AaronAA0721.villageragent.client;

import com.github.AaronAA0721.villageragent.content.VillagerBobberEntity;
import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.vertex.IVertexBuilder;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererManager;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.vector.Matrix3f;
import net.minecraft.util.math.vector.Matrix4f;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.math.vector.Vector3f;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Draws a villager's fishing bobber exactly like the vanilla {@code FishRenderer}: the camera-facing hook sprite and
 * a sagging black line, here running from the villager's rod tip ({@link VillagerBobberEntity#rodTip}) instead of a
 * player's hand.
 */
@OnlyIn(Dist.CLIENT)
public class VillagerBobberRenderer extends EntityRenderer<VillagerBobberEntity> {
    private static final ResourceLocation TEXTURE = new ResourceLocation("textures/entity/fishing_hook.png");
    private static final RenderType RENDER_TYPE = RenderType.entityCutout(TEXTURE);

    public VillagerBobberRenderer(EntityRendererManager manager) {
        super(manager);
    }

    @Override
    public void render(VillagerBobberEntity bobber, float yaw, float partialTicks, MatrixStack ms,
                       IRenderTypeBuffer buffer, int light) {
        ms.pushPose();

        // Hook sprite.
        ms.pushPose();
        ms.scale(0.5F, 0.5F, 0.5F);
        ms.mulPose(this.entityRenderDispatcher.cameraOrientation());
        ms.mulPose(Vector3f.YP.rotationDegrees(180.0F));
        MatrixStack.Entry entry = ms.last();
        Matrix4f pose = entry.pose();
        Matrix3f normal = entry.normal();
        IVertexBuilder hook = buffer.getBuffer(RENDER_TYPE);
        vertex(hook, pose, normal, light, 0.0F, 0, 0, 1);
        vertex(hook, pose, normal, light, 1.0F, 0, 1, 1);
        vertex(hook, pose, normal, light, 1.0F, 1, 1, 0);
        vertex(hook, pose, normal, light, 0.0F, 1, 0, 0);
        ms.popPose();

        // Line from the rod tip to the hook.
        Entity owner = bobber.getOwner();
        if (owner instanceof LivingEntity) {
            Vector3d tip = VillagerBobberEntity.rodTip((LivingEntity) owner, partialTicks);
            double bx = MathHelper.lerp(partialTicks, bobber.xo, bobber.getX());
            double by = MathHelper.lerp(partialTicks, bobber.yo, bobber.getY()) + 0.25D;
            double bz = MathHelper.lerp(partialTicks, bobber.zo, bobber.getZ());
            float dx = (float) (tip.x - bx);
            float dy = (float) (tip.y - by);
            float dz = (float) (tip.z - bz);
            IVertexBuilder line = buffer.getBuffer(RenderType.lines());
            Matrix4f linePose = ms.last().pose();
            for (int k = 0; k < 16; ++k) {
                stringVertex(dx, dy, dz, line, linePose, fraction(k, 16));
                stringVertex(dx, dy, dz, line, linePose, fraction(k + 1, 16));
            }
        }

        ms.popPose();
        super.render(bobber, yaw, partialTicks, ms, buffer, light);
    }

    private static float fraction(int a, int b) {
        return (float) a / (float) b;
    }

    private static void vertex(IVertexBuilder builder, Matrix4f pose, Matrix3f normal, int light,
                               float x, int y, int u, int v) {
        builder.vertex(pose, x - 0.5F, (float) y - 0.5F, 0.0F)
                .color(255, 255, 255, 255)
                .uv((float) u, (float) v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(light)
                .normal(normal, 0.0F, 1.0F, 0.0F)
                .endVertex();
    }

    /** Vanilla FishRenderer sag: a parabola from the hook (t=0) to the rod tip (t=1). */
    private static void stringVertex(float x, float y, float z, IVertexBuilder builder, Matrix4f pose, float t) {
        builder.vertex(pose, x * t, y * (t * t + t) * 0.5F + 0.25F, z * t).color(0, 0, 0, 255).endVertex();
    }

    @Override
    public ResourceLocation getTextureLocation(VillagerBobberEntity bobber) {
        return TEXTURE;
    }
}
