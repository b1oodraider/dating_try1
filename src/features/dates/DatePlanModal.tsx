import React, { useCallback, useEffect, useState } from 'react';
import { Lock, Pencil } from 'lucide-react';
import { CircleMarker, MapContainer } from 'react-leaflet';
import { api, formatDateTime, getErrorMessage } from '../../core/api/client';
import type { DatePlan } from '../../core/types';
import { Modal } from '../../shared/components/Modal';
import { Button } from '../../shared/components/Button';
import { PurchaseModal } from '../premium/components/PurchaseModal';
import { AdLabel, DATE_ACTIVITIES, MapTiles, PIN } from './map';
import { DatePlanEditor } from './DatePlanEditor';

function statusText(plan: DatePlan): string {
  switch (plan.status) {
    case 'PROPOSED':
      return plan.lastEditMine ? 'Ждём ответа' : 'Вам предложили встречу';
    case 'ACCEPTED':
      return 'Договорились';
    case 'DECLINED':
      return 'Отклонён';
    case 'CANCELLED':
      return 'Отменён';
  }
}

/** The date plan with its actions: accept / decline (the side it was proposed to), edit (premium), cancel. */
export const DatePlanModal: React.FC<{ matchId: string; planId: string; onClose: () => void }> = ({
  matchId,
  planId,
  onClose,
}) => {
  const [plan, setPlan] = useState<DatePlan | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState(false);
  const [buyPremium, setBuyPremium] = useState(false);

  const load = useCallback(async () => {
    try {
      setPlan(await api.getDatePlanById(planId));
      setError(null);
    } catch (e) {
      setError(getErrorMessage(e, 'Не удалось загрузить план'));
    } finally {
      setLoading(false);
    }
  }, [planId]);

  useEffect(() => {
    void load();
  }, [load]);

  const act = async (action: 'accept' | 'decline' | 'cancel') => {
    if (!plan) return;
    setBusy(true);
    setError(null);
    try {
      setPlan(await api.answerDatePlan(plan.id, action));
    } catch (e) {
      setError(getErrorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  const open = plan?.status === 'PROPOSED' || plan?.status === 'ACCEPTED';
  const points = plan?.items.map((i) => [i.latitude, i.longitude] as [number, number]) ?? [];

  return (
    <>
      <Modal isOpen onClose={onClose} title="План встречи" maxWidth="lg">
        {loading ? (
          <p className="py-8 text-center text-sm text-zinc-400">Загрузка…</p>
        ) : !plan ? (
          <p className="text-sm text-zinc-700">{error ?? 'Плана пока нет'}</p>
        ) : (
          <div className="space-y-4">
            <p className={`text-sm font-semibold ${plan.status === 'ACCEPTED' ? 'text-emerald-700' : 'text-zinc-600'}`}>
              {statusText(plan)}
            </p>
            {points.length > 0 && (
              <div className="h-44 rounded-xl overflow-hidden border border-zinc-200">
                <MapContainer
                  key={plan.version}
                  {...(points.length > 1
                    ? { bounds: points, boundsOptions: { padding: [32, 32] as [number, number] } }
                    : { center: points[0], zoom: 14 })}
                  className="h-full w-full"
                  dragging={false}
                  zoomControl={false}
                  scrollWheelZoom={false}
                  doubleClickZoom={false}
                  touchZoom={false}
                  boxZoom={false}
                  keyboard={false}
                >
                  <MapTiles />
                  {points.map((p, i) => (
                    <CircleMarker key={i} center={p} radius={9} pathOptions={{ color: PIN.picked, fillOpacity: 0.8 }} />
                  ))}
                </MapContainer>
              </div>
            )}
            <ul className="space-y-3">
              {plan.items.map((item, i) => (
                <li key={i} className="text-sm">
                  <p className="font-semibold text-zinc-900">{formatDateTime(item.startsAt)}</p>
                  <p className="text-zinc-800">
                    {item.placeName} · {DATE_ACTIVITIES[item.activity] ?? item.activity}
                  </p>
                  {item.address && <p className="text-zinc-500">{item.address}</p>}
                  {item.venue?.ad && <AdLabel ad={item.venue.ad} />}
                  {item.comment && <p className="text-zinc-700">«{item.comment}»</p>}
                </li>
              ))}
            </ul>
            {error && <p className="text-sm text-rose-600">{error}</p>}
            <div className="space-y-2">
              {plan.canRespond && (
                <>
                  <Button className="w-full" disabled={busy} onClick={() => void act('accept')}>
                    Принять
                  </Button>
                  <Button className="w-full" variant="outline" disabled={busy} onClick={() => void act('decline')}>
                    Отклонить
                  </Button>
                </>
              )}
              {open && (
                <>
                  <Button
                    className="w-full"
                    variant="outline"
                    disabled={busy}
                    onClick={() => (plan.canEdit ? setEditing(true) : setBuyPremium(true))}
                  >
                    {plan.canEdit ? <Pencil className="w-4 h-4" /> : <Lock className="w-4 h-4" />}
                    Изменить (встречное предложение)
                  </Button>
                  {!plan.canEdit && (
                    <p className="text-xs text-center text-zinc-500">Изменять план можно с премиумом</p>
                  )}
                  <Button className="w-full" variant="ghost" disabled={busy} onClick={() => void act('cancel')}>
                    Отменить план
                  </Button>
                </>
              )}
            </div>
          </div>
        )}
      </Modal>

      {editing && plan && (
        <DatePlanEditor
          matchId={matchId}
          plan={plan}
          onClose={() => setEditing(false)}
          onSaved={(saved) => {
            setPlan(saved);
            setEditing(false);
          }}
        />
      )}

      {/* After a purchase the plan reloads (now editable). */}
      <PurchaseModal kind={buyPremium ? 'premium' : null} onClose={() => setBuyPremium(false)} onBought={() => void load()} />
    </>
  );
};
