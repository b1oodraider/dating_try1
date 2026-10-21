import React, { useState } from 'react';
import { api, getErrorMessage } from '../../../core/api/client';
import type { SupportCategory, SupportStaffTicket, SupportStatus } from '../../../core/types';
import { Button } from '../../../shared/components/Button';
import { useToast } from '../../../shared/components/Toast';
import { cardClass, Field, formatTs, inputClass, LoadState, Pager, useLoad } from './shared';

const STATUS_LABEL: Record<SupportStatus, string> = {
  OPEN: 'Открытые',
  ANSWERED: 'Отвечены',
  CLOSED: 'Закрытые',
};

const CATEGORY_LABEL: Record<SupportCategory, string> = {
  ACCOUNT: 'Аккаунт',
  PROFILE: 'Анкета',
  LIKES_MATCHES: 'Лайки и мэтчи',
  PAYMENTS: 'Оплата',
  SAFETY: 'Безопасность',
  BUG: 'Ошибка в приложении',
  OTHER: 'Другое',
};

/** Support tickets (SUPPORT, MODERATOR, ADMIN). The account card link is only for moderators and admins. */
export const SupportPanel: React.FC<{ onOpenAccount?: (accountId: string) => void }> = ({ onOpenAccount }) => {
  const [status, setStatus] = useState<SupportStatus>('OPEN');
  const [page, setPage] = useState(0);
  const [openId, setOpenId] = useState<string | null>(null);
  const list = useLoad((signal) => api.admin.listSupportTickets({ status, page }, signal), [status, page]);

  if (openId) {
    return (
      <SupportTicket
        ticketId={openId}
        onBack={() => {
          setOpenId(null);
          list.reload();
        }}
        onOpenAccount={onOpenAccount}
      />
    );
  }

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap gap-1 bg-zinc-100/80 p-1 rounded-xl w-fit max-w-full" role="tablist">
        {(Object.keys(STATUS_LABEL) as SupportStatus[]).map((s) => (
          <button
            key={s}
            type="button"
            role="tab"
            aria-selected={status === s}
            onClick={() => {
              setStatus(s);
              setPage(0);
            }}
            className={`px-3 py-1.5 rounded-lg text-xs font-semibold transition-all cursor-pointer ${
              status === s ? 'bg-white text-zinc-900 shadow-xs' : 'text-zinc-600 hover:text-zinc-900'
            }`}
          >
            {STATUS_LABEL[s]}
          </button>
        ))}
      </div>
      <LoadState state={list} empty="Обращений нет." isEmpty={(d) => d.items.length === 0}>
        {(data) => (
          <>
            <ul className="space-y-2">
              {data.items.map((t) => (
                <li key={t.id}>
                  <button
                    type="button"
                    onClick={() => setOpenId(t.id)}
                    className={`${cardClass} w-full text-left hover:border-zinc-300 transition-colors cursor-pointer`}
                  >
                    <div className="flex items-center gap-2 min-w-0">
                      <span className="text-sm font-semibold text-zinc-900 truncate">{t.displayName ?? 'Без анкеты'}</span>
                      {t.testAccount && <span className="text-[10px] text-amber-700 bg-amber-50 px-1.5 rounded">тест</span>}
                    </div>
                    <div className="text-xs text-zinc-500">
                      {CATEGORY_LABEL[t.category] ?? t.category} · {formatTs(t.lastMessageAt)}
                    </div>
                    {t.preview && <p className="text-xs text-zinc-700 line-clamp-2 break-words">{t.preview}</p>}
                  </button>
                </li>
              ))}
            </ul>
            <Pager page={data.page} limit={data.limit} total={data.totalElements} onPage={setPage} />
          </>
        )}
      </LoadState>
    </div>
  );
};

const SupportTicket: React.FC<{
  ticketId: string;
  onBack: () => void;
  onOpenAccount?: (accountId: string) => void;
}> = ({ ticketId, onBack, onOpenAccount }) => {
  const ticket = useLoad(() => api.admin.getSupportTicket(ticketId), [ticketId]);
  return (
    <div className="space-y-3">
      <Button type="button" size="sm" variant="ghost" onClick={onBack}>
        ← К обращениям
      </Button>
      <LoadState state={ticket}>
        {(t) => (
          <>
            <div className={cardClass}>
              <h3 className="text-sm font-bold text-zinc-900">{t.displayName ?? 'Без анкеты'}</h3>
              <Field label="Тема">{CATEGORY_LABEL[t.category] ?? t.category}</Field>
              <Field label="Статус">{STATUS_LABEL[t.status] ?? t.status}</Field>
              <Field label="Телефон">{t.phoneMasked}</Field>
              {t.testAccount && <Field label="Аккаунт">тестовый</Field>}
              <Field label="Создано">{formatTs(t.createdAt)}</Field>
              {onOpenAccount && (
                <Button type="button" size="sm" variant="outline" onClick={() => onOpenAccount(t.accountId)}>
                  Аккаунт
                </Button>
              )}
            </div>
            <ul className={`${cardClass} space-y-2`} aria-label="Переписка">
              {t.messages.map((m) => (
                <li key={m.id} className={`flex ${m.fromStaff ? 'justify-end' : 'justify-start'}`}>
                  <div
                    className={`max-w-[80%] px-3 py-2 rounded-2xl text-sm whitespace-pre-wrap break-words ${
                      m.fromStaff ? 'bg-zinc-900 text-white rounded-br-md' : 'bg-zinc-100 text-zinc-900 rounded-bl-md'
                    }`}
                  >
                    <div className="text-[10px] opacity-60 mb-0.5">
                      {m.fromStaff ? 'Поддержка' : 'Пользователь'} · {formatTs(m.createdAt)}
                    </div>
                    {m.text}
                  </div>
                </li>
              ))}
            </ul>
            <ReplyBox ticket={t} onChange={ticket.setData} />
          </>
        )}
      </LoadState>
    </div>
  );
};

const ReplyBox: React.FC<{ ticket: SupportStaffTicket; onChange: (t: SupportStaffTicket) => void }> = ({
  ticket,
  onChange,
}) => {
  const { showToast } = useToast();
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);

  const run = async (action: () => Promise<SupportStaffTicket>, done: string) => {
    setBusy(true);
    try {
      onChange(await action());
      showToast(done, 'success');
      return true;
    } catch (e) {
      showToast(getErrorMessage(e), 'error');
      return false;
    } finally {
      setBusy(false);
    }
  };

  const reply = async () => {
    if (!text.trim()) return;
    if (await run(() => api.admin.replySupportTicket(ticket.id, text.trim()), 'Ответ отправлен')) setText('');
  };

  return (
    <div className={cardClass}>
      <textarea
        className={inputClass}
        rows={3}
        maxLength={4000}
        aria-label="Ответ пользователю"
        placeholder="Ответ пользователю"
        value={text}
        onChange={(e) => setText(e.target.value)}
      />
      <div className="flex flex-wrap gap-2">
        <Button type="button" isLoading={busy} disabled={!text.trim()} onClick={() => void reply()}>
          Ответить
        </Button>
        {ticket.status !== 'CLOSED' && (
          <Button
            type="button"
            variant="outline"
            isLoading={busy}
            onClick={() => void run(() => api.admin.closeSupportTicket(ticket.id), 'Обращение закрыто')}
          >
            Закрыть
          </Button>
        )}
      </div>
    </div>
  );
};
