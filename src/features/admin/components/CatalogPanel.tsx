import React, { useState } from 'react';
import { api, ApiError } from '../../../core/api/client';
import type { Author, CatalogEntry, CatalogKind, CatalogRarity, EquipSlot } from '../../../core/types';
import { Button } from '../../../shared/components/Button';
import {
  Badge,
  cardClass,
  Check,
  Field,
  formatTs,
  FormButtons,
  inputClass,
  Labeled,
  LoadState,
  optNum,
  optText,
  SubTabs,
  useLoad,
  useSubmit,
} from './shared';

const RARITIES: CatalogRarity[] = ['COMMON', 'RARE', 'EPIC', 'LEGENDARY'];
const SLOTS: EquipSlot[] = ['PHOTO_FRAME', 'PROFILE_STATUS', 'CHAT_WALLPAPER'];

/** ISO -> value for <input type="datetime-local"> in local time. */
const toLocalInput = (iso: string | null) => {
  if (!iso) return '';
  const d = new Date(iso);
  return new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
};
const fromLocalInput = (s: string) => (s ? new Date(s).toISOString() : undefined);

/** Catalog items (editors) and authors (list for editors, changes for ADMIN). */
export const CatalogPanel: React.FC<{ isAdmin: boolean }> = ({ isAdmin }) => {
  const [tab, setTab] = useState<'items' | 'authors'>('items');
  const authors = useLoad((signal) => api.admin.listAuthors(signal), []);
  return (
    <div className="space-y-3">
      <SubTabs
        tabs={[
          ['items', 'Предметы'],
          ['authors', 'Авторы'],
        ]}
        value={tab}
        onChange={setTab}
      />
      <LoadState state={authors}>
        {(list) =>
          tab === 'items' ? <Items authors={list} /> : <Authors authors={list} isAdmin={isAdmin} reload={authors.reload} />
        }
      </LoadState>
    </div>
  );
};

// --- Items ---

const Items: React.FC<{ authors: Author[] }> = ({ authors }) => {
  const [kind, setKind] = useState<CatalogKind | ''>('');
  const [category, setCategory] = useState('');
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<string | null>(null);
  const items = useLoad(
    (signal) => api.admin.listCatalogItems({ kind: kind || undefined, category: category.trim() || undefined }, signal),
    [kind, category]
  );

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap gap-2">
        <select className={`${inputClass} w-auto`} value={kind} aria-label="Тип" onChange={(e) => setKind(e.target.value as CatalogKind | '')}>
          <option value="">Все типы</option>
          <option value="CONSUMABLE">Расходники</option>
          <option value="COSMETIC">Косметика</option>
        </select>
        <input
          className={`${inputClass} w-48`}
          placeholder="Категория, напр. PHOTO_FRAME"
          aria-label="Категория"
          value={category}
          onChange={(e) => setCategory(e.target.value.toUpperCase())}
        />
      </div>
      {creating ? (
        <ItemForm authors={authors} onDone={() => (setCreating(false), items.reload())} onCancel={() => setCreating(false)} />
      ) : (
        <Button type="button" size="sm" onClick={() => setCreating(true)}>
          + Новый предмет
        </Button>
      )}
      <LoadState state={items} empty="Предметов нет." isEmpty={(d) => d.length === 0}>
        {(list) => (
          <ul className="space-y-2">
            {list.map((it) =>
              editing === it.code ? (
                <li key={it.code}>
                  <ItemForm
                    item={it}
                    authors={authors}
                    onDone={() => (setEditing(null), items.reload())}
                    onCancel={() => setEditing(null)}
                  />
                </li>
              ) : (
                <li key={it.code} className={cardClass}>
                  <div className="flex flex-wrap items-center gap-2">
                    {typeof it.attributes.imageUrl === 'string' && (
                      <img src={it.attributes.imageUrl} alt="" className="w-10 h-10 rounded-lg object-cover bg-zinc-100" />
                    )}
                    <span className="text-sm font-semibold text-zinc-900">{it.title}</span>
                    {!it.active && <Badge>выключен</Badge>}
                    {it.rarity && <Badge>{it.rarity}</Badge>}
                  </div>
                  <Field label="Код">{it.code}</Field>
                  <Field label="Тип / категория / слот">
                    {it.kind} / {it.category} / {it.equipSlot ?? '—'}
                  </Field>
                  <Field label="Доступен">
                    {it.availableFrom || it.availableUntil
                      ? `${formatTs(it.availableFrom)} — ${formatTs(it.availableUntil)}`
                      : 'всегда'}
                  </Field>
                  <Field label="Автор">{it.authorName ?? '—'}</Field>
                  <Field label="Атрибуты">{JSON.stringify(it.attributes)}</Field>
                  <div className="flex flex-wrap gap-2 items-center">
                    <Button type="button" size="sm" variant="outline" onClick={() => setEditing(it.code)}>
                      Изменить
                    </Button>
                    <ImageUpload code={it.code} onDone={items.reload} />
                  </div>
                </li>
              )
            )}
          </ul>
        )}
      </LoadState>
    </div>
  );
};

