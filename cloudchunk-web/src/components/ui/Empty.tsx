import type { ReactNode } from 'react';

interface Props {
  icon?: ReactNode;
  title: string;
  description?: string;
  action?: ReactNode;
}

export function Empty({ icon, title, description, action }: Props) {
  return (
    <div className="flex flex-col items-center justify-center gap-3 rounded-xl border border-dashed border-slate-200 bg-white/60 px-6 py-16 text-center">
      {icon && <div className="text-slate-300">{icon}</div>}
      <div className="text-base font-medium text-slate-700">{title}</div>
      {description && <div className="max-w-md text-sm text-slate-500">{description}</div>}
      {action && <div className="mt-2">{action}</div>}
    </div>
  );
}
