import { useCallback, useEffect, useState } from 'react';
import { knowledgeApi, statusLabels, type AssistantCapabilities, type KnowledgeDocument, type Library, type SemanticCapabilities } from '../lib/knowledge';
import { MaterialIcon } from './ui/MaterialIcon';
import { KnowledgeWorkspace } from './KnowledgeWorkspace';
import { AiBatchIndexPanel } from './AiBatchIndexPanel';
import { AiAgentHistory } from './AiAgentHistory';
import { AgentWorkflowPanel } from './AgentWorkflowPanel';
import './ai-hub.css';

type Tab = 'overview' | 'explore' | 'prepare' | 'organize';

const tabs: Array<{ id: Tab; label: string; icon: string }> = [
  { id: 'overview', label: '工作台首页', icon: 'space_dashboard' },
  { id: 'explore', label: '搜索与问答', icon: 'manage_search' },
  { id: 'prepare', label: '资料准备', icon: 'library_add' },
  { id: 'organize', label: '整理记录', icon: 'rule_folder' },
];

const examples = ['上传过程中如何保证文件完整性？', '哪些文档介绍了服务降级和恢复？', '搜索有关权限校验的资料'];

export function AiHub({ focusTick }: { focusTick: number }) {
  const [tab, setTab] = useState<Tab>('overview');
  const [seed, setSeed] = useState('');
  const [searchTick, setSearchTick] = useState(0);

  useEffect(() => {
    const focus = () => { setSeed(''); setTab('explore'); setSearchTick(v => v + 1); };
    window.addEventListener('cloudchunk:focus-ai-search', focus);
    return () => window.removeEventListener('cloudchunk:focus-ai-search', focus);
  }, []);

  useEffect(() => {
    if (focusTick === 0) return;
    setSeed('');
    setTab('explore');
    setSearchTick(v => v + 1);
  }, [focusTick]);

  const explore = (prompt = '') => {
    setSeed(prompt);
    setTab('explore');
    setSearchTick(v => v + 1);
  };

  return (
    <div className="ai-hub px-md py-lg md:px-xl lg:px-2xl">
      <section className="ai-hub-hero">
        <div>
          <span className="ai-hub-kicker"><MaterialIcon>auto_awesome</MaterialIcon> CLOUDCHUNK · AI-NATIVE WORKSPACE</span>
          <h1>文件不只是存起来，<br/><span>更能成为你的知识。</span></h1>
          <p>从当前账号的授权资料中搜索原文、核验引用、提问和整理文件。AI 只在明确授权后使用，文件操作始终由你确认。</p>
          <div className="ai-hub-hero-actions">
            <button type="button" className="ai-hub-primary" onClick={() => explore()}>
              <MaterialIcon>search</MaterialIcon>开始智能搜索<MaterialIcon>arrow_forward</MaterialIcon>
            </button>
            <button type="button" className="ai-hub-secondary" onClick={() => setTab('prepare')}>
              <MaterialIcon>folder_open</MaterialIcon>准备我的资料
            </button>
          </div>
          <p className="ai-hub-privacy"><MaterialIcon>verified_user</MaterialIcon>遵循当前文件权限 · 模型请求逐次授权 · 不自动移动或重命名文件</p>
        </div>
        <div className="ai-hub-orbit" aria-hidden="true">
          <div className="ai-orbit-icon ai-orbit-file"><MaterialIcon>description</MaterialIcon></div>
          <div className="ai-orbit-icon ai-orbit-spark"><MaterialIcon>auto_awesome</MaterialIcon></div>
          <div className="ai-orbit-icon ai-orbit-folder"><MaterialIcon>folder_managed</MaterialIcon></div>
          <div className="ai-orbit-core"><MaterialIcon>neurology</MaterialIcon><span>YOUR KNOWLEDGE</span></div>
        </div>
      </section>

      <nav className="ai-hub-tabs" aria-label="AI 工作台导航">
        {tabs.map(item => (
          <button type="button" key={item.id} onClick={() => setTab(item.id)}
            aria-current={tab === item.id ? 'page' : undefined}
            className={tab === item.id ? 'ai-hub-tab-active' : ''}>
            <MaterialIcon>{item.icon}</MaterialIcon>{item.label}
          </button>
        ))}
      </nav>

      {tab === 'overview' && <Overview onExplore={explore} onPrepare={() => setTab('prepare')} onOrganize={() => setTab('organize')} />}
      {tab === 'explore' && (
        <div className="ai-hub-workspace">
          <div className="ai-hub-context"><MaterialIcon>shield</MaterialIcon>
            检索前先检查当前权限。无需开启模型即可使用关键词搜索；语义搜索和模型问答有独立授权流程。
            <span className="ml-auto">快捷键 <kbd>Ctrl / ⌘ + K</kbd></span>
          </div>
          <KnowledgeWorkspace initialQuery={seed} focusTick={searchTick} />
        </div>
      )}
      {tab === 'prepare' && <AiBatchIndexPanel onExplore={() => explore()} />}
      {tab === 'organize' && (
        <div className="ai-hub-organize">
          <div className="ai-hub-section-head">
            <div><h2>可审核的智能整理</h2><p>从搜索结果生成候选目录建议并保存成执行计划。每一步单独审核、确认、记录和条件撤销。</p></div>
            <button type="button" className="ai-hub-outline" onClick={() => explore()}>寻找待整理的文件 <MaterialIcon>arrow_forward</MaterialIcon></button>
          </div>
          <AgentWorkflowPanel />
          <AiAgentHistory />
          <div className="ai-hub-safety">
            <MaterialIcon>fact_check</MaterialIcon>
            <div><strong>先预览，后操作</strong><p>AI 不能绕过当前文件权限、同名冲突检查或你的最终确认。建议过期后需要重新生成。</p></div>
          </div>
        </div>
      )}
    </div>
  );
}

