import React, { useEffect, useState } from 'react';
import { ApiError, api, getErrorMessage, isAbortError } from '../../../core/api/client';
import type { AchievementReward, TestingState } from '../../../core/types';
import { Modal } from '../../../shared/components/Modal';
import { Button } from '../../../shared/components/Button';
import { AlertCircle, CheckCircle, Clock, Crown, Package, RotateCcw, Trophy } from 'lucide-react';

interface TestPanelModalProps {
  isOpen: boolean;
  onClose: () => void;
  /** Called after every successful action (wallet, premium, cosmetics or profile decorations may have changed). */
  onChanged: () => void;
}

/** Stub catalog codes (migration 007); any other code can be typed in. */
const KNOWN_ITEM_CODES = [
  'SUPERLIKE',
  'GACHA_TICKET',
  'BOOST',
  'STICKERS_STUB_CATS',
  'STICKERS_STUB_SPACE',
  'WALLPAPER_STUB_NIGHT',
  'WALLPAPER_STUB_SUNSET',
  'STATUS_STUB_EARLY_BIRD',
  'STATUS_STUB_BOOKWORM',
  'FRAME_STUB_GOLD',
  'FRAME_STUB_NEON',
];

function formatDateTime(iso: string): string {
  return new Date(iso).toLocaleString('ru-RU', { dateStyle: 'medium', timeStyle: 'short' });
}

function describeReward(r: AchievementReward): string {
  return r.type === 'PREMIUM_DAYS' ? `премиум +${r.days} дн.` : `${r.itemCode} ×${r.quantity}`;
}

function describeError(e: unknown): string {
  const message = getErrorMessage(e, 'Действие не выполнено');
  if (e instanceof ApiError) {
    const fields = e.fieldErrors ? Object.values(e.fieldErrors).join('; ') : '';
    return `${message}${fields ? `: ${fields}` : ''} (${e.code})`;
  }
  return message;
}

/** Parses a non-negative integer input; null when invalid. */
function parseCount(value: string): number | null {
  const n = Number(value.trim());
  return value.trim() !== '' && Number.isInteger(n) && n >= 0 ? n : null;
}