const ImageUpload: React.FC<{ code: string; onDone: () => void }> = ({ code, onDone }) => {
  const { busy, run } = useSubmit();
  return (
    <label className="text-xs text-zinc-700 cursor-pointer border border-zinc-200 rounded-xl px-3 py-1.5 hover:bg-zinc-50">
      {busy ? 'Загрузка…' : 'Загрузить картинку'}
      <input
        type="file"
        accept="image/jpeg,image/png,image/webp"
        className="sr-only"
        disabled={busy}
        onChange={(e) => {
          const file = e.target.files?.[0];
          e.target.value = '';
          if (!file) return;
          void run(async () => {
            await api.admin.uploadCatalogImage(code, file);
            onDone();
          }, 'Картинка загружена');
        }}
      />
    </label>
  );
};

const ItemForm: React.FC<{ item?: CatalogEntry; authors: Author[]; onDone: () => void; onCancel: () => void }> = ({
  item,
  authors,
  onDone,
  onCancel,
}) => {
  const [code, setCode] = useState('');
  const [kind, setKind] = useState<CatalogKind>('COSMETIC');
  const [category, setCategory] = useState('');
  const [equipSlot, setEquipSlot] = useState<EquipSlot | ''>('');
  const [title, setTitle] = useState(item?.title ?? '');
  const [rarity, setRarity] = useState<CatalogRarity | ''>(item?.rarity ?? '');
  const [active, setActive] = useState(item?.active ?? true);
  const [from, setFrom] = useState(toLocalInput(item?.availableFrom ?? null));
  const [until, setUntil] = useState(toLocalInput(item?.availableUntil ?? null));
  const [authorId, setAuthorId] = useState(item?.authorId ?? '');
  const [attributes, setAttributes] = useState(JSON.stringify(item?.attributes ?? {}, null, 2));
  const [jsonError, setJsonError] = useState('');
  const { busy, errors, run } = useSubmit();

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    let attrs: Record<string, unknown>;
    try {
      const parsed: unknown = JSON.parse(attributes);
      if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) throw new Error();
      attrs = parsed as Record<string, unknown>;
    } catch {
      setJsonError('Атрибуты должны быть JSON-объектом, например {"icon": "star"}');
      return;
    }
    setJsonError('');
    const common = {
      title,
      rarity: rarity || undefined,
      attributes: attrs,
      active,
      availableFrom: fromLocalInput(from),
      availableUntil: fromLocalInput(until),
      authorId: authorId || undefined,
    };
    void run(async () => {
      if (item) await api.admin.updateCatalogItem(item.code, common);
      else
        await api.admin.createCatalogItem({
          code: code.trim(),
          kind,
          category: category.trim(),
          equipSlot: equipSlot || undefined,
          ...common,
        });
      onDone();
    }, item ? 'Предмет сохранён' : 'Предмет создан');
  };

  const attrErrors = Object.entries(errors).filter(([k]) => k === 'attributes' || k.startsWith('attributes.'));

  return (
    <form onSubmit={submit} className={cardClass}>
      <h3 className="text-sm font-bold text-zinc-900">{item ? `Предмет ${item.code}` : 'Новый предмет'}</h3>
      {!item && (
        <>
          <p className="text-xs text-zinc-500">Код, тип, категория и слот потом не меняются.</p>
          <Labeled label="Код (A–Z, цифры, _)" error={errors.code}>
            <input className={inputClass} value={code} maxLength={50} onChange={(e) => setCode(e.target.value.toUpperCase())} />
          </Labeled>
          <Labeled label="Тип" error={errors.kind}>
            <select className={inputClass} value={kind} onChange={(e) => setKind(e.target.value as CatalogKind)}>
              <option value="COSMETIC">Косметика</option>
              <option value="CONSUMABLE">Расходник</option>
            </select>
          </Labeled>
          <Labeled label="Категория (A–Z, цифры, _), напр. PHOTO_FRAME, STICKER_PACK" error={errors.category}>
            <input className={inputClass} value={category} maxLength={30} onChange={(e) => setCategory(e.target.value.toUpperCase())} />
          </Labeled>
          <Labeled label="Слот" error={errors.equipSlot}>
            <select className={inputClass} value={equipSlot} onChange={(e) => setEquipSlot(e.target.value as EquipSlot | '')}>
              <option value="">По категории (по умолчанию)</option>
              {SLOTS.map((s) => (
                <option key={s} value={s}>
                  {s}
                </option>
              ))}
            </select>
          </Labeled>
        </>
      )}
      <Labeled label="Название" error={errors.title}>
        <input className={inputClass} value={title} maxLength={80} onChange={(e) => setTitle(e.target.value)} />
      </Labeled>
      <Labeled label="Редкость" error={errors.rarity}>
        <select className={inputClass} value={rarity} onChange={(e) => setRarity(e.target.value as CatalogRarity | '')}>
          <option value="">Нет</option>
          {RARITIES.map((r) => (
            <option key={r} value={r}>
              {r}
            </option>
          ))}
        </select>
      </Labeled>
      <Labeled label="Доступен с" error={errors.availableFrom}>
        <input className={inputClass} type="datetime-local" value={from} onChange={(e) => setFrom(e.target.value)} />
      </Labeled>
      <Labeled label="Доступен до (не включая)" error={errors.availableUntil}>
        <input className={inputClass} type="datetime-local" value={until} onChange={(e) => setUntil(e.target.value)} />
      </Labeled>
      <Labeled label="Автор" error={errors.authorId}>
        <select className={inputClass} value={authorId} onChange={(e) => setAuthorId(e.target.value)}>
          <option value="">Нет</option>
          {authors.map((a) => (
            <option key={a.id} value={a.id}>
              {a.displayName}
            </option>
          ))}
        </select>
      </Labeled>
      <Labeled label="Атрибуты (JSON)" error={jsonError}>
        <textarea
          className={`${inputClass} font-mono`}
          rows={6}
          value={attributes}
          onChange={(e) => setAttributes(e.target.value)}
        />
      </Labeled>
      {attrErrors.map(([k, m]) => (
        <p key={k} className="text-xs text-rose-600">
          {k}: {m}
        </p>
      ))}
      <Check label="Активен" checked={active} onChange={setActive} />
      <FormButtons busy={busy} onCancel={onCancel} />
    </form>
  );
};

