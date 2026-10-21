import React, { useState } from 'react';
import { api } from '../../../core/api/client';
import type { CatalogEntry, PremiumAllowance, PremiumComposition, PremiumView } from '../../../core/types';
import { Button } from '../../../shared/components/Button';
import { cardClass, errorsFor, Field, inputClass, Labeled, LoadState, useLoad, useSubmit } from './shared';

const PERIOD_LABEL: Record<PremiumAllowance['period'], string> = { DAILY: 'в день', WEEKLY: 'в неделю', MONTHLY: 'в месяц' };
const MODE_LABEL: Record<PremiumAllowance['mode'], string> = { TOP_UP: 'доливать до', ADD: 'добавлять' };

const describe = (a: PremiumAllowance) => `${a.itemCode}: ${MODE_LABEL[a.mode]} ${a.quantity} ${PERIOD_LABEL[a.period]}`;

/** What premium includes (ADMIN only): current vs config defaults, edit, reset. */
export const PremiumPanel: React.FC = () => {
  const state = useLoad(async (signal) => {
    const [view, items] = await Promise.all([
      api.admin.getPremium(signal),
      api.admin.listCatalogItems({ kind: 'CONSUMABLE' }, signal),
    ]);
    return { view, consumables: items.filter((i) => i.active) };
  }, []);

  return (
    <LoadState state={state}>
      {({ view, consumables }) => (
        <PremiumEditor
          key={JSON.stringify(view.current)}
          view={view}
          consumables={consumables}
          onChange={(v) => state.setData({ view: v, consumables })}
        />
      )}
    </LoadState>
  );
};

const Summary: React.FC<{ title: string; c: PremiumComposition; showOverride?: boolean }> = ({ title, c, showOverride }) => (
  <div className={cardClass}>
    <h3 className="text-sm font-bold text-zinc-900">{title}</h3>
    <Field label="Функции">
      {c.features.join(', ') || '—'}
      {showOverride && (c.featuresOverridden ? ' (изменено)' : ' (из конфига)')}
    </Field>
    <Field label="Начисления">
      {c.allowances.map(describe).join('; ') || '—'}
      {showOverride && (c.allowancesOverridden ? ' (изменено)' : ' (из конфига)')}
    </Field>
  </div>
);

const PremiumEditor: React.FC<{ view: PremiumView; consumables: CatalogEntry[]; onChange: (v: PremiumView) => void }> = ({
  view,
  consumables,
  onChange,
}) => {
  const [features, setFeatures] = useState(view.current.features.join('\n'));
  const [allowances, setAllowances] = useState<Array<Omit<PremiumAllowance, 'quantity'> & { quantity: string }>>(() =>
    view.current.allowances.map((a) => ({ ...a, quantity: String(a.quantity) }))
  );
  const { busy, errors, run } = useSubmit();

  const patch = (i: number, p: Partial<(typeof allowances)[number]>) =>
    setAllowances((as) => as.map((a, j) => (j === i ? { ...a, ...p } : a)));

  const save = () => {
    const f = features
      .split(/[\s,]+/)
      .map((s) => s.trim().toUpperCase())
      .filter(Boolean);
    const a = allowances.map((x) => ({ ...x, quantity: Number(x.quantity) }));
    // Only send a list that changed: an absent list keeps the current one (and its "from config" state).
    const body = {
      features: JSON.stringify(f) === JSON.stringify(view.current.features) ? undefined : f,
      allowances: JSON.stringify(a) === JSON.stringify(view.current.allowances) ? undefined : a,
    };
    if (!body.features && !body.allowances) return;
    void run(async () => onChange(await api.admin.updatePremium(body)), 'Состав премиума сохранён');
  };

  const reset = () => {
    if (!window.confirm('Вернуть состав премиума к значениям из конфига?')) return;
    void run(async () => onChange(await api.admin.resetPremium()), 'Состав премиума сброшен');
  };

  return (
    <div className="space-y-3">
      <Summary title="Сейчас" c={view.current} showOverride />
      <Summary title="По умолчанию (конфиг)" c={view.defaults} />
      <div className={cardClass}>
        <h3 className="text-sm font-bold text-zinc-900">Изменить</h3>
        <Labeled label="Функции (коды через пробел или с новой строки, напр. ADVANCED_FILTERS)" error={errorsFor(errors, 'features')}>
          <textarea className={`${inputClass} font-mono`} rows={4} value={features} onChange={(e) => setFeatures(e.target.value)} />
        </Labeled>
        <p className="text-xs text-zinc-500">Начисления расходников (не больше одного на предмет и период)</p>
        {errors.allowances && <p className="text-xs text-rose-600">{errors.allowances}</p>}
        {allowances.map((a, i) => (
          <div key={i} className="flex flex-wrap items-end gap-2 border border-zinc-100 rounded-xl p-2">
            <select className={`${inputClass} w-auto`} aria-label="Предмет" value={a.itemCode} onChange={(e) => patch(i, { itemCode: e.target.value })}>
              <option value="">— выберите —</option>
              {consumables.map((it) => (
                <option key={it.code} value={it.code}>
                  {it.title} ({it.code})
                </option>
              ))}
              {a.itemCode && !consumables.some((it) => it.code === a.itemCode) && <option value={a.itemCode}>{a.itemCode}</option>}
            </select>
            <input
              className={`${inputClass} w-24`}
              type="number"
              min={1}
              max={1000}
              aria-label="Количество"
              value={a.quantity}
              onChange={(e) => patch(i, { quantity: e.target.value })}
            />
            <select
              className={`${inputClass} w-auto`}
              aria-label="Период"
              value={a.period}
              onChange={(e) => patch(i, { period: e.target.value as PremiumAllowance['period'] })}
            >
              {(Object.keys(PERIOD_LABEL) as PremiumAllowance['period'][]).map((p) => (
                <option key={p} value={p}>
                  {PERIOD_LABEL[p]}
                </option>
              ))}
            </select>
            <select
              className={`${inputClass} w-auto`}
              aria-label="Режим"
              value={a.mode}
              onChange={(e) => patch(i, { mode: e.target.value as PremiumAllowance['mode'] })}
            >
              {(Object.keys(MODE_LABEL) as PremiumAllowance['mode'][]).map((m) => (
                <option key={m} value={m}>
                  {MODE_LABEL[m]}
                </option>
              ))}
            </select>
            <Button type="button" size="sm" variant="ghost" onClick={() => setAllowances((as) => as.filter((_, j) => j !== i))}>
              Убрать
            </Button>
            {errorsFor(errors, `allowances[${i}]`) && (
              <p className="w-full text-xs text-rose-600">{errorsFor(errors, `allowances[${i}]`)}</p>
            )}
          </div>
        ))}
        {allowances.length < 20 && (
          <Button
            type="button"
            size="sm"
            variant="outline"
            onClick={() => setAllowances((as) => [...as, { itemCode: '', quantity: '1', period: 'DAILY', mode: 'TOP_UP' }])}
          >
            + Начисление
          </Button>
        )}
        <div className="flex flex-wrap gap-2">
          <Button type="button" size="sm" isLoading={busy} onClick={save}>
            Сохранить
          </Button>
          <Button type="button" size="sm" variant="outline" isLoading={busy} onClick={reset}>
            Сбросить к конфигу
          </Button>
        </div>
      </div>
    </div>
  );
};