export const TestPanelModal: React.FC<TestPanelModalProps> = ({ isOpen, onClose, onChanged }) => {
  const [state, setState] = useState<TestingState | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [itemCode, setItemCode] = useState(KNOWN_ITEM_CODES[0]);
  const [quantity, setQuantity] = useState('1');
  const [premiumDays, setPremiumDays] = useState('7');
  const [clockDays, setClockDays] = useState('1');

  useEffect(() => {
    if (!isOpen) return;
    const controller = new AbortController();
    setError(null);
    api.testing
      .getState(controller.signal)
      .then(setState)
      .catch((e: unknown) => {
        if (!isAbortError(e)) setError(describeError(e));
      });
    return () => controller.abort();
  }, [isOpen]);

  const run = async (key: string, action: () => Promise<TestingState>) => {
    setBusy(key);
    setError(null);
    try {
      setState(await action());
      onChanged();
    } catch (e: unknown) {
      setError(describeError(e));
    } finally {
      setBusy(null);
    }
  };

  const withCount = (value: string, min: number, key: string, action: (n: number) => Promise<TestingState>) => {
    const n = parseCount(value);
    if (n === null || n < min) {
      setError(`Введите целое число не меньше ${min}`);
      return;
    }
    void run(key, () => action(n));
  };

  const code = itemCode.trim().toUpperCase();
  const wallet = state?.wallet;
  const input =
    'px-2.5 py-1.5 text-xs bg-zinc-50 border border-zinc-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-zinc-900';
  const block = 'p-3.5 border border-zinc-200/80 rounded-xl space-y-2.5';
  const blockTitle = 'text-xs font-bold text-zinc-900 flex items-center gap-1.5';

  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      title="Тест-панель"
      description="Только для тестовых аккаунтов: экономика, достижения и часы аккаунта"
      maxWidth="2xl"
    >
      <div className="space-y-4">
        {error && (
          <div role="alert" className="sticky top-0 z-10 p-3 bg-rose-50 border border-rose-200 text-rose-700 text-xs rounded-xl flex items-start gap-2">
            <AlertCircle className="w-4 h-4 shrink-0 mt-0.5" />
            <span>{error}</span>
          </div>
        )}

        {!state ? (
          !error && <p className="text-xs text-zinc-500">Загрузка состояния…</p>
        ) : (
          <>
            {/* Clock */}
            <div className={block}>
              <div className={blockTitle}>
                <Clock className="w-3.5 h-3.5 text-zinc-500" /> Часы экономики
              </div>
              <div className="text-xs text-zinc-600 space-y-0.5">
                <div>
                  Сейчас для аккаунта: <span className="font-mono text-zinc-900">{formatDateTime(state.economyNow)}</span>
                </div>
                <div>
                  Сдвиг: <span className="font-mono text-zinc-900">{state.clockOffsetDays} дн.</span>
                </div>
              </div>
              <div className="flex flex-wrap items-center gap-2">
                <Button
                  type="button"
                  size="sm"
                  isLoading={busy === 'week'}
                  disabled={busy !== null}
                  onClick={() => void run('week', () => api.testing.advanceClock(7))}
                >
                  Следующая неделя
                </Button>
                <input
                  type="number"
                  min={1}
                  max={3650}
                  value={clockDays}
                  onChange={(e) => setClockDays(e.target.value)}
                  className={`${input} w-20 font-mono`}
                  aria-label="Дней вперёд"
                />
                <Button
                  type="button"
                  size="sm"
                  variant="outline"
                  isLoading={busy === 'advance'}
                  disabled={busy !== null}
                  onClick={() => withCount(clockDays, 1, 'advance', (n) => api.testing.advanceClock(n))}
                >
                  Сдвинуть на N дн.
                </Button>
                <Button
                  type="button"
                  size="sm"
                  variant="ghost"
                  isLoading={busy === 'clock-reset'}
                  disabled={busy !== null || state.clockOffsetDays === 0}
                  onClick={() => void run('clock-reset', () => api.testing.resetClock())}
                >
                  <RotateCcw className="w-3.5 h-3.5" /> Реальное время
                </Button>
              </div>
            </div>

            {/* Premium */}
            <div className={block}>
              <div className={blockTitle}>
                <Crown className="w-3.5 h-3.5 text-amber-500" /> Премиум
              </div>
              <div className="text-xs text-zinc-600">
                {wallet?.premium.active
                  ? `Активен до ${formatDateTime(wallet.premium.until ?? state.economyNow)}`
                  : wallet?.premium.until
                    ? `Не активен (закончился ${formatDateTime(wallet.premium.until)})`
                    : 'Не активен'}
              </div>
              <div className="flex flex-wrap items-center gap-2">
                <input
                  type="number"
                  min={1}
                  max={3650}
                  value={premiumDays}
                  onChange={(e) => setPremiumDays(e.target.value)}
                  className={`${input} w-20 font-mono`}
                  aria-label="Дней премиума"
                />
                <Button
                  type="button"
                  size="sm"
                  isLoading={busy === 'premium'}
                  disabled={busy !== null}
                  onClick={() => withCount(premiumDays, 1, 'premium', (n) => api.testing.grantPremium(n))}
                >
                  +N дн. премиума
                </Button>
                <Button
                  type="button"
                  size="sm"
                  variant="outline"
                  isLoading={busy === 'premium-off'}
                  disabled={busy !== null || !wallet?.premium.active}
                  onClick={() => void run('premium-off', () => api.testing.removePremium())}
                >
                  Снять премиум
                </Button>
              </div>
            </div>

            {/* Wallet */}
            <div className={block}>
              <div className={blockTitle}>
                <Package className="w-3.5 h-3.5 text-zinc-500" /> Кошелёк
              </div>
              {wallet && wallet.items.length > 0 ? (
                <table className="w-full text-xs">
                  <thead>
                    <tr className="text-left text-[10px] uppercase tracking-wider text-zinc-400">
                      <th className="font-semibold py-1">Код</th>
                      <th className="font-semibold py-1 text-right">Всего</th>
                      <th className="font-semibold py-1 text-right">Еженед.</th>
                      <th className="font-semibold py-1 pl-3">Надето</th>
                    </tr>
                  </thead>
                  <tbody>
                    {wallet.items.map((i) => (
                      <tr key={i.code} className="border-t border-zinc-100">
                        <td className="py-1 font-mono text-zinc-800">{i.code}</td>
                        <td className="py-1 text-right font-mono">{i.quantity}</td>
                        <td className="py-1 text-right font-mono text-zinc-500">{i.periodicQuantity}</td>
                        <td className="py-1 pl-3 text-zinc-500">
                          {i.equipSlot && wallet.equipped[i.equipSlot] === i.code ? i.equipSlot : ''}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              ) : (
                <p className="text-xs text-zinc-400">Кошелёк пуст.</p>
              )}
              <div className="flex flex-wrap items-center gap-2 pt-1">
                <input
                  list="test-panel-item-codes"
                  value={itemCode}
                  onChange={(e) => setItemCode(e.target.value)}
                  placeholder="Код предмета"
                  className={`${input} w-56 font-mono`}
                  aria-label="Код предмета"
                />
                <datalist id="test-panel-item-codes">
                  {KNOWN_ITEM_CODES.map((c) => (
                    <option key={c} value={c} />
                  ))}
                </datalist>
                <input
                  type="number"
                  min={0}
                  value={quantity}
                  onChange={(e) => setQuantity(e.target.value)}
                  className={`${input} w-20 font-mono`}
                  aria-label="Количество"
                />
                <Button
                  type="button"
                  size="sm"
                  isLoading={busy === 'grant'}
                  disabled={busy !== null || code === ''}
                  onClick={() => withCount(quantity, 1, 'grant', (n) => api.testing.grantItem(code, n))}
                >
                  Выдать N
                </Button>
                <Button
                  type="button"
                  size="sm"
                  variant="outline"
                  isLoading={busy === 'set'}
                  disabled={busy !== null || code === ''}
                  onClick={() => withCount(quantity, 0, 'set', (n) => api.testing.setItemBalance(code, n))}
                >
                  Установить баланс
                </Button>
              </div>
              <p className="text-[11px] text-zinc-400">
                «Установить баланс» задаёт купленную часть (PERMANENT); еженедельная часть премиума не меняется.
                Для косметики — 0 или 1.
              </p>
            </div>

            {/* Achievements */}
            <div className={block}>
              <div className={blockTitle}>
                <Trophy className="w-3.5 h-3.5 text-zinc-500" /> Достижения
              </div>
              <ul className="space-y-2">
                {state.achievements.map((a) => (
                  <li key={a.code} className="flex items-start justify-between gap-3 text-xs">
                    <div className="flex items-start gap-2 min-w-0">
                      <CheckCircle className={`w-4 h-4 shrink-0 mt-0.5 ${a.completed ? 'text-emerald-600' : 'text-zinc-300'}`} />
                      <div className="min-w-0">
                        <div className="font-semibold text-zinc-900">
                          {a.title} <span className="font-mono font-normal text-zinc-400">{a.code}</span>
                        </div>
                        <div className="text-zinc-500">Награда: {a.rewards.map(describeReward).join(', ') || '—'}</div>
                        {a.completedAt && <div className="text-emerald-700">Выполнено {formatDateTime(a.completedAt)}</div>}
                      </div>
                    </div>
                    {a.completed ? (
                      <Button
                        type="button"
                        size="sm"
                        variant="outline"
                        isLoading={busy === `reset:${a.code}`}
                        disabled={busy !== null}
                        onClick={() => void run(`reset:${a.code}`, () => api.testing.resetAchievement(a.code))}
                      >
                        Сбросить
                      </Button>
                    ) : (
                      <Button
                        type="button"
                        size="sm"
                        isLoading={busy === `complete:${a.code}`}
                        disabled={busy !== null}
                        onClick={() => void run(`complete:${a.code}`, () => api.testing.completeAchievement(a.code))}
                      >
                        Выполнить
                      </Button>
                    )}
                  </li>
                ))}
              </ul>
              <p className="text-[11px] text-zinc-400">Сброс не забирает уже выданные награды; после сброса можно выполнить снова.</p>
            </div>
          </>
        )}
      </div>
    </Modal>
  );
};
