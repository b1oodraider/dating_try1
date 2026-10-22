import React, { useState } from 'react';
import { MapPin, Plus, X } from 'lucide-react';
import { api, ApiError, getErrorMessage } from '../../core/api/client';
import type { DateActivity, DatePlan, DatePlanItemInput } from '../../core/types';
import { Modal } from '../../shared/components/Modal';
import { Button } from '../../shared/components/Button';
import { PurchaseModal } from '../premium/components/PurchaseModal';
import { adText, DATE_ACTIVITIES } from './map';
import { MapPicker, type PlacePick } from './MapPicker';

const MAX_POINTS = 5;
const MAX_DAYS_AHEAD = 120;

interface Draft {
  key: string;
  place: PlacePick | null;
  /** `datetime-local` value (local time), '' = not chosen. */
  at: string;
  activity: DateActivity;
  comment: string;
}

/** Local `YYYY-MM-DDTHH:mm` for a datetime-local input. */
function toLocalInput(d: Date): string {
  const two = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${two(d.getMonth() + 1)}-${two(d.getDate())}T${two(d.getHours())}:${two(d.getMinutes())}`;
}

const newDraft = (): Draft => ({ key: crypto.randomUUID(), place: null, at: '', activity: 'COFFEE', comment: '' });

/** Propose a date (or edit the plan in play — a counter proposal). Calls onSaved with the saved plan. */
export const DatePlanEditor: React.FC<{
  matchId: string;
  plan?: DatePlan | null;
  onClose: () => void;
  onSaved: (plan: DatePlan) => void;
}> = ({ matchId, plan, onClose, onSaved }) => {
  const [drafts, setDrafts] = useState<Draft[]>(() =>
    plan
      ? plan.items.map((i) => ({
          key: crypto.randomUUID(),
          place: { name: i.placeName, address: i.address, latitude: i.latitude, longitude: i.longitude, venue: i.venue },
          at: toLocalInput(new Date(i.startsAt)),
          activity: i.activity,
          comment: i.comment ?? '',
        }))
      : [newDraft()]
  );
  const [picking, setPicking] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [buyPremium, setBuyPremium] = useState(false);

  const update = (key: string, change: Partial<Draft>) =>
    setDrafts((prev) => prev.map((d) => (d.key === key ? { ...d, ...change } : d)));

  const now = new Date();
  const minAt = toLocalInput(now);
  const maxAt = toLocalInput(new Date(now.getTime() + MAX_DAYS_AHEAD * 86_400_000));

  const save = async () => {
    const items: DatePlanItemInput[] = [];
    for (const d of drafts) {
      if (!d.place || !d.at) {
        setError('У каждого пункта должны быть место и время');
        return;
      }
      const at = new Date(d.at);
      if (at <= new Date() || at.getTime() > Date.now() + MAX_DAYS_AHEAD * 86_400_000) {
        setError(`Время встречи — в будущем и не дальше ${MAX_DAYS_AHEAD} дней`);
        return;
      }
      items.push({
        placeName: d.place.name,
        address: d.place.address ?? undefined,
        latitude: d.place.latitude,
        longitude: d.place.longitude,
        venueId: d.place.venue?.id,
        startsAt: at.toISOString(),
        activity: d.activity,
        comment: d.comment.trim() || undefined,
      });
    }
    setSaving(true);
    setError(null);
    try {
      onSaved(plan ? await api.editDatePlan(plan.id, plan.version, items) : await api.proposeDatePlan(matchId, items));
    } catch (e) {
      if (e instanceof ApiError && e.isPremiumRequired) {
        setBuyPremium(true);
      } else if (e instanceof ApiError && e.code === 'PLAN_CHANGED') {
        setError('План уже изменили — закройте и откройте его снова');
      } else {
        // PLAN_ALREADY_OPEN and validation errors come with the server's own Russian message.
        setError(getErrorMessage(e));
      }
    } finally {
      setSaving(false);
    }
  };

  const pickingDraft = drafts.find((d) => d.key === picking) ?? null;

  return (
    <>
      <Modal isOpen onClose={onClose} title={plan ? 'Изменить план' : 'Предложить встречу'} maxWidth="lg">
        <div className="space-y-4">
          {drafts.map((d, index) => (
            <div key={d.key} className="border border-zinc-200 rounded-xl p-3 space-y-2">
              <div className="flex items-center justify-between">
                <span className="text-sm font-semibold text-zinc-900">Пункт {index + 1}</span>
                {drafts.length > 1 && (
                  <button
                    type="button"
                    aria-label="Убрать пункт"
                    title="Убрать пункт"
                    onClick={() => setDrafts((prev) => prev.filter((x) => x.key !== d.key))}
                    className="p-1 text-zinc-400 hover:text-zinc-700"
                  >
                    <X className="w-4 h-4" />
                  </button>
                )}
              </div>
              <button
                type="button"
                onClick={() => setPicking(d.key)}
                className="w-full flex items-start gap-2 text-left p-2 rounded-lg bg-zinc-50 hover:bg-zinc-100"
              >
                <MapPin className="w-4 h-4 mt-0.5 text-zinc-500 shrink-0" />
                <span className="min-w-0">
                  <span className="block text-sm text-zinc-900">{d.place?.name ?? 'Выбрать место на карте'}</span>
                  {d.place?.venue?.ad ? (
                    <span className="block text-[11px] text-zinc-500">{adText(d.place.venue.ad)}</span>
                  ) : (
                    d.place?.address && <span className="block text-[11px] text-zinc-500">{d.place.address}</span>
                  )}
                </span>
              </button>
              <label className="block text-xs text-zinc-600">
                Дата и время
                <input
                  type="datetime-local"
                  value={d.at}
                  min={minAt}
                  max={maxAt}
                  onChange={(e) => update(d.key, { at: e.target.value })}
                  className="mt-1 w-full px-3 py-2 text-sm bg-zinc-50 border border-zinc-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-zinc-900"
                />
              </label>
              <div className="flex flex-wrap gap-1.5">
                {(Object.entries(DATE_ACTIVITIES) as [DateActivity, string][]).map(([code, label]) => (
                  <button
                    key={code}
                    type="button"
                    aria-pressed={d.activity === code}
                    onClick={() => update(d.key, { activity: code })}
                    className={`px-2.5 py-1 text-xs rounded-full border ${
                      d.activity === code
                        ? 'bg-zinc-900 text-white border-zinc-900'
                        : 'bg-white text-zinc-700 border-zinc-200 hover:bg-zinc-50'
                    }`}
                  >
                    {label}
                  </button>
                ))}
              </div>
              <textarea
                value={d.comment}
                onChange={(e) => update(d.key, { comment: e.target.value })}
                maxLength={300}
                rows={2}
                placeholder="Комментарий, например: встречу у входа"
                className="w-full px-3 py-2 text-sm bg-zinc-50 border border-zinc-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-zinc-900"
              />
            </div>
          ))}
          {drafts.length < MAX_POINTS && (
            <button
              type="button"
              onClick={() => setDrafts((prev) => [...prev, newDraft()])}
              className="flex items-center gap-1 text-sm text-rose-600 hover:underline"
            >
              <Plus className="w-4 h-4" /> Добавить пункт
            </button>
          )}
          {error && <p className="text-sm text-rose-600">{error}</p>}
          <Button className="w-full" isLoading={saving} onClick={() => void save()}>
            {plan ? 'Отправить изменения' : 'Предложить'}
          </Button>
        </div>
      </Modal>

      {pickingDraft && (
        <MapPicker
          initial={pickingDraft.place}
          onClose={() => setPicking(null)}
          onPick={(place) => {
            update(pickingDraft.key, { place, ...(place.venue ? { activity: place.venue.category } : {}) });
            setPicking(null);
          }}
        />
      )}

      <PurchaseModal
        kind={buyPremium ? 'premium' : null}
        onClose={() => setBuyPremium(false)}
        onBought={() => void save()}
      />
    </>
  );
};
