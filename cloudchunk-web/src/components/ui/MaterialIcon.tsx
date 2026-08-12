import type { CSSProperties } from 'react';
import { cn } from '../../lib/utils';

export function MaterialIcon({
  children,
  className,
  filled = false,
  title,
}: {
  children: string;
  className?: string;
  filled?: boolean;
  title?: string;
}) {
  const style: CSSProperties = {
    fontVariationSettings: `'FILL' ${filled ? 1 : 0}, 'wght' 400, 'GRAD' 0, 'opsz' 24`,
  };
  return (
    <span aria-hidden="true" className={cn('material-symbols-outlined', className)} style={style} title={title}>
      {children}
    </span>
  );
}
