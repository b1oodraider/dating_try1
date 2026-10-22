import React, { useEffect, useRef, useState } from 'react';
import { Rocket } from 'lucide-react';
import type { BoostStatus } from '../../../core/types';
import { Button } from '../../../shared/components/Button';

interface BoostButtonProps {
  status: BoostStatus | null;
  isActivating: boolean;
  /** Boosts left: start one. */
  onActivate: () => void;
  /** No boosts left: open the purchase window. */
  onBuy: () => void;
  /** The active boost ran out: reload the status. */
  onExpired: () => void;
}

/** Ms left of the active boost, by the server clock at the moment the status arrived. */
function leftMs(status: BoostStatus, receivedAt: number): number {
  if (!status.active || !status.endsAt) return 0;
  return Math.max(0, Date.parse(status.endsAt) - Date.parse(status.now) - (Date.now() - receivedAt));
}

const clock = (ms: number) => {
  const s = Math.ceil(ms / 1000);
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
};

/** Boost in the feed header: a countdown while active, otherwise a rocket with the number of boosts left. */
export const BoostButton: React.FC<BoostButtonProps> = ({ status, isActivating, onActivate, onBuy, onExpired }) => {
  const [received, setReceived] = useState({ status, at: Date.now() });
  if (received.status !== status) setReceived({ status, at: Date.now() });
  const receivedAt = received.at;
  const [, tick] = useState(0);

  const active = !!status?.active;
  const left = status ? leftMs(status, receivedAt) : 0;
  useEffect(() => {
    if (!active) return;
    const timer = setInterval(() => tick((n) => n + 1), 1000);
    return () => clearInterval(timer);
  }, [active]);
  // Once per status: a reload that still says "active" (clock skew) must not loop.
  const expiredFor = useRef<BoostStatus | null>(null);
  useEffect(() => {
    if (active && left === 0 && expiredFor.current !== status) {
      expiredFor.current = status;
      onExpired();
    }
  }, [active, left, status, onExpired]);

  if (!status) return null;
  if (active) {
    return (
      <span
        title={`Буст идёт: ${status.impressions} показов`}
        className="flex items-center gap-1.5 px-3 py-1.5 bg-amber-50 border border-amber-300 text-amber-800 rounded-xl text-xs font-semibold shadow-2xs font-mono"
      >
        <Rocket className="w-3.5 h-3.5 text-amber-600 animate-pulse" />
        {clock(left)}
      </span>
    );
  }
  const available = status.available;
  return (
    <Button
      variant="outline"
      size="sm"
      className="border-amber-300 text-amber-800 hover:bg-amber-50"
      onClick={available > 0 ? onActivate : onBuy}
      isLoading={isActivating}
      title={
        available > 0
          ? `На ${status.durationMinutes} минут ваша анкета чаще появляется в ленте`
          : 'Бустов нет. Премиум даёт 1 буст в неделю'
      }
    >
      <Rocket className="w-3.5 h-3.5 mr-1 text-amber-600" />
      {available > 0 ? `Включить буст (${available})` : 'Буст'}
    </Button>
  );
};
