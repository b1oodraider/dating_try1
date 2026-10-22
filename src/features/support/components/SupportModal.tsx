import React, { useEffect, useState } from 'react';
import { ArrowLeft, ChevronDown, ChevronRight, PenLine, Send } from 'lucide-react';
import { ApiError, api, formatDateTime, getErrorMessage, isAbortError } from '../../../core/api/client';
import { useAuth } from '../../../core/context/AuthContext';
import type { FaqEntry, SupportCategory, SupportStatus, SupportTicket, SupportTicketSummary } from '../../../core/types';
import { Modal } from '../../../shared/components/Modal';
import { Button } from '../../../shared/components/Button';

export const SUPPORT_CATEGORIES: Record<SupportCategory, string> = {
  ACCOUNT: 'Аккаунт и вход',
  PROFILE: 'Анкета и фото',
  LIKES_MATCHES: 'Лайки и пары',
  PAYMENTS: 'Покупки и премиум',
  SAFETY: 'Безопасность и жалобы',
  BUG: 'Ошибка в приложении',
  OTHER: 'Другое',
};

const SUPPORT_STATUSES: Record<SupportStatus, string> = {
  OPEN: 'Ждёт ответа',
  ANSWERED: 'Есть ответ',
  CLOSED: 'Закрыто',
};

function describeError(e: unknown): string {
  if (e instanceof ApiError && e.code === 'RATE_LIMITED') {
    return 'Сегодня вы уже написали много обращений. Попробуйте завтра или допишите в открытое обращение.';
  }
  return getErrorMessage(e);
}

const textarea =
  'w-full px-3 py-2 text-sm bg-zinc-50 border border-zinc-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-zinc-900 resize-y';

interface SupportModalProps {
  isOpen: boolean;
  onClose: () => void;
}

