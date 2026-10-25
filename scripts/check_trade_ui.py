"""Run the screen's actual selection methods without starting Minecraft.

Small API doubles exercise selection/close logic; they do not test rendering or networking.
Requires a JDK (pass --jdk if java/javac are not on PATH).
"""
from pathlib import Path
import argparse
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('--jdk', type=Path)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
source = (root / 'src/main/java/com/github/AaronAA0721/villageragent/client/VillagerTradeScreen.java').read_text(encoding='utf-8')

def method(name):
    import re
    match = re.search(r'    (?:private|public) [^\n]+\b' + name + r'\(', source)
    start = source.index('{', match.start())
    depth = 1
    end = start + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[match.start():end]

names = ['addToBuySlots', 'handlePlayerInventoryClick', 'handleSlotInteraction', 'returnSellSlotsToPlayer', 'clearSellSlots', 'clearBuySlots', 'goBack', 'onClose']
for optional in ['availablePlayerItem', 'discardSelection', 'removed']:
    if (' ' + optional + '(') in source:
        names.append(optional)
body = '\n'.join(method(name) for name in names)
code = '''
import java.util.*;
class ItemStack {
    static final ItemStack EMPTY = new ItemStack("", 0);
    String item; int count; String tag="";
    ItemStack(String item, int count) { this.item=item; this.count=count; }
    boolean isEmpty(){return count<=0;}
    int getCount(){return count;}
    int getMaxStackSize(){return 64;}
    ItemStack copy(){ItemStack s=new ItemStack(item,count);s.tag=tag;return s;}
    void setCount(int n){count=n;}
    void shrink(int n){count-=n;}
    void grow(int n){count+=n;}
    boolean sameItem(ItemStack b){return item.equals(b.item);}
    static boolean isSame(ItemStack a,ItemStack b){return a.sameItem(b);}
    static boolean tagMatches(ItemStack a,ItemStack b){return a.tag.equals(b.tag);}
}
class Inventory {
    List<ItemStack> items=new ArrayList<>(Collections.nCopies(36,ItemStack.EMPTY));
    ItemStack getItem(int i){return items.get(i);}
    void setItem(int i,ItemStack s){items.set(i,s);}
    boolean add(ItemStack s){for(int i=0;i<36;i++)if(items.get(i).isEmpty()){items.set(i,s.copy());return true;}return false;}
}
class Minecraft {
    static Minecraft instance=new Minecraft();
    static Minecraft getInstance(){return instance;}
    static class Player {Inventory inventory=new Inventory();}
    Player player=new Player();
    void setScreen(Object o){}
}
class Screen { public void onClose(){} public void removed(){} }
public class TradeUiChecks extends Screen {
    ItemStack[] buySlots={ItemStack.EMPTY,ItemStack.EMPTY},sellSlots={ItemStack.EMPTY,ItemStack.EMPTY};
    ItemStack heldItem=ItemStack.EMPTY;
    List<ItemStack> villagerInventory=new ArrayList<>(),villagerArmor=new ArrayList<>();
    Object parentScreen;
''' + body + '''
    static void check(boolean ok,String msg){if(!ok)throw new AssertionError(msg);}
    public static void main(String[] args){
        TradeUiChecks s=new TradeUiChecks();
        s.villagerInventory.add(new ItemStack("iron",3));
        for(int i=0;i<5;i++)s.addToBuySlots(new ItemStack("iron",3));
        check(s.buySlots[0].getCount()+s.buySlots[1].getCount()==3,"Repeated clicks requested more than villager stock");
        s.clearBuySlots();s.villagerInventory.clear();s.villagerInventory.add(new ItemStack("iron",100));
        s.addToBuySlots(new ItemStack("iron",60));s.addToBuySlots(new ItemStack("iron",60));
        check(s.buySlots[0].getCount()<=64 && s.buySlots[1].getCount()<=64,"Stack size exceeded");
        check(s.buySlots[0].getCount()+s.buySlots[1].getCount()==100,"Two-slot total must respect stock");
        s.clearBuySlots();s.villagerInventory.clear();
        s.villagerArmor.add(new ItemStack("helmet",1));
        for(int i=0;i<5;i++)s.addToBuySlots(new ItemStack("helmet",1));
        check(s.buySlots[0].getCount()+s.buySlots[1].getCount()==1,"Equipped armor cannot be selected repeatedly");
        Minecraft.getInstance().player.inventory.setItem(0,new ItemStack("gold",8));
        s.handlePlayerInventoryClick(0,true,false);
        check(Minecraft.getInstance().player.inventory.getItem(0).getCount()==8,"Selecting an offer must not mutate real client inventory");
        s.handleSlotInteraction(s.sellSlots,0,true,false,true);
        s.handlePlayerInventoryClick(0,true,false);
        check(s.heldItem.isEmpty(),"Cannot reserve the same player stock twice");
        s.clearSellSlots();
        check(Minecraft.getInstance().player.inventory.getItem(0).getCount()==8,"Clear must not add refund copies");
        s.handlePlayerInventoryClick(0,false,true);
        s.handleSlotInteraction(s.sellSlots,0,true,false,true);
        s.goBack();s.goBack();
        s.handlePlayerInventoryClick(0,true,false);s.onClose();s.onClose();
        int total=0;for(ItemStack stack:Minecraft.getInstance().player.inventory.items)total+=stack.getCount();
        check(total==8,"Cancel/close must preserve exactly the original inventory");
        List<ItemStack> player=new ArrayList<>(Arrays.asList(new ItemStack("gold",8),ItemStack.EMPTY));
        List<ItemStack> villager=new ArrayList<>(Arrays.asList(new ItemStack("iron",3),ItemStack.EMPTY));
        ItemStack[] offers={new ItemStack("gold",4),ItemStack.EMPTY};
        ItemStack[] requests={new ItemStack("iron",2),new ItemStack("iron",2)};
        check(TradeInventory.plan(player,villager,Collections.emptyList(),offers,requests)==null,"Reject aggregate request exceeding stock");
        check(player.get(0).getCount()==8 && villager.get(0).getCount()==3,"Failed trade must not debit either side");
        requests[1]=new ItemStack("iron",1);
        TradeInventory.Plan plan=TradeInventory.plan(player,villager,Collections.emptyList(),offers,requests);
        check(plan!=null && TradeInventory.count(plan.player,new ItemStack("iron",1))==3,"Deliver exact quantity across both request slots");
        check(TradeInventory.count(plan.player,new ItemStack("gold",1))==4 && TradeInventory.count(plan.villager,new ItemStack("gold",1))==4,"Debit and credit offers exactly once");
        offers[1]=new ItemStack("gold",5);
        check(TradeInventory.plan(player,villager,Collections.emptyList(),offers,requests)==null,"Reject aggregate offer exceeding stock");
        offers[1]=ItemStack.EMPTY;
        player.get(0).tag="named";
        check(TradeInventory.plan(player,villager,Collections.emptyList(),offers,requests)==null,"Do not substitute different NBT");
        offers[0].tag="named";
        plan=TradeInventory.plan(player,villager,Collections.emptyList(),offers,requests);
        check(plan!=null && plan.villager.get(0).tag.equals("named"),"Preserve transferred NBT");
        List<ItemStack> fullPlayer=Arrays.asList(new ItemStack("gold",64));
        check(TradeInventory.plan(fullPlayer,villager,Collections.emptyList(),new ItemStack[]{new ItemStack("gold",1)},requests)==null,"Full player inventory rejects without losing items");
        check(fullPlayer.get(0).getCount()==64,"Capacity failure preserves player inventory");
        List<ItemStack> fullVillager=Arrays.asList(new ItemStack("iron",64));
        check(TradeInventory.plan(player,fullVillager,Collections.emptyList(),offers,new ItemStack[]{new ItemStack("iron",1)})==null,"Full villager inventory rejects without losing items");
        plan=TradeInventory.plan(Arrays.asList(new ItemStack("gold",1)),Arrays.asList(new ItemStack("iron",1)),Collections.emptyList(),new ItemStack[]{new ItemStack("gold",1)},new ItemStack[]{new ItemStack("iron",1)});
        check(plan!=null,"A full-to-full exchange may use slots freed by payment");
        plan=TradeInventory.plan(player,Arrays.asList(ItemStack.EMPTY,ItemStack.EMPTY),Arrays.asList(new ItemStack("iron",1)),new ItemStack[]{},new ItemStack[]{new ItemStack("iron",1)});
        check(plan!=null && plan.armor.get(0).isEmpty(),"Equipped items are debited");
        check(TradeInventory.plan(player,villager,Collections.emptyList(),new ItemStack[]{new ItemStack("gold",65)},new ItemStack[]{})==null,"Reject oversized packet stacks");
        System.out.println("Trade selection and inventory checks passed");
    }
}
'''
helper = (root / 'src/main/java/com/github/AaronAA0721/villageragent/ai/TradeInventory.java').read_text(encoding='utf-8')
helper = '\n'.join(line for line in helper.splitlines() if not line.startswith(('package ', 'import ')))
code += helper.replace('public final class TradeInventory', 'final class TradeInventory')
with tempfile.TemporaryDirectory(prefix='trade-ui-') as temp:
    folder = Path(temp)
    (folder / 'TradeUiChecks.java').write_text(code, encoding='utf-8')
    def executable(name):
        return str(args.jdk / 'bin' / (name + '.exe')) if args.jdk else name
    subprocess.run([executable('javac'), '-encoding', 'UTF-8', str(folder / 'TradeUiChecks.java')], check=True)
    subprocess.run([executable('java'), '-cp', temp, 'TradeUiChecks'], check=True)
