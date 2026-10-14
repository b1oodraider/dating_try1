import React, { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import { PREMIUM_FEATURE, type PublicProfile, type Wallet } from '../../../core/types';
import { api, ApiError, getErrorMessage, isAbortError } from '../../../core/api/client';
import { useAuth } from '../../../core/context/AuthContext';
import { DiscoveryFiltersModal } from './DiscoveryFiltersModal';
import {
  countAdvancedFilters,
  countBasicFilters,
  loadDiscoveryFilters,
  saveDiscoveryFilters,
  withoutAdvanced,
  type DiscoveryFilterState,
} from '../filterStorage';
import { lockedFeaturesOf } from '../../premium/premium';
import { ProfileFeedCard } from './ProfileFeedCard';
import { ProfileDetailModal } from './ProfileDetailModal';
import { Frown, LogIn, AlertCircle, Compass, Crown, SlidersHorizontal, Lock } from 'lucide-react';
import { Button } from '../../../shared/components/Button';

const PAGE_SIZE = 12;

interface DiscoveryFeedProps {
  onRequestAuth: () => void;
  /** Bumped when premium / economy changed (test panel): reload the wallet, saved lists and the feed. */
  revision?: number;
}

export const DiscoveryFeed: React.FC<DiscoveryFeedProps> = ({ onRequestAuth, revision = 0 }) => {
  const { account } = useAuth();
  const accountId = account?.id ?? null;

  const [profiles, setProfiles] = useState<PublicProfile[]>([]);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [isLoading, setIsLoading] = useState(true);
  const [isLoadingMore, setIsLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [loadMoreError, setLoadMoreError] = useState<string | null>(null);

  const [activeProfileModal, setActiveProfileModal] = useState<PublicProfile | null>(null);
  const [isFiltersOpen, setIsFiltersOpen] = useState(false);

  /** Filters of the "Фильтры" modal, persisted per account (survive reloads and re-login). */
  const [filtersOwner, setFiltersOwner] = useState(accountId);
  const [filters, setFilters] = useState<DiscoveryFilterState>(() => loadDiscoveryFilters(accountId));
  if (filtersOwner !== accountId) {
    // Another account (or logout): switch to its own saved filters during render, before any fetch.
    setFiltersOwner(accountId);
    setFilters(loadDiscoveryFilters(accountId));
  }
  const applyFilters = (next: DiscoveryFilterState) => {
    setPremiumNotice(null);
    setFilters(next);
    if (accountId) saveDiscoveryFilters(accountId, next);
  };

  /** Applied saved hard filters (excluded goals, exclusion words): filter indicator and empty-feed hint. */
  const [savedListsInfo, setSavedListsInfo] = useState({ excludedGoals: 0, exclusionWords: 0 });
  /** Premium state; null while unknown (then nothing is locked and the server decides). */
  const [wallet, setWallet] = useState<Wallet | null>(null);
  /** Features the server refused with 403 PREMIUM_REQUIRED since the last wallet load. */
  const [refusedFeatures, setRefusedFeatures] = useState<string[]>([]);
  /** Shown once when the server refused the advanced filters (stale premium state). */
  const [premiumNotice, setPremiumNotice] = useState<string | null>(null);
  /** Bumped after a wallet reload caused by `revision`: forces a feed reload even if no filter changed. */
  const [feedNonce, setFeedNonce] = useState(0);

  const lockedFeatures = useMemo(() => lockedFeaturesOf(wallet, refusedFeatures), [wallet, refusedFeatures]);
  const advancedLocked = lockedFeatures.has(PREMIUM_FEATURE.ADVANCED_FILTERS);
  const wordsLocked = lockedFeatures.has(PREMIUM_FEATURE.EXCLUSION_WORDS);

  // Without premium the advanced filters stay saved but are not sent (else 403). Keyed by content, so a
  // wallet load that only locks/unlocks the feature doesn't refetch the feed when nothing changes.
  const queryKey = JSON.stringify(advancedLocked ? withoutAdvanced(filters) : filters);
  const query = useMemo(() => JSON.parse(queryKey) as DiscoveryFilterState, [queryKey]);

  const handlePremiumRequired = useCallback((feature: string) => {
    setRefusedFeatures((prev) => (prev.includes(feature) ? prev : [...prev, feature]));
  }, []);

  // Wallet: on login and after premium changes. The first load only unlocks/locks controls; later loads
  // (revision bumps) also restart the feed, because saved filters may have started or stopped applying.
  const walletRevisionRef = useRef<number | null>(null);
  useEffect(() => {
    if (!accountId) {
      setWallet(null);
      setRefusedFeatures([]);
      walletRevisionRef.current = null;
      return;
    }
    const controller = new AbortController();
    const isReload = walletRevisionRef.current !== null && walletRevisionRef.current !== revision;
    walletRevisionRef.current = revision;
    api
      .getWallet(controller.signal)
      .then((w) => {
        setWallet(w);
        setRefusedFeatures([]);
        setPremiumNotice(null);
        if (isReload) setFeedNonce((n) => n + 1);
      })
      .catch((e: unknown) => {
        // Unknown premium state: keep controls enabled, the server answers 403 if needed.
        if (!isAbortError(e)) console.warn('Wallet load failed', e);
        if (isReload && !controller.signal.aborted) setFeedNonce((n) => n + 1);
      });
    return () => controller.abort();
  }, [accountId, revision]);

  // Saved hard filters summary (for the indicator and the empty-feed hint); the modal refreshes it too.
  useEffect(() => {
    if (!accountId) {
      setSavedListsInfo({ excludedGoals: 0, exclusionWords: 0 });
      return;
    }
    const controller = new AbortController();
    void Promise.allSettled([
      api.getExcludedGoals(controller.signal),
      api.getExclusionWords(controller.signal),
    ]).then(([goalsResult, wordsResult]) => {
      if (controller.signal.aborted) return;
      setSavedListsInfo({
        excludedGoals:
          goalsResult.status === 'fulfilled' && goalsResult.value.active ? goalsResult.value.goalCodes.length : 0,
        exclusionWords:
          wordsResult.status === 'fulfilled' && wordsResult.value.active !== false ? wordsResult.value.words.length : 0,
      });
    });
    return () => controller.abort();
  }, [accountId, revision]);

  // Only the latest request may write to state: older ones are aborted.
  const controllerRef = useRef<AbortController | null>(null);

  const fetchPage = useCallback(
    async (pageToLoad: number, append: boolean) => {
      controllerRef.current?.abort();
      const controller = new AbortController();
      controllerRef.current = controller;

      if (append) {
        setIsLoadingMore(true);
        setLoadMoreError(null);
      } else {
        setIsLoading(true);
        setError(null);
        setLoadMoreError(null);
      }

      try {
        const result = await api.getDiscoveryProfiles(
          {
            city: query.city ?? undefined,
            minAge: query.minAge,
            maxAge: query.maxAge,
            tagCodes: query.tagCodes,
            tagLogic: query.tagLogic,
            minHeight: query.minHeight,
            maxHeight: query.maxHeight,
            goalCodes: query.goalCodes,
            page: pageToLoad,
            limit: PAGE_SIZE,
          },
          controller.signal
        );
        if (controller.signal.aborted) return;

        setProfiles((prev) => {
          if (!append) return result.items;
          const seen = new Set(prev.map((p) => p.id));
          return [...prev, ...result.items.filter((p) => !seen.has(p.id))];
        });
        setPage(result.page);
        setTotalPages(result.totalPages);
        setTotalElements(result.totalElements);
      } catch (e: unknown) {
        if (controller.signal.aborted || isAbortError(e)) return;
        if (e instanceof ApiError && e.isPremiumRequired) {
          // Premium ended (or the wallet is stale): lock the feature; fetchPage changes and the effect
          // below reloads the feed without the advanced filters.
          handlePremiumRequired(e.premiumFeature ?? PREMIUM_FEATURE.ADVANCED_FILTERS);
          setPremiumNotice('Расширенный поиск доступен с премиумом — показываем анкеты без него.');
          return;
        }
        const message = getErrorMessage(e, 'Не удалось загрузить анкеты.');
        if (append) {
          setLoadMoreError(message);
        } else {
          setProfiles([]);
          setError(message);
        }
      } finally {
        if (controllerRef.current === controller) {
          setIsLoading(false);
          setIsLoadingMore(false);
        }
      }
    },
    [query, handlePremiumRequired]
  );

  useEffect(() => {
    if (!accountId) {
      controllerRef.current?.abort();
      setProfiles([]);
      setTotalElements(0);
      setTotalPages(0);
      setIsLoading(false);
      return;
    }
    void fetchPage(0, false);
    return () => {
      controllerRef.current?.abort();
    };
  }, [accountId, fetchPage, feedNonce]);

  const handleSavedListsInfo = useCallback((info: { excludedGoals?: number; exclusionWords?: number }) => {
    setSavedListsInfo((prev) => ({ ...prev, ...info }));
  }, []);

  const appliedAdvancedCount = advancedLocked ? 0 : countAdvancedFilters(filters);
  const hiddenBySavedLists = savedListsInfo.excludedGoals > 0 || savedListsInfo.exclusionWords > 0;
  /** Filters that currently narrow the feed (the "Фильтры" button indicator). */
  const activeFilterCount =
    countBasicFilters(filters) +
    appliedAdvancedCount +
    (savedListsInfo.excludedGoals > 0 ? 1 : 0) +
    (savedListsInfo.exclusionWords > 0 ? 1 : 0);
  /** Premium filter values are saved but not applied right now. */
  const hasLockedFilters = advancedLocked && countAdvancedFilters(filters) > 0;

  const hasMore = page + 1 < totalPages;

  if (!accountId) {
    return (
      <div className="max-w-md mx-auto text-center py-16 space-y-4">
        <div className="w-12 h-12 bg-zinc-200/80 rounded-2xl flex items-center justify-center mx-auto text-zinc-600">
          <Compass className="w-6 h-6" />
        </div>
        <h2 className="text-lg font-bold text-zinc-900">Поиск анкет доступен после входа</h2>
        <p className="text-xs text-zinc-500">
          Анкеты видят только участники закрытого тестирования. Войдите по номеру телефона, чтобы
          смотреть профили и фильтровать их по карточкам общения.
        </p>
        <Button type="button" variant="primary" onClick={onRequestAuth}>
          <LogIn className="w-4 h-4 mr-1" /> Войти по номеру телефона
        </Button>
      </div>
    );
  }

  return (
    <div className="space-y-5 max-w-5xl mx-auto pb-12">
      {/* Intro and Filters */}
      <div className="space-y-3">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2">
          <div>
            <h1 className="text-xl font-bold text-zinc-900">Поиск анкет</h1>
            <p className="text-xs text-zinc-500">
              Знакомьтесь с людьми, выбирая подходящий формат и ритм общения
            </p>
          </div>
          <div className="text-xs text-zinc-400 self-start sm:self-auto font-mono">
            Найдено: <strong className="text-zinc-800">{totalElements}</strong> анкет
          </div>
        </div>

        {/* The single entry point to every filter. */}
        <div className="flex flex-wrap items-center gap-2">
          <Button
            type="button"
            variant={activeFilterCount > 0 ? 'primary' : 'outline'}
            size="sm"
            onClick={() => setIsFiltersOpen(true)}
            aria-label={activeFilterCount > 0 ? `Фильтры, активно: ${activeFilterCount}` : 'Фильтры'}
          >
            <SlidersHorizontal className="w-3.5 h-3.5" />
            Фильтры
            {activeFilterCount > 0 && (
              <span className="ml-0.5 px-1.5 py-0.5 leading-none bg-amber-500 text-white rounded-full text-[10px] font-bold">
                {activeFilterCount}
              </span>
            )}
          </Button>
          {hasLockedFilters && (
            <span className="text-[11px] text-amber-700 flex items-center gap-1">
              <Lock className="w-3 h-3" /> Премиум-фильтры сохранены, но не применяются
            </span>
          )}
        </div>

        {premiumNotice && (
          <p className="text-[11px] text-amber-800 bg-amber-50 border border-amber-200 rounded-xl px-3 py-2 flex items-center gap-1.5">
            <Crown className="w-3.5 h-3.5 shrink-0 text-amber-500" />
            {premiumNotice}
          </p>
        )}
      </div>

      {/* Grid or Empty/Loading/Error State */}
      {isLoading ? (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-5">
          {[1, 2, 3, 4, 5, 6].map((idx) => (
            <div
              key={idx}
              className="bg-white rounded-2xl border border-zinc-200/80 p-4 space-y-3 animate-pulse"
            >
              <div className="aspect-4/5 w-full bg-zinc-200 rounded-xl" />
              <div className="h-4 bg-zinc-200 rounded w-1/2" />
              <div className="h-3 bg-zinc-200 rounded w-3/4" />
              <div className="flex gap-1.5 pt-2">
                <div className="h-6 w-16 bg-zinc-200 rounded-full" />
                <div className="h-6 w-20 bg-zinc-200 rounded-full" />
              </div>
            </div>
          ))}
        </div>
      ) : error ? (
        <div className="p-12 bg-white rounded-2xl border border-zinc-200/80 text-center space-y-3">
          <div className="w-12 h-12 bg-rose-50 rounded-full flex items-center justify-center mx-auto text-rose-500">
            <AlertCircle className="w-6 h-6" />
          </div>
          <h3 className="text-base font-semibold text-zinc-900">Не удалось загрузить анкеты</h3>
          <p className="text-xs text-zinc-500 max-w-sm mx-auto">{error}</p>
          <div className="pt-2">
            <Button variant="outline" size="sm" onClick={() => void fetchPage(0, false)}>
              Повторить
            </Button>
          </div>
        </div>
      ) : profiles.length === 0 ? (
        <div className="p-12 bg-white rounded-2xl border border-zinc-200/80 text-center space-y-3">
          <div className="w-12 h-12 bg-zinc-100 rounded-full flex items-center justify-center mx-auto text-zinc-400">
            <Frown className="w-6 h-6" />
          </div>
          <h3 className="text-base font-semibold text-zinc-900">
            Ни одной анкеты не найдено
          </h3>
          <p className="text-xs text-zinc-500 max-w-sm mx-auto">
            Попробуйте ослабить фильтры: переключить логику карточек на «Любая (ИЛИ)», расширить возраст или сбросить город.
            {appliedAdvancedCount > 0 && ' Учтите фильтры расширенного поиска по росту и целям.'}
            {hiddenBySavedLists && ' Часть анкет также скрыта вашими скрытыми целями или словами-исключениями.'}
          </p>
          <div className="pt-2">
            <Button variant="outline" size="sm" onClick={() => setIsFiltersOpen(true)}>
              <SlidersHorizontal className="w-3.5 h-3.5" /> Изменить фильтры
            </Button>
          </div>
        </div>
      ) : (
        <>
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-5">
            {profiles.map((profile) => (
              <ProfileFeedCard
                key={profile.id}
                profile={profile}
                onOpenDetails={(p) => setActiveProfileModal(p)}
              />
            ))}
          </div>

          {(hasMore || loadMoreError) && (
            <div className="flex flex-col items-center gap-2 pt-2">
              {loadMoreError && (
                <p className="text-xs text-rose-600 flex items-center gap-1.5">
                  <AlertCircle className="w-3.5 h-3.5 shrink-0" />
                  {loadMoreError}
                </p>
              )}
              <Button
                type="button"
                variant="outline"
                onClick={() => void fetchPage(page + 1, true)}
                isLoading={isLoadingMore}
              >
                {loadMoreError ? 'Повторить' : 'Показать ещё'}
              </Button>
            </div>
          )}
        </>
      )}

      {isFiltersOpen && (
        <DiscoveryFiltersModal
          onClose={() => setIsFiltersOpen(false)}
          filters={filters}
          onApply={applyFilters}
          advancedLocked={advancedLocked}
          wordsLocked={wordsLocked}
          onSavedListsInfo={handleSavedListsInfo}
          // The server applies saved lists before pagination: start the feed over from page 0.
          onSavedListsChanged={() => void fetchPage(0, false)}
          onPremiumRequired={handlePremiumRequired}
        />
      )}

      {/* Profile Detail Modal */}
      <ProfileDetailModal
        profile={activeProfileModal}
        isOpen={!!activeProfileModal}
        onClose={() => setActiveProfileModal(null)}
      />
    </div>
  );
};