/** «Помощь»: FAQ, my tickets, a ticket thread and a new ticket (mobile support_screen.dart). */
export const SupportModal: React.FC<SupportModalProps> = ({ isOpen, onClose }) => {
  const { account } = useAuth();
  /** null = list; 'new' = new ticket form; else an open ticket id. */
  const [view, setView] = useState<string | null>(null);
  const [faq, setFaq] = useState<FaqEntry[]>([]);
  const [openFaq, setOpenFaq] = useState<string | null>(null);
  const [tickets, setTickets] = useState<SupportTicketSummary[] | null>(null);
  const [ticket, setTicket] = useState<SupportTicket | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [text, setText] = useState('');
  const [category, setCategory] = useState<SupportCategory>('OTHER');
  const [sending, setSending] = useState(false);

  // The list: FAQ for everyone, tickets once signed in.
  useEffect(() => {
    if (!isOpen || view !== null) return;
    const controller = new AbortController();
    setError(null);
    api.getFaq(controller.signal).then(setFaq).catch((e: unknown) => {
      if (!isAbortError(e)) console.warn('Failed to load FAQ', e);
    });
    if (account) {
      api.listMyTickets(controller.signal).then(setTickets).catch((e: unknown) => {
        if (!isAbortError(e)) setError(describeError(e));
      });
    }
    return () => controller.abort();
  }, [isOpen, view, account]);

  // One ticket.
  useEffect(() => {
    if (!isOpen || view === null || view === 'new') return;
    const controller = new AbortController();
    setTicket(null);
    setError(null);
    api.getMyTicket(view, controller.signal).then(setTicket).catch((e: unknown) => {
      if (!isAbortError(e)) setError(describeError(e));
    });
    return () => controller.abort();
  }, [isOpen, view]);

  const go = (next: string | null) => {
    setView(next);
    setText('');
    setError(null);
  };

  const close = () => {
    go(null);
    onClose();
  };

  const send = async () => {
    const trimmed = text.trim();
    if (!trimmed) {
      setError('Опишите, что случилось');
      return;
    }
    setSending(true);
    setError(null);
    try {
      if (view === 'new') {
        const created = await api.createTicket(category, trimmed);
        setView(created.id);
        setTicket(created);
      } else if (view) {
        setTicket(await api.writeToTicket(view, trimmed));
      }
      setText('');
    } catch (e) {
      setError(describeError(e));
    } finally {
      setSending(false);
    }
  };

  const errorBox = error && (
    <div role="alert" className="p-3 bg-rose-50 border border-rose-200 text-rose-700 text-xs rounded-xl">
      {error}
    </div>
  );
  const back = (
    <button type="button" onClick={() => go(null)} className="flex items-center gap-1 text-xs font-semibold text-zinc-600 hover:text-zinc-900">
      <ArrowLeft className="w-3.5 h-3.5" /> Назад
    </button>
  );

  let body: React.ReactNode;
  if (view === 'new') {
    body = (
      <div className="space-y-3">
        {back}
        <label className="block text-xs font-semibold text-zinc-700">
          Тема
          <select
            value={category}
            onChange={(e) => setCategory(e.target.value as SupportCategory)}
            className="mt-1 w-full px-3 py-2 text-sm bg-zinc-50 border border-zinc-200 rounded-xl"
          >
            {Object.entries(SUPPORT_CATEGORIES).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </label>
        <label className="block text-xs font-semibold text-zinc-700">
          Что случилось?
          <textarea
            value={text}
            onChange={(e) => setText(e.target.value)}
            rows={6}
            maxLength={4000}
            placeholder="Опишите проблему; если это ошибка — что вы делали перед ней"
            className={`mt-1 ${textarea}`}
          />
        </label>
        {errorBox}
        <Button type="button" className="w-full" isLoading={sending} onClick={() => void send()}>
          Отправить
        </Button>
      </div>
    );
  } else if (view !== null) {
    body = (
      <div className="space-y-3">
        {back}
        {!ticket ? (
          errorBox || <p className="text-xs text-zinc-500">Загрузка…</p>
        ) : (
          <>
            <div className="text-center">
              <div className="text-sm font-bold text-zinc-900">{SUPPORT_CATEGORIES[ticket.category] ?? ticket.category}</div>
              <div className="text-[11px] text-zinc-500">{SUPPORT_STATUSES[ticket.status] ?? ticket.status}</div>
            </div>
            <div className="space-y-2 max-h-[45vh] overflow-y-auto pr-1">
              {ticket.messages.map((m) => (
                <div key={m.id} className={`flex ${m.fromStaff ? 'justify-start' : 'justify-end'}`}>
                  <div
                    className={`max-w-[80%] px-3.5 py-2 rounded-2xl text-sm whitespace-pre-wrap ${
                      m.fromStaff ? 'bg-zinc-100 text-zinc-900' : 'bg-rose-500 text-white'
                    }`}
                  >
                    {m.fromStaff && <div className="text-[11px] font-extrabold text-rose-600">Поддержка</div>}
                    {m.text}
                    <div className={`text-[10px] ${m.fromStaff ? 'text-zinc-500' : 'text-white/70'}`}>
                      {formatDateTime(m.createdAt)}
                    </div>
                  </div>
                </div>
              ))}
            </div>
            {errorBox}
            <div className="flex items-end gap-2">
              <textarea
                value={text}
                onChange={(e) => setText(e.target.value)}
                rows={2}
                maxLength={4000}
                placeholder="Сообщение"
                className={textarea}
              />
              <Button type="button" isLoading={sending} disabled={!text.trim()} onClick={() => void send()} aria-label="Отправить">
                <Send className="w-4 h-4" />
              </Button>
            </div>
          </>
        )}
      </div>
    );
  } else {
    body = (
      <div className="space-y-4">
        {account && tickets && tickets.length > 0 && (
          <section className="space-y-2">
            <h3 className="text-sm font-bold text-zinc-900">Мои обращения</h3>
            <div className="border border-zinc-200/80 rounded-xl divide-y divide-zinc-100">
              {tickets.map((t) => (
                <button
                  key={t.id}
                  type="button"
                  onClick={() => go(t.id)}
                  className="w-full flex items-center justify-between gap-3 px-3.5 py-2.5 text-left hover:bg-zinc-50"
                >
                  <div className="min-w-0">
                    <div className="text-sm font-semibold text-zinc-900">{SUPPORT_CATEGORIES[t.category] ?? t.category}</div>
                    <div className="text-[11px] text-zinc-500">
                      {[t.status !== 'ANSWERED' && SUPPORT_STATUSES[t.status], t.lastMessageAt && formatDateTime(t.lastMessageAt)]
                        .filter(Boolean)
                        .join(' · ')}
                    </div>
                  </div>
                  {t.status === 'ANSWERED' ? (
                    <span className="shrink-0 px-2.5 py-0.5 rounded-full bg-emerald-100 text-emerald-900 text-[11px] font-semibold">
                      Есть ответ
                    </span>
                  ) : (
                    <ChevronRight className="w-4 h-4 text-zinc-400 shrink-0" />
                  )}
                </button>
              ))}
            </div>
          </section>
        )}
        {errorBox}
        <section className="space-y-2">
          <h3 className="text-sm font-bold text-zinc-900">Частые вопросы</h3>
          {faq.length === 0 ? (
            <p className="text-xs text-zinc-400">Пока нет вопросов.</p>
          ) : (
            <div className="border border-zinc-200/80 rounded-xl divide-y divide-zinc-100">
              {faq.map((f) => (
                <div key={f.id}>
                  <button
                    type="button"
                    aria-expanded={openFaq === f.id}
                    onClick={() => setOpenFaq(openFaq === f.id ? null : f.id)}
                    className="w-full flex items-center justify-between gap-3 px-3.5 py-2.5 text-left text-sm font-semibold text-zinc-900 hover:bg-zinc-50"
                  >
                    {f.question}
                    <ChevronDown className={`w-4 h-4 shrink-0 text-zinc-400 transition-transform ${openFaq === f.id ? 'rotate-180' : ''}`} />
                  </button>
                  {openFaq === f.id && <p className="px-3.5 pb-3 text-sm text-zinc-700 whitespace-pre-wrap">{f.answer}</p>}
                </div>
              ))}
            </div>
          )}
        </section>
        {account ? (
          <Button type="button" className="w-full" onClick={() => go('new')}>
            <PenLine className="w-4 h-4" /> Написать в поддержку
          </Button>
        ) : (
          <p className="text-xs text-zinc-500 text-center">Войдите, чтобы написать в поддержку.</p>
        )}
      </div>
    );
  }

  return (
    <Modal isOpen={isOpen} onClose={close} title={view === 'new' ? 'Новое обращение' : 'Помощь'} maxWidth="lg">
      {body}
    </Modal>
  );
};
