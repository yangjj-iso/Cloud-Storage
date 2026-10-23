import { Layers3 } from 'lucide-react';
import { productName } from '../../lib/navigation';

export function Brand({ compact = false }: { compact?: boolean }) {
  return (
    <span className="brand">
      <span className="brand-mark">
        <Layers3 size={23} strokeWidth={1.8} />
      </span>
      {!compact && (
        <span>
          {productName}
          <span className="brand-dot">.</span>
        </span>
      )}
    </span>
  );
}
