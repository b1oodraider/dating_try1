import React, { useState } from 'react';
import { api, ApiError } from '../../../core/api/client';
import type { AdminGoal, AdminTag, AdminTagCategory, DeleteResult } from '../../../core/types';
import { Button } from '../../../shared/components/Button';
import {
  Badge,
  cardClass,
  Check,
  Field,
  FormButtons,
  inputClass,
  Labeled,
  LoadState,
  optNum,
  SubTabs,
  useLoad,
  useSubmit,
} from './shared';

type Tab = 'tags' | 'categories' | 'goals';

const deletedText = (r: DeleteResult, what: string) =>
  r.result === 'DELETED' ? `${what}: удалено` : `${what}: используется в анкетах — скрыто (можно показать снова)`;

/** Tag categories, tags («карточки») and goals (CONTENT_EDITOR or ADMIN). */
export const ContentPanel: React.FC = () => {
  const [tab, setTab] = useState<Tab>('tags');
  const state = useLoad(async (signal) => {
    const [categories, tags, goals] = await Promise.all([
      api.admin.listTagCategories(signal),
      api.admin.listTags(signal),
      api.admin.listGoals(signal),
    ]);
    return { categories, tags, goals };
  }, []);

  return (
    <div className="space-y-3">
      <SubTabs
        tabs={[
          ['tags', 'Карточки'],
          ['categories', 'Категории'],
          ['goals', 'Цели'],
        ]}
        value={tab}
        onChange={setTab}
      />
      <LoadState state={state}>
        {(d) =>
          tab === 'tags' ? (
            <Tags tags={d.tags} categories={d.categories} reload={state.reload} />
          ) : tab === 'categories' ? (
            <Categories tags={d.tags} categories={d.categories} reload={state.reload} />
          ) : (
            <Goals goals={d.goals} reload={state.reload} />
          )
        }
      </LoadState>
    </div>
  );
};

// --- Tags ---

const Tags: React.FC<{ tags: AdminTag[]; categories: AdminTagCategory[]; reload: () => void }> = ({
  tags,
  categories,
  reload,
}) => {
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<string | null>(null);
  const { busy, run, showToast } = useSubmit();

  const remove = (t: AdminTag) => {
    if (!window.confirm(`Удалить карточку «${t.label}»? Если она используется в анкетах, её скроют.`)) return;
    void run(async () => {
      showToast(deletedText(await api.admin.deleteTag(t.code), t.label), 'success');
      reload();
    });
  };
  const activate = (t: AdminTag) =>
    void run(async () => {
      await api.admin.updateTag(t.code, {
        category: t.category,
        label: t.label,
        description: t.description,
        sortOrder: t.sortOrder,
        active: true,
      });
      reload();
    }, 'Карточка снова видна');

  // Group by category in category order; tags of an unknown category go last under their own code.
  const groups = [
    ...categories.map((c) => ({ code: c.code, label: c.label })),
    ...[...new Set(tags.map((t) => t.category))]
      .filter((code) => !categories.some((c) => c.code === code))
      .map((code) => ({ code, label: code })),
  ];

  return (
    <div className="space-y-3">
      {creating ? (
        <TagForm categories={categories} onDone={() => (setCreating(false), reload())} onCancel={() => setCreating(false)} />
      ) : (
        <Button type="button" size="sm" onClick={() => setCreating(true)} disabled={categories.length === 0}>
          + Новая карточка
        </Button>
      )}
      {groups.map((g) => {
        const items = tags.filter((t) => t.category === g.code);
        return (
          <section key={g.code} className="space-y-2">
            <h3 className="text-sm font-bold text-zinc-900">
              {g.label} <span className="text-xs font-normal text-zinc-500">({g.code})</span>
            </h3>
            {items.length === 0 && <p className="text-xs text-zinc-500">Карточек нет.</p>}
            {items.map((t) =>
              editing === t.code ? (
                <TagForm
                  key={t.code}
                  tag={t}
                  categories={categories}
                  onDone={() => (setEditing(null), reload())}
                  onCancel={() => setEditing(null)}
                />
              ) : (
                <div key={t.code} className={cardClass}>
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-sm font-semibold text-zinc-900">{t.label}</span>
                    {!t.active && <Badge>скрыта</Badge>}
                  </div>
                  <p className="text-xs text-zinc-700">{t.description}</p>
                  <Field label="Код">{t.code}</Field>
                  <Field label="Порядок">{t.sortOrder}</Field>
                  <Field label="В анкетах">{t.usageCount}</Field>
                  {t.scalePosition !== null && <Field label="Место на шкале">{t.scalePosition}</Field>}
                  <div className="flex flex-wrap gap-2">
                    <Button type="button" size="sm" variant="outline" onClick={() => setEditing(t.code)}>
                      Изменить
                    </Button>
                    {!t.active && (
                      <Button type="button" size="sm" variant="outline" isLoading={busy} onClick={() => activate(t)}>
                        Показать снова
                      </Button>
                    )}
                    <Button type="button" size="sm" variant="ghost" isLoading={busy} onClick={() => remove(t)}>
                      Удалить
                    </Button>
                  </div>
                </div>
              )
            )}
          </section>
        );
      })}
    </div>
  );
};

