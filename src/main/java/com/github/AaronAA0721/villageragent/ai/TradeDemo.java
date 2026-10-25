package com.github.AaronAA0721.villageragent.ai;

import com.github.AaronAA0721.villageragent.config.ModConfig;
import net.minecraft.command.CommandSource;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.ItemEntity;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.world.server.ServerWorld;
import java.util.*;

/** Operator-directed meeting: generated gift or two sequential real-inventory deliveries. */
public final class TradeDemo {
    private static final Map<ServerWorld, List<Session>> SESSIONS = new IdentityHashMap<>();

    public static boolean isActive(UUID id) {
        for (List<Session> list : SESSIONS.values())
            for (Session s : list) if (s.from.equals(id) || s.to.equals(id)) return true;
        return false;
    }

    public static int start(CommandSource source, Entity from, Entity to, ItemStack stack) {
        return start(source, from, to, stack, ItemStack.EMPTY);
    }

    public static int startExchange(CommandSource source, Entity from, Entity to, ItemStack first, ItemStack second) {
        if (second.isEmpty()) return 0;
        return start(source, from, to, first, second);
    }

    private static int start(CommandSource source, Entity from, Entity to, ItemStack stack, ItemStack payment) {
        if (!ModConfig.ENABLE_AI_AGENTS.get() || !(from instanceof VillagerEntity)
                || !(to instanceof VillagerEntity) || from == to || from.level != source.getLevel()
                || to.level != source.getLevel() || !from.isAlive() || !to.isAlive()
                || ((VillagerEntity) from).isBaby() || ((VillagerEntity) to).isBaby()
                || ((VillagerEntity) from).isNoAi() || ((VillagerEntity) to).isNoAi()
                || from.distanceToSqr(to) > 128 * 128 || stack.isEmpty()
                || isActive(from.getUUID()) || isActive(to.getUUID())) {
            source.sendFailure(new StringTextComponent("演示需要 AI 开启、同维度 128 格内两个不同的成年村民（无 NoAI，且未参与演示）。"));
            return 0;
        }
        ServerWorld world = source.getLevel();
        VillagerAgentData a = VillagerAgentManager.getOrCreateAgent((VillagerEntity) from);
        VillagerAgentData b = VillagerAgentManager.getOrCreateAgent((VillagerEntity) to);
        if (InventoryTransfer.spaceFor(b.getInventory(), stack) < stack.getCount()) {
            source.sendFailure(new StringTextComponent("接收方背包空间不足。"));
            return 0;
        }
        if (!payment.isEmpty() && (count(a.getInventory(), stack) < stack.getCount()
                || count(b.getInventory(), payment) < payment.getCount()
                || InventoryTransfer.spaceFor(a.getInventory(), payment) < payment.getCount())) {
            source.sendFailure(new StringTextComponent("双方必须备齐指定物品（含 NBT），并为收货预留空间。"));
            return 0;
        }
        for (Entity e : Arrays.asList(from, to)) {
            VillagerAgentData agent = VillagerAgentManager.getAgent(e.getUUID());
            ActionLifecycle.cancel(agent, (VillagerEntity) e, world);
            agent.setRunningGoal(null);
            agent.setCurrentActivity("demo_trade");
        }
        SESSIONS.computeIfAbsent(world, w -> new ArrayList<>()).add(new Session(source, from, to, stack, payment));
        source.sendSuccess(new StringTextComponent(payment.isEmpty()
                ? "赠送演示开始：生成物品，不扣库存、不收款。"
                : "双向交易开始：第一位村民先交货，第二位收到后回赠；全程占用双方。"), false);
        return 1;
    }

