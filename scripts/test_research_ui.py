"""UI integration checks with isolated API fixtures. No model, GPU or real project writes.

Run Vite on 127.0.0.1:5174, then: python scripts/test_research_ui.py
Requires Python Playwright and its Chromium browser.
"""
import copy
import json
import re
from pathlib import Path
from urllib.parse import urlparse, parse_qs
from playwright.sync_api import sync_playwright, expect

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "scratch" / "research-ui-check"
OUTPUT.mkdir(parents=True, exist_ok=True)
STAMP = "2026-10-03T10:00:00Z"
projects = [dict(id="research_demo", title="稀疏连接：先验证可行性", sourceType="portfolio", contactId="ekina", updatedAt=STAMP),
            dict(id="legacy_demo", title="旧课题兼容检查", sourceType="portfolio", contactId="ekina", updatedAt=STAMP)]
boards = {
    "research_demo": dict(projectId="research_demo", goal="稀疏连接：先验证可行性", status="planning", now="准备最小验证", updatedAt=STAMP,
        nextDeliverable="基线与变体的同协议试跑报告", collaboration=dict(mode="checkpoints",budget=120,currency="CNY",priorities="先验证再扩展"),
        roadmap=[dict(id="pilot",title="最小验证",status="planned",purpose="判断是否值得继续投入",deliverable="可复现试跑结果",acceptance="核对协议与运行记录",resources="GPU 与数据规模待评估",costMin=None,costMax=None,dependsOn=[]),
                 dict(id="full",title="正式对照",status="conditional",condition="试跑支持假设后",dependsOn=["pilot"],resources="待吞吐量实测后确定")],
        guidance=[], decisions=[], done=[], findings=[], timeline=[]),
    "legacy_demo": dict(projectId="legacy_demo",goal="旧课题兼容检查",next=["核对文献"],done=[dict(title="旧报告",result="历史内容保留")],findings=[],timeline=[])
}
writes = []
chat_requests = []
fail_settings = [False]
conflict_research = [False]
research_requests = []
review_campaign = dict(id="campaign_demo",status="running",paused=False,topics=[dict(id="t1",title="综述队列检查",status="drafting",projectId="research_demo",dependsOn=[])])
records = [
    dict(id="a1",type="attempt",question="首轮训练能否完成",method="本地小样本试跑",result="训练中止，已保存数据校验结果",outcome="failed",artifacts=["runs/pilot/check.json"]),
    dict(id="e1",type="evidence",title="小样本试跑指标",kind="experiment",source="runs/pilot/metrics.json",summary="单种子结果出现提升，尚需检查数据划分",conditions="仅限当前划分和随机种子",producer="实验执行者",attemptId="a1",reviewIds=["r1"],assessment="reviewed"),
    dict(id="r1",type="review",evidenceId="e1",reviewer="审查者 B",summary="阅读记录，未实际重新运行",artifact="reviews/pilot.md",method="model_review",verdict="supports",assessment="reviewed"),
    dict(id="c1",type="conclusion",title="当前划分上出现提升",statement="需要多种子复核",conditions="小样本试跑",evidenceIds=["e1"],dependsOn=[],assessment="reviewed"),
    dict(id="e2",type="evidence",title="资源测量记录",kind="experiment",source="runs/timing.json",summary="已测量本地耗时",conditions="本地设备",producer="实验执行者",reviewIds=["r2"],assessment="reviewed"),
    dict(id="r2",type="review",evidenceId="e2",reviewer="审查者 C",summary="核对运行日志",artifact="reviews/timing.md",method="execution_check",verdict="supports",assessment="reviewed"),
    dict(id="c2",type="conclusion",title="值得进行多种子验证",statement="先用小规模实验检查稳定性",conditions="沿用同一协议",evidenceIds=["e2"],dependsOn=["c1"],assessment="reviewed"),
    dict(id="round2",type="checkpoint",question="收益能否跨种子复现",nextAction="先补充两组随机种子",continueWhen="同协议结果稳定",stopWhen="发现数据泄漏或提升消失",resources="本地 GPU，先测吞吐",basisIds=["c2"],costMin=None,costMax=None,currency="CNY",assessment="planned"),
    dict(id="unsafe",type="evidence",title="不可信来源地址测试",kind="analysis",source="javascript:alert(1)",summary="只显示原文，不执行地址",conditions="测试材料",producer="测试",reviewIds=[],assessment="unreviewed"),
]
boards["research_demo"]["research"] = dict(revision=len(records), records=records, checkpoint=copy.deepcopy(records[7]), attentionCount=0)

