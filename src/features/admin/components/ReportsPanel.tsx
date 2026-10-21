import React, { useState } from 'react';
import { api, getErrorMessage } from '../../../core/api/client';
import type { ModerationDecision, ModerationReport, ModerationStatus, ReportReason } from '../../../core/types';
import { Button } from '../../../shared/components/Button';
import { useToast } from '../../../shared/components/Toast';
import { REPORT_REASONS } from '../../safety/components/ReportModal';
import { cardClass, Field, formatTs, inputClass, LoadState, Pager, REASON_LABEL, useLoad } from './shared';

const STATUS_LABEL: Record<ModerationStatus, string> = {
  NEW: 'Новая',
  IN_REVIEW: 'На проверке',
  RESOLVED: 'Нарушение подтверждено',
  DISMISSED: 'Отклонена',
};

export const ReportsPanel: React.FC<{ onOpenAccount: (accountId: string) => void }> = ({ onOpenAccount }) => {
  const [status, setStatus] = useState<ModerationStatus | ''>('');
  const [sort, setSort] = useState<'priority' | 'oldest'>('priority');
  const [page, setPage] = useState(0);
  const [openId, setOpenId] = useState<string | null>(null);
  const list = useLoad(
    (signal) => api.admin.listReports({ status: status || undefined, sort, page }, signal),
    [status, sort, page]
  );

  if (openId) {
    return (
      <ReportDetails
        reportId={openId}
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
      <div className="flex flex-wrap gap-2">
        <select
          className={`${inputClass} w-auto`}
          value={status}
          onChange={(e) => {
            setStatus(e.target.value as ModerationStatus | '');
            setPage(0);
          }}
        >
          <option value="">Открытые</option>
          {(Object.keys(STATUS_LABEL) as ModerationStatus[]).map((s) => (
            <option key={s} value={s}>
              {STATUS_LABEL[s]}
            </option>
          ))}
        </select>
        <select
          className={`${inputClass} w-auto`}
          value={sort}
          onChange={(e) => {
            setSort(e.target.value as 'priority' | 'oldest');
            setPage(0);
          }}
        >
          <option value="priority">Сначала срочные</option>
          <option value="oldest">Сначала старые</option>
        </select>
      </div>
      <LoadState state={list} empty="Жалоб нет." isEmpty={(d) => d.items.length === 0}>
        {(data) => (
          <>
            <ul className="space-y-2">
              {data.items.map((r) => (
                <li key={r.id}>
                  <button
                    type="button"
                    onClick={() => setOpenId(r.id)}
                    className={`${cardClass} w-full text-left hover:border-zinc-300 transition-colors cursor-pointer`}
                  >
                    <div className="flex items-center justify-between gap-2">
                      <span className="text-sm font-semibold text-zinc-900 truncate">
                        {r.reportedDisplayName ?? 'Анкета удалена'}
                      </span>
                      <span className="text-[11px] font-bold px-2 py-0.5 rounded-lg bg-rose-50 text-rose-700 shrink-0">
                        {r.priority}
                      </span>
                    </div>
                    <div className="text-xs text-zinc-500">
                      {REASON_LABEL[r.reason] ?? r.reason} · {STATUS_LABEL[r.status]} · {formatTs(r.createdAt)}
                    </div>
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

const ReportDetails: React.FC<{
  reportId: string;
  onBack: () => void;
  onOpenAccount: (accountId: string) => void;
}> = ({ reportId, onBack, onOpenAccount }) => {
  const report = useLoad(() => api.admin.getReport(reportId), [reportId]);
  return (
    <div className="space-y-3">
      <Button type="button" size="sm" variant="ghost" onClick={onBack}>
        ← К очереди
      </Button>
      <LoadState state={report}>
        {(r) => (
          <>
            <div className={cardClass}>
              <h3 className="text-sm font-bold text-zinc-900">{r.reportedDisplayName ?? 'Анкета удалена'}</h3>
              <Field label="Причина">{REASON_LABEL[r.reason] ?? r.reason}</Field>
              <Field label="Статус">{STATUS_LABEL[r.status]}</Field>
              <Field label="Приоритет">
                {r.priority} {r.priorityReasons.length > 0 && `(${r.priorityReasons.join(', ')})`}
              </Field>
              <Field label="Риск">{r.reportedRiskScore ?? '—'}</Field>
              <Field label="Подтверждённых нарушений">{r.reportedConfirmedViolations}</Field>
              <Field label="Создана">{formatTs(r.createdAt)}</Field>
              {r.comment && <Field label="Комментарий">{r.comment}</Field>}
              {r.messageSnapshot && (
                <div className="p-3 bg-zinc-50 border border-zinc-200 rounded-xl text-xs whitespace-pre-wrap break-words">
                  {r.messageSnapshot}
                </div>
              )}
              {r.decision && (
                <>
                  <Field label="Решение">{r.decision}</Field>
                  {r.finalReason && <Field label="Итоговая причина">{REASON_LABEL[r.finalReason]}</Field>}
                  <Field label="Санкция применена">{r.sanctioned ? 'да' : 'нет'}</Field>
                  {r.moderatorNote && <Field label="Заметка модератора">{r.moderatorNote}</Field>}
                  <Field label="Решено">{formatTs(r.decidedAt)}</Field>
                </>
              )}
              <div className="flex flex-wrap gap-2">
                <Button type="button" size="sm" variant="outline" onClick={() => onOpenAccount(r.reportedAccountId)}>
                  Аккаунт нарушителя
                </Button>
                <Button type="button" size="sm" variant="outline" onClick={() => onOpenAccount(r.reporterAccountId)}>
                  Аккаунт автора жалобы
                </Button>
              </div>
            </div>
            {!r.decision && <DecisionForm report={r} onDecided={report.setData} />}
          </>
        )}
      </LoadState>
    </div>
  );
};

const DecisionForm: React.FC<{ report: ModerationReport; onDecided: (r: ModerationReport) => void }> = ({
  report,
  onDecided,
}) => {
  const { showToast } = useToast();
  const [decision, setDecision] = useState<ModerationDecision>('ACCEPTED');
  // Silence is never a confirmed violation (the API answers 400); the new reason must differ from the reported one.
  const otherReasons = REPORT_REASONS.filter((r) => r.value !== report.reason && r.value !== 'NOT_RESPONDING');
  const [reason, setReason] = useState<ReportReason>(otherReasons[0].value);
  const [note, setNote] = useState('');
  const [sanction, setSanction] = useState(true);
  const [clearAboutText, setClearAboutText] = useState(false);
  const [clearDailyStatus, setClearDailyStatus] = useState(false);
  const [busy, setBusy] = useState(false);
  const confirmed = decision !== 'REJECTED';

  const submit = async () => {
    setBusy(true);
    try {
      const result = await api.admin.decideReport(report.id, {
        decision,
        reason: decision === 'RECLASSIFIED' ? reason : undefined,
        note: note.trim() || undefined,
        ...(confirmed && { sanction, clearAboutText, clearDailyStatus }),
      });
      showToast('Решение сохранено', 'success');
      onDecided(result);
    } catch (e) {
      showToast(getErrorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className={cardClass}>
      <h3 className="text-sm font-bold text-zinc-900">Решение</h3>
      <select
        className={inputClass}
        value={decision}
        onChange={(e) => setDecision(e.target.value as ModerationDecision)}
      >
        <option value="ACCEPTED">Нарушение подтверждено</option>
        <option value="RECLASSIFIED">Нарушение другого вида</option>
        <option value="REJECTED">Нарушения нет</option>
      </select>
      {decision === 'RECLASSIFIED' && (
        <select className={inputClass} value={reason} onChange={(e) => setReason(e.target.value as ReportReason)}>
          {otherReasons.map((r) => (
            <option key={r.value} value={r.value}>
              {r.label}
            </option>
          ))}
        </select>
      )}
      {confirmed && (
        <div className="space-y-1.5 text-xs text-zinc-700">
          <label className="flex items-center gap-2">
            <input type="checkbox" checked={sanction} onChange={(e) => setSanction(e.target.checked)} />
            Применить политику санкций
          </label>
          <label className="flex items-center gap-2">
            <input type="checkbox" checked={clearAboutText} onChange={(e) => setClearAboutText(e.target.checked)} />
            Удалить текст «о себе»
          </label>
          <label className="flex items-center gap-2">
            <input type="checkbox" checked={clearDailyStatus} onChange={(e) => setClearDailyStatus(e.target.checked)} />
            Удалить статус дня
          </label>
        </div>
      )}
      <textarea
        className={inputClass}
        rows={3}
        maxLength={1000}
        placeholder="Заметка (необязательно)"
        value={note}
        onChange={(e) => setNote(e.target.value)}
      />
      <Button type="button" variant={confirmed ? 'danger' : 'primary'} isLoading={busy} onClick={() => void submit()}>
        Сохранить решение
      </Button>
    </div>
  );
};
