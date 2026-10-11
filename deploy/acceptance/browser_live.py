"""Real UI -> loopback Spring API -> disposable MySQL/Redis. No API mocking.
Invoked only by ProductJourneyIT with synthetic pre-indexed files; not a production smoke.
"""
import argparse
import json
import os
from pathlib import Path
import re
import time
from urllib.parse import urlsplit

from playwright.sync_api import sync_playwright, expect
from browser_support import serve


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--api", required=True)
    parser.add_argument("--dist", type=Path, required=True)
    parser.add_argument("--fixture", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    report = {"schemaVersion": 1, "mode": "fullstack-local", "passed": False,
              "commit": os.environ.get("ACCEPTANCE_SHA", os.environ.get("GITHUB_SHA", "local")),
              "journeys": [], "keywordSearchMs": [], "pageErrors": 0}
    fixture = {}
    errors = []
    stage = "initialization"
    args.report.parent.mkdir(parents=True, exist_ok=True)
    # Remove stale success before starting any external process.
    args.report.write_text(json.dumps(report), encoding="utf-8")
    try:
        fixture = json.loads(args.fixture.read_text(encoding="utf-8"))
        with serve(args.dist, args.api) as base, sync_playwright() as pw:
            browser = pw.chromium.launch()
            context = browser.new_context(viewport={"width": 1440, "height": 1000}, service_workers="block")
            context.route("**/*", lambda route: route.continue_()
                          if urlsplit(route.request.url).netloc == urlsplit(base).netloc else route.abort())
            page = context.new_page()
            page.on("pageerror", lambda error: errors.append(type(error).__name__))
            page.on("dialog", lambda dialog: dialog.accept())
            page.set_default_timeout(15000)
            stage = "login"
            page.goto(base)
            page.get_by_label("邮箱或用户名").fill(fixture["username"])
            page.get_by_label("密码", exact=True).fill(fixture["password"])
            page.locator('form button[type="submit"]').click()
            # Login lands in the upload center; use the same visible entry as a user.
            stage = "enter-workbench"
            page.get_by_role("button", name=re.compile("前往 AI 智能工作台")).click()
            navigation = page.get_by_role("navigation", name="AI 工作台导航")
            expect(navigation).to_be_visible()
            report["journeys"].append("login")
            stage = "keyword-search"
            navigation.get_by_role("button", name=re.compile("搜索与问答")).click()
            query = page.get_by_label("搜索已解析文档正文")
            query.fill("M46")
            for _ in range(20):
                start = time.perf_counter()
                with page.expect_response(lambda r: r.request.method == "POST" and r.url.endswith("/knowledge/search")) as response:
                    page.get_by_role("button", name=re.compile("检索正文")).click()
                body = response.value.json()
                assert response.value.ok and body["code"] == 0
                assert body["data"]["evidence"]
                expect(page.get_by_text("synthetic acceptance evidence for", exact=False).first).to_be_visible()
                report["keywordSearchMs"].append(round((time.perf_counter() - start) * 1000, 2))
            report["journeys"].append("keyword-search")
            stage = "strategy-editor"
            navigation.get_by_role("button", name=re.compile("整理策略")).click()
            strategy = page.get_by_role("region", name="可审核的智能整理策略")
            expect(strategy).to_be_visible()
            strategy.get_by_label("策略名称", exact=True).fill("Browser acceptance")
            stage = "strategy-file-type"
            # This label wraps the select: option text is included in its accessible
            # label. Match the stable leading label, not the concatenated options.
            strategy.get_by_label(re.compile(r"^文件类型")).select_option("TEXT")
            stage = "strategy-target-directory"
            target = strategy.get_by_label("目标目录（从右侧浏览区选择）")
            expect(target.locator('option[value="' + fixture["folderId"] + '"]')).to_have_count(1)
            target.select_option(fixture["folderId"])
            stage = "save-rule"
            with page.expect_response(lambda r: r.request.method == "POST" and r.url.endswith("/organize/templates")) as created:
                strategy.get_by_role("button", name=re.compile("保存规则（不执行）")).click()
            assert created.value.json()["code"] == 0
            stage = "select-files"
            first = strategy.get_by_label("选择文件 Acceptance.txt", exact=True)
            second = strategy.get_by_label("选择文件 Keep.txt", exact=True)
            expect(first).to_be_enabled()
            first.check(); second.check()
            stage = "strategy-preview"
            with page.expect_response(lambda r: r.url.endswith("/organize/simulate")) as preview:
                strategy.get_by_role("button", name=re.compile("模拟冲突与影响")).click()
            plan = preview.value.json()["data"]
            assert all(step["state"] == "READY" for step in plan["steps"])
            steps = strategy.locator(".ai-strategy-steps article")
            expect(steps).to_have_count(2)
            expect(strategy.get_by_role("button", name="确认执行", exact=True)).to_have_count(0)
            report["journeys"].append("strategy-preview")
            stage = "approve-without-execute"
            with page.expect_response(lambda r: r.url.endswith("/steps/1/approve")) as approved:
                strategy.get_by_role("button", name="审批本项", exact=True).click()
            assert approved.value.json()["data"]["steps"][0]["state"] == "APPROVED"
            expect(steps.nth(1).get_by_text("上一项未完成", exact=True)).to_be_visible()
            report["journeys"].append("approve-without-execute")
            stage = "execute-single"
            with page.expect_response(lambda r: r.url.endswith("/steps/1/execute")) as applied:
                strategy.get_by_role("button", name="确认执行", exact=True).click()
            after = applied.value.json()["data"]
            assert after["steps"][0]["state"] == "APPLIED" and after["steps"][1]["state"] == "READY"
            expect(steps.nth(0).get_by_text("已移动", exact=True)).to_be_visible()
            report["journeys"].append("execute-single")
            stage = "cancel-preserves-move"
            with page.expect_response(lambda r: r.url.endswith("/cancel")) as cancelled:
                strategy.get_by_role("button", name="取消剩余计划", exact=True).click()
            cancelled_data = cancelled.value.json()["data"]
            assert cancelled_data["status"] == "CANCELLED" and cancelled_data["steps"][0]["state"] == "APPLIED"
            report["journeys"].append("cancel-preserves-move")
            stage = "reload-history"
            page.reload()
            page.get_by_role("button", name=re.compile("前往 AI 智能工作台")).click()
            navigation.get_by_role("button", name=re.compile("整理策略")).click()
            strategy.get_by_role("button", name=re.compile(r"Browser acceptance · CANCELLED")).click()
            expect(strategy.locator(".ai-strategy-steps article").first.get_by_text("已移动", exact=True)).to_be_visible()
            report["journeys"].append("reload-history")
            stage = "mobile-layout"
            page.set_viewport_size({"width": 390, "height": 844})
            expect(strategy.get_by_role("heading", name="整理规则与冲突模拟")).to_be_visible()
            assert strategy.evaluate("el => el.scrollWidth <= el.clientWidth + 1"), "Strategy layout overflows mobile viewport"
            report["journeys"].append("mobile-layout")
            assert not errors, "Browser JavaScript errors occurred"
            report["passed"] = True
            context.close(); browser.close()
        return 0
    except Exception as error:
        message = str(error)
        for key in ("username", "password"):
            secret = fixture.get(key)
            if isinstance(secret, str) and secret:
                message = message.replace(secret, "[redacted]")
        # Bounded synthetic-only diagnostics survive Maven's forked-process output
        # handling; credentials are removed before the artifact is written.
        report["failure"] = {"stage": stage, "type": type(error).__name__, "message": message[:2000]}
        return 1
    finally:
        report["pageErrors"] = len(errors)
        args.report.write_text(json.dumps(report, indent=2, allow_nan=False), encoding="utf-8")


if __name__ == "__main__":
    raise SystemExit(main())
