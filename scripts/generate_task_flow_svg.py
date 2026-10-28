"""Generate the source-verified task execution diagram (standard-library only)."""
from pathlib import Path
from html import escape
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'docs/task_execution_flow.svg'
parts = ['''<svg xmlns="http://www.w3.org/2000/svg" width="2400" height="3220" viewBox="0 0 2400 3220">
<title>通用任务执行、复杂任务分解与失败恢复</title>
<desc>依据当前工作区 Java 方法体核对。上部是所有目标任务共用的执行骨架，下部展开农务与物品获取的具体实现。</desc>
<defs><marker id="arrow" markerWidth="10" markerHeight="10" refX="8" refY="5" orient="auto-start-reverse"><path d="M0 0 L10 5 L0 10" fill="context-stroke"/></marker></defs>
<style>text{font-family:'Microsoft YaHei','Noto Sans CJK SC',sans-serif;fill:#18324b} .title{font-size:42px;font-weight:700}.sub{font-size:22px;fill:#52677d}.heading{font-size:27px;font-weight:700}.body{font-size:21px}.code{font-family:Consolas,'Microsoft YaHei',monospace;font-size:20px;fill:#e5eefc}.label{font-size:19px;font-weight:600;paint-order:stroke;stroke:#f4f7fb;stroke-width:7px;stroke-linejoin:round}</style>
<rect width="2400" height="3220" fill="#f4f7fb"/>''']

def text(x,y,s,cls='body',fill=None):
    parts.append(f'<text x="{x}" y="{y}" class="{cls}"'+(f' style="fill:{fill}"' if fill else '')+f'>{escape(s)}</text>')

def box(x,y,w,h,title,lines=(),code=(),color='#3975c6'):
    parts.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="16" fill="white" stroke="{color}" stroke-width="2"/>')
    parts.append(f'<rect x="{x}" y="{y+17}" width="6" height="32" rx="3" fill="{color}"/>')
    text(x+22,y+39,title,'heading')
    yy=y+66
    for s in lines:
        text(x+22,yy,s); yy+=27
    if code:
        cy=yy
        parts.append(f'<rect x="{x+16}" y="{cy}" width="{w-32}" height="{len(code)*25+16}" rx="9" fill="#14283f"/>')
        for i,s in enumerate(code): text(x+29,cy+25+i*25,s,'code')
        assert cy+len(code)*25+16 <= y+h-8, title

def arrow(points,label=None,lx=0,ly=0,color='#57738e',dash=False):
    d='M'+' L'.join(f'{x},{y}' for x,y in points)
    parts.append(f'<path d="{d}" fill="none" stroke="{color}" stroke-width="3" marker-end="url(#arrow)"'+(' stroke-dasharray="9 7"' if dash else '')+'/>')
    if label: text(lx,ly,label,'label',color)

text(60,70,'任务如何落地：根任务 → Skill → 策略／分解 → 执行 → 恢复与退出','title')
text(60,112,'源码快照：2026-10-28 当前工作区（含未提交修改） · 实线＝调用／结果流；虚线＝重试／递归回路','sub')
text(60,155,'01  通用执行骨架','heading')

box(60,185,690,205,'A  根任务与调度',
    ['候选保存为 AgentGoal；只有根任务进入调度。','先过滤可执行项，再按 Utility 选择／决定抢占。'],
    ['VillagerAgentManager.processGoals(...)','  → GoalScheduler.pickNextGoal(...)'])
box(60,435,690,245,'B  找运行器：Skill 分派',
    ['按注册顺序，选择首个 canHandle(...) 为真的 Skill。','canHandle 负责类型／能力等门槛；未找到则诊断失败。'],
    ['BehaviorExecutor.findHandler(...)','  → VillagerSkill.canHandle(...)','executeRoot(...) → TaskExecution.tick(...)'])
box(60,725,690,215,'C  执行一个节点',
    ['根节点和子节点使用相同的分派与执行逻辑。','RUNNING 叶节点还检查导航失败；节点可返回 BLOCKED。'],
    ['BehaviorExecutor.executeNode(...)','  → skill.execute(agent, goal, villager, world)'])
