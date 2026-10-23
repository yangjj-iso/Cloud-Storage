"""Stateful built-UI contracts for the file manager. Synthetic responses, not a backend test.
Real directory persistence is additionally covered by browser_live.py against Spring/MySQL.
"""
import argparse
import io
import json
import os
from pathlib import Path
import re
import unittest
from urllib.parse import parse_qs, urlsplit
import zipfile

from playwright.sync_api import sync_playwright, expect
from browser_support import serve
from browser_contract import Backend

DATE = '2026-10-24T09:30:00'


class DriveBackend(Backend):
    def __init__(self):
        super().__init__()
        self.user['username'] = 'PICO'
        self.items = []
        for ident, name, directory, parent, size in [
            (1, '项目资料', True, 0, 0), (2, '灵感收集', True, 0, 0),
            (3, 'CloudForge 产品设计.pdf', False, 0, 2480128),
            (4, 'Architecture.md', False, 0, 18240), (5, '品牌视觉.png', False, 0, 1824024),
            (6, '演示录像.mp4', False, 0, 42048128), (7, '设计资源.zip', False, 0, 5148200),
            (8, '接口设计.md', False, 1, 4096), (9, '归档', True, 1, 0),
        ]:
            self.items.append(dict(id=ident, fileId=f'{ident:032x}', fileName=name, isDir=directory,
                                   parentId=parent, fileSize=size, status=1, createdAt=DATE, updatedAt=DATE))
        self.next_id = 10
        self.shared = []
        self.fail_list = False
        self.mutations = []

    def route(self, route):
        request = route.request
        path = urlsplit(request.url).path
        method = request.method
        body = request.post_data_json if request.post_data else None
        def reply(data=None, code=0, status=200):
            route.fulfill(status=status, content_type='application/json', body=json.dumps(dict(code=code, message='synthetic response', data=data)))
        if path == '/api/v1/quota/me':
            active = [item for item in self.items if item['status'] == 1 and not item['isDir']]
            reply(dict(userId=1, totalBytes=20*1024**3, usedBytes=sum(item['fileSize'] for item in active), fileCount=len(active), reservedBytes=0, reservedFileCount=0)); return
        if path == '/api/v1/drive/list':
            if self.fail_list: reply(None, 100500, 503); return
            parent = int(parse_qs(urlsplit(request.url).query).get('parentId', ['0'])[0])
            reply([item for item in self.items if item['parentId'] == parent and item['status'] == 1]); return
        if path == '/api/v1/drive/audit': reply([]); return
        if path == '/api/v1/drive/mkdir':
            if any(item['status'] == 1 and item['parentId'] == body['parentId'] and item['fileName'] == body['name'] for item in self.items): reply(None, 300004, 409); return
            item = dict(id=self.next_id, fileId='', fileName=body['name'], isDir=True, parentId=body['parentId'], fileSize=0, status=1, createdAt=DATE, updatedAt=DATE)
            self.next_id += 1; self.items.append(item); self.mutations.append((path, body)); reply(item); return
        if path in ('/api/v1/drive/rename', '/api/v1/drive/move'):
            item = next(item for item in self.items if item['id'] == body['id'])
            if path.endswith('/rename'): item['fileName'] = body['newName']
            else: item['parentId'] = body['newParentId']
            self.mutations.append((path, body)); reply(); return
        if path == '/api/v1/drive/download-zip':
            payload = io.BytesIO()
            with zipfile.ZipFile(payload, 'w') as archive: archive.writestr('synthetic.txt', 'contract fixture')
            self.mutations.append((path, body)); route.fulfill(status=200, content_type='application/zip', body=payload.getvalue()); return
        if method == 'DELETE' and re.fullmatch(r'/api/v1/drive/\d+', path):
            next(item for item in self.items if item['id'] == int(path.split('/')[-1]))['status'] = 0
            self.mutations.append((path, body)); reply(); return
        if path == '/api/v1/recycle/list': reply([item for item in self.items if item['status'] == 0]); return
        if method == 'POST' and re.fullmatch(r'/api/v1/recycle/\d+/restore', path):
            next(item for item in self.items if item['id'] == int(path.split('/')[-2]))['status'] = 1
            self.mutations.append((path, body)); reply(); return
        if path == '/api/v1/share' and method == 'POST':
            item = next(item for item in self.items if item['id'] == body['userFileId'])
            self.shared.append(dict(shareId='test-share', fileName=item['fileName'], fileSize=item['fileSize'], viewCount=0, expireAt=DATE))
            self.mutations.append((path, body)); reply(dict(shareId='test-share', shareCode='ABCD', shareUrl='/#/s/test-share')); return
        if path == '/api/v1/share/list': reply(dict(records=self.shared, total=len(self.shared), size=50, page=1)); return
        if method == 'DELETE' and path == '/api/v1/share/test-share': self.shared.clear(); reply(); return
        if re.fullmatch(r'/api/v1/file/[0-9a-f]+', path): reply(None, 100500, 503); return
        if '/url' in path or path.startswith('/api/v1/transcode/'): reply(None, 100500, 503); return
        super().route(route)


