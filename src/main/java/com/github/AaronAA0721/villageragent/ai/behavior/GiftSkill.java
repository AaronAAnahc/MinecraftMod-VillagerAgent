package com.github.AaronAA0721.villageragent.ai.behavior;

import com.github.AaronAA0721.villageragent.ai.AgentGoal;
import com.github.AaronAA0721.villageragent.ai.CooperativeSupply;
import com.github.AaronAA0721.villageragent.ai.InventoryTransfer;
import com.github.AaronAA0721.villageragent.ai.Invitations;
import com.github.AaronAA0721.villageragent.ai.KnownVillagers;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentData;
import com.github.AaronAA0721.villageragent.ai.VillagerAgentManager;
import com.github.AaronAA0721.villageragent.ai.VillagerEquipmentHelper;
import com.github.AaronAA0721.villageragent.ai.work.ResourceCatalog;
import net.minecraft.entity.Entity;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particles.ParticleTypes;
import net.minecraft.util.Hand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvents;
import net.minecraft.world.server.ServerWorld;

/**
 * "gift" goal: walk to a villager ({@link VillagerApproach}) and hand over an item you can spare.
 *
 * <p>Only stock that {@link CooperativeSupply#giftable} allows is given (never the working tool, a profession
 * reserve, an acquisition target or goods promised to a delivery), so a gift cannot break the giver's own work.
 * The count is re-checked on arrival; giving fewer than asked is fine, nothing at all ends the goal.
 */
public final class GiftSkill implements VillagerSkill {
    private static final int GIVER_BONUS = 2, RECEIVER_BONUS = 4;

    @Override public String id() { return "gift"; }
    @Override public boolean isComposite() { return true; }

    @Override
    public boolean canHandle(VillagerAgentData agent, AgentGoal goal, VillagerEntity villager, ServerWorld world) {
        return "gift".equals(goal.getGoalType()) && goal.getTargetEntityId() != null && goal.getTargetItem() != null;
    }

    @Override
    public SkillResult execute(VillagerAgentData agent, AgentGoal goal, VillagerEntity villager, ServerWorld world) {
        Item item = ResourceCatalog.item(goal.getTargetItem());
        String who = KnownVillagers.nameOf(goal.getTargetEntityId());
        if (item == null || item == Items.AIR || CooperativeSupply.giftable(agent, item) <= 0) {
            agent.addMemory("Wanted to give " + who + " " + goal.getTargetItem() + " but I have none to spare");
            return SkillResult.DELETE;
        }
        VillagerAgentData receiver = VillagerAgentManager.getAgent(goal.getTargetEntityId());
        Entity e = world.getEntity(goal.getTargetEntityId());
        if (receiver == null || !(e instanceof VillagerEntity) || !e.isAlive()) {
            agent.addMemory("Couldn't find " + who + " to give a present");
            return SkillResult.DELETE;
        }
        VillagerEntity other = (VillagerEntity) e;
        VillagerApproach.Walk walk = agent.brain().tasks.current().state(VillagerApproach.Walk.class, VillagerApproach.Walk::new);
        SkillResult r = VillagerApproach.tick(agent, villager, world, other, receiver.getName(), goal, walk);
        if (r == SkillResult.RUNNING) return SkillResult.RUNNING;
        VillagerApproach.stop(agent, villager, world, r == SkillResult.SUCCESS ? other : null, walk);
        if (r != SkillResult.SUCCESS) {
            agent.addMemory("Couldn't reach " + who + " to give a present");
            return SkillResult.FAIL;
        }
        int want = Math.min(Math.max(1, goal.getTargetQuantity()), CooperativeSupply.giftable(agent, item));
        int given = InventoryTransfer.move(agent.getInventory(), receiver.getInventory(), item, want);
        if (given <= 0) {
            agent.addMemory(who + " had no room for my present");
            return SkillResult.DELETE;
        }
        handOver(world, villager, other, new ItemStack(item, given));
        VillagerEquipmentHelper.refreshEquipment(villager, agent);
        VillagerEquipmentHelper.refreshEquipment(other, receiver);
        String what = given + " " + new ItemStack(item).getHoverName().getString();
        Invitations.say(agent, villager, world, "Here, " + receiver.getName() + " - " + what + " for you.");
        agent.addMemory("Gave " + receiver.getName() + " " + what);
        receiver.addMemory(agent.getName() + " gave me " + what + " as a present");
        agent.updateRelationship(receiver.getVillagerId().toString(), GIVER_BONUS);
        receiver.updateRelationship(agent.getVillagerId().toString(), RECEIVER_BONUS);
        goal.setCompleted(true);
        return SkillResult.SUCCESS;
    }

    /** Visible hand-over: the giver swings, the item pops with the pickup sound and hearts over the receiver. */
    private static void handOver(ServerWorld world, VillagerEntity giver, VillagerEntity receiver, ItemStack stack) {
        giver.swing(Hand.MAIN_HAND);
        world.playSound(null, receiver.getX(), receiver.getY(), receiver.getZ(), SoundEvents.ITEM_PICKUP,
                SoundCategory.NEUTRAL, 0.6f, 1.2f);
        world.sendParticles(ParticleTypes.HEART, receiver.getX(), receiver.getEyeY() + 0.4, receiver.getZ(),
                3, 0.3, 0.2, 0.3, 0.0);
    }
}
