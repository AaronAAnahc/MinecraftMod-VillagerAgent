package com.github.AaronAA0721.villageragent.ai;

import net.minecraft.entity.Entity;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.server.ServerWorld;
import java.util.*;

/** Runtime boards for persistent vanilla village identities. */
public final class VillageMarkets {
    public static final class Market {
        public final long id;
        public final BlockPos center;
        public final boolean meetingPoint;
        public final VillageSignalBoard board = new VillageSignalBoard();
        final Map<UUID, Long> lastNoticeVisits = new HashMap<>();
        private final List<VillagerAgentData> members = new ArrayList<>();
        Market(long key, BlockPos p) { id=key; center=p.immutable(); meetingPoint=false; }
        public List<VillagerAgentData> members() { return Collections.unmodifiableList(members); }
    }
    private static final class Index {
        long refreshed = Long.MIN_VALUE;
        final List<Market> markets = new ArrayList<>();
        final Map<UUID, Market> membership = new HashMap<>();
    }
    private static final Map<ServerWorld, Index> WORLDS = new IdentityHashMap<>();
    private static Index index(ServerWorld world) {
        Index index = WORLDS.computeIfAbsent(world, ignored -> new Index());
        long now=world.getGameTime();
        if(index.refreshed!=Long.MIN_VALUE && now-index.refreshed<100) return index;
        index.refreshed=now;
        Map<UUID,Market> previous=new HashMap<>(index.membership);
        index.membership.clear();
        for(Market m:index.markets) m.members.clear();
        List<VillagerEntity> loaded=new ArrayList<>();
        for(VillagerAgentData a:VillagerAgentManager.getAllAgents()) {
            Entity e=world.getEntity(a.getVillagerId());
            if(e instanceof VillagerEntity && e.isAlive()) loaded.add((VillagerEntity)e);
        }
        loaded.sort(Comparator.comparing(v->v.getUUID().toString()));
        VillageRegistry registry=VillageRegistry.get(world);
        // Discover all loaded residents first so assignment does not depend on iteration order.
        for(VillagerEntity v:loaded) registry.discover(world,v.blockPosition());
        for(VillagerEntity v:loaded) {
            Long key=registry.join(world,v.getUUID(),v.blockPosition());
            if(key==null) continue;
            Market best=null;
            for(Market m:index.markets) if(m.id==key.longValue()) { best=m; break; }
            if(best==null) { best=new Market(key,registry.center(key)); index.markets.add(best); }
            VillagerAgentData a=VillagerAgentManager.getAgent(v.getUUID());
            if(a!=null && !previous.containsKey(v.getUUID())) {
                // New resident joining this village — seed with shared chunk memories.
                a.importSharedMemories(registry.getSharedMemories(key));
            }
            best.members.add(a); index.membership.put(v.getUUID(),best);
        }
        for(Map.Entry<UUID,Market> old:previous.entrySet())
            if(index.membership.get(old.getKey())!=old.getValue()) old.getValue().board.removeResident(old.getKey());
        // A cached center survives temporary absence; its quotes do not.
        for(Market m:index.markets) if(m.members.isEmpty()) m.board.clearMessages();
        return index;
    }
    public static Market market(ServerWorld world, UUID resident) {
        Index i=index(world);
        if(i.refreshed==Long.MIN_VALUE) {
            i.refreshed=Long.MIN_VALUE; i=index(world);
        }
        return i.membership.get(resident);
    }
    public static VillageSignalBoard board(ServerWorld world, UUID resident) {
        Market m=market(world,resident);
        return m==null?null:m.board;
    }
    public static VillageSignalBoard combatBoard(VillagerEntity v) {
        VillageSignalBoard board=board((ServerWorld)v.level,v.getUUID());
        // Unaffiliated residents cannot broadcast to other unaffiliated residents.
        return board==null?new VillageSignalBoard():board;
    }
    public static List<Market> loaded(ServerWorld world) {
        List<Market> result=new ArrayList<>();
        for(Market m:index(world).markets) if(!m.members.isEmpty()) result.add(m);
        return result;
    }
    public static List<VillagerAgentData> residents(ServerWorld world, UUID resident) {
        Market m=market(world,resident); return m==null?Collections.emptyList():m.members();
    }
    public static boolean same(ServerWorld world, UUID a, UUID b) {
        Market m=market(world,a); return m!=null && m==market(world,b);
    }
    public static void unload(ServerWorld world) {
        Index i=WORLDS.remove(world);
        if(i!=null) for(Market m:i.markets) m.board.clearMessages();
    }
    private VillageMarkets() {}
}
