import { cn } from '../../lib/utils';

interface Props {
  value: number; // 0-100
  indeterminate?: boolean;
  variant?: 'brand' | 'success' | 'danger' | 'neutral';
  className?: string;
}

const fillClass = {
  brand: 'bg-brand-600',
  success: 'bg-emerald-500',
  danger: 'bg-rose-500',
  neutral: 'bg-slate-400',
};

export function Progress({ value, indeterminate, variant = 'brand', className }: Props) {
  const pct = Math.min(Math.max(value, 0), 100);
  return (
    <div className={cn('h-1.5 w-full overflow-hidden rounded-full bg-slate-200/70', className)}>
      {indeterminate ? (
        <div
          className={cn(
            'h-full w-1/3 animate-shimmer rounded-full',
            fillClass[variant]
          )}
          style={{
            backgroundImage:
              'linear-gradient(90deg, transparent, rgba(255,255,255,0.5), transparent)',
            backgroundSize: '200% 100%',
          }}
        />
      ) : (
        <div
          className={cn('h-full rounded-full transition-all duration-300', fillClass[variant])}
          style={{ width: `${pct}%` }}
        />
      )}
    </div>
  );
}
