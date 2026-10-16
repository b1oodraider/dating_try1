import React, { useCallback, useEffect, useRef, useState } from 'react';
import { api, ApiError, getErrorMessage, isAbortError } from '../../../core/api/client';
import { PREMIUM_FEATURE, type Goal } from '../../../core/types';
import { Button } from '../../../shared/components/Button';
import { GoalToggleList } from '../../goals/components/GoalChips';
import { PremiumLockHint } from '../../premium/components/PremiumBadge';
import { AlertCircle, Ban, Check } from 'lucide-react';

const sameSet = (a: string[], b: string[]) => a.length === b.length && a.every((c) => b.includes(c));

interface ExcludedGoalsEditorProps {
  goals: Goal[];
  /** Premium ADVANCED_FILTERS is not available: the saved list is shown read-only. */
  locked: boolean;
  /** Called with the server list after the initial load (active = the list is applied to the feed). */
  onLoaded?: (goalCodes: string[], active: boolean) => void;
  /** Called after a successful save; the feed should reload from page 0. */
  onSaved: (goalCodes: string[], active: boolean) => void;
  /** The server answered 403 PREMIUM_REQUIRED (details.feature). */
  onPremiumRequired?: (feature: string) => void;
}

type LoadState = { status: 'loading' } | { status: 'ready' } | { status: 'error'; message: string };

/** "Скрывать цели": saved hard filter, profiles having any of these goals are hidden from the feed. */
export const ExcludedGoalsEditor: React.FC<ExcludedGoalsEditorProps> = ({
  goals,
  locked,
  onLoaded,
  onSaved,
  onPremiumRequired,
}) => {
  const [loadState, setLoadState] = useState<LoadState>({ status: 'loading' });
  const [saved, setSaved] = useState<string[]>([]);
  const [draft, setDraft] = useState<string[]>([]);
  const [active, setActive] = useState(true);
  const [isSaving, setIsSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [justSaved, setJustSaved] = useState(false);

  const controllerRef = useRef<AbortController | null>(null);
  const onLoadedRef = useRef(onLoaded);
  useEffect(() => {
    onLoadedRef.current = onLoaded;
  }, [onLoaded]);

  const load = useCallback(async () => {
    controllerRef.current?.abort();
    const controller = new AbortController();
    controllerRef.current = controller;
    setLoadState({ status: 'loading' });
    try {
      const result = await api.getExcludedGoals(controller.signal);
      if (controller.signal.aborted) return;
      setSaved(result.goalCodes);
      setDraft(result.goalCodes);
      setActive(result.active);
      setLoadState({ status: 'ready' });
      onLoadedRef.current?.(result.goalCodes, result.active);
    } catch (e: unknown) {
      if (controller.signal.aborted || isAbortError(e)) return;
      setLoadState({ status: 'error', message: getErrorMessage(e, 'Не удалось загрузить скрытые цели.') });
    }
  }, []);

  useEffect(() => {
    void load();
    return () => {
      controllerRef.current?.abort();
    };
  }, [load]);

  const isReady = loadState.status === 'ready';
  const isDirty = !sameSet(draft, saved);

  const toggle = (code: string) => {
    setJustSaved(false);
    setSaveError(null);
    setDraft((prev) => (prev.includes(code) ? prev.filter((c) => c !== code) : [...prev, code]));
  };

  const handleSave = async () => {
    setIsSaving(true);
    setSaveError(null);
    setJustSaved(false);
    try {
      const result = await api.updateExcludedGoals(draft);
      setSaved(result.goalCodes);
      setDraft(result.goalCodes);
      setActive(result.active);
      setJustSaved(true);
      onSaved(result.goalCodes, result.active);
    } catch (e: unknown) {
      if (e instanceof ApiError && e.isPremiumRequired) {
        onPremiumRequired?.(e.premiumFeature ?? PREMIUM_FEATURE.ADVANCED_FILTERS);
        return;
      }
      const fieldError = e instanceof ApiError ? e.fieldErrors?.goalCodes : undefined;
      setSaveError(fieldError ?? getErrorMessage(e, 'Не удалось сохранить скрытые цели.'));
    } finally {
      setIsSaving(false);
    }
  };

  return (
    <section className="space-y-2" aria-label="Скрывать цели">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="flex items-center gap-2 text-xs font-semibold text-zinc-800">
          <Ban className="w-3.5 h-3.5 text-zinc-500" />
          Скрывать цели
          {isReady && saved.length > 0 && (
            <span className="px-1.5 py-0.5 leading-none bg-amber-500 text-white rounded-full text-[10px] font-bold">
              {saved.length}
            </span>
          )}
          {isReady && isDirty && <span className="text-[11px] font-normal text-amber-600">не сохранено</span>}
        </span>
        {locked && <PremiumLockHint />}
      </div>
      <p className="text-[11px] text-zinc-500">
        Анкеты с любой из отмеченных целей не попадут в ленту. Список сохраняется и виден только вам.
      </p>

      {loadState.status === 'loading' && <p className="text-xs text-zinc-400">Загрузка списка...</p>}

      {loadState.status === 'error' && (
        <div className="p-2.5 bg-rose-50 border border-rose-200 text-rose-700 text-xs rounded-xl flex items-center justify-between gap-2">
          <span className="flex items-center gap-2">
            <AlertCircle className="w-4 h-4 shrink-0" />
            {loadState.message}
          </span>
          <Button type="button" variant="outline" size="sm" onClick={() => void load()}>
            Повторить
          </Button>
        </div>
      )}

      {isReady && (
        <>
          {saved.length > 0 && (!active || locked) && (
            <p className="text-[11px] text-amber-700">Сохранено, но не применяется без премиума.</p>
          )}
          <GoalToggleList
            goals={goals}
            selectedCodes={draft}
            onToggle={toggle}
            disabled={locked || isSaving}
            tone="exclude"
          />
          {saveError && (
            <p role="alert" className="text-[11px] text-rose-600 flex items-center gap-1">
              <AlertCircle className="w-3 h-3 shrink-0" /> {saveError}
            </p>
          )}
          <div className="flex items-center justify-end gap-1.5 text-[11px]">
            {justSaved && !isDirty && (
              <span className="flex items-center gap-1 text-emerald-600">
                <Check className="w-3 h-3" /> Сохранено
              </span>
            )}
            {isDirty && (
              <Button
                type="button"
                variant="ghost"
                size="sm"
                onClick={() => {
                  setDraft(saved);
                  setSaveError(null);
                }}
                disabled={isSaving}
              >
                Отменить
              </Button>
            )}
            <Button
              type="button"
              variant="primary"
              size="sm"
              onClick={() => void handleSave()}
              isLoading={isSaving}
              disabled={locked || !isDirty || isSaving}
            >
              Сохранить
            </Button>
          </div>
        </>
      )}
    </section>
  );
};
