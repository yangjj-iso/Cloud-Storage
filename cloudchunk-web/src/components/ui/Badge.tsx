import type { ReactNode } from 'react';
import { cn } from '../../lib/utils';

type Tone = 'brand' | 'success' | 'warning' | 'danger' | 'neutral' | 'info';

interface Props {
  children: ReactNode;
  tone?: Tone;
  className?: string;
  leftIcon?: ReactNode;
}

const toneClass: Record<Tone, string> = {
  brand: 'bg-brand-50 text-brand-700 ring-brand-600/10',
  success: 'bg-emerald-50 text-emerald-700 ring-emerald-600/10',
  warning: 'bg-amber-50 text-amber-700 ring-amber-600/10',
  danger: 'bg-rose-50 text-rose-700 ring-rose-600/10',
  info: 'bg-sky-50 text-sky-700 ring-sky-600/10',
  neutral: 'bg-slate-100 text-slate-700 ring-slate-600/10',
};

export function Badge({ children, tone = 'neutral', className, leftIcon }: Props) {
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset',
        toneClass[tone],
        className
      )}
    >
      {leftIcon}
      {children}
    </span>
  );
}