def api(route):
    req = route.request
    parsed = urlparse(req.url)
    path = parsed.path
    body = req.post_data_json if req.method in ("PUT", "POST") and req.post_data else {}
    result = {}
    if path == "/api/reviews":
        result = [review_campaign]
    elif path == "/api/reviews/campaign_demo/pause":
        review_campaign['paused']=body['paused']
        result=review_campaign
    elif path == "/api/contacts":
        result = [dict(id="ekina",displayName="Ekina",threadId="daily",toolProfile="life"),dict(id="agent",displayName="代理人",threadId="contact_agent",toolProfile="executor")]
    elif path == "/api/projects/dock":
        result = projects if parse_qs(parsed.query).get("contactId",["ekina"])[0] == "ekina" else []
    elif path == "/api/projects/research":
        ident = "research_" + body["requestId"]
        result = dict(id=ident,title=body["brief"],sourceType="portfolio",contactId="ekina",updatedAt=STAMP)
        if ident not in boards:
            projects.insert(0,result)
            boards[ident]=dict(projectId=ident,goal=body["brief"],status="planning",now="等待开始调研",collaboration=body["collaboration"],nextDeliverable="原始来源核验、研究现状与技术比较报告",guidance=[],roadmap=[])
        writes.append((path,body))
    elif path.startswith("/api/projects/"):
        ident = path.split("/")[3]
        if path.endswith("/timeline"):
            result = dict(project=next(p for p in projects if p["id"]==ident),board=copy.deepcopy(boards[ident]),summary=dict(total=4,done=1,running=0,blocked=0),steps=[])
        elif path.endswith("/collaboration"):
            if fail_settings[0]:
                fail_settings[0]=False
                route.fulfill(status=503,content_type="application/json",body=json.dumps(dict(error="测试：保存失败")))
                return
            boards[ident]["collaboration"]=body
            writes.append((path,body))
            result=boards[ident]
        elif path.endswith("/guidance"):
            boards[ident].setdefault("guidance",[]).insert(0,dict(id=body["requestId"],target=body["target"],text=body["text"],at=STAMP,status="pending"))
            writes.append((path,body)); result=boards[ident]
        elif path.endswith("/research-records"):
            research_requests.append(copy.deepcopy(body))
            ledger=boards[ident]["research"]
            if conflict_research[0]:
                conflict_research[0]=False
                ledger["revision"]+=1
                ledger["records"].append(dict(id="concurrent",type="attempt",question="并行补充检查",method="检查日志",result="已记录",outcome="inconclusive",artifacts=[]))
                route.fulfill(status=409,content_type="application/json",body=json.dumps(dict(error="研究记录已更新，请重新读取课题板后合并提交")))
                return
            assert body["expectedRevision"] == ledger["revision"]
            for record in body["records"]:
                assert record["type"] == "withdrawal" and record["targetId"] == "e1"
                ledger["records"].append({**record,"at":STAMP})
                ledger["revision"]+=1
                for entry in ledger["records"]:
                    if entry["id"]=="e1": entry.update(assessment="withdrawn",withdrawalReason=record["reason"])
                    elif entry["id"] in ("r1","c1","c2","round2"): entry["assessment"]="needs_review"
                ledger["checkpoint"]["assessment"]="needs_review"
                ledger["attentionCount"]=2
            result=boards[ident]
        else:
            result=boards.get(ident,{})
    elif path == "/api/chat":
        chat_requests.append(body)
        result=dict(answer="已读取当前课题的指导与参与约定。")
    elif path == "/api/ui-prefs": result=dict(theme="paper",crewCollapsed=True)
    elif path.startswith("/api/channel") or path in ("/api/skills","/api/mcp/servers","/api/permissions/capabilities"): result=[]
    elif path == "/api/home/autonomy": result=dict(enabled=False)
    elif path == "/api/agents/live": result=dict(running=0,queued=0)
    elif path == "/api/lab/papers/preview": result=dict(hasPaper=False)
    route.fulfill(content_type="application/json",body=json.dumps(result,ensure_ascii=False))