// --- Authors ---

const Authors: React.FC<{ authors: Author[]; isAdmin: boolean; reload: () => void }> = ({ authors, isAdmin, reload }) => {
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<string | null>(null);
  const { busy, run } = useSubmit();

  const remove = (a: Author) => {
    if (!window.confirm(`Удалить автора «${a.displayName}»?`)) return;
    void run(async () => {
      try {
        await api.admin.deleteAuthor(a.id);
      } catch (e) {
        if (e instanceof ApiError && e.code === 'AUTHOR_IN_USE') throw new Error('У автора есть предметы в каталоге — удалить нельзя.');
        throw e;
      }
      reload();
    }, 'Автор удалён');
  };

  return (
    <div className="space-y-3">
      {isAdmin &&
        (creating ? (
          <AuthorForm onDone={() => (setCreating(false), reload())} onCancel={() => setCreating(false)} />
        ) : (
          <Button type="button" size="sm" onClick={() => setCreating(true)}>
            + Новый автор
          </Button>
        ))}
      {authors.length === 0 && <p className="text-xs text-zinc-500">Авторов нет.</p>}
      {authors.map((a) =>
        editing === a.id ? (
          <AuthorForm key={a.id} author={a} onDone={() => (setEditing(null), reload())} onCancel={() => setEditing(null)} />
        ) : (
          <div key={a.id} className={cardClass}>
            <span className="text-sm font-semibold text-zinc-900">{a.displayName}</span>
            <Field label="Аккаунт">{a.accountId ?? '—'}</Field>
            <Field label="Доля, %">{a.revenueSharePercent}</Field>
            <Field label="Предметов">{a.itemCount}</Field>
            {a.contactNote && <Field label="Контакт">{a.contactNote}</Field>}
            {isAdmin && (
              <div className="flex flex-wrap gap-2">
                <Button type="button" size="sm" variant="outline" onClick={() => setEditing(a.id)}>
                  Изменить
                </Button>
                <Button type="button" size="sm" variant="ghost" isLoading={busy} onClick={() => remove(a)}>
                  Удалить
                </Button>
              </div>
            )}
          </div>
        )
      )}
    </div>
  );
};

