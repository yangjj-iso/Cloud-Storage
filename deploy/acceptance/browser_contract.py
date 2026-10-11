"""Built-UI browser contracts with synthetic API responses, NOT a backend or provider test."""
import argparse
import copy
import json
import os
from pathlib import Path
import re
import unittest
from urllib.parse import urlsplit

from playwright.sync_api import sync_playwright, expect
from browser_support import serve

K = '/api/v1/knowledge'
DATE = '2099-01-01T00:00:00'


class Backend:
    def __init__(self):
        self.user = {'id': 1, 'username': 'synthetic', 'role': 'user'}
        self.calls, self.unknown = [], []
        self.fail_execute = False
        self.deny_detail = False
        self.steps = [{'stepNo': i, 'moveProposalId': f'move-{i}', 'userFileId': str(100 + i),
                       'fileName': f'Document-{i}.txt', 'destinationName': 'Archive',
                       'expiresAt': DATE, 'state': 'PENDING'} for i in (1, 2)]
        self.cancelled = False
        self.evidence = [{'userFileId': str(100 + i), 'fileName': f'Document-{i}.txt',
                          'generation': 1, 'chunkIndex': 0, 'startOffset': 0, 'endOffset': 40,
                          'content': 'M46 synthetic contract evidence.', 'status': 'READY'} for i in (1, 2)]
        self.proposals = [{'id': f'move-{i}', 'userFileId': str(100 + i), 'fileName': f'Document-{i}.txt',
                           'sourceParentId': 0, 'destinationId': 200, 'destinationName': 'Archive',
                           'expiresAt': DATE, 'status': 'PENDING'} for i in (1, 2)]

    def workflow(self):
        finished = {'APPLIED', 'SKIPPED', 'REVERTED'}
        pending = sum(s['state'] not in finished for s in self.steps)
        return {'summary': {'id': 'plan-1', 'status': 'CANCELLED' if self.cancelled else 'ACTIVE' if pending else 'COMPLETED',
                            'total': len(self.steps), 'applied': sum(s['state'] == 'APPLIED' for s in self.steps),
                            'pending': pending, 'skipped': sum(s['state'] == 'SKIPPED' for s in self.steps), 'createdAt': DATE},
                'steps': copy.deepcopy(self.steps), 'events': []}

    def count(self, suffix):
        return sum(method == 'POST' and path.endswith(suffix) for method, path, _ in self.calls)

    def route(self, route):
        request = route.request
        path = urlsplit(request.url).path
        method = request.method
        body = request.post_data_json if request.post_data else None
        self.calls.append((method, path, body))
        def reply(data=None, status=200, code=0):
            route.fulfill(status=status, content_type='application/json',
                          body=json.dumps({'code': code, 'message': 'synthetic response', 'data': data}))
        if method == 'POST' and path == '/api/v1/auth/login':
            reply({'token': 'synthetic-only-token', 'tokenType': 'Bearer', 'expiresInSeconds': 3600, 'user': self.user}); return
        if method == 'GET' and path == '/api/v1/auth/me': reply(self.user); return
        if method == 'GET' and path == '/api/v1/quota/me':
            reply({'userId': 1, 'totalBytes': 1000000, 'usedBytes': 80, 'fileCount': 2, 'reservedBytes': 0, 'reservedFileCount': 0}); return
        if method == 'GET' and path in ('/api/v1/file', '/api/v1/share/list'):
            reply({'records': [], 'total': 0, 'size': 20, 'page': 1}); return
        if method == 'GET' and path in ('/api/v1/drive/list', '/api/v1/recycle/list'):
            reply([]); return
        if method == 'GET' and path == '/api/v1/ping': reply({'status': 'UP'}); return
        if method == 'GET' and path == K + '/documents':
            reply({'documents': [{**e, 'mimeType': 'text/plain', 'contentChars': 40, 'chunkCount': 1, 'reason': None}
                                 for e in self.evidence], 'hasMore': False, 'page': 1}); return
        if method == 'GET' and path in (K + '/assistant/capabilities', K + '/semantic/capabilities', K + '/assistant/rename/capabilities'):
            reply({'enabled': False, 'retrievalMode': 'SELECTED_KEYWORD_EVIDENCE', 'operation': 'RENAME'}); return
        if method == 'GET' and path == K + '/assistant/move/capabilities': reply({'enabled': True, 'operation': 'MOVE'}); return
        if method == 'GET' and path == K + '/assistant/usage':
            counter = {'used': 0, 'limit': 50, 'remaining': 50, 'reportedCalls': 0, 'inputTokens': 0, 'outputTokens': 0, 'estimatedUsd': None}
            reply({'dayUtc': '2026-10-11', 'chat': counter, 'embedding': counter}); return
        if method == 'GET' and path == K + '/semantic/health':
            reply({'state': 'EMBEDDING_DISABLED', 'readyDocuments': 0, 'mirroredDocuments': 0, 'pendingDocuments': 0, 'qdrantReachable': False}); return
        if method == 'GET' and path in (K + '/assistant/move/recent', K + '/assistant/rename/recent',
                                       K + '/organize/templates', K + '/assistant/strategy/recommendations',
                                       K + '/assistant/strategy/consent-history', K + '/assistant/strategy/feedback-reviews'):
            reply([]); return
        if method == 'GET' and path == K + '/assistant/strategy/recommendation-policy':
            reply({'recommendationsAllowed': True, 'explicitChoice': False}); return
        if method == 'GET' and path == K + '/assistant/strategy/quality':
            reply({'accepted': 0, 'rejected': 0, 'unrated': 0, 'acceptanceRatio': None, 'window': 'synthetic', 'note': 'synthetic'}); return
        if method == 'GET' and path == K + '/assistant/workflows': reply([self.workflow()['summary']]); return
        if method == 'GET' and path == K + '/assistant/workflows/plan-1':
            reply(None, 401, 100002) if self.deny_detail else reply(self.workflow()); return
        match = re.fullmatch(re.escape(K) + r'/assistant/workflows/plan-1/steps/(\d+)/(approve|execute|undo|skip)', path)
        if method == 'POST' and match:
            number, action = int(match[1]), match[2]
            assert body == {'confirmed': True}
            self.steps[number - 1]['state'] = {'approve': 'APPROVED', 'execute': 'APPLIED', 'undo': 'REVERTED', 'skip': 'SKIPPED'}[action]
            if action == 'execute' and self.fail_execute:
                self.fail_execute = False; reply(None, 503, 100500)
            else: reply(self.workflow())
            return
        if method == 'POST' and path == K + '/assistant/workflows/plan-1/cancel':
            self.cancelled = True; reply(self.workflow()); return
        if method == 'POST' and path == K + '/search':
            reply({'evidence': self.evidence, 'hasMore': False, 'mode': 'LITERAL_KEYWORD'}); return
        if method == 'GET' and path == K + '/assistant/move/folders':
            reply({'folders': [{'id': 200, 'name': 'Archive', 'parentId': 0}, {'id': 201, 'name': 'Backup', 'parentId': 0}], 'hasMore': False}); return
        if method == 'POST' and path == K + '/assistant/move/batch/suggest':
            assert body['consent'] is True
            chosen = [p for p in self.proposals if p['userFileId'] in {s['userFileId'] for s in body['sources']}]
            reply({'items': [{'source': {'userFileId': p['userFileId'], 'generation': 1, 'chunkIndex': 0},
                               'proposal': copy.deepcopy(p), 'status': 'READY'} for p in chosen], 'proposed': len(chosen), 'skipped': 0}); return
        if method == 'POST' and re.fullmatch(re.escape(K) + r'/assistant/move/move-\d+/apply', path):
            proposal = next(p for p in self.proposals if '/' + p['id'] + '/' in path)
            proposal['status'] = 'APPLIED'
            reply({'proposalId': proposal['id'], 'userFileId': proposal['userFileId'], 'destinationId': 200, 'status': 'APPLIED'}); return
        if method == 'POST' and path == K + '/assistant/workflows':
            assert body['confirmed'] is True
            self.steps = [s for s in self.steps if s['moveProposalId'] in body['moveProposalIds']]
            for i, step in enumerate(self.steps, 1): step['stepNo'] = i
            reply(self.workflow()); return
        self.unknown.append(method + ' ' + path)
        reply(None, 501, 100500)