box(60,985,690,280,'D  任务实际内容：具体 Skill 的 execute',
    ['这里写“怎么做”：直接动作，或策略选择后逐步分解。','复杂任务自己调用 executeChild；框架不会自动分解。','isComposite() 是声明；不是自动展开任务图的解释器。'],
    ['interface VillagerSkill {','  canHandle(...); execute(...);','  isComposite(); isPrimitive();','}'])

box(850,185,690,260,'E  策略／方法判断：两种现有实现',
    ['① 手写规则：在 Skill 内观察状态并选下一步。','② 可替换方法：TaskMethodExecution 评分并保留尝试。','② 当前接入物品获取；不是所有 Skill 的必经步骤。'],
    ['// 规则例：FarmSkill.scan / choose','// 方法例：AcquisitionTasks.execute','//        → TaskMethodExecution.tick'])
box(850,500,690,275,'F  复杂任务分解与递归',
    ['父任务构造 AgentGoal 子任务；每帧只有一个活动子节点。','子节点重新找 Skill，可继续分解为更深的子任务。','Frame.state(...) 保存这次执行的局部状态。'],
    ['BehaviorExecutor.executeChild(..., factory)','  → TaskExecution.child(factory, runner)','  → BehaviorExecutor.executeNode(child)','  → childSkill.execute(...)'])
box(850,830,690,220,'G  叶任务／动作执行',
    ['具体 Skill 驱动导航、方块操作、库存操作或外部请求。','完成条件由任务实现核对；“到达”并不等于获得物品。'],
    ['SkillResult: RUNNING | SUCCESS | WAITING','             BLOCKED | FAIL | DELETE'])
box(850,1105,690,250,'H  子任务返回：父任务决定下一步',
    ['RUNNING：保留子帧，下 tick 再执行。','其他结果：清身体动作／释放资源，丢弃子帧。','复制 child.lastFailure 给父任务；父任务可消化或上抛。'],
    ['executeChild → clearBody / releaseTree','TaskExecution.child → parent.child = null','// 子任务成功不会自动完成父任务'])

box(1640,185,700,240,'I  根任务结果汇总',
    ['processGoals 接收根结果；RUNNING 保持本轮执行。','BLOCKED／FAIL 才调用全局恢复；无 handler 也走此路。','普通执行超时可转为 FAIL；无 Failure 信息则不恢复。'],
    ['VillagerAgentManager.processGoals(...)','  → FailureRecoveryPlanner.handle(...)'], '#cc6b43')
box(1640,480,700,340,'J  全局恢复：补充目标，不直接替原任务执行',
    ['MISSING_ITEM → 新建／复用 acquire_item。','NO_RECIPE + 目标物品 → 同样转物品获取。','NO_JOB_SITE → 新建／复用 find_job_site。','能力不足／其他原因 → 记录记忆，没有自动练级分解。','目标去重、必要时提高库存目标量并清除恢复任务冷却。'],
    ['FailureRecoveryPlanner.handle(...)','  → recoverMissingItem(...)','  → recoverJobSite(...)','  → agent.getGoals().add(goal)'], '#cc6b43')
box(1640,875,700,240,'K  清理当前执行，再执行退出策略',
    ['根结果不是 RUNNING 时，释放动作、导航与执行树。','获取会话独立保存，WAITING／抢占不等于取消方法。'],
    ['ActionLifecycle.cancel(...)','agent.setRunningGoal(null)','GoalExitPolicy.retain(goal, result, now)'], '#348c75')
box(1640,1170,700,295,'L  在哪里结束？',
    ['SUCCESS：一次性目标移除；可重复目标冷却后保留。','DELETE：移除目标。','WAITING／可恢复物品 BLOCKED：保留，冷却至少 200 tick。','其余失败：累计 3 次后移除；未达上限则冷却重试。','最后应用任务自己的最小退出冷却。'],
    ['retain == false → goals.remove(current)','retain == true  → cooldown → 再次调度'], '#348c75')

