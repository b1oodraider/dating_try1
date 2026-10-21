import React, { useState } from 'react';
import { api } from '../../../core/api/client';
import type { FaqItem } from '../../../core/types';
import { Button } from '../../../shared/components/Button';
import { Badge, cardClass, Check, Field, formatTs, FormButtons, inputClass, Labeled, LoadState, optNum, useLoad, useSubmit } from './shared';

/** FAQ editor (CONTENT_EDITOR, SUPPORT or ADMIN). */
export const FaqPanel: React.FC = () => {
  const list = useLoad((signal) => api.admin.listFaq(signal), []);
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<string | null>(null);
  const { busy, run } = useSubmit();

  const remove = (f: FaqItem) => {
    if (!window.confirm(`Удалить вопрос «${f.question}»?`)) return;
    void run(async () => {
      await api.admin.deleteFaq(f.id);
      list.reload();
    }, 'Вопрос удалён');
  };

  return (
    <div className="space-y-3">
      {creating ? (
        <FaqForm onDone={() => (setCreating(false), list.reload())} onCancel={() => setCreating(false)} />
      ) : (
        <Button type="button" size="sm" onClick={() => setCreating(true)}>
          + Новый вопрос
        </Button>
      )}
      <LoadState state={list} empty="Вопросов пока нет." isEmpty={(d) => d.length === 0}>
        {(items) => (
          <ul className="space-y-2">
            {items.map((f) =>
              editing === f.id ? (
                <li key={f.id}>
                  <FaqForm item={f} onDone={() => (setEditing(null), list.reload())} onCancel={() => setEditing(null)} />
                </li>
              ) : (
                <li key={f.id} className={cardClass}>
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-sm font-semibold text-zinc-900">{f.question}</span>
                    {!f.active && <Badge>скрыт</Badge>}
                  </div>
                  <p className="text-xs text-zinc-700 whitespace-pre-wrap break-words">{f.answer}</p>
                  <Field label="Порядок">{f.sortOrder}</Field>
                  <Field label="Изменён">{formatTs(f.updatedAt)}</Field>
                  <div className="flex flex-wrap gap-2">
                    <Button type="button" size="sm" variant="outline" onClick={() => setEditing(f.id)}>
                      Изменить
                    </Button>
                    <Button type="button" size="sm" variant="ghost" isLoading={busy} onClick={() => remove(f)}>
                      Удалить
                    </Button>
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

const FaqForm: React.FC<{ item?: FaqItem; onDone: () => void; onCancel: () => void }> = ({ item, onDone, onCancel }) => {
  const [question, setQuestion] = useState(item?.question ?? '');
  const [answer, setAnswer] = useState(item?.answer ?? '');
  const [sortOrder, setSortOrder] = useState(String(item?.sortOrder ?? 0));
  const [active, setActive] = useState(item?.active ?? true);
  const { busy, errors, run } = useSubmit();

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    const body = { question, answer, sortOrder: optNum(sortOrder), active };
    void run(async () => {
      if (item) await api.admin.updateFaq(item.id, body);
      else await api.admin.createFaq(body);
      onDone();
    }, item ? 'Вопрос сохранён' : 'Вопрос добавлен');
  };

  return (
    <form onSubmit={submit} className={cardClass}>
      <Labeled label="Вопрос" error={errors.question}>
        <input className={inputClass} value={question} maxLength={200} onChange={(e) => setQuestion(e.target.value)} />
      </Labeled>
      <Labeled label="Ответ" error={errors.answer}>
        <textarea className={inputClass} rows={5} value={answer} maxLength={4000} onChange={(e) => setAnswer(e.target.value)} />
      </Labeled>
      <Labeled label="Порядок (0–100000)" error={errors.sortOrder}>
        <input className={inputClass} type="number" min={0} max={100000} value={sortOrder} onChange={(e) => setSortOrder(e.target.value)} />
      </Labeled>
      <Check label="Показывать пользователям" checked={active} onChange={setActive} />
      <FormButtons busy={busy} onCancel={onCancel} />
    </form>
  );
};