const TagForm: React.FC<{
  tag?: AdminTag;
  categories: AdminTagCategory[];
  onDone: () => void;
  onCancel: () => void;
}> = ({ tag, categories, onDone, onCancel }) => {
  const [code, setCode] = useState('');
  const [category, setCategory] = useState(tag?.category ?? categories[0]?.code ?? '');
  const [label, setLabel] = useState(tag?.label ?? '');
  const [description, setDescription] = useState(tag?.description ?? '');
  const [sortOrder, setSortOrder] = useState(String(tag?.sortOrder ?? 0));
  const [active, setActive] = useState(tag?.active ?? true);
  const { busy, errors, run } = useSubmit();

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    const body = { category, label, description, sortOrder: optNum(sortOrder), active };
    void run(async () => {
      if (tag) await api.admin.updateTag(tag.code, body);
      else await api.admin.createTag({ code: code.trim(), ...body });
      onDone();
    }, tag ? 'Карточка сохранена' : 'Карточка создана');
  };

  return (
    <form onSubmit={submit} className={cardClass}>
      <h3 className="text-sm font-bold text-zinc-900">{tag ? `Карточка ${tag.code}` : 'Новая карточка'}</h3>
      {!tag && (
        <Labeled label="Код (латиница A–Z, цифры, _; потом не меняется)" error={errors.code}>
          <input className={inputClass} value={code} maxLength={40} onChange={(e) => setCode(e.target.value.toUpperCase())} />
        </Labeled>
      )}
      <Labeled label="Категория" error={errors.category}>
        <select className={inputClass} value={category} onChange={(e) => setCategory(e.target.value)}>
          {categories.map((c) => (
            <option key={c.code} value={c.code}>
              {c.label}
            </option>
          ))}
        </select>
      </Labeled>
      <Labeled label="Название" error={errors.label}>
        <input className={inputClass} value={label} maxLength={60} onChange={(e) => setLabel(e.target.value)} />
      </Labeled>
      <Labeled label="Описание" error={errors.description}>
        <textarea className={inputClass} rows={2} value={description} maxLength={255} onChange={(e) => setDescription(e.target.value)} />
      </Labeled>
      <Labeled label="Порядок (0–100000)" error={errors.sortOrder}>
        <input className={inputClass} type="number" min={0} max={100000} value={sortOrder} onChange={(e) => setSortOrder(e.target.value)} />
      </Labeled>
      <Check label="Видна пользователям" checked={active} onChange={setActive} />
      <FormButtons busy={busy} onCancel={onCancel} />
    </form>
  );
};

// --- Categories ---

const Categories: React.FC<{ tags: AdminTag[]; categories: AdminTagCategory[]; reload: () => void }> = ({
  tags,
  categories,
  reload,
}) => {
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<string | null>(null);
  const [scaling, setScaling] = useState<string | null>(null);
  const { busy, run } = useSubmit();

  const remove = (c: AdminTagCategory) => {
    if (!window.confirm(`Удалить категорию «${c.label}»?`)) return;
    void run(async () => {
      try {
        await api.admin.deleteTagCategory(c.code);
      } catch (e) {
        if (e instanceof ApiError && e.code === 'CATEGORY_IN_USE') {
          throw new Error('В категории есть карточки (в том числе скрытые). Сначала перенесите их в другую категорию.');
        }
        throw e;
      }
      reload();
    }, 'Категория удалена');
  };

  return (
    <div className="space-y-3">
      {creating ? (
        <CategoryForm onDone={() => (setCreating(false), reload())} onCancel={() => setCreating(false)} />
      ) : (
        <Button type="button" size="sm" onClick={() => setCreating(true)}>
          + Новая категория
        </Button>
      )}
      {categories.map((c) =>
        editing === c.code ? (
          <CategoryForm key={c.code} category={c} onDone={() => (setEditing(null), reload())} onCancel={() => setEditing(null)} />
        ) : scaling === c.code ? (
          <ScaleForm
            key={c.code}
            category={c}
            tags={tags.filter((t) => t.category === c.code)}
            onDone={() => (setScaling(null), reload())}
            onCancel={() => setScaling(null)}
          />
        ) : (
          <div key={c.code} className={cardClass}>
            <span className="text-sm font-semibold text-zinc-900">{c.label}</span>
            <Field label="Код">{c.code}</Field>
            <Field label="Порядок">{c.sortOrder}</Field>
            <Field label="Карточек">{c.tagCount}</Field>
            <Field label="Шкала">{c.scale ? `${c.scaleMinLabel} → ${c.scaleMaxLabel}` : 'нет'}</Field>
            <div className="flex flex-wrap gap-2">
              <Button type="button" size="sm" variant="outline" onClick={() => setEditing(c.code)}>
                Изменить
              </Button>
              <Button type="button" size="sm" variant="outline" onClick={() => setScaling(c.code)}>
                Шкала
              </Button>
              <Button type="button" size="sm" variant="ghost" isLoading={busy} onClick={() => remove(c)}>
                Удалить
              </Button>
            </div>
          </div>
        )
      )}
    </div>
  );
};

