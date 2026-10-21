import React, { useState } from 'react';
import { api } from '../../../core/api/client';
import type { AchievementDefinition, AchievementReward, CatalogEntry } from '../../../core/types';
import { Button } from '../../../shared/components/Button';
import { Badge, cardClass, Check, errorsFor, Field, FormButtons, inputClass, Labeled, LoadState, useLoad, useSubmit } from './shared';

interface RewardRow {
  type: AchievementReward['type'];
  itemCode: string;
  quantity: string;
  days: string;
}

const describeReward = (r: AchievementReward) =>
  r.type === 'ITEM' ? `${r.itemCode} × ${r.quantity}` : `Премиум на ${r.days} дн.`;

/** Achievement definitions (editors): codes are fixed, only texts, rewards and active change. */
export const AchievementsPanel: React.FC = () => {
  const state = useLoad(async (signal) => {
    const [achievements, items] = await Promise.all([
      api.admin.listAchievements(signal),
      api.admin.listCatalogItems({}, signal),
    ]);
    return { achievements, items: items.filter((i) => i.active) };
  }, []);
  const [editing, setEditing] = useState<string | null>(null);

  return (
    <LoadState state={state} empty="Достижений нет." isEmpty={(d) => d.achievements.length === 0}>
      {({ achievements, items }) => (
        <ul className="space-y-2">
          {achievements.map((a) =>
            editing === a.code ? (
              <li key={a.code}>
                <AchievementForm
                  achievement={a}
                  items={items}
                  onDone={() => (setEditing(null), state.reload())}
                  onCancel={() => setEditing(null)}
                />
              </li>
            ) : (
              <li key={a.code} className={cardClass}>
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-sm font-semibold text-zinc-900">{a.title}</span>
                  {!a.active && <Badge>выключено</Badge>}
                </div>
                {a.description && <p className="text-xs text-zinc-700">{a.description}</p>}
                <Field label="Код">{a.code}</Field>
                <Field label="Награды">{a.rewards.length ? a.rewards.map(describeReward).join(', ') : 'нет'}</Field>
                <Field label="Выполнили">{a.completionCount}</Field>
                <Button type="button" size="sm" variant="outline" onClick={() => setEditing(a.code)}>
                  Изменить
                </Button>
              </li>
            )
          )}
        </ul>
      )}
    </LoadState>
  );
};

const AchievementForm: React.FC<{
  achievement: AchievementDefinition;
  items: CatalogEntry[];
  onDone: () => void;
  onCancel: () => void;
}> = ({ achievement, items, onDone, onCancel }) => {
  const [title, setTitle] = useState(achievement.title);
  const [description, setDescription] = useState(achievement.description ?? '');
  const [active, setActive] = useState(achievement.active);
  const [rewards, setRewards] = useState<RewardRow[]>(() =>
    achievement.rewards.map((r) => ({
      type: r.type,
      itemCode: r.itemCode ?? '',
      quantity: String(r.quantity ?? 1),
      days: String(r.days ?? 30),
    }))
  );
  const { busy, errors, run } = useSubmit();

  const patch = (i: number, p: Partial<RewardRow>) => setRewards((rs) => rs.map((r, j) => (j === i ? { ...r, ...p } : r)));

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    const body = {
      title,
      description: description.trim(),
      active,
      rewards: rewards.map<AchievementReward>((r) =>
        r.type === 'ITEM'
          ? { type: 'ITEM', itemCode: r.itemCode, quantity: Number(r.quantity) }
          : { type: 'PREMIUM_DAYS', days: Number(r.days) }
      ),
    };
    void run(async () => {
      await api.admin.updateAchievement(achievement.code, body);
      onDone();
    }, 'Достижение сохранено');
  };

  return (
    <form onSubmit={submit} className={cardClass}>
      <h3 className="text-sm font-bold text-zinc-900">Достижение {achievement.code}</h3>
      <Labeled label="Название" error={errors.title}>
        <input className={inputClass} value={title} maxLength={100} onChange={(e) => setTitle(e.target.value)} />
      </Labeled>
      <Labeled label="Описание" error={errors.description}>
        <textarea className={inputClass} rows={2} value={description} maxLength={300} onChange={(e) => setDescription(e.target.value)} />
      </Labeled>
      <Check label="Активно" checked={active} onChange={setActive} />
      <div className="space-y-2">
        <p className="text-xs text-zinc-500">Награды (до 10; новые применяются к следующим выполнениям)</p>
        {errors.rewards && <p className="text-xs text-rose-600">{errors.rewards}</p>}
        {rewards.map((r, i) => (
          <div key={i} className="flex flex-wrap items-end gap-2 border border-zinc-100 rounded-xl p-2">
            <select
              className={`${inputClass} w-auto`}
              aria-label="Тип награды"
              value={r.type}
              onChange={(e) => patch(i, { type: e.target.value as RewardRow['type'] })}
            >
              <option value="ITEM">Предмет</option>
              <option value="PREMIUM_DAYS">Дни премиума</option>
            </select>
            {r.type === 'ITEM' ? (
              <>
                <select
                  className={`${inputClass} w-auto`}
                  aria-label="Предмет"
                  value={r.itemCode}
                  onChange={(e) => patch(i, { itemCode: e.target.value })}
                >
                  <option value="">— выберите —</option>
                  {items.map((it) => (
                    <option key={it.code} value={it.code}>
                      {it.title} ({it.code})
                    </option>
                  ))}
                </select>
                <input
                  className={`${inputClass} w-24`}
                  type="number"
                  min={1}
                  max={1000}
                  aria-label="Количество"
                  value={r.quantity}
                  onChange={(e) => patch(i, { quantity: e.target.value })}
                />
              </>
            ) : (
              <input
                className={`${inputClass} w-24`}
                type="number"
                min={1}
                max={3650}
                aria-label="Дней"
                value={r.days}
                onChange={(e) => patch(i, { days: e.target.value })}
              />
            )}
            <Button type="button" size="sm" variant="ghost" onClick={() => setRewards((rs) => rs.filter((_, j) => j !== i))}>
              Убрать
            </Button>
            {errorsFor(errors, `rewards[${i}]`) && (
              <p className="w-full text-xs text-rose-600">{errorsFor(errors, `rewards[${i}]`)}</p>
            )}
          </div>
        ))}
        {rewards.length < 10 && (
          <Button
            type="button"
            size="sm"
            variant="outline"
            onClick={() => setRewards((rs) => [...rs, { type: 'ITEM', itemCode: '', quantity: '1', days: '30' }])}
          >
            + Награда
          </Button>
        )}
      </div>
      <p className="text-xs text-zinc-500">Косметика выдаётся ровно в 1 экземпляре.</p>
      <FormButtons busy={busy} onCancel={onCancel} />
    </form>
  );
};
