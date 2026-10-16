import React from 'react';
import { Crown, Lock } from 'lucide-react';

/** "Премиум" pill shown next to the name of a premium profile. */
export const PremiumBadge: React.FC<{ size?: 'sm' | 'md'; className?: string }> = ({ size = 'sm', className = '' }) => (
  <span
    title="Премиум"
    className={`inline-flex items-center gap-1 rounded-full border font-semibold whitespace-nowrap bg-amber-50 text-amber-700 border-amber-300 ${
      size === 'sm' ? 'text-[10px] px-1.5 py-0.5' : 'text-xs px-2 py-0.5'
    } ${className}`}
  >
    <Crown className={size === 'sm' ? 'w-2.5 h-2.5' : 'w-3 h-3'} aria-hidden="true" />
    Премиум
  </span>
);

/** Short paywall hint for a disabled premium control. */
export const PremiumLockHint: React.FC<{ className?: string; children?: React.ReactNode }> = ({
  className = '',
  children,
}) => (
  <p className={`text-[11px] text-amber-700 flex items-center gap-1 ${className}`}>
    <Lock className="w-3 h-3 shrink-0" aria-hidden="true" />
    <span>{children ?? 'Доступно с премиумом'}</span>
  </p>
);