const CategoryForm: React.FC<{ category?: AdminTagCategory; onDone: () => void; onCancel: () => void }> = ({
  category,
  onDone,
  onCancel,
}) => {
  const [code, setCode] = useState('');
  const [label, setLabel] = useState(category?.label ?? '');
  const [sortOrder, setSortOrder] = useState(String(category?.sortOrder ?? 0));
  const { busy, errors, run } = useSubmit();

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    const body = { label, sortOrder: optNum(sortOrder) };
    void run(async () => {
      if (category) await api.admin.updateTagCategory(category.code, body);
      else await api.admin.createTagCategory({ code: code.trim(), ...body });
      onDone();
    }, category ? 'Категория сохранена' : 'Категория создана');
  };

  return (
    <form onSubmit={submit} className={cardClass}>
      <h3 className="text-sm font-bold text-zinc-900">{category ? `Категория ${category.code}` : 'Новая категория'}</h3>
      {!category && (
        <Labeled label="Код (строчные a–z, цифры, _; потом не меняется)" error={errors.code}>
          <input className={inputClass} value={code} maxLength={30} onChange={(e) => setCode(e.target.value.toLowerCase())} />
        </Labeled>
      )}
      <Labeled label="Название" error={errors.label}>
        <input className={inputClass} value={label} maxLength={50} onChange={(e) => setLabel(e.target.value)} />
      </Labeled>
      <Labeled label="Порядок (0–100000)" error={errors.sortOrder}>
        <input className={inputClass} type="number" min={0} max={100000} value={sortOrder} onChange={(e) => setSortOrder(e.target.value)} />
      </Labeled>
      <FormButtons busy={busy} onCancel={onCancel} />
    </form>
  );
};

const ScaleForm: React.FC<{ category: AdminTagCategory; tags: AdminTag[]; onDone: () => void; onCancel: () => void }> = ({
  category,
  tags,
  onDone,
  onCancel,
}) => {
  const [scale, setScale] = useState(category.scale);
  const [minLabel, setMinLabel] = useState(category.scaleMinLabel ?? '');
  const [maxLabel, setMaxLabel] = useState(category.scaleMaxLabel ?? '');
  const [positions, setPositions] = useState<Record<string, string>>(() =>
    Object.fromEntries(tags.map((t) => [t.code, t.scalePosition === null ? '' : String(t.scalePosition)]))
  );
  const { busy, errors, run } = useSubmit();
  const shown = new Set(['scaleMinLabel', 'scaleMaxLabel', ...tags.flatMap((t) => [`positions.${t.code}`, `positions[${t.code}]`])]);

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    const body = scale
      ? {
          scale,
          scaleMinLabel: minLabel.trim(),
          scaleMaxLabel: maxLabel.trim(),
          positions: Object.fromEntries(
            Object.entries(positions)
              .filter(([, v]) => v.trim() !== '')
              .map(([k, v]) => [k, Number(v)])
          ),
        }
      : { scale: false };
    void run(async () => {
      await api.admin.setTagCategoryScale(category.code, body);
      onDone();
    }, 'Шкала сохранена');
  };

  return (
    <form onSubmit={submit} className={cardClass}>
      <h3 className="text-sm font-bold text-zinc-900">Шкала: {category.label}</h3>
      <p className="text-xs text-zinc-500">
        Карточки без места на шкалу не попадают. Нужны обе подписи и хотя бы 2 карточки этой категории.
      </p>
      <Check label="Категория — шкала" checked={scale} onChange={setScale} />
      {scale && (
        <>
          <Labeled label="Подпись начала (1)" error={errors.scaleMinLabel}>
            <input className={inputClass} value={minLabel} maxLength={60} onChange={(e) => setMinLabel(e.target.value)} />
          </Labeled>
          <Labeled label="Подпись конца (100)" error={errors.scaleMaxLabel}>
            <input className={inputClass} value={maxLabel} maxLength={60} onChange={(e) => setMaxLabel(e.target.value)} />
          </Labeled>
          {tags.map((t) => (
            <Labeled key={t.code} label={`${t.label} (${t.code}), 1–100`} error={errors[`positions.${t.code}`] ?? errors[`positions[${t.code}]`]}>
              <input
                className={inputClass}
                type="number"
                min={1}
                max={100}
                value={positions[t.code] ?? ''}
                onChange={(e) => setPositions((p) => ({ ...p, [t.code]: e.target.value }))}
              />
            </Labeled>
          ))}
        </>
      )}
      {Object.entries(errors)
        .filter(([k]) => !shown.has(k))
        .map(([k, m]) => (
          <p key={k} className="text-xs text-rose-600">
            {m}
          </p>
        ))}
      <FormButtons busy={busy} onCancel={onCancel} />
    </form>
  );
};

