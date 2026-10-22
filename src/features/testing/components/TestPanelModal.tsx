import React, { useEffect, useState } from 'react';
import { ApiError, api, getErrorMessage, isAbortError } from '../../../core/api/client';
import type { AchievementReward, TestingState } from '../../../core/types';
import { Modal } from '../../../shared/components/Modal';
import { Button } from '../../../shared/components/Button';
import { AlertCircle, CheckCircle, Clock, Crown, Package, RotateCcw, Trophy } from 'lucide-react';
import { useToast } from '../../../shared/components/Toast';

interface TestPanelModalProps {
  isOpen: boolean;
  onClose: () => void;
  /** Called after every successful action (wallet, premium, cosmetics or profile decorations may have changed). */
  onChanged: () => void;
}


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

/** Around me when the browser gives a location quickly, else the centre of Moscow (as mobile). */
function currentPoint(): Promise<[number, number]> {
  const moscow: [number, number] = [55.7558, 37.6173];
  return new Promise((resolve) => {
    if (!navigator.geolocation) return resolve(moscow);
    navigator.geolocation.getCurrentPosition(
      (p) => resolve([p.coords.latitude, p.coords.longitude]),
      () => resolve(moscow),
      { timeout: 5000, maximumAge: 600_000 }
    );
  });
}

/** Parses a non-negative integer input; null when invalid. */
function parseCount(value: string): number | null {
  const n = Number(value.trim());
  return value.trim() !== '' && Number.isInteger(n) && n >= 0 ? n : null;
}