arrow([(405,390),(405,435)])
arrow([(405,680),(405,725)])
arrow([(405,940),(405,985)])
arrow([(750,1100),(800,1100),(800,310),(850,310)],'选择策略',787,293)
arrow([(1195,445),(1195,500)],'需要子任务',1210,479)
arrow([(1195,775),(1195,830)])
arrow([(1195,1050),(1195,1105)])
arrow([(850,1235),(820,1235),(820,638),(850,638)],'父任务继续／下一子任务',855,1385,dash=True)
arrow([(850,575),(780,575),(780,815),(750,815)],'递归分派',785,700,dash=True)
arrow([(1540,1235),(1590,1235),(1590,305),(1640,305)],'根结果向上返回',1552,1450)
arrow([(750,535),(770,535),(770,170),(1990,170),(1990,185)],'无 handler：诊断 Failure → BLOCKED',855,165,color='#cc6b43')
arrow([(1990,425),(1990,480)],'BLOCKED / FAIL',2010,458,color='#cc6b43')
arrow([(1990,820),(1990,875)],color='#cc6b43')
arrow([(2340,315),(2370,315),(2370,995),(2340,995)],'其他非 RUNNING',2135,850,color='#348c75')
arrow([(1990,1115),(1990,1170)],color='#348c75')
arrow([(1640,720),(1565,720),(1565,130),(405,130),(405,185)],'恢复目标重新入队',1680,128,color='#cc6b43',dash=True)
text(60,1310,'通用抽象：目标 → 分派 → 策略','heading')
text(60,1348,'→ 子任务递归／叶操作 → 父验收 → 根退出','heading')
text(60,1390,'直接 Skill 可由 D 进入 G，无需创建方法或子任务。','body')
text(60,1426,'普通帧取消后重建；获取会话独立保存。','body')
text(60,1462,'非目标驱动的战斗／显式建造占用身体时暂停此链。','body')

parts.append('<path d="M60 1510 H2340" stroke="#ced9e6" stroke-width="2"/>')
text(60,1570,'02  两种具体展开｜都嵌入上面的 D → E → F → G → H，不是两套调度器','heading')

box(60,1610,1090,325,'实例 A · 日常农务 farm：规则分解的内容就在这里',
    ['FarmSkill implements VillagerSkill；isComposite() 返回 true。','execute 管理整场农务会话；scan 生成候选，choose 选择当前局部步骤。','候选先判有效／可达；不是一次性生成固定的全量子任务队列。'],
    ['FarmSkill.execute(...)','  → refresh(...) → scan(...)  // 生成 List<Plan>','  → choose(...)               // 选下一步','  → BehaviorExecutor.executeChild(..., () -> new AgentGoal(type,...))','// State: pending / plan / rejected / completed / field'])
box(60,1985,1090,310,'农务子任务清单与实际执行函数',
    ['远处田地：move → MoveSkill；到达后 FarmMemory.validateArrival。','HARVEST → harvest；PLANT → plant；COLLECT → collect_crops。','TILL_REVERTED／TILL_RECLAIM → till（恢复耕地／开垦，后者要求等级 2）。'],
    ['FarmActionSkill.execute(...) → FarmSkill.valid(...)','  → FarmingAction.approachTarget(...)','  → FarmSkill.perform(...)','    → harvestBlockAt / plantSpecificCropAt / plantSmartAt / tillBlockAt'])
box(60,2345,1090,280,'农务的循环、局部失败与结束',
    ['choose 优先：HARVEST → PLANT → COLLECT → TILL_REVERTED → TILL_RECLAIM。','采收会尝试立即补种；成功后也可追加 PLANT；随后继续挑选／刷新。','某个作业点失败：加入 rejected，清当前失败，再试其他点。','前往田地失败：向上返回；扫描耗尽且缺种子／锄头：BLOCKED。','无工作且无缺项：SUCCESS；会话达到 6000 tick：SUCCESS。'],
    ['// collect_crops 的 perform 返回 true；接近掉落物后由拾取机制收集','// 农务根 BLOCKED / FAIL → I → J；不会每个坏地块都创建恢复根'])

box(1250,1610,1090,325,'实例 B · 获取物品 acquire_item：先选方法，再分解',
    ['AcquireItemSkill → AcquisitionTasks：先核对真实库存是否达到绝对目标量。','未满足才运行方法会话；按目标产出发现候选并检查契约能力要求。','TaskContract 描述物品前提／产出／等级；具体操作仍在实现函数内。'],
    ['AcquireItemSkill.execute(...) → AcquisitionTasks.execute(...)','  → TaskMethodExecution.tick(...)','  → AcquisitionMethods.candidates(...) → ItemTaskCatalog.discover(...)','interface TaskMethod<C> { id(); estimate(C); start(C, UUID);','                          tick(C); close(C); }'])
