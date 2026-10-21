import React, { useCallback, useEffect, useState } from 'react';
import { ApiError, getErrorMessage, isAbortError } from '../../../core/api/client';
import type { ReportReason, RestrictionKind } from '../../../core/types';
import { REPORT_REASONS } from '../../safety/components/ReportModal';
import { Button } from '../../../shared/components/Button';
import { useToast } from '../../../shared/components/Toast';

export const inputClass =
  'w-full min-w-0 px-3 py-2 text-sm bg-zinc-50 border border-zinc-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-zinc-900 focus:bg-white transition-all';
export const cardClass = 'bg-white border border-zinc-200/80 rounded-2xl p-4 space-y-3 shadow-xs min-w-0';

export const REASON_LABEL = Object.fromEntries(REPORT_REASONS.map((r) => [r.value, r.label])) as Record<
  ReportReason,
  string
>;

export const KIND_LABEL: Record<RestrictionKind, string> = {
  LIKES: 'Лайки',
  MESSAGES: 'Сообщения',
  HIDDEN: 'Скрыт из ленты',
  SUSPENDED: 'Блокировка',
  VERIFICATION_REQUIRED: 'Нужна проверка селфи',
};

export const formatTs = (iso: string | null | undefined) => (iso ? new Date(iso).toLocaleString('ru-RU') : '—');

/** Loads on mount and whenever `deps` change; `reload` re-runs it. Aborts stale requests. */
export function useLoad<T>(load: (signal: AbortSignal) => Promise<T>, deps: React.DependencyList) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(true);
  const [tick, setTick] = useState(0);

  const run = useCallback(load, deps);

  useEffect(() => {
    const ctrl = new AbortController();
    setLoading(true);
    setError(null);
    run(ctrl.signal)
      .then((d) => setData(d))
      .catch((e: unknown) => {
        if (!isAbortError(e)) setError(e);
      })
      .finally(() => {
        if (!ctrl.signal.aborted) setLoading(false);
      });
    return () => ctrl.abort();
  }, [run, tick]);

  return { data, setData, error, loading, reload: () => setTick((t) => t + 1) };
}

export const Loading: React.FC = () => (
  <div className="py-8 flex justify-center">
    <div className="w-6 h-6 border-2 border-zinc-900 border-t-transparent rounded-full animate-spin" />
  </div>
);

export const Empty: React.FC<{ text: string }> = ({ text }) => (
  <p className="py-8 text-center text-xs text-zinc-500">{text}</p>
);

export const ErrorBox: React.FC<{ error: unknown; onRetry?: () => void }> = ({ error, onRetry }) => {
  const forbidden = error instanceof ApiError && error.status === 403;
  return (
    <div className="p-3 bg-rose-50 border border-rose-200 rounded-xl text-xs text-rose-800 space-y-2">
      <p>{forbidden ? 'Нет прав для этого раздела.' : getErrorMessage(error)}</p>
      {onRetry && !forbidden && (
        <Button type="button" size="sm" variant="outline" onClick={onRetry}>
          Повторить
        </Button>
      )}
    </div>
  );
};

/** Wraps the usual loading / error / empty states around a loaded value. */
export function LoadState<T>({
  state,
  empty,
  isEmpty,
  children,
}: {
  state: { data: T | null; error: unknown; loading: boolean; reload: () => void };
  empty?: string;
  isEmpty?: (data: T) => boolean;
  children: (data: T) => React.ReactNode;
}) {
  if (state.loading && state.data === null) return <Loading />;
  if (state.error) return <ErrorBox error={state.error} onRetry={state.reload} />;
  if (state.data === null) return null;
  if (empty && isEmpty?.(state.data)) return <Empty text={empty} />;
  return <>{children(state.data)}</>;
}