export const TestPanelModal: React.FC<TestPanelModalProps> = ({ isOpen, onClose, onChanged }) => {
  const { showToast } = useToast();
  const [state, setState] = useState<TestingState | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [itemCode, setItemCode] = useState('SUPERLIKE');
  const [catalog, setCatalog] = useState<Awaited<ReturnType<typeof api.testing.getCatalog>>>([]);
  const [totalBalance, setTotalBalance] = useState(false);
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
    api.testing.getCatalog(controller.signal).then(setCatalog).catch((e: unknown) => {
      if (!isAbortError(e)) console.warn('Catalog load failed', e);
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

  /** Social actions return no TestingState: report with a toast. */
  const social = async (key: string, action: () => Promise<string>) => {
    setBusy(key);
    try {
      showToast(await action(), 'success');
      onChanged();
    } catch (e) {
      showToast(getErrorMessage(e), 'error');
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
                  {catalog.map((c) => (
                    <option key={c.code} value={c.code}>
                      {`${c.title} · ${c.kind === 'CONSUMABLE' ? 'Расходники' : 'Косметика'} · ${c.category}`}
                    </option>
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
                  onClick={() => withCount(quantity, 0, 'set', (n) => api.testing.setItemBalance(code, n, totalBalance))}
                >
                  Установить баланс
                </Button>
                <label className="flex items-center gap-1.5 text-[11px] text-zinc-600">
                  <input type="checkbox" checked={totalBalance} onChange={(e) => setTotalBalance(e.target.checked)} />
                  весь баланс
                </label>
              </div>
              <div className="flex flex-wrap items-center gap-2 pt-2 border-t border-zinc-100">
                <span className="text-[11px] text-zinc-500 font-medium">Быстрая выдача:</span>
                <Button
                  type="button"
                  size="sm"
                  variant="outline"
                  disabled={busy !== null}
                  onClick={() => void run('grant-tickets', () => api.testing.grantItem('GACHA_TICKET', 5))}
                >
                  +5 Билетов
                </Button>
                <Button
                  type="button"
                  size="sm"
                  variant="outline"
                  disabled={busy !== null}
                  onClick={() => void run('grant-boosts', () => api.testing.grantItem('BOOST', 3))}
                >
                  +3 Буста
                </Button>
                <Button
                  type="button"
                  size="sm"
                  variant="outline"
                  disabled={busy !== null}
                  onClick={() => void run('grant-superlikes', () => api.testing.grantItem('SUPERLIKE', 5))}
                >
                  +5 Суперлайков
                </Button>
              </div>
              <p className="text-[11px] text-zinc-400">
                «Установить баланс» задаёт купленную часть (PERMANENT); еженедельная часть премиума не меняется, а с «весь баланс» — задаёт всё вместе с ней.
                Для косметики — 0 или 1.
              </p>
            </div>

            {/* Social & Match Simulation */}
            <div className={block}>
              <div className={blockTitle}>
                <Package className="w-3.5 h-3.5 text-zinc-500" /> Социальные тесты (лайки и мэтчи)
              </div>
              <div className="flex flex-wrap gap-2 text-xs">
                <Button
                  type="button"
                  size="sm"
                  variant="outline"
                  disabled={busy !== null}
                  onClick={async () => {
                    setBusy('seed-like');
                    try {
                      await api.testing.seedLikeMe('seed-1', false);
                      onChanged();
                      showToast('Демо-профиль поставил вам лайк! Проверьте раздел «Симпатии».', 'success');
                    } catch (e) {
                      showToast(getErrorMessage(e), 'error');
                    } finally {
                      setBusy(null);
                    }
                  }}
                >
                  Входящий лайк
                </Button>

                <Button
                  type="button"
                  size="sm"
                  variant="outline"
                  disabled={busy !== null}
                  onClick={async () => {
                    setBusy('seed-superlike');
                    try {
                      await api.testing.seedLikeMe('seed-2', true);
                      onChanged();
                      showToast('Входящий суперлайк создан! Проверьте раздел «Симпатии».', 'success');
                    } catch (e) {
                      showToast(getErrorMessage(e), 'error');
                    } finally {
                      setBusy(null);
                    }
                  }}
                >
                  Входящий суперлайк
                </Button>

                <Button
                  type="button"
                  size="sm"
                  variant="outline"
                  disabled={busy !== null}
                  onClick={async () => {
                    setBusy('seed-match');
                    try {
                      await api.testing.seedMatch('seed-1');
                      onChanged();
                      showToast('Взаимный мэтч создан! Проверьте раздел «Чаты».', 'success');
                    } catch (e) {
                      showToast(getErrorMessage(e), 'error');
                    } finally {
                      setBusy(null);
                    }
                  }}
                >
                  Создать взаимный мэтч
                </Button>

                <Button
                  type="button"
                  size="sm"
                  variant="outline"
                  disabled={busy !== null}
                  onClick={async () => {
                    setBusy('clear-social');
                    try {
                      await Promise.all([api.testing.clearReactions(), api.testing.clearPasses()]);
                      onChanged();
                      showToast('Все ваши лайки и пропуски очищены — лента снова полная.', 'info');
                    } catch (e) {
                      showToast(getErrorMessage(e), 'error');
                    } finally {
                      setBusy(null);
                    }
                  }}
                >
                  Сбросить реакции и пропуски
                </Button>
              </div>
              <p className="text-[11px] text-zinc-500 pt-2 border-t border-zinc-100">
                Толпа: анкеты под мою анкету (пол, возраст, город) с фото из загруженных
              </p>
              <div className="flex flex-wrap gap-2">
                {[10, 30].map((count) => (
                  <Button
                    key={count}
                    type="button"
                    size="sm"
                    variant="outline"
                    isLoading={busy === `crowd-${count}`}
                    disabled={busy !== null}
                    onClick={() => void social(`crowd-${count}`, async () => `Создано анкет: ${(await api.testing.createCrowd(count)).created}`)}
                  >
                    +{count} анкет
                  </Button>
                ))}
                <Button
                  type="button"
                  size="sm"
                  variant="outline"
                  isLoading={busy === 'crowd-like'}
                  disabled={busy !== null}
                  onClick={() =>
                    void social('crowd-like', async () => `Вас лайкнули ${(await api.testing.createCrowd(20, true)).created} новых анкет`)
                  }
                >
                  +20 и все лайкнули меня
                </Button>
                <Button
                  type="button"
                  size="sm"
                  variant="outline"
                  isLoading={busy === 'venues'}
                  disabled={busy !== null}
                  onClick={() =>
                    void social('venues', async () => {
                      const [lat, lng] = await currentPoint();
                      await api.testing.createVenues(lat, lng);
                      return 'Добавлены 3 тестовых места (одно рекламное) — смотрите карту в плане встречи';
                    })
                  }
                >
                  Тестовые места для плана встречи
                </Button>
              </div>
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
