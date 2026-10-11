import { useCallback, useEffect, useState } from 'react';
import { api, type AdminOperationsOverview, type AdminOperationsAlert } from '../lib/api';
import { MaterialIcon } from './ui/MaterialIcon';

const knownAlerts: Record<string, string> = {
  CloudChunkApiScrapeDown: 'API 指标不可采集',
  CloudChunkApiHigh5xx: 'API 5xx 错误率升高',
  CloudChunkApiP99LatencyHigh: 'API P99 时延升高',
  CloudChunkHikariConnectionsExhausted: '数据库连接池持续等待',
};

function formatPercentage(value: number | null) {
  return value === null ? '数据不足' : (value * 100).toFixed(3) + '%';
}

function AlertEntry({ alert }: { alert: AdminOperationsAlert }) {
  const risky = alert.severity === 'critical';
  return (
    <div className="grid grid-cols-1 gap-sm border-b border-outline-variant py-md last:border-0 md:grid-cols-[1fr_100px_150px] md:items-center">
      <div className="min-w-0">
        <strong className="block text-body-md text-on-surface">{knownAlerts[alert.name] ?? '受控告警'}</strong>
        <span className="block truncate text-label-sm text-on-surface-variant">事件指纹：{alert.fingerprint}</span>
      </div>
      <span className={risky ? 'text-error text-label-md font-semibold' : 'text-on-surface-variant text-label-md'}>
        {risky ? '严重' : alert.severity === 'warning' ? '警告' : '未分类'}
      </span>
      <time className="text-label-sm text-on-surface-variant">
        {alert.startedAt ? new Date(alert.startedAt).toLocaleString('zh-CN') : '时间未知'}
      </time>
    </div>
  );
}

export function OperationsPanel() {
  const [state, setState] = useState<AdminOperationsOverview | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      setState(await api.adminOperations());
      setError(null);
    } catch (e) {
      setState(null);
      setError(e instanceof Error ? e.message : '未知错误');
    } finally {
      setLoading(false);
    }
  }, []);
  useEffect(() => { void refresh(); }, [refresh]);
  const slo = state?.slo;

  return (
    <section className="mt-xl space-y-md">
      <div className="flex flex-wrap items-center justify-between gap-md">
        <div>
          <h2 className="text-headline-md text-on-surface">SLO 与事件总览</h2>
          <p className="mt-xs text-body-md text-on-surface-variant">7 日观察窗口、99.9% 初始目标，仅基于采集到的 HTTP 请求；不是 SLA 保证。</p>
        </div>
        <button type="button" onClick={() => void refresh()} disabled={loading}
          className="flex h-10 items-center gap-sm rounded-lg border border-outline-variant bg-white px-md text-label-md disabled:opacity-50">
          <MaterialIcon className={loading ? 'animate-spin' : ''}>refresh</MaterialIcon>
          {loading ? '更新中' : '刷新事件'}
        </button>
      </div>
      {error && <p role="alert" className="rounded-lg border border-error/40 p-md text-error">指标或告警读取失败：{error}</p>}
      {!state && !error && <p className="text-body-md text-on-surface-variant">正在获取运维数据…</p>}
      {state && slo && (
        <>
          <div className="grid gap-gutter md:grid-cols-4">
            <Metric title="观察可用率" value={formatPercentage(slo.observedAvailability)} foot="仅已观察 HTTP 请求" />
            <Metric title="目标可用率" value={formatPercentage(slo.objective)} foot="滚动 7 天" />
            <Metric title="1 小时错误预算燃烧" value={slo.burnRate1h === null ? '不足' : slo.burnRate1h.toFixed(2) + '×'} foot="以 0.1% 错误预算为基准" />
            <Metric title="监控采样覆盖" value={formatPercentage(slo.scrapeCoverage)} foot={slo.state === 'BREACH' ? '目标已突破' : slo.state === 'OBSERVED' ? '已观测' : '未达到完整评估条件'} />
          </div>
          <div className="rounded-xl border border-outline-variant bg-surface-container-lowest p-lg">
            <div className="flex items-center justify-between gap-md">
              <h3 className="font-semibold text-body-lg">当前活动告警</h3>
              <span className="text-label-md text-on-surface-variant">
                {state.alertSource === 'VERIFIED' ? `当前 ${state.alerts.length} 条（最多展示 20 条）` : '告警数据不可用或未配置'}
              </span>
            </div>
            {state.alertSource === 'VERIFIED' && state.alerts.length === 0
              ? <p className="mt-md text-body-md text-on-surface-variant">告警源可访问，当前没有匹配的活动告警；不代表历史故障为零。</p>
              : state.alerts.map((item) => <AlertEntry key={item.fingerprint} alert={item} />)}
            <p className="mt-md text-label-sm text-on-surface-variant">
              状态：{slo.state} · 7 日请求数 {slo.requests7d.toLocaleString()}。若指标缺失、窗口未覆盖或低流量，不能将此结果作为生产 SLA 通过证明。
            </p>
          </div>
          <p className="text-body-md text-on-surface-variant">
            故障事件确认、缓解与复盘仍在受保护的运维审计流程中执行；本页面不提供静默告警、重启服务或直接回滚操作。
          </p>
        </>
      )}
    </section>
  );
}

function Metric({ title, value, foot }: { title: string; value: string; foot: string }) {
  return (
    <div className="rounded-xl border border-outline-variant bg-surface-container-lowest p-lg">
      <div className="text-label-md text-on-surface-variant">{title}</div>
      <strong className="mt-sm block text-headline-md text-on-surface">{value}</strong>
      <div className="mt-xs text-label-sm text-on-surface-variant">{foot}</div>
    </div>
  );
}
