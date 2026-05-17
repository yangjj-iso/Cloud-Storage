# 纯小白如何在 GitHub 上贡献自己的代码？——从零到第一个 PR 的完整指南

> 很多新手觉得 GitHub 是大佬的专属地，自己代码写得烂，不敢提交。其实 GitHub 的本质就是"代码仓库 + 社区"，任何人都可以贡献代码，哪怕你只是改了一个错别字。
>
> 本文从一个完全零基础的小白视角出发，手把手教你：从注册账号到提交第一个 PR，每一步都有截图级的说明。

---

## 一、为什么要往 GitHub 上贡献代码？

### 1.1 不是为了炫技，而是为了成长

| 好处 | 说明 |
|------|------|
| **提升技术** | 读别人的代码，比写自己的代码学得更快 |
| **简历加分** | GitHub 绿墙（contribution graph）是面试官最看重的指标之一 |
| **社区认可** | 你的 PR 被 merge 了，说明你的代码被认可了 |
| **积累人脉** | 开源社区的 maintainer 都是行业大佬，混个脸熟 |
| **真实项目经验** | 比自己写 demo 更有含金量，面试时可以讲 |

### 1.2 误区：我代码写得烂，不敢贡献

- **真相**：开源项目最需要的不是完美的代码，而是**愿意贡献的人**
- **事实**：很多 PR 就是改一个错别字、修一个文档、补一个测试用例
- **记住**：**所有大佬都是从第一次提交开始的**

---

## 二、准备工作：注册 GitHub 账号 + 配置环境

### 2.1 注册 GitHub 账号

1. 打开 https://github.com
2. 点击右上角 **Sign up**
3. 填写用户名、邮箱、密码
4. 验证邮箱（重要！不验证无法提交代码）

### 2.2 安装 Git

**Windows**：
- 下载 https://git-scm.com/download/win
- 一路 Next，安装完成后右键菜单出现 **Git Bash Here**

**Mac**：
```bash
brew install git
```

**Linux**：
```bash
sudo apt install git    # Ubuntu/Debian
sudo yum install git    # CentOS
```

### 2.3 配置 Git 用户信息

```bash
# 配置用户名（GitHub 用户名）
git config --global user.name "你的用户名"

# 配置邮箱（GitHub 注册邮箱）
git config --global user.email "你的邮箱@example.com"

# 查看配置是否生效
git config --list
```

### 2.4 配置 SSH Key（免密推送）

```bash
# 生成 SSH Key
ssh-keygen -t ed25519 -C "你的邮箱@example.com"

# 一路回车，使用默认路径
# 查看公钥
cat ~/.ssh/id_ed25519.pub
```

复制公钥内容，到 GitHub → Settings → SSH and GPG keys → New SSH key，粘贴保存。

验证：
```bash
ssh -T git@github.com
# 输出：Hi xxx! You've successfully authenticated... 说明成功
```

---

## 三、核心概念：5 分钟搞懂 GitHub 的工作流

在动手之前，先搞懂这几个概念，不然你后面每一步都会懵：

### 3.1 仓库（Repository）

仓库就是一个项目的"文件夹"，里面存放所有代码、文档、配置。

```
仓库 = 项目文件夹 + 版本历史
```

### 3.2 Fork 和 Clone 的区别

| 操作 | 干了什么 | 类比 |
|------|---------|------|
| **Fork** | 在你的 GitHub 账号下复制一份仓库 | 把别人的课本复印一份，自己可以在上面写笔记 |
| **Clone** | 把仓库下载到本地电脑 | 把复印的课本拿回家 |

### 3.3 分支（Branch）

```
main（主分支）───→ 稳定代码，随时可以发布
  │
  └── feature/fix-typo（功能分支）───→ 你在改的代码，不影响主分支
```

### 3.4 提交（Commit）

```
commit = 一次修改的快照
```

每次 commit 都要写一条**提交信息**，说明你改了什么。

### 3.5 Pull Request（PR）

```
PR = "我改好了，请把我的代码合并进去"
```

这是你贡献代码的核心方式：你改完代码，提交 PR，项目维护者审核后 merge。

---

## 四、完整工作流：从 Fork 到 PR 的 6 步

### 第 1 步：Fork 原始仓库