const AuthorForm: React.FC<{ author?: Author; onDone: () => void; onCancel: () => void }> = ({ author, onDone, onCancel }) => {
  const [displayName, setDisplayName] = useState(author?.displayName ?? '');
  const [accountId, setAccountId] = useState(author?.accountId ?? '');
  const [share, setShare] = useState(author ? String(author.revenueSharePercent) : '');
  const [contactNote, setContactNote] = useState(author?.contactNote ?? '');
  const { busy, errors, run } = useSubmit();

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    const body = {
      displayName,
      accountId: optText(accountId),
      revenueSharePercent: optNum(share),
      contactNote: contactNote.trim(),
    };
    void run(async () => {
      if (author) await api.admin.updateAuthor(author.id, body);
      else await api.admin.createAuthor(body);
      onDone();
    }, author ? 'Автор сохранён' : 'Автор создан');
  };

  return (
    <form onSubmit={submit} className={cardClass}>
      <Labeled label="Имя" error={errors.displayName}>
        <input className={inputClass} value={displayName} maxLength={80} onChange={(e) => setDisplayName(e.target.value)} />
      </Labeled>
      <Labeled label="ID аккаунта (необязательно)" error={errors.accountId}>
        <input className={inputClass} value={accountId} onChange={(e) => setAccountId(e.target.value)} />
      </Labeled>
      <Labeled label="Доля выручки, % (пусто — по умолчанию)" error={errors.revenueSharePercent}>
        <input className={inputClass} type="number" min={0} max={100} value={share} onChange={(e) => setShare(e.target.value)} />
      </Labeled>
      <Labeled label="Контакт (видят только сотрудники)" error={errors.contactNote}>
        <textarea className={inputClass} rows={2} value={contactNote} maxLength={500} onChange={(e) => setContactNote(e.target.value)} />
      </Labeled>
      <FormButtons busy={busy} onCancel={onCancel} />
    </form>
  );
};
