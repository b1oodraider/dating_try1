import React, { useState } from 'react';
import { CalendarHeart, Eye, EyeOff, Lock, Rocket, RotateCcw, SlidersHorizontal, Star, TrendingUp, Crown } from 'lucide-react';
import { api, getErrorMessage } from '../../../core/api/client';
import type { StubProduct } from '../../../core/types';
import { Modal } from '../../../shared/components/Modal';
import { Button } from '../../../shared/components/Button';
import { useToast } from '../../../shared/components/Toast';

/** What the purchase window sells. */
export type PurchaseKind = 'premium' | 'superlikes' | 'boosts';

interface Offer {
  title: string;
  lead: string;
  points: [React.ElementType, string][];
  product: StubProduct;
  button: string;
}

const OFFERS: Record<PurchaseKind, Offer> = {
  premium: {
    title: 'Премиум',
    lead: 'Больше способов найти своего человека — и спокойнее знакомиться.',
    product: 'PREMIUM_MONTH',
    button: 'Купить премиум на месяц',
    points: [
      [CalendarHeart, 'План встречи прямо в чате: место на карте, время, договориться в пару нажатий'],
      [Star, '5 суперлайков и буст каждую неделю'],
      [RotateCcw, 'Вернуть анкету, которую пролистнули случайно, и пропущенных — в любой момент'],
      [SlidersHorizontal, 'Поиск по росту и слова-исключения: меньше неподходящих анкет'],
      [EyeOff, 'Невидимка: анкету видят только те, кого вы лайкнули'],
    ],
  },
  superlikes: {
    title: 'Суперлайки',
    lead: 'Суперлайк показывает, что вы особенно заинтересованы: человек увидит вас среди первых.',
    product: 'SUPERLIKE_PACK',
    button: 'Купить 5 суперлайков',
    points: [
      [TrendingUp, 'Вас замечают раньше остальных лайков'],
      [Crown, 'С премиумом — 5 суперлайков каждую неделю'],
    ],
  },
  boosts: {
    title: 'Бусты',
    lead: 'Буст на 30 минут чаще показывает вашу анкету тем, кому вы подходите.',
    product: 'BOOST_PACK',
    button: 'Купить 3 буста',
    points: [
      [Eye, 'Больше показов — больше шансов на взаимность'],
      [Lock, 'Другие не видят, что анкета в бусте'],
      [Crown, 'С премиумом — буст каждую неделю'],
    ],
  },
};

interface PurchaseModalProps {
  /** null = closed. */
  kind: PurchaseKind | null;
  onClose: () => void;
  /** Called after a (stub) purchase; the caller refreshes what it shows. */
  onBought?: () => void;
}

/**
 * The purchase window, ON TOP of the current screen: closing it leaves the user where they were.
 * Render it last in the opener so it stacks above that opener's own modals.
 */
export const PurchaseModal: React.FC<PurchaseModalProps> = ({ kind, onClose, onBought }) => {
  const { showToast } = useToast();
  const [shown, setShown] = useState<PurchaseKind | null>(null);
  const [buying, setBuying] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // «Что ещё даёт премиум» switches the open window to the premium offer.
  const current = kind === null ? null : (shown ?? kind);

  const close = () => {
    setShown(null);
    setError(null);
    onClose();
  };

  const buy = async (offer: Offer) => {
    setBuying(true);
    setError(null);
    try {
      // shortcut: a stub that grants the product without payment, until payments are decided.
      await api.buyStub(offer.product);
      showToast(`${offer.title}: готово`, 'success');
      onBought?.();
      close();
    } catch (e) {
      setError(getErrorMessage(e));
    } finally {
      setBuying(false);
    }
  };

  if (current === null) return null;
  const offer = OFFERS[current];
  return (
    <Modal isOpen onClose={close} title={offer.title}>
      <p className="text-sm text-zinc-700 mb-4">{offer.lead}</p>
      <ul className="space-y-3 mb-4">
        {offer.points.map(([Icon, text]) => (
          <li key={text} className="flex gap-3 text-sm text-zinc-800">
            <Icon className="w-5 h-5 shrink-0 text-zinc-500" />
            <span>{text}</span>
          </li>
        ))}
      </ul>
      {current !== 'premium' && (
        <button type="button" className="text-sm text-rose-600 hover:underline mb-4" onClick={() => setShown('premium')}>
          Что ещё даёт премиум
        </button>
      )}
      {error && <p className="text-sm text-rose-600 mb-2">{error}</p>}
      <Button className="w-full" isLoading={buying} onClick={() => buy(offer)}>
        {current === 'boosts' ? <Rocket className="w-4 h-4" /> : null}
        {offer.button}
      </Button>
      <p className="text-xs text-zinc-500 text-center mt-2">Оплата пока не подключена: покупка тестовая и бесплатная.</p>
    </Modal>
  );
};
