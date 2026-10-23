# CloudForge 文件空间

CloudForge 是本仓库的用户界面名称。Java 包名、Maven 模块、API、配置前缀、数据库迁移与浏览器会话键继续使用 `cloudchunk`，已有部署无需迁移。

## 前端架构

| 层次 | 路径 | 职责 |
| --- | --- | --- |
| 应用装配 | `src/App.tsx` | 认证入口、公开分享路由、按需加载页面、页面错误边界 |
| 工作空间 | `components/Sidebar.tsx`、`Topbar.tsx` | 桌面与移动导航、账号入口、全局知识搜索 |
| 导航契约 | `lib/navigation.ts` | 唯一页面列表、页面标题和品牌名称 |
| 文件业务 | `features/drive/` | 目录浏览生命周期、过期请求隔离、文件外观、目标目录选择、下载保存 |
| 界面基础 | `components/ui/` | 本地 SVG、品牌、键盘可操作的对话框 |
| 数据访问 | `lib/api.ts`、`store/` | 既有 REST 契约与分域状态、上传任务、认证、文件和配额 |
| 视觉系统 | `styles/product.css`、`tailwind.config.js` | 统一颜色、间距、控件、桌面/手机布局、减少动画偏好 |

网盘目录状态由 `useDriveBrowser` 持有：进入子目录或点击面包屑后，查询指定父目录；旧目录请求晚到时不能覆盖新目录；卸载后不再更新状态。切换目录会清空所选文件，避免在另一目录打包隐藏选项。分类数量和搜索范围均为当前目录，不冒充全盘统计。

移动操作使用独立 `FolderPicker` 浏览真实目录，不能选择自身，并保持后端权限、循环关系与同名冲突检查。创建和重命名失败保留输入；删除先确认；分享保留提取码。对话框支持 Tab 焦点循环、Escape 关闭和焦点恢复。手机上的文件操作按钮始终可见。

AI 工作台的模型授权、审核和执行分离规则保持不变。取消了登录页尚未实现的 Google、SSO、条款和隐私政策按钮。图标随前端构建，不再依赖 Google 图标字体。AI 和管理员页面按需加载，普通上传页面无需先下载这些功能代码。

## 验证与运行

```bash
cd cloudchunk-web
npm ci
npm test
npm run build
cd ..
python3 -m pip install -r deploy/acceptance/requirements.txt
python3 -m playwright install --with-deps chromium
python3 deploy/acceptance/browser_drive.py \
  --dist cloudchunk-web/dist \
  --report artifacts/m46/drive-contract.json \
  --screenshots artifacts/m46/screenshots
```

`browser_drive.py` 是明确使用合成响应的前端交互测试：涵盖目录导航、冲突、移动请求、回收站还原、分享取消、ZIP 下载、筛选、排序、选择范围、加载错误恢复和手机操作。截图使用合成文件，不含用户文件。

`browser_live.py` 在真实 Spring HTTP / MySQL / Redis 验收中增加“创建文件夹 → 重命名 → 移动 → 回收 → 还原 → 刷新确认持久化”的流程；`budget_gate.py` 强制要求此流程的证据。它与原有 AI 审核验收及独立上传 Worker 验收一起由 PR 的 release gate 执行。不能使用前端合成测试报告代替真实后端通过记录。

本地完整启动继续使用：

```bash
cp deploy/.env.example deploy/.env
# 按实际环境配置 deploy/.env
docker compose --profile app -f deploy/docker-compose.yml --env-file deploy/.env up -d --build
```

AI 模型属于可选配置；未配置时文件管理、上传、分享与关键词检索仍按既有服务实现工作。测试不向真实模型提供方发送文件。

## 界面

截图来自真实构建后的 Chromium，使用合成 API 响应与文件，不代表生产账号数据。

- [登录页](./screenshots/login-desktop.png)
- [上传中心](./screenshots/upload-desktop.png)
- [桌面云盘](./screenshots/drive-desktop.png)
- [手机云盘](./screenshots/drive-mobile.png)