1. 打开你想贡献的项目页面（比如 `https://github.com/xxx/cloudchunk`）
2. 点击右上角 **Fork** 按钮
3. 等待几秒，你的账号下就多了一份仓库副本

### 第 2 步：Clone 你 Fork 的仓库到本地

```bash
# Clone 你自己的仓库（注意是你的用户名，不是原作者的）
git clone git@github.com:你的用户名/cloudchunk.git

# 进入项目目录
cd cloudchunk
```

### 第 3 步：添加上游仓库（重要！）

```bash
# 添加原始仓库为 upstream，方便同步最新代码
git remote add upstream git@github.com:原作者/cloudchunk.git

# 查看远程仓库配置
git remote -v
# origin    git@github.com:你的用户名/cloudchunk.git (fetch)
# origin    git@github.com:你的用户名/cloudchunk.git (push)
# upstream  git@github.com:原作者/cloudchunk.git (fetch)
# upstream  git@github.com:原作者/cloudchunk.git (push)
```

为什么要加 upstream？→ 原作者一直在更新代码，你需要同步最新代码，避免冲突。

### 第 4 步：创建功能分支

```bash
# 先同步最新代码
git checkout main
git pull upstream main

# 创建功能分支（分支名要语义化）
git checkout -b fix/fix-readme-typo
```

**分支命名规范**（Conventional Commits）：

| 类型 | 前缀 | 示例 |
|------|------|------|
| 修 Bug | `fix/` | `fix/fix-upload-error` |
| 新功能 | `feat/` | `feat/add-progress-bar` |
| 改文档 | `docs/` | `docs/fix-readme-typo` |
| 改样式 | `style/` | `style/fix-css-indent` |
| 重构 | `refactor/` | `refactor/simplify-upload` |

### 第 5 步：修改代码 + 提交

```bash
# 修改代码（比如改 README.md 里的一个错别字）
vim README.md

# 查看改了什么
git diff

# 暂存修改
git add README.md

# 提交（commit message 要规范）
git commit -m "docs: fix typo in README.md"
```

**Commit Message 规范**（Conventional Commits）：

```
<type>(<scope>): <subject>

type:   feat / fix / docs / style / refactor / test / chore
scope:  可选，影响范围
subject: 简短描述
```

示例：
```bash
git commit -m "docs: fix typo in README.md"
git commit -m "feat(upload): add progress bar for chunk upload"
git commit -m "fix(merge): fix merge lock timeout issue"
```

### 第 6 步：推送到远程 + 提交 PR

```bash
# 推送到你的 Fork 仓库
git push origin fix/fix-readme-typo
```

然后到 GitHub 网页上：
1. 打开你 Fork 的仓库页面
2. GitHub 会自动提示 **"Compare & pull request"**，点击它
3. 填写 PR 标题和描述（描述清楚你改了什么、为什么改）
4. 点击 **Create pull request**

---

## 五、PR 模板：怎么写一个让 maintainer 愿意 merge 的 PR？

### 5.1 标题要清晰

```
❌ fix bug
✅ fix(upload): fix chunk MD5 mismatch when network unstable
```

### 5.2 描述要完整

```markdown
## 改了什么

- 修复了上传分片时 MD5 校验失败后，错误分片没有异步删除的问题

## 为什么要改

- 之前 MD5 不匹配时，错误分片留在 MinIO 里，占用存储空间
- 用户重传时，可能因为 MinIO 里已有同名分片导致 Compose 失败

## 怎么测试

1. 模拟一个分片上传，MD5 校验失败
2. 检查 MinIO 中错误分片是否被删除
3. 重传同一分片，验证可以正常上传

## 关联 Issue

Closes #42
```

### 5.3 PR 要点清单

| 要点 | 说明 |
|------|------|
| ✅ 改动小而聚焦 | 一个 PR 只做一件事 |
| ✅ Commit Message 规范 | feat/fix/docs 前缀 |
| ✅ 描述清楚 | What / Why / How |
| ✅ 关联 Issue | Closes #xx |
| ✅ 代码格式 | 遵循项目的代码风格 |
| ✅ 不引入无关修改 | 不要混入其他改动 |

---

## 六、小白适合贡献什么？——从简单到难

### 6.1 Level 1：改文档（零代码门槛）