class BrowserContracts(unittest.TestCase):
    browser = None
    base = ''
    unknown = []

    def setUp(self):
        self.api = Backend()
        self.context = self.browser.new_context(viewport={'width': 1440, 'height': 1000}, service_workers='block')
        self.context.route('**/*', lambda r: self.api.route(r) if urlsplit(r.request.url).path.startswith('/api/')
                           and urlsplit(r.request.url).netloc == urlsplit(self.base).netloc else r.continue_()
                           if urlsplit(r.request.url).netloc == urlsplit(self.base).netloc else r.abort())
        self.page = self.context.new_page()
        self.errors = []
        self.page.on('pageerror', lambda e: self.errors.append(str(e)))
        self.accept_dialog = True
        self.page.on('dialog', lambda d: d.accept() if self.accept_dialog else d.dismiss())
        self.page.set_default_timeout(8000)
        self.addCleanup(self.context.close)
        self.page.goto(self.base)
        self.page.get_by_label('邮箱或用户名').fill('synthetic')
        self.page.get_by_label('密码', exact=True).fill('Synthetic-only-pass-46!')
        self.page.locator('form button[type="submit"]').click()
        self.enter_workbench()

    def enter_workbench(self):
        # Login and reload land in the upload center. Exercise the visible entry,
        # rather than injecting client state or assuming a different default page.
        self.page.get_by_role('button', name=re.compile('前往 AI 智能工作台')).click()
        self.nav = self.page.get_by_role('navigation', name='AI 工作台导航')
        expect(self.nav).to_be_visible()

    def tearDown(self):
        self.unknown.extend(self.api.unknown)
        self.assertFalse(self.api.unknown, f'Unhandled contract requests: {self.api.unknown}')
        self.assertFalse(self.errors, f'Browser errors: {self.errors}')

    def plan(self):
        self.nav.get_by_role('button', name=re.compile('整理记录')).click()
        panel = self.page.get_by_role('region', name='受控 AI Agent 工作流')
        panel.get_by_role('button', name=re.compile('2 步 · 进行中')).click()
        expect(panel.get_by_role('button', name='审核批准本项', exact=True)).to_be_enabled()
        return panel

    def batch(self):
        self.nav.get_by_role('button', name=re.compile('搜索与问答')).click()
        self.page.get_by_label('搜索已解析文档正文').fill('M46')
        self.page.get_by_role('button', name=re.compile('检索正文')).click()
        panel = self.page.get_by_role('region', name='AI 批量整理预览')
        files = panel.get_by_role('group', name='选择参与整理的文件')
        files.get_by_label('Document-1.txt', exact=True).check()
        files.get_by_label('Document-2.txt', exact=True).check()
        panel.get_by_role('group', name='选择目标文件夹候选').get_by_label(re.compile('Archive')).check()
        return panel

    def test_approval_never_executes_and_only_one_step_advances(self):
        panel = self.plan()
        panel.get_by_role('button', name='审核批准本项', exact=True).click()
        expect(panel.get_by_role('button', name='执行本项移动', exact=True)).to_be_enabled()
        self.assertEqual(0, self.api.count('/execute'))
        self.assertEqual('PENDING', self.api.steps[1]['state'])
        panel.get_by_role('button', name='执行本项移动', exact=True).click()
        expect(panel.get_by_text('仅当前步骤执行成功；后续步骤仍需要独立批准。', exact=True)).to_be_visible()
        self.assertEqual(1, self.api.count('/execute'))
        self.assertEqual('PENDING', self.api.steps[1]['state'])
        expect(panel.get_by_role('progressbar', name='整理计划处理进度')).to_have_attribute('value', '1')

    def test_rapid_duplicate_click_has_one_mutation(self):
        panel = self.plan()
        panel.get_by_role('button', name='审核批准本项', exact=True).evaluate('(button) => { button.click(); button.click(); }')
        expect(panel.get_by_role('button', name='执行本项移动', exact=True)).to_be_enabled()
        self.assertEqual(1, self.api.count('/approve'))

    def test_dismissed_confirmation_does_not_write(self):
        panel = self.plan(); self.accept_dialog = False
        panel.get_by_role('button', name='审核批准本项', exact=True).click()
        expect(panel.get_by_role('button', name='审核批准本项', exact=True)).to_be_enabled()
        self.assertEqual(0, self.api.count('/approve'))
        self.assertEqual('PENDING', self.api.steps[0]['state'])

    def test_uncertain_write_requires_authoritative_refresh(self):
        panel = self.plan()
        panel.get_by_role('button', name='审核批准本项', exact=True).click()
        self.api.fail_execute = True
        panel.get_by_role('button', name='执行本项移动', exact=True).click()
        expect(panel.get_by_role('alert')).to_contain_text('请先刷新计划')
        expect(panel.get_by_role('button', name='执行本项移动', exact=True)).to_be_disabled()
        self.assertEqual(1, self.api.count('/execute'))
        panel.get_by_role('button', name=re.compile('刷新计划')).click()
        expect(panel.get_by_role('button', name='尝试条件撤销', exact=True)).to_be_enabled()
        self.assertEqual(1, self.api.count('/execute'))
        self.assertEqual('PENDING', self.api.steps[1]['state'])

    def test_cancel_is_not_rollback_and_history_survives_reload(self):
        panel = self.plan()
        panel.get_by_role('button', name='审核批准本项', exact=True).click()
        panel.get_by_role('button', name='执行本项移动', exact=True).click()
        panel.get_by_role('button', name='取消剩余计划', exact=True).click()
        expect(panel.get_by_text('计划已取消。已执行的移动不会自动恢复。', exact=True)).to_be_visible()
        self.assertEqual('APPLIED', self.api.steps[0]['state'])
        self.page.reload()
        self.enter_workbench()
        self.nav.get_by_role('button', name=re.compile('整理记录')).click()
        panel.get_by_role('button', name=re.compile('2 步 · 已取消')).click()
        expect(panel.get_by_role('button', name='尝试条件撤销', exact=True)).to_be_enabled()
        self.page.set_viewport_size({'width': 390, 'height': 844})
        self.assertTrue(panel.evaluate('el => el.scrollWidth <= el.clientWidth + 1'))

    def test_expired_auth_discards_private_plan(self):
        panel = self.plan(); self.api.deny_detail = True
        panel.get_by_role('button', name=re.compile('刷新计划')).click()
        expect(self.page.get_by_label('邮箱或用户名')).to_be_visible()
        expect(self.page.get_by_role('region', name='受控 AI Agent 工作流')).to_have_count(0)
        self.assertIsNone(self.page.evaluate("localStorage.getItem('cloudchunk.auth')"))

    def test_batch_candidate_change_consumes_consent(self):
        panel = self.batch()
        consent = panel.get_by_label(re.compile('本次同意把已选文件'))
        generate = panel.get_by_role('button', name='生成批量预览（不执行）', exact=True)
        expect(generate).to_be_disabled(); consent.check()
        expect(generate).to_be_enabled()
        panel.get_by_role('group', name='选择目标文件夹候选').get_by_label(re.compile('Backup')).check()
        expect(consent).not_to_be_checked(); expect(generate).to_be_disabled()
        self.assertEqual(0, self.api.count('/batch/suggest'))

    def test_batch_saves_only_not_yet_executed_proposals(self):
        panel = self.batch()
        panel.get_by_label(re.compile('本次同意把已选文件')).check()
        panel.get_by_role('button', name='生成批量预览（不执行）', exact=True).click()
        items = panel.locator('.kw-batch-preview-item')
        expect(items).to_have_count(2)
        items.first.get_by_label('我已核对本项来源与目标', exact=True).check()
        items.first.get_by_role('button', name='批准并执行本项移动', exact=True).click()
        expect(items.first.get_by_role('button', name='尝试撤销本项', exact=True)).to_be_enabled()
        panel.get_by_role('button', name=re.compile('保存未执行建议为计划（1 项，不执行）')).click()
        expect(panel.get_by_role('region', name='受控 AI Agent 工作流')).to_be_visible()
        saved = [body for method, path, body in self.api.calls if method == 'POST' and path == K + '/assistant/workflows']
        self.assertEqual(['move-2'], saved[-1]['moveProposalIds'])
        self.assertEqual(1, self.api.count('/apply'))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--dist', type=Path, required=True)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    report = {'schemaVersion': 1, 'mode': 'browser-contract', 'passed': False,
              'commit': os.environ.get('ACCEPTANCE_SHA', os.environ.get('GITHUB_SHA', 'local')),
              'tests': 0, 'failures': 0, 'skipped': 0, 'unknownRequests': 0}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report), encoding='utf-8')
    try:
        with serve(args.dist) as base, sync_playwright() as pw:
            browser = pw.chromium.launch()
            BrowserContracts.browser, BrowserContracts.base = browser, base
            result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(BrowserContracts))
            report.update(tests=result.testsRun, failures=len(result.failures) + len(result.errors), skipped=len(result.skipped),
                          unknownRequests=len(BrowserContracts.unknown), passed=result.wasSuccessful() and not BrowserContracts.unknown)
            browser.close()
        return 0 if report['passed'] else 1
    finally:
        args.report.write_text(json.dumps(report, indent=2), encoding='utf-8')


if __name__ == '__main__':
    raise SystemExit(main())