export const Pager: React.FC<{ page: number; limit: number; total: number; onPage: (p: number) => void }> = ({
  page,
  limit,
  total,
  onPage,
}) => {
  const pages = Math.max(1, Math.ceil(total / limit));
  if (pages <= 1) return null;
  return (
    <div className="flex items-center justify-between gap-2 text-xs text-zinc-500">
      <Button type="button" size="sm" variant="outline" disabled={page === 0} onClick={() => onPage(page - 1)}>
        Назад
      </Button>
      <span>
        {page + 1} / {pages}
      </span>
      <Button type="button" size="sm" variant="outline" disabled={page + 1 >= pages} onClick={() => onPage(page + 1)}>
        Вперёд
      </Button>
    </div>
  );
};

export const Field: React.FC<{ label: string; children: React.ReactNode }> = ({ label, children }) => (
  <div className="flex flex-wrap gap-x-2 text-xs min-w-0">
    <span className="text-zinc-500">{label}:</span>
    <span className="text-zinc-900 break-all min-w-0">{children}</span>
  </div>
);

/** Runs a write: busy flag, success toast, error toast and server fieldErrors (cleared on each run). */
export function useSubmit() {
  const { showToast } = useToast();
  const [busy, setBusy] = useState(false);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const run = async (action: () => Promise<unknown>, done?: string): Promise<boolean> => {
    setBusy(true);
    setErrors({});
    try {
      await action();
      if (done) showToast(done, 'success');
      return true;
    } catch (e) {
      if (e instanceof ApiError && e.fieldErrors) setErrors(e.fieldErrors);
      showToast(getErrorMessage(e), 'error');
      return false;
    } finally {
      setBusy(false);
    }
  };
  return { busy, errors, run, showToast };
}

/** Messages of fieldErrors whose key is `prefix` or nested under it ("rewards[0]" matches "rewards[0].quantity"). */
export const errorsFor = (errors: Record<string, string>, prefix: string) =>
  Object.entries(errors)
    .filter(([k]) => k === prefix || k.startsWith(prefix + '.') || k.startsWith(prefix + '['))
    .map(([, m]) => m)
    .join(' ');

export const Labeled: React.FC<{ label: string; error?: string; children: React.ReactNode }> = ({ label, error, children }) => (
  <label className="block space-y-1 text-xs min-w-0">
    <span className="text-zinc-500">{label}</span>
    {children}
    {error && <span className="block text-rose-600">{error}</span>}
  </label>
);

export const Check: React.FC<{ label: string; checked: boolean; onChange: (v: boolean) => void }> = ({ label, checked, onChange }) => (
  <label className="flex items-center gap-2 text-xs text-zinc-700">
    <input type="checkbox" checked={checked} onChange={(e) => onChange(e.target.checked)} />
    {label}
  </label>
);

export function SubTabs<T extends string>({ tabs, value, onChange }: { tabs: Array<[T, string]>; value: T; onChange: (v: T) => void }) {
  return (
    <div className="flex flex-wrap gap-1 bg-zinc-100/80 p-1 rounded-xl w-fit max-w-full" role="tablist">
      {tabs.map(([id, label]) => (
        <button
          key={id}
          type="button"
          role="tab"
          aria-selected={value === id}
          onClick={() => onChange(id)}
          className={`px-3 py-1.5 rounded-lg text-xs font-semibold transition-all cursor-pointer ${
            value === id ? 'bg-white text-zinc-900 shadow-xs' : 'text-zinc-600 hover:text-zinc-900'
          }`}
        >
          {label}
        </button>
      ))}
    </div>
  );
}

/** '' -> undefined (the server default), else a number. */
export const optNum = (s: string) => (s.trim() === '' ? undefined : Number(s));
/** '' -> undefined, else the trimmed text. */
export const optText = (s: string) => (s.trim() === '' ? undefined : s.trim());

export const Badge: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <span className="text-[10px] text-zinc-600 bg-zinc-100 px-1.5 rounded">{children}</span>
);

export const FormButtons: React.FC<{ busy: boolean; onCancel: () => void }> = ({ busy, onCancel }) => (
  <div className="flex flex-wrap gap-2">
    <Button type="submit" size="sm" isLoading={busy}>
      Сохранить
    </Button>
    <Button type="button" size="sm" variant="ghost" onClick={onCancel}>
      Отмена
    </Button>
  </div>
);