with sync_playwright() as p:
    browser=p.chromium.launch(headless=True)
    page=browser.new_page(viewport=dict(width=1440,height=1050))
    errors=[]
    page.on("pageerror",lambda e: errors.append(str(e)))
    page.route(re.compile(r"^http://127\.0\.0\.1:5174/api/"),api)
    page.route(re.compile(r"^http://127\.0\.0\.1:5174/assets/"),lambda route: route.fulfill(status=204))
    page.route("https://**/*",lambda route: route.abort())
    page.goto("http://127.0.0.1:5174/shell/")
    page.wait_for_timeout(800)
    expect(page.get_by_text("Ekina", exact=True).first).to_be_visible()
    assert not page.get_by_text("自主模式", exact=True).count()
    assert not page.get_by_text("新建 Agent", exact=True).count()
    page.screenshot(path=str(OUTPUT/'initial.png'),full_page=True)
    (OUTPUT/'initial.txt').write_text(page.locator('body').inner_text()+'\nERRORS: '+repr(errors),encoding='utf-8')
    workspace=page.get_by_role("region",name="研究项目工作台")
    expect(workspace.get_by_role("heading",name="稀疏连接：先验证可行性")).to_be_visible()
    expect(workspace.get_by_text("基线与变体的同协议试跑报告")).to_be_visible()
    assert not page.locator('.task-dock [role="progressbar"]').count()
    queue=page.get_by_role("region",name="综述任务队列")
    expect(queue.get_by_text("检索与撰写",exact=True)).to_be_visible()
    queue.get_by_role("button",name="暂停后续步骤",exact=True).click()
    expect(queue.get_by_text("综述计划 · 已暂停后续步骤",exact=True)).to_be_visible()
    assert review_campaign['paused']
    queue.get_by_role("button",name="继续后续步骤",exact=True).click()
    expect(queue.get_by_role("button",name="暂停后续步骤",exact=True)).to_be_visible()

    workspace.get_by_role("button",name="调整约定",exact=True).click()
    workspace.get_by_label("参与方式").select_option("guided")
    workspace.get_by_label("规划预算",exact=True).fill("80")
    fail_settings[0]=True
    workspace.get_by_role("button",name="保存约定",exact=True).click()
    expect(workspace.get_by_role("alert")).to_contain_text("保存失败")
    expect(workspace.get_by_label("规划预算",exact=True)).to_have_value("80")
    workspace.get_by_role("button",name="保存约定",exact=True).click()
    expect(workspace.get_by_role("status")).to_contain_text("参与约定已保存")
    assert boards['research_demo']['collaboration']['mode']=='guided'

    workspace.get_by_role("button",name="指导这一步",exact=True).click()
    workspace.get_by_label("告诉 Ekina 你希望调整什么").fill("先做本地检查，不要扩展实验规模。")
    workspace.get_by_role("button",name="保存指导",exact=True).click()
    expect(workspace.get_by_role("status")).to_contain_text("指导已保存")
    assert boards['research_demo']['guidance'][0]['target']=='步骤：最小验证'
    workspace.get_by_role("button",name="让 Ekina 处理指导",exact=True).click()
    expect(page.get_by_text("已读取当前课题的指导与参与约定。",exact=True)).to_be_visible()
    assert chat_requests[-1]['projectId']=='research_demo'
    assert '保存的指导' in chat_requests[-1]['message']

    workspace.get_by_role("button",name="资源与费用",exact=True).click()
    expect(workspace.get_by_text("费用待估",exact=True).first).to_be_visible()
    expect(workspace.get_by_text("实际支出尚未接入；不能据此计算剩余可用额度。")).to_be_visible()
    page.get_by_label("当前项目",exact=True).select_option("legacy_demo")
    expect(workspace.get_by_text("以下来自旧版“下一步”，依赖、资源和验收尚未补齐。")).to_be_visible()
    page.get_by_label("当前项目",exact=True).select_option("research_demo")
    workspace.get_by_role("button",name="正式对照",exact=False).click()
    expect(workspace.get_by_text("试跑支持假设后",exact=True)).to_be_visible()
    page.screenshot(path=str(OUTPUT/'research-workspace.png'),full_page=True)

    expect(workspace.get_by_role("article",name="下一轮验证")).to_contain_text("收益能否跨种子复现")
    workspace.get_by_role("button",name="成果与依据",exact=True).click()
    ledger_ui=workspace.get_by_role("region",name="研究证据记录")
    ledger_ui.get_by_role("button",name="值得进行多种子验证 已有支持性审查",exact=True).click()
    detail=ledger_ui.get_by_role("article",name="研究记录详情")
    detail.get_by_role("button",name="当前划分上出现提升",exact=True).click()
    detail.get_by_role("button",name="小样本试跑指标",exact=True).click()
    expect(detail).to_contain_text("仅限当前划分和随机种子")
    detail.get_by_role("button",name="模型审查 · 支持",exact=True).click()
    expect(detail).to_contain_text("未实际重新运行")
    detail.get_by_role("button",name="小样本试跑指标",exact=True).click()
    page.screenshot(path=str(OUTPUT/'evidence-trace.png'),full_page=True)
    detail.get_by_role("button",name="撤回这条记录",exact=True).click()
    ledger_ui.get_by_label("撤回原因",exact=False).fill("数据划分有误，需要重跑。")
    conflict_research[0]=True
    ledger_ui.get_by_role("button",name="保存撤回原因",exact=True).click()
    expect(ledger_ui.get_by_role("alert")).to_contain_text("研究记录已更新")
    expect(ledger_ui.get_by_label("撤回原因",exact=False)).to_have_value("数据划分有误，需要重跑。")
    # Wait for the conflict recovery GET to be applied before the explicit retry.
    expect(ledger_ui.get_by_text("10 条记录",exact=True)).to_be_visible()
    ledger_ui.get_by_role("button",name="保存撤回原因",exact=True).click()
    expect(ledger_ui.get_by_role("status")).to_contain_text("已撤回")
    assert research_requests[-1]["records"][0]["id"] == research_requests[-2]["records"][0]["id"]
    ledger_ui.get_by_role("button",name="值得进行多种子验证 依据变化 · 需复核",exact=True).click()
    expect(detail).to_contain_text("依据变化 · 需复核")
    ledger_ui.get_by_role("button",name="证据 · 3",exact=True).click()
    ledger_ui.get_by_role("button",name="不可信来源地址测试 尚待审查",exact=True).click()
    expect(detail.get_by_text("javascript:alert(1)",exact=True)).to_be_visible()
    assert detail.locator("a[href^='javascript:']").count()==0
    workspace.get_by_role("button",name="研究路线",exact=True).click()
    expect(workspace.get_by_text("计划依据发生变化，请先复核再决定是否继续。")).to_be_visible()
    page.screenshot(path=str(OUTPUT/'evidence-withdrawal.png'),full_page=True)

    page.get_by_role("button",name="＋ 新课题",exact=True).click()
    page.get_by_role("button",name="探索感兴趣的技术",exact=True).click()
    page.get_by_label("技术、来源链接，或吸引你的地方",exact=True).fill("果蝇大脑：先核对原始来源")
    page.get_by_role("button",name="创建研究课题",exact=True).click()
    expect(workspace.get_by_role("heading",name="果蝇大脑：先核对原始来源",exact=True)).to_be_visible()
    assert writes[-1][1]['entry']=='explore'
    assert len(chat_requests)==1, 'Creating a project must not execute research or compute automatically'
    page.reload()
    expect(workspace.get_by_role("heading",name="果蝇大脑：先核对原始来源",exact=True)).to_be_visible()
    page.set_viewport_size(dict(width=1024,height=900))
    assert page.locator('.task-dock').evaluate('(e)=>e.scrollWidth<=e.clientWidth+1')
    assert not errors, errors
    browser.close()
    print("PASS: settings, guidance, costs, legacy, evidence trace, review levels, withdrawal conflict/retry, dependent warnings, unsafe links, creation, reload, layout.")