**适合人群**：完全不会写代码，但能看懂英文文档

- 修复 README.md 中的错别字
- 补充缺失的文档
- 翻译文档（中文/英文）
- 修正示例代码中的错误

```bash
# 找到文档中的错别字
vim docs/01-architecture.md

# 提交
git add docs/01-architecture.md
git commit -m "docs: fix typo in architecture guide"
```

### 6.2 Level 2：补测试用例（低门槛）

**适合人群**：会写一点代码，但不敢改核心逻辑

- 补充缺失的单元测试
- 增加边界条件测试
- 提高测试覆盖率

```java
// 补充一个测试用例
@Test
void shouldReturnInstantWhenMd5Exists() {
    // given
    FileMeta existing = new FileMeta();
    existing.setFileMd5("abc123");
    existing.setStatus(FileStatus.AVAILABLE);
    fileMetaMapper.insert(existing);

    // when
    InitUploadResponse resp = uploadService.init(req, userId);

    // then
    assertEquals("INSTANT", resp.getMode());
    assertEquals(1, existing.getRefCount());
}
```

### 6.3 Level 3：修 Bug（中等门槛）

**适合人群**：能看懂代码，能定位问题

- 从 Issues 中找 `good first issue` 或 `bug` 标签
- 复现 Bug → 定位原因 → 写修复代码 → 提交 PR

### 6.4 Level 4：加新功能（高门槛）

**适合人群**：对项目有深入理解

- 先在 Issue 中讨论方案
- 得到 maintainer 认可后再开发
- 开发完提交 PR

---

## 七、如何找到适合小白的项目？

### 7.1 专门为小白准备的项目

| 平台 | 地址 | 说明 |
|------|------|------|
| Good First Issue | https://goodfirstissue.dev | 专门收集适合新手的 Issue |
| First Timers Only | https://www.firsttimersonly.com | 专门为第一次贡献者准备 |
| Up For Grabs | https://up-for-grabs.net | 收集了有"待认领"标签的项目 |
| Codetriage | https://www.codetriage.com | 按语言筛选项目 |

### 7.2 GitHub 搜索技巧

在 GitHub 搜索框中输入：

```
language:java label:"good first issue" label:"help wanted"
```

```
language:java label:"beginner" stars:>100
```

### 7.3 找你熟悉的项目

- 你日常使用的开源库（Spring Boot、MyBatis-Plus、Redis 等）
- 你关注的技术博客作者的项目
- 你的朋友/同事的项目

---

## 八、常见问题与避坑指南

### 8.1 PR 被 merge 前要同步最新代码

```bash
# 同步上游最新代码
git checkout main
git pull upstream main

# 切回功能分支
git checkout fix/fix-readme-typo

# 合并最新代码（rebase 更干净）
git rebase main

# 如果有冲突，解决冲突后继续
git add .
git rebase --continue

# 强制推送（因为 rebase 改变了历史）
git push origin fix/fix-readme-typo --force-with-lease
```

### 8.2 一个 PR 只做一件事

```
❌ 一个 PR 里修了 3 个 bug + 改了 2 个文档 + 加了 1 个功能
✅ 一个 PR 只修一个 bug 或只加一个功能
```

### 8.3 不要直接改 main 分支

```
❌ 在 main 分支上直接改代码
✅ 每次改代码都新建功能分支
```

### 8.4 Commit Message 要规范

```
❌ git commit -m "fix"
❌ git commit -m "update"
❌ git commit -m "修改了上传的bug"
✅ git commit -m "fix(upload): fix chunk MD5 mismatch when network unstable"
```

### 8.5 PR 被 review 后要修改怎么办？

```bash
# 在本地修改代码
vim src/main/java/xxx.java

# 追加提交（不要新开 PR）
git add .
git commit -m "fix(upload): address review comments"
git push origin fix/fix-readme-typo
```

PR 会自动更新，不需要新建 PR。

### 8.6 PR 被拒绝怎么办？

- **不要灰心**，这是正常的！
- 看看 maintainer 的反馈，理解原因
- 修改后重新提交
- 即使被拒绝，你也在过程中学到了东西

---

## 九、完整工作流图（一张图记住所有步骤）

