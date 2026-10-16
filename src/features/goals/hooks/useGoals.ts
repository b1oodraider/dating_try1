import { useCallback, useEffect, useState } from 'react';
import { api, getErrorMessage } from '../../../core/api/client';
import type { Goal } from '../../../core/types';

// Goals are a tiny reference list: fetch once per page load and share (like tags).
let cachedGoals: Goal[] | null = null;
let inflight: Promise<Goal[]> | null = null;

function loadGoals(): Promise<Goal[]> {
  if (cachedGoals) return Promise.resolve(cachedGoals);
  if (!inflight) {
    inflight = api
      .getGoals()
      .then((goals) => {
        cachedGoals = [...goals].sort((a, b) => a.sortOrder - b.sortOrder);
        return cachedGoals;
      })
      .finally(() => {
        inflight = null;
      });
  }
  return inflight;
}

export function useGoals() {
  const [goals, setGoals] = useState<Goal[]>(cachedGoals ?? []);
  const [isLoading, setIsLoading] = useState<boolean>(cachedGoals === null);
  const [error, setError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    if (cachedGoals) {
      setGoals(cachedGoals);
      setIsLoading(false);
      return;
    }
    let cancelled = false;
    setIsLoading(true);
    setError(null);
    loadGoals()
      .then((loaded) => {
        if (!cancelled) setGoals(loaded);
      })
      .catch((e: unknown) => {
        if (!cancelled) setError(getErrorMessage(e, 'Не удалось загрузить список целей.'));
      })
      .finally(() => {
        if (!cancelled) setIsLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [attempt]);

  const reload = useCallback(() => setAttempt((n) => n + 1), []);

  return { goals, isLoading, error, reload };
}
