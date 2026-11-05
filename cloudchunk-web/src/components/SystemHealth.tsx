import { useCallback, useEffect, useState } from 'react';
import { api, type AdminHealthSnapshot, type AdminHealthComponent } from '../lib/api';
import { MaterialIcon } from './ui/MaterialIcon';
import { OperationsPanel } from './OperationsPanel';

const labels: Record<AdminHealthComponent['id'], string> = {
  mysql: 'MySQL 数据库',
  redis: 'Redis 会话服务',
  rocketmq: 'RocketMQ 消息服务',
  minio: 'MinIO 对象存储',
  'ai-chat': 'AI 问答服务',
  'ai-embedding': 'AI 向量模型',
  qdrant: 'Qdrant 向量镜像',
};

const statusLabels: Record<AdminHealthComponent['status'], string> = {
  UP: '已验证',
  DOWN: '不可用',
  REACHABLE_ONLY: '仅 TCP 可连接',
  UNREACHABLE: '无法连接',
  NOT_PROBED: '未主动探测',
  DISABLED: '未启用',
};

export function SystemHealth() {
  const [snapshot, setSnapshot] = useState<AdminHealthSnapshot | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      const next = await api.adminDependencyHealth();
      setSnapshot(next);
      setError(null);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '无法获取诊断信息');
      setSnapshot(null);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void refresh(); }, [refresh]);
  const unavailable = snapshot?.components.filter((item) =>
    item.status === 'DOWN' || item.status === 'UNREACHABLE').length ?? 0;

  return (
    <div className="p-margin-mobile md:p-margin-desktop">
      <div className="mb-xl flex flex-wrap items-center justify-between gap-md">
        <div>
          <h1 className="text-headline-lg">依赖与运行状态</h1>
          <p className="mt-xs text-body-md text-on-surface-variant">仅管理员可见、只读诊断；TCP 连接正常不等于业务功能可用。</p>
        </div>
        <button type="button" onClick={() => void refresh()} disabled={loading}
          className="flex h-10 items-center gap-sm rounded-lg border border-outline-variant bg-white px-md text-label-md disabled:opacity-50">
          <MaterialIcon className={loading ? 'animate-spin' : ''}>refresh</MaterialIcon>
          {loading ? '检测中' : '重新检测'}
        </button>
      </div>

      {error && <div role="alert" className="mb-lg rounded-xl border border-error/40 bg-error-container p-md text-on-error-container">状态获取失败：{error}</div>}
      {!snapshot && !error && <p className="text-body-md text-on-surface-variant">正在读取依赖诊断结果…</p>}
      {snapshot && (
        <>
          <div className="mb-lg grid gap-gutter md:grid-cols-3">
            <Summary title="诊断结果" value={snapshot.status === 'DEGRADED' ? '存在风险' : '部分已验证'} note="不是发布准入或全部依赖健康证明" />
            <Summary title="明确不可连接" value={String(unavailable)} note="数据库、Redis 或依赖网络异常" />
            <Summary title="超时未完成 Outbox" value={snapshot.oldOutboxEvents === null ? '未知' : String(snapshot.oldOutboxEvents)} note="超过 10 分钟仍未完成的任务" />
          </div>
          <div className="mb-md text-label-sm text-on-surface-variant">采样时间：{new Date(snapshot.sampledAt).toLocaleString('zh-CN')}。不显示依赖地址、凭据、文件内容。</div>
          <div className="grid gap-gutter md:grid-cols-2 xl:grid-cols-3">
            {snapshot.components.map((item) => (
              <section key={item.id} className="rounded-xl border border-outline-variant bg-surface-container-lowest p-lg shadow-sm">
                <div className="flex items-center justify-between gap-sm">
                  <h2 className="text-body-lg font-semibold text-on-surface">{labels[item.id]}</h2>
                  <span className={item.status === 'DOWN' || item.status === 'UNREACHABLE'
                    ? 'text-label-md font-semibold text-error' : 'text-label-md font-semibold text-on-surface-variant'}>
                    {statusLabels[item.status]}
                  </span>
                </div>
                <p className="mt-md text-body-md text-on-surface-variant">{item.note}</p>
                <div className="mt-sm text-label-sm text-outline">检查方式：{item.check}</div>
              </section>
            ))}
          </div>
          <p className="mt-xl rounded-xl bg-surface-container p-md text-body-md text-on-surface-variant">
            AI Provider 未调用外部 API，Qdrant 仅显示配置状态；RocketMQ 与 MinIO 仅测试 TCP 连接，无法代替真实上传或消息消费验收。
            故障注入仅在独立测试环境执行，生产请使用 M3.6 发布门禁和审批流程。
          </p>
        </>
      )}
      <OperationsPanel />
    </div>
  );
}

function Summary({ title, value, note }: { title: string; value: string; note: string }) {
  return (
    <section className="rounded-xl border border-outline-variant bg-surface-container-lowest p-lg">
      <p className="text-label-md text-on-surface-variant">{title}</p>
      <strong className="mt-sm block text-headline-md text-on-surface">{value}</strong>
      <p className="mt-xs text-label-sm text-on-surface-variant">{note}</p>
    </section>
  );
}
