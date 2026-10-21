import React, { useEffect, useState } from 'react';
import { api, getErrorMessage, isAbortError } from '../../../core/api/client';
import type { VerificationReview } from '../../../core/types';
import { Button } from '../../../shared/components/Button';
import { useToast } from '../../../shared/components/Toast';
import { cardClass, ErrorBox, Field, formatTs, inputClass, LoadState, Loading, Pager, useLoad } from './shared';

type Status = VerificationReview['status'];
const STATUS_LABEL: Record<Status, string> = {
  PENDING: 'Ждут проверки',
  AWAITING_SELFIE: 'Ждут селфи',
  APPROVED: 'Одобрены',
  REJECTED: 'Отклонены',
  EXPIRED: 'Истекли',
};

export const VerificationsPanel: React.FC<{ onOpenAccount: (accountId: string) => void }> = ({ onOpenAccount }) => {
  const [status, setStatus] = useState<Status>('PENDING');
  const [page, setPage] = useState(0);
  const [openId, setOpenId] = useState<string | null>(null);
  const list = useLoad((signal) => api.admin.listVerifications({ status, page }, signal), [status, page]);

  if (openId) {
    return (
      <VerificationDetails
        requestId={openId}
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
      <select
        className={`${inputClass} w-auto`}
        value={status}
        onChange={(e) => {
          setStatus(e.target.value as Status);
          setPage(0);
        }}
      >
        {(Object.keys(STATUS_LABEL) as Status[]).map((s) => (
          <option key={s} value={s}>
            {STATUS_LABEL[s]}
          </option>
        ))}
      </select>
      <LoadState state={list} empty="Запросов нет." isEmpty={(d) => d.items.length === 0}>
        {(data) => (
          <>
            <ul className="space-y-2">
              {data.items.map((v) => (
                <li key={v.id}>
                  <button
                    type="button"
                    onClick={() => setOpenId(v.id)}
                    className={`${cardClass} w-full text-left hover:border-zinc-300 transition-colors cursor-pointer`}
                  >
                    <div className="text-sm font-semibold text-zinc-900 truncate">{v.displayName ?? 'Без анкеты'}</div>
                    <div className="text-xs text-zinc-500">
                      {v.instruction} · {formatTs(v.submittedAt)}
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

const VerificationDetails: React.FC<{
  requestId: string;
  onBack: () => void;
  onOpenAccount: (accountId: string) => void;
}> = ({ requestId, onBack, onOpenAccount }) => {
  const review = useLoad(() => api.admin.getVerification(requestId), [requestId]);
  return (
    <div className="space-y-3">
      <Button type="button" size="sm" variant="ghost" onClick={onBack}>
        ← К очереди
      </Button>
      <LoadState state={review}>
        {(v) => (
          <>
            <div className={cardClass}>
              <h3 className="text-sm font-bold text-zinc-900">{v.displayName ?? 'Без анкеты'}</h3>
              <Field label="Жест">{v.instruction}</Field>
              <Field label="Статус">{STATUS_LABEL[v.status] ?? v.status}</Field>
              <Field label="Отправлено">{formatTs(v.submittedAt)}</Field>
              {v.decidedAt && <Field label="Решено">{formatTs(v.decidedAt)}</Field>}
              {v.rejectReason && <Field label="Причина отказа">{v.rejectReason}</Field>}
              <Button type="button" size="sm" variant="outline" onClick={() => onOpenAccount(v.accountId)}>
                Аккаунт
              </Button>
            </div>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <div className={cardClass}>
                <h4 className="text-xs font-semibold text-zinc-500">Селфи</h4>
                {v.selfieAvailable ? <Selfie requestId={v.id} /> : <p className="text-xs text-zinc-500">Селфи нет.</p>}
              </div>
              <div className={cardClass}>
                <h4 className="text-xs font-semibold text-zinc-500">Фото анкеты</h4>
                {v.profilePhotos.length === 0 ? (
                  <p className="text-xs text-zinc-500">Фото нет.</p>
                ) : (
                  <div className="grid grid-cols-2 gap-2">
                    {v.profilePhotos.map((p) => (
                      <img key={p.id} src={p.url} alt="" className="w-full aspect-[3/4] object-cover rounded-xl bg-zinc-100" />
                    ))}
                  </div>
                )}
              </div>
            </div>
            {v.status === 'PENDING' && <VerificationDecision requestId={v.id} onDecided={review.setData} />}
          </>
        )}
      </LoadState>
    </div>
  );
};

/** The selfie needs the Authorization header, so it is fetched as a blob and shown via an object URL. */
const Selfie: React.FC<{ requestId: string }> = ({ requestId }) => {
  const [url, setUrl] = useState<string | null>(null);
  const [error, setError] = useState<unknown>(null);

  useEffect(() => {
    const ctrl = new AbortController();
    let objectUrl: string | null = null;
    api.admin
      .getVerificationSelfie(requestId, ctrl.signal)
      .then((blob) => {
        objectUrl = URL.createObjectURL(blob);
        setUrl(objectUrl);
      })
      .catch((e: unknown) => {
        if (!isAbortError(e)) setError(e);
      });
    return () => {
      ctrl.abort();
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [requestId]);

  if (error) return <ErrorBox error={error} />;
  if (!url) return <Loading />;
  return <img src={url} alt="Селфи" className="w-full rounded-xl bg-zinc-100" />;
};

const VerificationDecision: React.FC<{ requestId: string; onDecided: (v: VerificationReview) => void }> = ({
  requestId,
  onDecided,
}) => {
  const { showToast } = useToast();
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);

  const decide = async (approve: boolean) => {
    if (!approve && !reason.trim()) {
      showToast('Укажите причину отказа — её увидит пользователь.', 'error');
      return;
    }
    setBusy(true);
    try {
      onDecided(await api.admin.decideVerification(requestId, approve, approve ? undefined : reason.trim()));
      showToast(approve ? 'Селфи одобрено' : 'Селфи отклонено', 'success');
    } catch (e) {
      showToast(getErrorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className={cardClass}>
      <textarea
        className={inputClass}
        rows={2}
        maxLength={500}
        placeholder="Причина отказа (её увидит пользователь)"
        value={reason}
        onChange={(e) => setReason(e.target.value)}
      />
      <div className="flex flex-wrap gap-2">
        <Button type="button" isLoading={busy} onClick={() => void decide(true)}>
          Одобрить
        </Button>
        <Button type="button" variant="danger" isLoading={busy} onClick={() => void decide(false)}>
          Отклонить
        </Button>
      </div>
    </div>
  );
};