```
1. Fork 仓库
     │
     ▼
2. Clone 到本地
     │
     ▼
3. 添加 upstream 远程仓库
     │
     ▼
4. 同步最新代码 + 创建功能分支
     │
     ▼
5. 修改代码 + 提交
     │
     ▼
6. 推送到远程 + 提交 PR
     │
     ▼
7. 等待 Review → 修改 → 最终 Merge
```

---

## 十、实战案例：以 CloudChunk 为例

### 10.1 找到项目

假设你想贡献 CloudChunk 项目，项目地址：`https://github.com/xxx/cloudchunk`

### 10.2 从 Issue 开始

```bash
# 浏览 Issues 列表
# 找到标签为 "good first issue" 或 "help wanted" 的 Issue
# 假设你找到了 Issue #42: "上传分片 MD5 校验失败后，错误分片未清理"
```

### 10.3 完整操作

```bash
# 1. Fork 仓库（在 GitHub 网页上操作）

# 2. Clone
git clone git@github.com:你的用户名/cloudchunk.git
cd cloudchunk

# 3. 添加 upstream
git remote add upstream git@github.com:xxx/cloudchunk.git

# 4. 同步最新代码
git checkout main
git pull upstream main

# 5. 创建功能分支
git checkout -b fix/async-delete-corrupted-chunk

# 6. 修改代码
# 编辑 UploadService.java，把同步删除改为异步删除
vim cloudchunk-core/src/main/java/com/cloudchunk/upload/service/UploadService.java

# 7. 提交
git add cloudchunk-core/src/main/java/com/cloudchunk/upload/service/UploadService.java
git commit -m "fix(upload): async delete corrupted chunk when MD5 mismatch"

# 8. 推送
git push origin fix/async-delete-corrupted-chunk

# 9. 在 GitHub 网页上创建 PR
```

### 10.4 PR 描述示例

```markdown
## 改了什么

- 修复了上传分片时 MD5 校验失败后，错误分片没有异步删除的问题
- 改为使用 `BoundedVirtualThreadExecutor` 异步删除错误分片

## 为什么要改

- 之前 MD5 不匹配时，错误分片留在 MinIO 里，占用存储空间
- 用户重传时，可能因为 MinIO 里已有同名分片导致 Compose 失败
- 同步删除会阻塞主流程，影响上传接口 TP99

## 怎么测试

1. 模拟一个分片上传，MD5 校验失败
2. 检查 MinIO 中错误分片是否被删除
3. 重传同一分片，验证可以正常上传
4. 检查上传接口 TP99 不受影响

## 关联 Issue

Closes #42
```

---

## 十一、进阶：让你的 GitHub 绿墙更绿

### 11.1 每天贡献一点

- 不需要一次提交大代码，每天贡献一点就行
- 改文档、补测试、修 Bug 都算贡献
- 坚持一年，你的绿墙会非常好看

### 11.2 参与开源社区

- 加入项目的 Discord / Slack / 微信群
- 在 Issue 中积极讨论
- 帮助其他新手解决问题
- Review 别人的 PR

### 11.3 维护自己的开源项目

- 把自己写的小工具开源
- 写好 README、文档、贡献指南
- 添加 `CONTRIBUTING.md`、`CODE_OF_CONDUCT.md`
- 给 Issue 打标签 `good first issue`，吸引新手贡献

---

## 十二、总结

### 5 步口诀

```
1. Fork → 2. Clone → 3. Branch → 4. Commit → 5. PR
```

### 3 个心态

1. **不要怕**：所有大佬都是从第一次提交开始的
2. **不要急**：从改文档开始，慢慢积累
3. **不要停**：坚持贡献，绿墙会越来越绿

### 记住

> **GitHub 不是大佬的专属地，而是每个程序员的成长之路。你的第一个 PR，可能就是改一个错别字，但这是你迈出开源的第一步。**

---

## 参考资源

| 资源 | 链接 |
|------|------|
| GitHub 官方文档 | https://docs.github.com |
| Pro Git 电子书 | https://git-scm.com/book/zh/v2 |
| Conventional Commits | https://www.conventionalcommits.org/zh-hans |
| Good First Issue | https://goodfirstissue.dev |
| First Timers Only | https://www.firsttimersonly.com |
| 如何为开源项目做贡献 | https://opensource.guide/zh-hans/how-to-contribute/ |