    public static void tick(ServerWorld world) {
        List<Session> list = SESSIONS.get(world);
        if (list == null) return;
        Iterator<Session> it = list.iterator();
        while (it.hasNext()) {
            Session s = it.next();
            Entity ea = world.getEntity(s.from), eb = world.getEntity(s.to);
            if (!ModConfig.ENABLE_AI_AGENTS.get() || !(ea instanceof VillagerEntity)
                    || !(eb instanceof VillagerEntity) || !ea.isAlive() || !eb.isAlive() || ++s.ticks > 600) {
                finish(world, s, "演示中止：超时、实体离开或 AI 已关闭；已抛出的物品留在地面。");
                it.remove(); continue;
            }
            VillagerEntity a = (VillagerEntity) (s.leg == 0 ? ea : eb), b = (VillagerEntity) (s.leg == 0 ? eb : ea);
            if (a.isNoAi() || b.isNoAi()) {
                finish(world, s, "交易中止：村民 NoAI；已交付部分不回滚。"); it.remove(); continue;
            }
            ItemStack offer = s.leg == 0 ? s.stack : s.payment;
            a.getLookControl().setLookAt(b, 30, 30);
            b.getLookControl().setLookAt(a, 30, 30);
            if (s.drop == null) {
                if (a.distanceToSqr(b) > 4 || !a.canSee(b)) {
                    if (s.ticks % 10 == 1) {
                        a.getNavigation().moveTo(b, 0.65);
                        b.getNavigation().moveTo(a, 0.65);
                    }
                    continue;
                }
                a.getNavigation().stop(); b.getNavigation().stop();
                if (++s.ready < 20) continue;
                VillagerAgentData sender = VillagerAgentManager.getAgent(a.getUUID());
                if (!s.payment.isEmpty() && count(sender.getInventory(), offer) < offer.getCount()) {
                    finish(world, s, "交易中止：发送方库存不足；已交付部分不回滚。"); it.remove(); continue;
                }
                ItemEntity drop = new ItemEntity(world, a.getX(), a.getEyeY() - 0.3, a.getZ(), offer.copy());
                drop.setDeltaMovement(b.position().subtract(a.position()).normalize().scale(0.22).add(0, 0.15, 0));
                // Reserve the visible item until this session delivers it or releases it on failure.
                drop.setPickUpDelay(40);
                drop.setThrower(a.getUUID());
                drop.setOwner(b.getUUID());
                if (!world.addFreshEntity(drop)) {
                    finish(world, s, "演示中止：物品生成被取消。"); it.remove(); continue;
                }
                if (!s.payment.isEmpty()) {
                    remove(sender.getInventory(), offer);
                    VillagerEquipmentHelper.refreshEquipment(a, sender);
                }
                a.swing(Hand.MAIN_HAND);
                s.drop = drop; s.thrownAt = s.ticks;
            } else {
                if (!s.drop.isAlive()) {
                    finish(world, s, "演示中止：抛出的物品已消失。"); it.remove(); continue;
                }
                s.drop.setPickUpDelay(40);
                if (s.ticks - s.thrownAt < 15) continue;
                if (!ItemAttractionSystem.pickupBox(b).intersects(s.drop.getBoundingBox())) {
                    if (s.ticks % 10 == 0) b.getNavigation().moveTo(s.drop.getX(), s.drop.getY(), s.drop.getZ(), 0.65);
                    continue;
                }
                b.getNavigation().stop();
                s.drop.setPickUpDelay(0);
                ItemAttractionSystem.collect(s.drop, b, VillagerAgentManager.getAgent(b.getUUID()), Integer.MAX_VALUE);
                if (s.drop.isAlive()) {
                    finish(world, s, "交易中止：接收空间不足，剩余物品留在地面；已交付部分不回滚。");
                    it.remove(); continue;
                }
                if (s.leg == 0 && !s.payment.isEmpty()) {
                    s.leg = 1; s.drop = null; s.ready = 0;
                    s.source.sendSuccess(new StringTextComponent("第一笔交付完成，开始回赠。"), false);
                } else {
                    finish(world, s, "交易完成：指定物品已交付。"); it.remove();
                }
            }
        }
        if (list.isEmpty()) SESSIONS.remove(world);
    }

    public static void clear(ServerWorld world) {
        List<Session> list = SESSIONS.remove(world);
        if (list != null) for (Session s : list) finish(world, s, "演示因世界卸载结束。");
    }

    private static void finish(ServerWorld world, Session s, String message) {
        if (s.drop != null && s.drop.isAlive()) { s.drop.setOwner(null); s.drop.setPickUpDelay(10); }
        for (UUID id : Arrays.asList(s.from, s.to)) {
            Entity e = world.getEntity(id);
            if (e instanceof VillagerEntity) ((VillagerEntity) e).getNavigation().stop();
            VillagerAgentData agent = VillagerAgentManager.getAgent(id);
            if (agent != null) agent.setCurrentActivity("idle");
        }
        s.source.sendSuccess(new StringTextComponent(message), false);
    }

    private static final class Session {
        final CommandSource source;
        final UUID from, to;
        final ItemStack stack, payment;
        int ticks, ready, thrownAt, leg;
        ItemEntity drop;
        Session(CommandSource source, Entity a, Entity b, ItemStack stack, ItemStack payment) {
            this.source = source; from = a.getUUID(); to = b.getUUID(); this.stack = stack.copy(); this.payment = payment.copy();
        }
    }
    private static boolean matches(ItemStack a, ItemStack b) {
        return !a.isEmpty() && ItemStack.isSame(a, b) && ItemStack.tagMatches(a, b);
    }
    private static int count(AgentInventory inv, ItemStack offer) {
        int count = 0;
        for (ItemStack stack : inv.getItems()) if (matches(stack, offer)) count += stack.getCount();
        return count;
    }
    private static void remove(AgentInventory inv, ItemStack offer) {
        int left = offer.getCount();
        for (ItemStack stack : inv.getItems()) {
            if (!matches(stack, offer)) continue;
            int n = Math.min(left, stack.getCount()); stack.shrink(n); left -= n;
            if (left == 0) return;
        }
    }
    private TradeDemo() {}
}
