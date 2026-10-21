import React, { useState } from 'react';
import { api, getErrorMessage } from '../../../core/api/client';
import type { AdminAccount, AdminRestriction, RestrictionKind, StaffRole } from '../../../core/types';
import { Button } from '../../../shared/components/Button';
import { useToast } from '../../../shared/components/Toast';
import { cardClass, Field, formatTs, inputClass, KIND_LABEL, LoadState, Pager, useLoad } from './shared';

const STAFF_ROLES: StaffRole[] = ['ADMIN', 'MODERATOR', 'CONTENT_EDITOR', 'SUPPORT'];
const ROLE_LABEL: Record<string, string> = { ADMIN: 'Админ', MODERATOR: 'Модератор', CONTENT_EDITOR: 'Редактор контента', SUPPORT: 'Поддержка' };
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const METRIC_LABEL: Record<string, string> = {
  likesGiven30d: 'Лайков за 30 дн.',
  superlikesGiven30d: 'Суперлайков за 30 дн.',
  passesGiven30d: 'Пропусков за 30 дн.',
  profilesSeen30d: 'Просмотрел анкет за 30 дн.',
  likeRatio: 'Доля лайков',
  medianSwipeMs: 'Медианная пауза между реакциями, мс',
  timesShown30d: 'Показан другим за 30 дн.',
  likesReceived30d: 'Получил лайков за 30 дн.',
  inboundLikeRate: 'Доля входящих лайков',
  matches90d: 'Мэтчей за 90 дн.',
  dialogs90d: 'Диалогов за 90 дн.',
  silentMatches90d: 'Мэтчей без сообщений',
  responseRate: 'Отвечает, когда пишут первыми',
  ghostRate: 'Не отвечает («гостит»)',
  initiationRate: 'Пишет первым',
  medianResponseSeconds: 'Медианное время ответа, с',
  messagesSent90d: 'Сообщений за 90 дн.',
  avgMessageLength: 'Средняя длина сообщения',
  oneWordShare: 'Доля сообщений из одного слова',
  reportsReceived: 'Жалоб на него',
  reportsMade: 'Жалоб от него',
  confirmedViolations: 'Подтверждённых нарушений',
  blockedBy: 'Заблокировали его',
  activeDays30d: 'Активных дней за 30 дн.',
};
const RATE_KEYS = new Set(['likeRatio', 'inboundLikeRate', 'responseRate', 'ghostRate', 'initiationRate', 'oneWordShare']);

const formatMetric = (key: string, v: number | null) =>
  v === null ? '—' : RATE_KEYS.has(key) ? `${Math.round(v * 100)}%` : String(Math.round(v * 10) / 10);

export const AccountsPanel: React.FC<{
  isAdmin: boolean;
  accountId: string | null;
  onOpenAccount: (accountId: string | null) => void;
}> = ({ isAdmin, accountId, onOpenAccount }) => {
  if (accountId) {
    return (
      <div className="space-y-3">
        <Button type="button" size="sm" variant="ghost" onClick={() => onOpenAccount(null)}>
          ← К поиску
        </Button>
        <AccountCard accountId={accountId} isAdmin={isAdmin} />
      </div>
    );
  }
  return (
    <div className="space-y-4">
      <Lookup isAdmin={isAdmin} onOpenAccount={onOpenAccount} />
      <RiskQueue onOpenAccount={onOpenAccount} />
      {isAdmin && <RolesOverview onOpenAccount={onOpenAccount} />}
    </div>
  );
};