box(1250,1985,1090,310,'可选方法与“真正分解子任务”的位置',
    ['craft → Craft.tick：采购缺料后执行 craft 子任务。','craft_inputs／资源生产 → Produce.tick：先前提，再生产。','buy_product → Buy.tick：购买；cooperative_supply → Supply.tick：请求外援。'],
    ['AcquisitionMethods.Produce.tick(Context c)','  → contract(c).items → 缺项创建 acquire_item 子任务（递归选方法）','  → 前提满足 → 创建 craft 或 extract_item 子任务','  → BehaviorExecutor.executeChild(...)','// 资源方法 ID：woodcutting / mining / quarrying / farming'])
box(1250,2345,1090,280,'方法的局部失败、切换与最终验收',
    ['score = 剩余成本 + 失败惩罚 + 停滞时间惩罚；仅选有限且可行的候选。','FAILED／安全边界超时：close 旧尝试，冷却并重新选方法。','DONE 只结束方法；AcquisitionTasks 返回 RUNNING，之后再次核对库存。','实际库存满足才把目标设为 completed 并返回 SUCCESS。','无可用方法／外援等待通常映射为 WAITING，不直接触发全局恢复。'],
    ['TaskMethodExecution.tick → active.tick / estimate / close / start','AcquisitionTasks.execute: count(target) >= desired → SUCCESS'])
arrow([(605,1935),(605,1985)])
arrow([(605,2295),(605,2345)])
arrow([(1795,1935),(1795,1985)])
arrow([(1795,2295),(1795,2345)])
arrow([(1150,2490),(1195,2490),(1195,1760),(1150,1760)],'循环',1165,2315,dash=True)
arrow([(2340,2500),(2370,2500),(2370,1760),(2340,1760)],dash=True)

box(60,2690,1090,220,'不要混淆两个 farming',
    ['farm 是日常复杂任务：旅行、采收、收集、种植、翻地／开垦。','获取方法 farming 是采收成熟作物：走 extract_item，不调用整场 farm。'],
    ['Produce(true).tick → ExtractItemSkill.execute(...)','  → ResourceExtraction.tick(...)  // 与供货任务共用真实采集逻辑'], '#8365b0')
box(1250,2690,1090,220,'方法生命周期不等于执行帧生命周期',
    ['AcquisitionTasks 按根目标保存会话，可跨 WAITING／帧重建继续。','换方法时按 attempt UUID 清理所属子会话／交易需求／资源订单。'],
    ['AcquisitionMethods.Owned.close → cancelChildren / removeOwned','  / cancelOwned / discardChild','AcquisitionTasks.maintain → 清理已移除、过期、满足或跨维度目标'], '#8365b0')

text(60,2970,'源码导航（均位于 src/main/java/com/github/AaronAA0721/villageragent/）','heading')
text(60,3010,'ai/：VillagerAgentManager · GoalScheduler · TaskExecution · TaskMethodExecution · AcquisitionTasks · FailureRecoveryPlanner · GoalExitPolicy','body')
text(60,3046,'ai/behavior/：BehaviorExecutor · VillagerSkill · FarmSkill · FarmActionSkill · AcquireItemSkill · ExtractItemSkill','body')
text(60,3082,'ai/：AcquisitionMethods · ItemTaskCatalog · TaskContract · ActionLifecycle；ai/work/：ResourceExtraction','body')
text(60,3138,'阅读顺序：上部 A–L 看通用结构 → 下部定位任务内容 → 沿橙色分支追失败恢复 → 沿绿色分支找结束点。','sub')
text(60,3176,'本图描述当前实现，不代表任意任务已统一接入方法目录；未修改 Java 实现，也未运行 Minecraft 验收。','sub')
parts.append('</svg>')
OUT.write_text('\n'.join(parts), encoding='utf-8')
ET.parse(OUT)
print(OUT)