function Overview({ onExplore, onPrepare, onOrganize }: {
  onExplore: (query?: string) => void; onPrepare: () => void; onOrganize: () => void;
}) {
  const [library, setLibrary] = useState<Library | null>(null);
  const [assistant, setAssistant] = useState<AssistantCapabilities | null>(null);
  const [semantic, setSemantic] = useState<SemanticCapabilities | null>(null);
  const [error, setError] = useState('');
  const [revision, setRevision] = useState(0);
  const refresh = useCallback(() => setRevision(v => v + 1), []);

  useEffect(() => {
    const controller = new AbortController();
    setError('');
    void Promise.allSettled([
      knowledgeApi.library(1, controller.signal),
      knowledgeApi.assistantCapabilities(controller.signal),
      knowledgeApi.semanticCapabilities(controller.signal),
    ]).then(([docs, chat, vectors]) => {
      if (controller.signal.aborted) return;
      if (docs.status === 'fulfilled') setLibrary(docs.value);
      else { setLibrary(null); setError('资料状态暂不可用，可直接进入搜索重试'); }
      setAssistant(chat.status === 'fulfilled' ? chat.value : null);
      setSemantic(vectors.status === 'fulfilled' ? vectors.value : null);
    });
    return () => controller.abort();
  }, [revision]);
  const docs = library?.documents ?? [];
  const ready = docs.filter(d => d.status === 'READY' || d.status === 'PARTIAL').length;
  const needsIndex = docs.filter(d => d.status === 'NOT_INDEXED' || d.status === 'FAILED').length;
  return <div className="ai-hub-overview">
    <div className="ai-hub-overview-head">
      <div><h2>你的知识空间</h2><p>以下统计仅基于资料第一页（最多 20 份），不是整个网盘的文件总数。</p></div>
      <button type="button" onClick={refresh} className="ai-hub-outline"><MaterialIcon>refresh</MaterialIcon>更新</button>
    </div>
    {error && <div role="alert" className="ai-hub-notice">{error}</div>}
    <div className="ai-hub-stats">
      <article><span><MaterialIcon>library_books</MaterialIcon>可检索文档</span><strong>{library ? ready : '—'}</strong><small>当前页已解析或部分解析</small></article>
      <article><span><MaterialIcon>pending_actions</MaterialIcon>等待准备</span><strong>{library ? needsIndex : '—'}</strong><small>当前页待索引 / 可重试</small></article>
      <article><span><MaterialIcon>psychology</MaterialIcon>模型问答</span><strong className="ai-hub-stat-word">{assistant ? assistant.enabled ? '已启用' : '未启用' : '未知'}</strong><small>每次调用仍需你同意</small></article>
      <article><span><MaterialIcon>hub</MaterialIcon>混合检索</span><strong className="ai-hub-stat-word">{semantic ? semantic.enabled ? '可使用' : '关键词' : '未知'}</strong><small>取决于可用的个人索引</small></article>
    </div>

    <div className="ai-hub-overview-grid">
      <section className="ai-hub-feature">
        <div className="ai-hub-section-head"><div><span className="ai-hub-eyebrow">GET STARTED</span><h2>三步用好你的文件</h2></div></div>
        <div className="ai-hub-journey">
          <button type="button" onClick={onPrepare}><span>01</span><MaterialIcon>auto_stories</MaterialIcon><strong>准备知识</strong><small>为已上传文档建立可检索索引</small><MaterialIcon>arrow_outward</MaterialIcon></button>
          <button type="button" onClick={() => onExplore()}><span>02</span><MaterialIcon>manage_search</MaterialIcon><strong>寻找依据</strong><small>关键词或授权后混合检索</small><MaterialIcon>arrow_outward</MaterialIcon></button>
          <button type="button" onClick={onOrganize}><span>03</span><MaterialIcon>rule_folder</MaterialIcon><strong>审核整理</strong><small>逐项批准、记录和条件撤销</small><MaterialIcon>arrow_outward</MaterialIcon></button>
        </div>
      </section>
      <section className="ai-hub-feature">
        <span className="ai-hub-eyebrow">DISCOVER</span><h2>试试从这里开始</h2>
        <p className="ai-hub-feature-note">示例仅填入本地搜索框，不会自动向任何模型发送内容。</p>
        <div className="ai-hub-prompts">
          {examples.map(question => <button type="button" key={question} onClick={() => onExplore(question)}>
            <MaterialIcon>search</MaterialIcon><span>{question}</span><MaterialIcon>north_east</MaterialIcon>
          </button>)}
        </div>
      </section>
    </div>

    <section className="ai-hub-recent">
      <div className="ai-hub-section-head"><div><span className="ai-hub-eyebrow">RECENT SOURCES</span><h2>最近的资料</h2></div><button className="ai-hub-outline" onClick={onPrepare}>管理索引 <MaterialIcon>arrow_forward</MaterialIcon></button></div>
      {library && docs.length === 0 && <p className="ai-hub-recent-empty">还没有适合解析的文档。先上传 TXT、Markdown、文本型 PDF 或 DOCX，再建立资料索引。</p>}
      {!library && !error && <p className="ai-hub-recent-empty">正在检查资料…</p>}
      <div className="ai-hub-recent-grid">{docs.slice(0, 4).map(d => (
        <button type="button" key={d.userFileId} onClick={() => onExplore(d.fileName.slice(0, 100))}>
          <MaterialIcon>description</MaterialIcon><strong title={d.fileName}>{d.fileName}</strong>
          <span>{statusLabels[d.status] ?? d.status}</span>
        </button>
      ))}</div>
    </section>
  </div>;
}