class DriveContracts(unittest.TestCase):
    screenshots = None
    def setUp(self):
        self.api = DriveBackend()
        self.context = self.browser.new_context(viewport=dict(width=1440, height=1000), service_workers='block')
        self.context.route('**/*', lambda route: self.api.route(route) if urlsplit(route.request.url).path.startswith('/api/') and urlsplit(route.request.url).netloc == urlsplit(self.base).netloc else route.continue_() if urlsplit(route.request.url).netloc == urlsplit(self.base).netloc else route.abort())
        self.page = self.context.new_page(); self.page.set_default_timeout(7000)
        self.errors = []; self.page.on('pageerror', lambda error: self.errors.append(str(error)))
        self.addCleanup(self.context.close)
        self.page.goto(self.base)
        if self.screenshots: self.page.screenshot(path=str(self.screenshots / 'login-desktop.png'), full_page=True)
        self.page.get_by_label('邮箱或用户名').fill('synthetic')
        self.page.get_by_label('密码', exact=True).fill('Synthetic-only-pass-46!')
        self.page.locator('form button[type=submit]').click()
        expect(self.page.get_by_role('heading', name='上传中心', exact=True)).to_be_visible()
        if self.screenshots: self.page.screenshot(path=str(self.screenshots / 'upload-desktop.png'), full_page=True)
        self.navigate('我的云盘')

    def tearDown(self):
        self.assertFalse(self.api.unknown, self.api.unknown)
        self.assertFalse(self.errors, self.errors)

    def navigate(self, label):
        if self.page.viewport_size['width'] < 768:
            self.page.get_by_role('button', name='打开导航', exact=True).click()
            self.page.get_by_role('navigation', name='移动端页面导航').get_by_role('button', name=label, exact=True).click()
        else: self.page.get_by_role('navigation', name='主导航').get_by_role('button', name=label, exact=True).click()

    def menu(self, name, action):
        self.page.get_by_role('button', name='操作 ' + name, exact=True).click()
        self.page.locator('.file-menu').get_by_role('button', name=action, exact=True).click()

    def mkdir(self, name):
        self.page.get_by_role('button', name='新建文件夹', exact=True).click()
        dialog = self.page.get_by_role('dialog', name='新建文件夹', exact=True)
        dialog.get_by_label('文件夹名称', exact=True).fill(name)
        dialog.get_by_role('button', name='创建', exact=True).click()
        return dialog

    def test_folder_lifecycle_and_conflict_preserves_input(self):
        self.mkdir('新的资料')
        expect(self.page.get_by_role('button', name='新的资料 文件夹', exact=True)).to_be_visible()
        self.menu('新的资料', '重命名')
        dialog = self.page.get_by_role('dialog', name='重命名', exact=True)
        dialog.get_by_label('文件名称').fill('已整理资料')
        dialog.get_by_role('button', name='保存', exact=True).click()
        self.menu('已整理资料', '移动')
        dialog = self.page.get_by_role('dialog', name='移动文件', exact=True)
        expect(dialog.get_by_role('button', name='已整理资料', exact=True)).to_have_count(0)
        dialog.get_by_role('button', name='项目资料', exact=True).click()
        dialog.get_by_role('button', name='移动到此处', exact=True).click()
        expect(self.page.get_by_role('button', name='操作 已整理资料', exact=True)).to_have_count(0)
        self.page.get_by_role('button', name='项目资料 文件夹', exact=True).click()
        expect(self.page.get_by_role('button', name='操作 已整理资料', exact=True)).to_be_visible()
        dialog = self.mkdir('已整理资料')
        expect(dialog.get_by_role('alert')).to_be_visible()
        expect(dialog.get_by_label('文件夹名称')).to_have_value('已整理资料')
        self.page.keyboard.press('Escape')
        self.menu('已整理资料', '移入回收站')
        self.page.get_by_role('dialog').get_by_role('button', name='移入回收站', exact=True).click()
        self.navigate('回收站')
        self.page.get_by_role('button', name='还原 已整理资料', exact=True).click()
        expect(self.page.get_by_text('回收站是空的', exact=True)).to_be_visible()
        self.assertEqual(1, next(item for item in self.api.items if item['fileName'] == '已整理资料')['status'])
        moves = [body for path, body in self.api.mutations if path.endswith('/move')]
        self.assertEqual(1, moves[0]['newParentId'])

    def test_breadcrumb_search_filters_sort_grid_and_selection_scope(self):
        self.page.get_by_label('搜索当前目录').fill('Architecture')
        expect(self.page.locator('.drive-file')).to_have_count(1)
        self.page.get_by_role('button', name='清除搜索', exact=True).click()
        self.page.get_by_role('region', name='当前目录分类').get_by_role('button', name=re.compile('图片')).click()
        expect(self.page.locator('.drive-file')).to_have_count(1)
        self.page.get_by_role('button', name='清除类型筛选').click()
        self.page.get_by_label('排序方式').select_option('name')
        self.page.get_by_role('button', name='网格视图').click()
        expect(self.page.locator('.drive-items.grid')).to_be_visible()
        self.page.get_by_label('选择 Architecture.md', exact=True).check()
        self.page.get_by_role('button', name='项目资料 文件夹', exact=True).click()
        expect(self.page.locator('.selection-toolbar')).to_have_count(0)
        self.page.get_by_role('button', name='归档 文件夹', exact=True).click()
        self.page.get_by_role('navigation', name='文件夹路径').get_by_role('button', name='项目资料', exact=True).click()
        expect(self.page.get_by_role('button', name='操作 接口设计.md')).to_be_visible()
        self.page.get_by_role('navigation', name='文件夹路径').get_by_role('button', name='我的云盘', exact=True).click()
        expect(self.page.get_by_role('button', name='操作 Architecture.md')).to_be_visible()

    def test_share_cancel_and_selected_zip(self):
        self.menu('Architecture.md', '分享')
        dialog = self.page.get_by_role('dialog', name='分享已创建')
        expect(dialog.get_by_label('分享链接')).to_have_value(re.compile(r'/#/s/test-share\?code=ABCD$'))
        self.page.keyboard.press('Escape')
        self.page.get_by_label('选择 Architecture.md', exact=True).check()
        with self.page.expect_download() as download:
            self.page.get_by_role('button', name='打包下载', exact=True).click()
        self.assertTrue(download.value.suggested_filename.endswith('.zip'))
        self.navigate('我的分享')
        self.page.get_by_role('button', name='取消分享 Architecture.md', exact=True).click()
        self.page.get_by_role('dialog').get_by_role('button', name='取消分享', exact=True).click()
        expect(self.page.get_by_text('还没有分享链接', exact=True)).to_be_visible()
        self.assertEqual([], self.api.shared)

    def test_load_failure_retry_and_detail_failure(self):
        self.api.fail_list = True
        self.page.get_by_role('button', name='刷新目录', exact=True).click()
        expect(self.page.get_by_text('暂时无法读取文件', exact=True)).to_be_visible()
        self.api.fail_list = False
        self.page.get_by_role('button', name='重试', exact=True).click()
        self.page.get_by_role('button', name='Architecture.md 文档', exact=True).click()
        expect(self.page.get_by_role('button', name='重试', exact=True)).to_be_visible()

    def test_global_search_mobile_navigation_and_touch_actions(self):
        if self.screenshots: self.page.screenshot(path=str(self.screenshots / 'drive-desktop.png'), full_page=True)
        self.page.set_viewport_size(dict(width=390, height=844))
        expect(self.page.get_by_role('button', name='操作 Architecture.md')).to_be_visible()
        if self.screenshots: self.page.screenshot(path=str(self.screenshots / 'drive-mobile.png'), full_page=True)
        self.assertTrue(self.page.evaluate('document.documentElement.scrollWidth <= innerWidth'))
        self.menu('Architecture.md', '移动')
        expect(self.page.get_by_role('dialog')).to_be_visible()
        self.assertTrue(self.page.get_by_role('dialog').evaluate('el => el.scrollWidth <= el.clientWidth + 1'))
        self.page.keyboard.press('Escape')
        self.navigate('我的分享'); expect(self.page.get_by_role('heading', name=re.compile('我的分享'))).to_be_visible()
        self.navigate('上传中心'); expect(self.page.get_by_role('button', name='选择上传文件', exact=True)).to_be_visible()
        self.page.get_by_role('button', name='搜索知识资料', exact=True).click()
        expect(self.page.get_by_label('搜索已解析文档正文')).to_be_visible()
        self.assertFalse(any(path.endswith('/search') for _, path, _ in self.api.calls))


def main():
    parser = argparse.ArgumentParser(); parser.add_argument('--dist', type=Path, required=True); parser.add_argument('--report', type=Path, required=True); parser.add_argument('--screenshots', type=Path)
    args = parser.parse_args()
    if args.screenshots: args.screenshots.mkdir(parents=True, exist_ok=True)
    report = dict(schemaVersion=1, mode='drive-browser-contract', passed=False, tests=0, commit=os.environ.get('ACCEPTANCE_SHA', 'local'))
    args.report.parent.mkdir(parents=True, exist_ok=True); args.report.write_text(json.dumps(report))
    try:
        with serve(args.dist) as base, sync_playwright() as pw:
            browser = pw.chromium.launch(); DriveContracts.browser, DriveContracts.base, DriveContracts.screenshots = browser, base, args.screenshots
            result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(DriveContracts))
            report.update(passed=result.wasSuccessful(), tests=result.testsRun, failures=len(result.failures)+len(result.errors), skipped=len(result.skipped)); browser.close()
        return 0 if report['passed'] else 1
    finally: args.report.write_text(json.dumps(report, indent=2))

if __name__ == '__main__': raise SystemExit(main())
