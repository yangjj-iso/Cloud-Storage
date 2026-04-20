import { AlertCircle, CheckCircle2, Info, X } from 'lucide-react';
import { useAppStore } from '../../store';
import { cn } from '../../lib/utils';

const iconByKind = {
  success: <CheckCircle2 className="h-5 w-5 text-emerald-500" />,
  error: <AlertCircle className="h-5 w-5 text-rose-500" />,
  info: <Info className="h-5 w-5 text-sky-500" />,
};

const borderByKind = {
  success: 'border-emerald-200',
  error: 'border-rose-200',
  info: 'border-sky-200',
};

export function Toaster() {
  const toasts = useAppStore((s) => s.toasts);
  const dismiss = useAppStore((s) => s.dismissToast);

  return (
    <div className="pointer-events-none fixed right-4 top-4 z-50 flex w-80 flex-col gap-2">
      {toasts.map((t) => (
        <div
          key={t.id}
          className={cn(
            'pointer-events-auto flex items-start gap-3 rounded-xl border bg-white/95 p-3 pr-2 shadow-lg shadow-slate-900/5 backdrop-blur animate-slide-in',
            borderByKind[t.kind]
          )}
        >
          <div className="shrink-0 pt-0.5">{iconByKind[t.kind]}</div>
          <div className="flex-1 text-sm">
            <div className="font-medium text-slate-900">{t.title}</div>
            {t.description && (
              <div className="mt-0.5 text-xs text-slate-500 break-all">{t.description}</div>
            )}
          </div>
          <button
            onClick={() => dismiss(t.id)}
            className="rounded p-1 text-slate-400 transition-colors hover:bg-slate-100 hover:text-slate-600"
            aria-label="关闭"
          >
            <X className="h-4 w-4" />
          </button>
        </div>
      ))}
    </div>
  );
}
