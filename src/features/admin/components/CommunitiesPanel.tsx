import React, { useState } from 'react';
import { api } from '../../../core/api/client';
import type { AdminCommunity } from '../../../core/types';
import { Button } from '../../../shared/components/Button';
import { COMMUNITY_KINDS } from '../../communities/CommunitiesView';
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

/** Interest groups catalogue and user suggestions (CONTENT_EDITOR or ADMIN). */
export const CommunitiesPanel: React.FC = () => {
  const [tab, setTab] = useState<'groups' | 'suggestions'>('groups');
  return (
    <div className="space-y-3">
      <SubTabs
        tabs={[
          ['groups', 'Группы'],
          ['suggestions', 'Предложения'],
        ]}
        value={tab}
        onChange={setTab}
      />
      {tab === 'groups' ? <GroupsList /> : <SuggestionsList />}
    </div>
  );
};

const GroupsList: React.FC = () => {
  const list = useLoad((signal) => api.admin.listCommunities(signal), []);
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<number | null>(null);

  return (
    <div className="space-y-3">
      {creating ? (
        <GroupForm onDone={() => (setCreating(false), list.reload())} onCancel={() => setCreating(false)} />
      ) : (
        <Button type="button" size="sm" onClick={() => setCreating(true)}>
          + Новая группа
        </Button>
      )}
      <LoadState state={list} empty="Групп пока нет." isEmpty={(d) => d.length === 0}>
        {(items) => (
          <ul className="space-y-2">
            {items.map((c) =>
              editing === c.id ? (
                <li key={c.id}>
                  <GroupForm item={c} onDone={() => (setEditing(null), list.reload())} onCancel={() => setEditing(null)} />
                </li>
              ) : (
                <li key={c.id} className={cardClass}>
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-sm font-semibold text-zinc-900">
                      {c.emoji ? `${c.emoji} ${c.title}` : c.title}
                    </span>
                    <Badge>{COMMUNITY_KINDS[c.kind] ?? c.kind}</Badge>
                    {!c.active && <Badge>скрыта</Badge>}
                  </div>
                  {c.description && <p className="text-xs text-zinc-700 break-words">{c.description}</p>}
                  <Field label="Адрес">{c.slug}</Field>
                  <Field label="Участников">{c.members}</Field>
                  <Field label="Порядок">{c.sortOrder}</Field>
                  <Button type="button" size="sm" variant="outline" onClick={() => setEditing(c.id)}>
                    Изменить
                  </Button>
                </li>
              )
            )}
          </ul>
        )}
      </LoadState>
    </div>
  );
};

const GroupForm: React.FC<{ item?: AdminCommunity; onDone: () => void; onCancel: () => void }> = ({
  item,
  onDone,
  onCancel,
}) => {
  const [slug, setSlug] = useState(item?.slug ?? '');
  const [title, setTitle] = useState(item?.title ?? '');
  const [kind, setKind] = useState(item?.kind ?? 'FILM');
  const [emoji, setEmoji] = useState(item?.emoji ?? '');
  const [description, setDescription] = useState(item?.description ?? '');
  const [sortOrder, setSortOrder] = useState(String(item?.sortOrder ?? 0));
  const [active, setActive] = useState(item?.active ?? true);
  const { busy, errors, run } = useSubmit();

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    const body = {
      slug: item ? undefined : slug.trim(),
      title,
      kind,
      emoji: optText(emoji),
      description: optText(description),
      sortOrder: optNum(sortOrder),
      active,
    };
    void run(async () => {
      if (item) await api.admin.updateCommunity(item.id, body);
      else await api.admin.createCommunity(body);
      onDone();
    }, item ? 'Группа сохранена' : 'Группа добавлена');
  };

  return (
    <form onSubmit={submit} className={cardClass}>
      {!item && (
        <Labeled label="Адрес (латиница, цифры, дефис)" error={errors.slug}>
          <input className={inputClass} value={slug} maxLength={60} onChange={(e) => setSlug(e.target.value)} />
        </Labeled>
      )}
      <Labeled label="Название" error={errors.title}>
        <input className={inputClass} value={title} maxLength={80} onChange={(e) => setTitle(e.target.value)} />
      </Labeled>
      <Labeled label="Тип" error={errors.kind}>
        <select className={inputClass} value={kind} onChange={(e) => setKind(e.target.value)}>
          {Object.entries(COMMUNITY_KINDS).map(([code, label]) => (
            <option key={code} value={code}>
              {label}
            </option>
          ))}
        </select>
      </Labeled>
      <Labeled label="Смайлик" error={errors.emoji}>
        <input className={inputClass} value={emoji} maxLength={16} onChange={(e) => setEmoji(e.target.value)} />
      </Labeled>
      <Labeled label="Описание" error={errors.description}>
        <textarea className={inputClass} rows={3} value={description} maxLength={500} onChange={(e) => setDescription(e.target.value)} />
      </Labeled>
      <Labeled label="Порядок" error={errors.sortOrder}>
        <input className={inputClass} type="number" value={sortOrder} onChange={(e) => setSortOrder(e.target.value)} />
      </Labeled>
      <Check label="Показывать пользователям" checked={active} onChange={setActive} />
      <FormButtons busy={busy} onCancel={onCancel} />
    </form>
  );
};

const STATUS_LABEL: Record<string, string> = { NEW: 'новое', ACCEPTED: 'принято', REJECTED: 'отклонено' };

const SuggestionsList: React.FC = () => {
  const list = useLoad((signal) => api.admin.listCommunitySuggestions(signal), []);
  const { busy, run } = useSubmit();

  const decide = (suggestionId: string, accepted: boolean) =>
    void run(async () => {
      await api.admin.decideCommunitySuggestion(suggestionId, accepted);
      list.reload();
    }, accepted ? 'Принято: добавьте группу во вкладке «Группы»' : 'Отклонено');

  return (
    <LoadState state={list} empty="Предложений пока нет." isEmpty={(d) => d.length === 0}>
      {(items) => (
        <ul className="space-y-2">
          {items.map((s) => (
            <li key={s.id} className={cardClass}>
              <div className="flex flex-wrap items-center gap-2">
                <span className="text-sm font-semibold text-zinc-900">{s.title}</span>
                <Badge>{STATUS_LABEL[s.status] ?? s.status}</Badge>
              </div>
              {s.comment && <p className="text-xs text-zinc-700 whitespace-pre-wrap break-words">{s.comment}</p>}
              <Field label="Аккаунт">{s.accountId}</Field>
              <Field label="Отправлено">{formatTs(s.createdAt)}</Field>
              {s.status === 'NEW' && (
                <div className="flex flex-wrap gap-2">
                  <Button type="button" size="sm" isLoading={busy} onClick={() => decide(s.id, true)}>
                    Принять
                  </Button>
                  <Button type="button" size="sm" variant="ghost" isLoading={busy} onClick={() => decide(s.id, false)}>
                    Отклонить
                  </Button>
                </div>
              )}
            </li>
          ))}
        </ul>
      )}
    </LoadState>
  );
};
