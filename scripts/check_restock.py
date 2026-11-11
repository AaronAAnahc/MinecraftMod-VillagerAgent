"""Run the production restock execute/arrival methods with API doubles; no Minecraft simulation."""
from pathlib import Path
import re, subprocess, tempfile, argparse
parser=argparse.ArgumentParser();parser.add_argument('--jdk',type=Path,required=True);args=parser.parse_args()
root=Path(__file__).resolve().parents[1]
source=(root/'src/main/java/com/github/AaronAA0721/villageragent/ai/behavior/RestockSkill.java').read_text(encoding='utf-8')
def method(name):
    match=re.search(r'    (?:public|private static) [^\n]+\b'+name+r'\(',source)
    start=source.index('{',match.start());end=start+1;depth=1
    while depth:
        depth+=(source[end]=='{')-(source[end]=='}');end+=1
    return source[match.start():end]
body=method('execute')+'\n'+method('atWorkstation')
body=re.sub(r'net\.minecraft\.(?:[a-z]+\.)*([A-Z][A-Za-z0-9_]*)',r'\1',body)
code=r"""
import java.util.*;
import java.util.function.*;
enum SkillResult { RUNNING, SUCCESS, FAIL, WAITING }
class BlockPos {
 int x; BlockPos(int x){this.x=x;} double distSqr(BlockPos b){return (x-b.x)*(x-b.x);}
 public boolean equals(Object b){return b instanceof BlockPos && ((BlockPos)b).x==x;}
}
class GlobalPos {BlockPos p=new BlockPos(10);String dim="overworld";String dimension(){return dim;}BlockPos pos(){return p;}}
class MemoryModuleType {static Object JOB_SITE=new Object();}
class FailureReason {static FailureReason NO_JOB_SITE=new FailureReason(),UNREACHABLE=new FailureReason();FailureReason with(String s){return this;}}
class AgentGoal {
 int progress;AgentGoal(String a,String b,int c){} int getPriority(){return 6;}
 void setTargetLocation(BlockPos p){} void setArrivalDistanceSq(double n){} void setLastFailure(FailureReason r){} void markProgress(){progress++;}
}
class VillagerAgentData {
 static class Tasks {Object state;Tasks current(){return this;} <T>T state(Class<T> type,Supplier<T> supplier){if(state==null)state=supplier.get();return type.cast(state);}}
 static class Brain {Tasks tasks=new Tasks();} Brain b=new Brain();long last=-1;
 Brain brain(){return b;}void setLastRestockTime(long t){last=t;}void addMemory(String s){}String getProfession(){return "farmer";}
}
class VillagerEntity {
 GlobalPos gp=new GlobalPos(); BlockPos pos=new BlockPos(0);Navigation nav=new Navigation();
 class Brain {Optional<GlobalPos> getMemory(Object key){return Optional.ofNullable(gp);}}
 static class Navigation {void stop(){}}
 static class Data {Profession getProfession(){return new Profession();}}
 static class Profession {Poi getJobPoiType(){return new Poi();}}
 static class Poi {Predicate<PointOfInterestType> getPredicate(){return p->true;}}
 Brain getBrain(){return new Brain();}Data getVillagerData(){return new Data();}Navigation getNavigation(){return nav;}
 BlockPos blockPosition(){return pos;}Object getEyePosition(int n){return null;}void swing(Object h){}
}
class PointOfInterestType {static Optional<PointOfInterestType> forState(Boolean valid){return valid?Optional.of(new PointOfInterestType()):Optional.empty();}}
class RayTraceContext {enum BlockMode{COLLIDER} enum FluidMode{NONE}RayTraceContext(Object a,Object b,Object c,Object d,Object e){}}
class Vector3d {static Object atCenterOf(BlockPos p){return p;}}
class Hand {static Object MAIN_HAND=new Object();}
class SoundEvents {static Object VILLAGER_WORK_ARMORER=new Object();}
class SoundCategory {static Object NEUTRAL=new Object();}
class ServerWorld {
 boolean loaded=true,valid=true,visible=true;int sounds;String dimension(){return "overworld";}boolean isLoaded(BlockPos p){return loaded;}
 Boolean getBlockState(BlockPos p){return valid;}long getGameTime(){return 10000;}
 class Hit {BlockPos getBlockPos(){return new BlockPos(visible?10:9);}}Hit clip(RayTraceContext c){return new Hit();}
 void playSound(Object a,BlockPos b,Object c,Object d,float e,float f){sounds++;}
}
class ReachablePaths {static boolean reachable=true;static BlockPos standingBeside(ServerWorld w,VillagerEntity v,BlockPos p){return reachable?new BlockPos(9):null;}}
class BehaviorExecutor {
 static SkillResult result=SkillResult.RUNNING;static boolean arrive;
 static SkillResult executeChild(VillagerAgentData a,VillagerEntity v,ServerWorld w,Supplier<AgentGoal> goal){goal.get();if(arrive)v.pos=new BlockPos(9);return result;}
}
class DailySupplies {static int grants,amount=4;static int grant(VillagerAgentData a){grants++;return amount;}}
class BrainLog {static void diagnostic(Object a,String b,String c){}}
public class RestockChecks {
 static class State {BlockPos target,stand;}
"""+body+r"""
 static int checks;
 static void check(boolean ok,String reason){checks++;if(!ok)throw new AssertionError(reason);}
 public static void main(String[] args){
  RestockChecks skill=new RestockChecks();VillagerAgentData a=new VillagerAgentData();VillagerEntity v=new VillagerEntity();ServerWorld w=new ServerWorld();AgentGoal g=new AgentGoal("restock","",6);
  for(int i=0;i<500;i++) check(skill.execute(a,g,v,w)==SkillResult.RUNNING,"walking must not grant after 400 ticks");
  check(DailySupplies.grants==0 && a.last==-1 && w.sounds==0,"no remote stock/cooldown/sound");
  BehaviorExecutor.result=SkillResult.FAIL;
  check(skill.execute(a,g,v,w)==SkillResult.FAIL && DailySupplies.grants==0,"failed move cannot grant");
  BehaviorExecutor.result=SkillResult.SUCCESS;
  check(skill.execute(a,g,v,w)==SkillResult.FAIL && DailySupplies.grants==0,"child success still needs actual arrival");
  v.pos=new BlockPos(9);w.visible=false;
  check(skill.execute(a,g,v,w)==SkillResult.FAIL && DailySupplies.grants==0,"blocked view cannot grant");
  w.visible=true;w.valid=false;
  check(skill.execute(a,g,v,w)==SkillResult.FAIL && DailySupplies.grants==0,"destroyed workstation cannot grant");
  w.valid=true;w.loaded=false;
  check(skill.execute(a,g,v,w)==SkillResult.FAIL && DailySupplies.grants==0,"unloaded workstation cannot grant");
  w.loaded=true;v.gp.dim="nether";
  check(skill.execute(a,g,v,w)==SkillResult.FAIL && DailySupplies.grants==0,"wrong dimension cannot grant");
  v.gp.dim="overworld";v.gp.p=new BlockPos(11);
  check(skill.execute(a,g,v,w)==SkillResult.FAIL && DailySupplies.grants==0,"changed workstation cannot grant");
  v.gp.p=new BlockPos(10);DailySupplies.amount=0;
  check(skill.execute(a,g,v,w)==SkillResult.WAITING && a.last==-1 && w.sounds==0,"zero insertion does not consume restock cooldown or sound");
  DailySupplies.amount=4;
  check(skill.execute(a,g,v,w)==SkillResult.SUCCESS && a.last==10000 && w.sounds==1 && g.progress==1,"real arrival grants and plays sound once");
  System.out.println("Restock checks passed: "+checks);
 }
}
"""
with tempfile.TemporaryDirectory(prefix='restock-checks-') as tmp:
 p=Path(tmp);(p/'RestockChecks.java').write_text(code,encoding='utf-8')
 subprocess.run([str(args.jdk/'bin/javac.exe'),'-encoding','UTF-8',str(p/'RestockChecks.java')],check=True)
 subprocess.run([str(args.jdk/'bin/java.exe'),'-cp',str(p),'RestockChecks'],check=True)
