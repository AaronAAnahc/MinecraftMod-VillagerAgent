package com.github.AaronAA0721.villageragent.ai;

import net.minecraft.entity.Entity;
import net.minecraft.entity.ai.brain.memory.MemoryModuleType;
import net.minecraft.entity.merchant.villager.VillagerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.world.server.ServerWorld;
import java.util.*;

/** Runtime market membership, not generated structure boundaries. Reads loaded residents only. */
public final class VillageMarkets {
    public static final class Market {
        public final UUID id = UUID.randomUUID();
        public final BlockPos center;
        public final boolean meetingPoint;
        public final VillageSignalBoard board = new VillageSignalBoard();
        private final List<VillagerAgentData> members = new ArrayList<>();
        Market(BlockPos p, boolean meeting) { center=p.immutable(); meetingPoint=meeting; }
        public List<VillagerAgentData> members() { return Collections.unmodifiableList(members); }
    }
    private static final class Index {
        long refreshed = Long.MIN_VALUE;
        final List<Market> markets = new ArrayList<>();
        final Map<UUID, Market> membership = new HashMap<>();
    }
    private static final Map<ServerWorld, Index> WORLDS = new IdentityHashMap<>();
    private static BlockPos memory(VillagerEntity v, MemoryModuleType<GlobalPos> type) {
        Optional<GlobalPos> p = v.getBrain().getMemory(type);
        return p.isPresent() && p.get().dimension().equals(v.level.dimension()) ? p.get().pos() : null;
    }
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
        for(VillagerEntity v:loaded) {
            BlockPos p=memory(v,MemoryModuleType.MEETING_POINT);
            if(p!=null && index.markets.stream().noneMatch(m->m.meetingPoint && m.center.equals(p)))
                index.markets.add(new Market(p,true));
        }
        for(VillagerEntity v:loaded) {
            BlockPos meeting=memory(v,MemoryModuleType.MEETING_POINT);
            BlockPos anchor=meeting;
            if(anchor==null) anchor=memory(v,MemoryModuleType.HOME);
            if(anchor==null) anchor=memory(v,MemoryModuleType.JOB_SITE);
            VillagerAgentData a=VillagerAgentManager.getAgent(v.getUUID());
            if(anchor==null) anchor=a.getHomeBedPos();
            if(anchor==null) anchor=v.blockPosition();
            Market best=null; double distance=64*64+1;
            for(Market m:index.markets) {
                if(meeting!=null && (!m.meetingPoint || !m.center.equals(meeting))) continue;
                double d=anchor.distSqr(m.center);
                if(d<distance || (d==distance && m.meetingPoint && best!=null && !best.meetingPoint)) {
                    best=m; distance=d;
                }
            }
            if(best==null) { best=new Market(anchor,false); index.markets.add(best); }
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
        if(!i.membership.containsKey(resident) && world.getEntity(resident) instanceof VillagerEntity) {
            i.refreshed=Long.MIN_VALUE; i=index(world);
        }
        return i.membership.get(resident);
    }
    public static VillageSignalBoard board(ServerWorld world, UUID resident) {
        Market m=market(world,resident);
        if(m==null) throw new IllegalStateException("Market requested for unloaded resident " + resident);
        return m.board;
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