// --- Goals ---

const Goals: React.FC<{ goals: AdminGoal[]; reload: () => void }> = ({ goals, reload }) => {
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<string | null>(null);
  const { busy, run, showToast } = useSubmit();

  const remove = (g: AdminGoal) => {
    if (!window.confirm(`Удалить цель «${g.label}»? Если она используется, её скроют.`)) return;
    void run(async () => {
      showToast(deletedText(await api.admin.deleteGoal(g.code), g.label), 'success');
      reload();
    });
  };
  const activate = (g: AdminGoal) =>
    void run(async () => {
      await api.admin.updateGoal(g.code, { label: g.label, sortOrder: g.sortOrder, active: true });
      reload();
    }, 'Цель снова видна');

  return (
    <div className="space-y-3">
      {creating ? (
        <GoalForm onDone={() => (setCreating(false), reload())} onCancel={() => setCreating(false)} />
      ) : (
        <Button type="button" size="sm" onClick={() => setCreating(true)}>
          + Новая цель
        </Button>
      )}
      {goals.map((g) =>
        editing === g.code ? (
          <GoalForm key={g.code} goal={g} onDone={() => (setEditing(null), reload())} onCancel={() => setEditing(null)} />
        ) : (
          <div key={g.code} className={cardClass}>
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-sm font-semibold text-zinc-900">{g.label}</span>
              {!g.active && <Badge>скрыта</Badge>}
            </div>
            <Field label="Код">{g.code}</Field>
            <Field label="Порядок">{g.sortOrder}</Field>
            <Field label="Используется">{g.usageCount}</Field>
            <div className="flex flex-wrap gap-2">
              <Button type="button" size="sm" variant="outline" onClick={() => setEditing(g.code)}>
                Изменить
              </Button>
              {!g.active && (
                <Button type="button" size="sm" variant="outline" isLoading={busy} onClick={() => activate(g)}>
                  Показать снова
                </Button>
              )}
              <Button type="button" size="sm" variant="ghost" isLoading={busy} onClick={() => remove(g)}>
                Удалить
              </Button>
            </div>
          </div>
        )
      )}
    </div>
  );
};

const GoalForm: React.FC<{ goal?: AdminGoal; onDone: () => void; onCancel: () => void }> = ({ goal, onDone, onCancel }) => {
  const [code, setCode] = useState('');
  const [label, setLabel] = useState(goal?.label ?? '');
  const [sortOrder, setSortOrder] = useState(String(goal?.sortOrder ?? 0));
  const [active, setActive] = useState(goal?.active ?? true);
  const { busy, errors, run } = useSubmit();

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    const body = { label, sortOrder: optNum(sortOrder), active };
    void run(async () => {
      if (goal) await api.admin.updateGoal(goal.code, body);
      else await api.admin.createGoal({ code: code.trim(), ...body });
      onDone();
    }, goal ? 'Цель сохранена' : 'Цель создана');
  };

  return (
    <form onSubmit={submit} className={cardClass}>
      <h3 className="text-sm font-bold text-zinc-900">{goal ? `Цель ${goal.code}` : 'Новая цель'}</h3>
      {!goal && (
        <Labeled label="Код (латиница A–Z, цифры, _; потом не меняется)" error={errors.code}>
          <input className={inputClass} value={code} maxLength={40} onChange={(e) => setCode(e.target.value.toUpperCase())} />
        </Labeled>
      )}
      <Labeled label="Название" error={errors.label}>
        <input className={inputClass} value={label} maxLength={60} onChange={(e) => setLabel(e.target.value)} />
      </Labeled>
      <Labeled label="Порядок (0–100000)" error={errors.sortOrder}>
        <input className={inputClass} type="number" min={0} max={100000} value={sortOrder} onChange={(e) => setSortOrder(e.target.value)} />
      </Labeled>
      <Check label="Видна пользователям" checked={active} onChange={setActive} />
      <FormButtons busy={busy} onCancel={onCancel} />
    </form>
  );
};