const Lookup: React.FC<{ isAdmin: boolean; onOpenAccount: (id: string) => void }> = ({ isAdmin, onOpenAccount }) => {
  const { showToast } = useToast();
  const [query, setQuery] = useState('');
  const [busy, setBusy] = useState(false);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    const q = query.trim();
    if (!q) return;
    if (UUID_RE.test(q)) {
      onOpenAccount(q);
      return;
    }
    if (!isAdmin) {
      showToast('Введите ID аккаунта (поиск по телефону — только для админа).', 'error');
      return;
    }
    setBusy(true);
    try {
      const found = await api.admin.findAccountByPhone(q);
      onOpenAccount(found.accountId);
    } catch (err) {
      showToast(getErrorMessage(err), 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <form onSubmit={(e) => void submit(e)} className={cardClass}>
      <h3 className="text-sm font-bold text-zinc-900">Найти аккаунт</h3>
      <div className="flex gap-2">
        <input
          className={inputClass}
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder={isAdmin ? 'Телефон или ID аккаунта' : 'ID аккаунта'}
          autoComplete="off"
        />
        <Button type="submit" isLoading={busy}>
          Найти
        </Button>
      </div>
    </form>
  );
};

const RiskQueue: React.FC<{ onOpenAccount: (id: string) => void }> = ({ onOpenAccount }) => {
  const [page, setPage] = useState(0);
  const risk = useLoad((signal) => api.admin.listRisk({ page }, signal), [page]);
  return (
    <div className={cardClass}>
      <h3 className="text-sm font-bold text-zinc-900">Рискованные аккаунты</h3>
      <LoadState state={risk} empty="Рискованных аккаунтов нет." isEmpty={(d) => d.items.length === 0}>
        {(data) => (
          <>
            <ul className="divide-y divide-zinc-100">
              {data.items.map((r) => (
                <li key={r.accountId}>
                  <button
                    type="button"
                    onClick={() => onOpenAccount(r.accountId)}
                    className="w-full flex items-center justify-between gap-2 py-2 text-left text-xs cursor-pointer"
                  >
                    <span className="truncate text-zinc-900">{r.displayName ?? r.accountId}</span>
                    <span className="shrink-0 font-bold text-rose-700">
                      {r.score} · {r.level}
                    </span>
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

const RolesOverview: React.FC<{ onOpenAccount: (id: string) => void }> = ({ onOpenAccount }) => {
  const roles = useLoad(() => api.admin.getRoles(), []);
  return (
    <div className={cardClass}>
      <h3 className="text-sm font-bold text-zinc-900">Сотрудники</h3>
      <LoadState state={roles} empty="Сотрудников нет." isEmpty={(d) => d.accounts.length === 0}>
        {(data) => (
          <ul className="divide-y divide-zinc-100">
            {data.accounts.map((a) => (
              <li key={a.accountId}>
                <button
                  type="button"
                  onClick={() => onOpenAccount(a.accountId)}
                  className="w-full flex flex-wrap items-center justify-between gap-2 py-2 text-left text-xs cursor-pointer"
                >
                  <span className="font-mono text-zinc-900">{a.phoneMasked}</span>
                  <span className="text-zinc-500">{a.roles.map((r) => ROLE_LABEL[r] ?? r).join(', ')}</span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </LoadState>
    </div>
  );
};

const AccountCard: React.FC<{ accountId: string; isAdmin: boolean }> = ({ accountId, isAdmin }) => (
  <div className="space-y-3">
    <div className={cardClass}>
      <Field label="ID">
        <span className="font-mono">{accountId}</span>
      </Field>
      {isAdmin && <AccountInfo accountId={accountId} />}
    </div>
    <Restrictions accountId={accountId} isAdmin={isAdmin} />
    <RiskView accountId={accountId} />
    <BehaviorView accountId={accountId} />
  </div>
);

const AccountInfo: React.FC<{ accountId: string }> = ({ accountId }) => {
  const { showToast } = useToast();
  const account = useLoad(() => api.admin.getAccount(accountId), [accountId]);
  const [busyRole, setBusyRole] = useState<StaffRole | null>(null);

  const toggleRole = async (a: AdminAccount, role: StaffRole) => {
    const has = a.roles.includes(role);
    if (!window.confirm(`${has ? 'Снять' : 'Выдать'} роль «${ROLE_LABEL[role]}»?`)) return;
    setBusyRole(role);
    try {
      account.setData(has ? await api.admin.revokeRole(accountId, role) : await api.admin.grantRole(accountId, role));
      showToast('Роли обновлены', 'success');
    } catch (e) {
      showToast(getErrorMessage(e), 'error');
    } finally {
      setBusyRole(null);
    }
  };

  return (
    <LoadState state={account}>
      {(a) => (
        <>
          <Field label="Телефон">
            <span className="font-mono">{a.phoneMasked}</span>
          </Field>
          <Field label="Статус">{a.status}</Field>
          <Field label="Создан">{formatTs(a.createdAt)}</Field>
          {a.testAccount && <Field label="Тестовый">да</Field>}
          <div className="flex flex-wrap gap-2">
            {STAFF_ROLES.map((role) => (
              <Button
                key={role}
                type="button"
                size="sm"
                variant={a.roles.includes(role) ? 'primary' : 'outline'}
                isLoading={busyRole === role}
                disabled={busyRole !== null}
                onClick={() => void toggleRole(a, role)}
              >
                {ROLE_LABEL[role]}
              </Button>
            ))}
          </div>
        </>
      )}
    </LoadState>
  );
};

const isActive = (r: AdminRestriction) => !r.liftedAt && (!r.endsAt || new Date(r.endsAt) > new Date());

const Restrictions: React.FC<{ accountId: string; isAdmin: boolean }> = ({ accountId, isAdmin }) => {
  const { showToast } = useToast();
  const list = useLoad(() => api.admin.getRestrictions(accountId), [accountId]);
  const [kind, setKind] = useState<RestrictionKind>('LIKES');
  const [hours, setHours] = useState('');
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);

  const create = async (e: React.FormEvent) => {
    e.preventDefault();
    const h = hours.trim() ? Number(hours) : undefined;
    if (h !== undefined && (!Number.isInteger(h) || h < 1 || h > 87600)) {
      showToast('Часы: целое число от 1 до 87600 (пусто — до снятия).', 'error');
      return;
    }
    if (kind === 'SUSPENDED' && !window.confirm('Блокировка удалит все лайки аккаунта и закроет мэтчи. Продолжить?')) return;
    setBusy(true);
    try {
      await api.admin.createRestriction(accountId, { kind, hours: h, note: note.trim() || undefined });
      showToast('Ограничение выдано', 'success');
      setHours('');
      setNote('');
      list.reload();
    } catch (err) {
      showToast(getErrorMessage(err), 'error');
    } finally {
      setBusy(false);
    }
  };

  const lift = async (r: AdminRestriction) => {
    const liftNote = window.prompt('Снять ограничение? Заметка (необязательно):', '');
    if (liftNote === null) return;
    try {
      await api.admin.liftRestriction(r.id, liftNote.trim() || undefined);
      showToast('Ограничение снято', 'success');
      list.reload();
    } catch (err) {
      showToast(getErrorMessage(err), 'error');
    }
  };

  return (
    <div className={cardClass}>
      <h3 className="text-sm font-bold text-zinc-900">Ограничения</h3>
      <LoadState state={list} empty="Ограничений не было." isEmpty={(d) => d.length === 0}>
        {(items) => (
          <ul className="space-y-2">
            {items.map((r) => (
              <li key={r.id} className="p-3 bg-zinc-50 border border-zinc-200 rounded-xl space-y-1">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <span className="text-xs font-semibold text-zinc-900">
                    {KIND_LABEL[r.kind] ?? r.kind} · {r.reason}
                  </span>
                  <span className={`text-[11px] font-semibold ${isActive(r) ? 'text-rose-700' : 'text-zinc-400'}`}>
                    {r.liftedAt ? 'снято' : isActive(r) ? 'действует' : 'истекло'}
                  </span>
                </div>
                <div className="text-[11px] text-zinc-500">
                  {formatTs(r.startsAt)} — {r.endsAt ? formatTs(r.endsAt) : 'до снятия'} · {r.source}
                </div>
                {r.note && <div className="text-[11px] text-zinc-600 break-words">{r.note}</div>}
                {r.liftNote && <div className="text-[11px] text-zinc-600 break-words">Снято: {r.liftNote}</div>}
                {isAdmin && isActive(r) && (
                  <Button type="button" size="sm" variant="outline" onClick={() => void lift(r)}>
                    Снять
                  </Button>
                )}
              </li>
            ))}
          </ul>
        )}
      </LoadState>
      <form onSubmit={(e) => void create(e)} className="space-y-2 pt-2 border-t border-zinc-100">
        <div className="flex flex-wrap gap-2">
          <select
            className={`${inputClass} w-auto`}
            value={kind}
            onChange={(e) => setKind(e.target.value as RestrictionKind)}
          >
            {(Object.keys(KIND_LABEL) as RestrictionKind[]).map((k) => (
              <option key={k} value={k}>
                {KIND_LABEL[k]}
              </option>
            ))}
          </select>
          <input
            className={`${inputClass} w-32`}
            inputMode="numeric"
            placeholder="Часы"
            title="Пусто — до снятия"
            value={hours}
            onChange={(e) => setHours(e.target.value)}
          />
        </div>
        <input
          className={inputClass}
          maxLength={1000}
          placeholder="Заметка"
          value={note}
          onChange={(e) => setNote(e.target.value)}
        />
        <Button type="submit" size="sm" variant="danger" isLoading={busy}>
          Выдать ограничение
        </Button>
      </form>
    </div>
  );
};

const RiskView: React.FC<{ accountId: string }> = ({ accountId }) => {
  const risk = useLoad(() => api.admin.getRisk(accountId), [accountId]);
  return (
    <div className={cardClass}>
      <h3 className="text-sm font-bold text-zinc-900">Риск бота / фейка</h3>
      <LoadState state={risk}>
        {(r) => (
          <>
            <Field label="Скор">
              {r.score} / 100 · {r.level}
            </Field>
            {r.signals.length === 0 ? (
              <p className="text-xs text-zinc-500">Сигналов нет.</p>
            ) : (
              <ul className="space-y-1 text-xs">
                {r.signals.map((s) => (
                  <li key={s.code} className="break-words">
                    <span className="font-semibold">+{s.weight}</span> {s.code}
                    {s.detail && <span className="text-zinc-500"> — {s.detail}</span>}
                  </li>
                ))}
              </ul>
            )}
          </>
        )}
      </LoadState>
    </div>
  );
};

const BehaviorView: React.FC<{ accountId: string }> = ({ accountId }) => {
  const behavior = useLoad(() => api.admin.getBehavior(accountId), [accountId]);
  return (
    <div className={cardClass}>
      <h3 className="text-sm font-bold text-zinc-900">Поведение</h3>
      <LoadState state={behavior}>
        {(b) => (
          <dl className="grid grid-cols-1 sm:grid-cols-2 gap-x-4 gap-y-1 text-xs">
            {Object.entries(b.metrics).map(([key, value]) => (
              <div key={key} className="flex justify-between gap-2 py-0.5 border-b border-zinc-100">
                <dt className="text-zinc-500">{METRIC_LABEL[key] ?? key}</dt>
                <dd className="font-semibold text-zinc-900 shrink-0">{formatMetric(key, value)}</dd>
              </div>
            ))}
          </dl>
        )}
      </LoadState>
    </div>
  );
};
