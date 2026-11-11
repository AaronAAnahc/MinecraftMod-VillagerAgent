"""Execute production furnace/inventory transfers with small Minecraft API doubles.
No world ticks, recipe loading, pathfinding, or live AI scheduling are simulated.
"""
from pathlib import Path
import argparse
import re
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('--jdk', type=Path, required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
base = root / 'src/main/java/com/github/AaronAA0721/villageragent/ai'
def source(name):
    text = (base / (name + '.java')).read_text(encoding='utf-8')
    text = re.sub(r'^package .*?;|^import .*?;', '', text, flags=re.M)
    return text.replace('public final class', 'final class').replace('net.minecraft.block.AbstractFurnaceBlock.LIT', 'Boolean.TRUE').replace('net.minecraft.item.Item', 'Item')
code = r"""
import java.util.*;
class Item { final String name; final boolean fuel; Item(String n,boolean f){name=n;fuel=f;} }
class Items { static final Item GOLD_ORE=new Item("gold ore",false), IRON_ORE=new Item("iron ore",false); static final Item COAL=new Item("coal",true), CHARCOAL=new Item("charcoal",true); }
class ItemStack {
    static final ItemStack EMPTY=new ItemStack(null,0);
    final Item item; int count; String tag="";
    ItemStack(Item item,int count){this.item=item;this.count=count;}
    boolean isEmpty(){return count<=0 || item==null;}
    Item getItem(){return item;}
    int getCount(){return count;}
    int getMaxStackSize(){return 64;}
    void setCount(int n){count=n;}
    void grow(int n){count+=n;}
    void shrink(int n){count-=n;}
    ItemStack copy(){ItemStack s=new ItemStack(item,count);s.tag=tag;return s;}
    static boolean isSame(ItemStack a,ItemStack b){return a.item==b.item;}
    static boolean tagMatches(ItemStack a,ItemStack b){return a.tag.equals(b.tag);}
}
class AgentInventory {
    List<ItemStack> items=new ArrayList<>();
    AgentInventory(ItemStack... stacks){items.addAll(Arrays.asList(stacks));}
    List<ItemStack> getItems(){return items;}
    ItemStack getStackInSlot(int i){return items.get(i);}
    void setStackInSlot(int i,ItemStack s){items.set(i,s);}
}
class AbstractFurnaceTileEntity {
    ItemStack[] slots={ItemStack.EMPTY,ItemStack.EMPTY,ItemStack.EMPTY}; boolean lit;
    ItemStack getItem(int i){return slots[i];}
    void setItem(int i,ItemStack s){slots[i]=s;}
    int getMaxStackSize(){return 64;}
    class BlockState { boolean getValue(Boolean property){return lit;} }
    BlockState getBlockState(){return new BlockState();}
    static boolean isFuel(ItemStack s){return !s.isEmpty() && s.item.fuel;}
    boolean canPlaceItem(int i,ItemStack s){return i==1 && isFuel(s);}
    void setChanged(){}
}
""" + source('InventoryTransfer') + source('FurnaceInventory') + r"""
public class FurnaceInventoryChecks {
    static Item iron=new Item("iron",false), ore=new Item("ore",false), gold=new Item("gold",false),
        coal=Items.COAL, charcoal=Items.CHARCOAL, bucket=new Item("bucket",false);
    static int checks;
    static ItemStack s(Item i,int n){return new ItemStack(i,n);}
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    public static void main(String[] args){
        AbstractFurnaceTileEntity f=new AbstractFurnaceTileEntity();
        AgentInventory inv=new AgentInventory(s(ore,3),s(coal,4));
        f.setItem(1,s(charcoal,7));
        check(FurnaceInventory.load(inv,f,s(ore,1),s(iron,1)),"existing charcoal permits loading");
        check(f.getItem(1).getItem()==charcoal && f.getItem(1).getCount()==7 && inv.getStackInSlot(1).getCount()==4,"mixed fuel must not replace or debit");
        check(f.getItem(0).getCount()==1 && inv.getStackInSlot(0).getCount()==2,"ore conserved");
        check(FurnaceInventory.load(inv,f,s(ore,1),s(iron,1)) && f.getItem(0).getCount()==2,"matching input merges");
        f.setItem(0,s(gold,1));
        check(!FurnaceInventory.load(inv,f,s(ore,1),s(iron,1)) && inv.getStackInSlot(0).getCount()==1,"wrong input untouched");
        f.setItem(0,s(ore,64));
        check(!FurnaceInventory.load(inv,f,s(ore,1),s(iron,1)),"full input rejected");
        f.setItem(0,ItemStack.EMPTY);f.setItem(2,s(gold,1));
        check(!FurnaceInventory.load(inv,f,s(ore,1),s(iron,1)),"wrong output rejected");
        f.setItem(2,s(iron,64));
        check(!FurnaceInventory.load(inv,f,s(ore,1),s(iron,1)),"full output rejected");
        f.setItem(2,ItemStack.EMPTY);f.setItem(1,s(bucket,1));
        check(!FurnaceInventory.load(inv,f,s(ore,1),s(iron,1)) && f.getItem(1).getItem()==bucket,"fuel container preserved");
        f.setItem(1,ItemStack.EMPTY);
        check(FurnaceInventory.load(inv,f,s(ore,1),s(iron,1)) && f.getItem(1).getCount()==1 && inv.getStackInSlot(1).getCount()==3,"empty fuel accepts exactly one");
        f.setItem(0,ItemStack.EMPTY);f.setItem(1,ItemStack.EMPTY);
        inv=new AgentInventory(s(ore,2));
        check(!FurnaceInventory.load(inv,f,s(ore,1),s(iron,1)) && inv.getStackInSlot(0).getCount()==2,"missing fuel is atomic");
        f.lit=true;
        check(FurnaceInventory.load(inv,f,s(ore,1),s(iron,1)),"remaining burn time permits loading");
        f.setItem(2,s(iron,20));inv=new AgentInventory(s(iron,63),s(gold,64));
        check(FurnaceInventory.collect(inv,f)==1 && f.getItem(2).getCount()==19 && inv.getStackInSlot(0).getCount()==64,"partial capacity leaves remaining output");
        check(FurnaceInventory.collect(inv,f)==0 && f.getItem(2).getCount()==19,"full inventory preserves output");
        inv=new AgentInventory(s(iron,62),ItemStack.EMPTY);
        check(FurnaceInventory.collect(inv,f)==19 && f.getItem(2).isEmpty() && inv.getStackInSlot(1).getCount()==17,"merge before empty slot and exact debit");
        ItemStack tagged=s(iron,63);tagged.tag="special";inv=new AgentInventory(tagged);
        f.setItem(2,s(iron,4));
        check(FurnaceInventory.collect(inv,f)==0 && f.getItem(2).getCount()==4,"NBT mismatch cannot merge");
        f.getItem(2).tag="special";
        check(FurnaceInventory.collect(inv,f)==1 && f.getItem(2).getCount()==3,"NBT match partial collection");
        f.setItem(0,ItemStack.EMPTY);f.setItem(1,ItemStack.EMPTY);f.setItem(2,ItemStack.EMPTY);f.lit=false;
        inv=new AgentInventory(s(ore,2),s(new Item("wooden axe",true),1));
        check(!FurnaceInventory.load(inv,f,s(ore,1),s(iron,1)) && inv.getStackInSlot(1).getCount()==1,"must not burn carried tools");
        inv=new AgentInventory(s(coal,2));
        check(FurnaceInventory.ensureFuel(inv,f) && inv.getStackInSlot(0).getCount()==1,"refuel pending batch");
        check(!FurnaceInventory.fits(ItemStack.EMPTY,s(ore,65),64),"empty slot still enforces stack limit");
        f.setItem(1,s(charcoal,7));inv=new AgentInventory(s(coal,4),s(charcoal,2));
        check(FurnaceInventory.ensureFuel(inv,f) && f.getItem(1).getCount()==8 && inv.getStackInSlot(1).getCount()==1,"matching charcoal is topped up despite coal preference");
        f.setItem(1,ItemStack.EMPTY);inv=new AgentInventory(s(charcoal,4),s(coal,2));
        check(FurnaceInventory.ensureFuel(inv,f) && f.getItem(1).getItem()==coal,"empty fuel prefers coal independent of inventory order");
        f.setItem(1,s(coal,64));
        check(FurnaceInventory.ensureFuel(inv,f) && f.getItem(1).getCount()==64 && inv.getStackInSlot(1).getCount()==1,"full fuel cannot consume inventory");
        inv=new AgentInventory(s(Items.IRON_ORE,4),s(Items.GOLD_ORE,2));
        check(FurnaceInventory.selectOre(inv,ItemStack.EMPTY).getItem()==Items.GOLD_ORE,"empty input selects rarer gold");
        check(FurnaceInventory.selectOre(inv,s(Items.IRON_ORE,2)).getItem()==Items.IRON_ORE,"existing iron overrides rarity");
        check(FurnaceInventory.selectOre(inv,s(Items.IRON_ORE,64)).isEmpty(),"full input cannot select a different ore");
        System.out.println("Furnace inventory checks passed: "+checks);
    }
}
"""
with tempfile.TemporaryDirectory(prefix='furnace-checks-') as temp:
    path = Path(temp)
    (path / 'FurnaceInventoryChecks.java').write_text(code, encoding='utf-8')
    subprocess.run([str(args.jdk / 'bin/javac.exe'), '-encoding', 'UTF-8', str(path / 'FurnaceInventoryChecks.java')], check=True)
    subprocess.run([str(args.jdk / 'bin/java.exe'), '-cp', str(path), 'FurnaceInventoryChecks'], check=True)
