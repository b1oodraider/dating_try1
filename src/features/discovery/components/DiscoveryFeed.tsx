import React, { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import { PREMIUM_FEATURE, type PublicProfile, type Wallet, type BoostStatus, type ReceivedLikesCount } from '../../../core/types';
import { api, ApiError, getErrorMessage, isAbortError } from '../../../core/api/client';
import { useAuth } from '../../../core/context/AuthContext';
import { useToast } from '../../../shared/components/Toast';
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
import { SwipeDeck } from './SwipeDeck';
import { MatchModal } from '../../chat/components/MatchModal';
import { ReceivedLikesModal } from '../../chat/components/ReceivedLikesModal';
import {
  Frown,
  LogIn,
  AlertCircle,
  Compass,
  Crown,
  SlidersHorizontal,
  Lock,
  Heart,
  Rocket,
  Sparkles,
  Layers,
  LayoutGrid,
} from 'lucide-react';
import { Button } from '../../../shared/components/Button';

const PAGE_SIZE = 12;

interface DiscoveryFeedProps {
  onRequestAuth: () => void;
  /** Bumped when premium / economy changed (test panel): reload the wallet, saved lists and the feed. */
  revision?: number;
  onOpenChat?: (matchId: string) => void;
}

export const DiscoveryFeed: React.FC<DiscoveryFeedProps> = ({
  onRequestAuth,
  revision = 0,
  onOpenChat,
}) => {
  const { account, profile: myProfile } = useAuth();
  const accountId = account?.id ?? null;

  const { showToast } = useToast();
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
  const [viewMode, setViewMode] = useState<'swipe' | 'grid'>('swipe');

  // Social Features (Matches, Received Likes, Boost)
  const [matchedProfile, setMatchedProfile] = useState<PublicProfile | null>(null);
  const [matchId, setMatchId] = useState<string | undefined>(undefined);
  const [isReceivedLikesOpen, setIsReceivedLikesOpen] = useState(false);
  const [likesCount, setLikesCount] = useState<ReceivedLikesCount | null>(null);
  const [boostStatus, setBoostStatus] = useState<BoostStatus | null>(null);
  const [isActivatingBoost, setIsActivatingBoost] = useState(false);
  const [reactionProcessingId, setReactionProcessingId] = useState<string | null>(null);

  /** Filters of the "Фильтры" modal, persisted per account (survive reloads and re-login). */
  const [filtersOwner, setFiltersOwner] = useState(accountId);
  const [filters, setFilters] = useState<DiscoveryFilterState>(() => loadDiscoveryFilters(accountId));
  if (filtersOwner !== accountId) {
    setFiltersOwner(accountId);
    setFilters(loadDiscoveryFilters(accountId));
  }
  const applyFilters = (next: DiscoveryFilterState) => {
    setPremiumNotice(null);
    setFilters(next);
    if (accountId) saveDiscoveryFilters(accountId, next);
  };

  /** Applied saved hard filters */
  const [savedListsInfo, setSavedListsInfo] = useState({ excludedGoals: 0, exclusionWords: 0 });
  const [wallet, setWallet] = useState<Wallet | null>(null);
  const [refusedFeatures, setRefusedFeatures] = useState<string[]>([]);
  const [premiumNotice, setPremiumNotice] = useState<string | null>(null);
  const [feedNonce, setFeedNonce] = useState(0);

  const lockedFeatures = useMemo(() => lockedFeaturesOf(wallet, refusedFeatures), [wallet, refusedFeatures]);
  const advancedLocked = lockedFeatures.has(PREMIUM_FEATURE.ADVANCED_FILTERS);
  const wordsLocked = lockedFeatures.has(PREMIUM_FEATURE.EXCLUSION_WORDS);

  const queryKey = JSON.stringify(advancedLocked ? withoutAdvanced(filters) : filters);
  const query = useMemo(() => JSON.parse(queryKey) as DiscoveryFilterState, [queryKey]);

  const handlePremiumRequired = useCallback((feature: string) => {
    setRefusedFeatures((prev) => (prev.includes(feature) ? prev : [...prev, feature]));
  }, []);

  // Load Likes Count & Boost
  const loadSocialCounters = useCallback(async () => {
    if (!accountId) return;
    try {
      const [count, boost] = await Promise.all([
        api.getReceivedLikesCount().catch(() => null),
        api.getBoost().catch(() => null),
      ]);
      if (count) setLikesCount(count);
      if (boost) setBoostStatus(boost);
    } catch {
      // ignore
    }
  }, [accountId]);

  useEffect(() => {
    void loadSocialCounters();
  }, [loadSocialCounters, revision]);

  // Wallet load
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
        if (!isAbortError(e)) console.warn('Wallet load failed', e);
        if (isReload && !controller.signal.aborted) setFeedNonce((n) => n + 1);
      });
    return () => controller.abort();
  }, [accountId, revision]);

  // Saved hard filters summary
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

        // Record profile views for like-spam / ranking telemetry
        if (result.items.length > 0) {
          void api.sendProfileViews(
            result.items.slice(0, 5).map((p) => ({ profileId: p.id, dwellMs: 1200 }))
          ).catch(() => {});
        }
      } catch (e: unknown) {
        if (controller.signal.aborted || isAbortError(e)) return;
        if (e instanceof ApiError && e.isPremiumRequired) {
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

  // Reaction Handlers: Like, Superlike, Pass
  const handleLike = async (target: PublicProfile) => {
    setReactionProcessingId(target.id);
    try {
      const res = await api.likeProfile(target.id);
      setProfiles((prev) => prev.filter((p) => p.id !== target.id));
      setTotalElements((t) => Math.max(0, t - 1));
      if (res.matched) {
        setMatchedProfile(target);
        setMatchId(res.matchId);
      }
    } catch (e) {
      showToast(getErrorMessage(e, 'Не удалось поставить лайк'), 'error');
    } finally {
      setReactionProcessingId(null);
    }
  };

  const handleSuperlike = async (target: PublicProfile) => {
    setReactionProcessingId(target.id);
    try {
      const res = await api.superlikeProfile(target.id, crypto.randomUUID());
      setProfiles((prev) => prev.filter((p) => p.id !== target.id));
      setTotalElements((t) => Math.max(0, t - 1));
      if (res.matched) {
        setMatchedProfile(target);
        setMatchId(res.matchId);
      }
    } catch (e) {
      showToast(getErrorMessage(e, 'Не удалось поставить суперлайк. Проверьте баланс в Кошельке.'), 'error');
    } finally {
      setReactionProcessingId(null);
    }
  };

  const handlePass = async (target: PublicProfile) => {
    setReactionProcessingId(target.id);
    try {
      await api.passProfile(target.id);
      setProfiles((prev) => prev.filter((p) => p.id !== target.id));
      setTotalElements((t) => Math.max(0, t - 1));
    } catch (e) {
      showToast(getErrorMessage(e, 'Не удалось пропустить анкету'), 'error');
    } finally {
      setReactionProcessingId(null);
    }
  };

  const handleActivateBoost = async () => {
    setIsActivatingBoost(true);
    try {
      const updated = await api.activateBoost(crypto.randomUUID());
      setBoostStatus(updated);
      showToast('Буст успешно активирован на 30 минут!', 'success');
    } catch (e) {
      showToast(getErrorMessage(e, 'Не удалось активировать буст. Проверьте наличие предмета «Буст» в Кошельке.'), 'error');
    } finally {
      setIsActivatingBoost(false);
    }
  };

  const handleSavedListsInfo = useCallback((info: { excludedGoals?: number; exclusionWords?: number }) => {
    setSavedListsInfo((prev) => ({ ...prev, ...info }));
  }, []);

  const appliedAdvancedCount = advancedLocked ? 0 : countAdvancedFilters(filters);
  const hiddenBySavedLists = savedListsInfo.excludedGoals > 0 || savedListsInfo.exclusionWords > 0;
  const activeFilterCount =
    countBasicFilters(filters) +
    appliedAdvancedCount +
    (savedListsInfo.excludedGoals > 0 ? 1 : 0) +
    (savedListsInfo.exclusionWords > 0 ? 1 : 0);
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
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
          <div>
            <h1 className="text-xl font-bold text-zinc-900">Поиск анкет</h1>
            <p className="text-xs text-zinc-500">
              Знакомьтесь с людьми, выбирая подходящий формат и ритм общения
            </p>
          </div>

          <div className="flex items-center gap-2 self-start sm:self-auto">
            {/* Boost Widget */}
            {boostStatus?.active ? (
              <span className="flex items-center gap-1.5 px-3 py-1.5 bg-amber-50 border border-amber-300 text-amber-800 rounded-xl text-xs font-semibold shadow-2xs">
                <Rocket className="w-3.5 h-3.5 text-amber-600 animate-pulse" />
                Буст активен: {boostStatus.impressions} показов
              </span>
            ) : boostStatus && boostStatus.available > 0 ? (
              <Button
                variant="outline"
                size="sm"
                className="border-amber-300 text-amber-800 hover:bg-amber-50"
                onClick={handleActivateBoost}
                isLoading={isActivatingBoost}
              >
                <Rocket className="w-3.5 h-3.5 mr-1 text-amber-600" />
                Включить буст ({boostStatus.available})
              </Button>
            ) : null}

            {/* Received Likes Trigger */}
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => setIsReceivedLikesOpen(true)}
              className="border-rose-200 text-rose-700 bg-rose-50/50 hover:bg-rose-100"
            >
              <Heart className="w-3.5 h-3.5 mr-1 fill-rose-500 text-rose-500" />
              Симпатии
              {likesCount && likesCount.count > 0 && (
                <span className="ml-1 px-1.5 py-0.2 rounded-full bg-rose-500 text-white font-bold text-[10px]">
                  {likesCount.count}
                </span>
              )}
            </Button>
          </div>
        </div>

        {/* Filter Toolbar */}
        <div className="flex flex-wrap items-center justify-between gap-2 pt-1">
          <div className="flex flex-wrap items-center gap-2">
            <Button
              type="button"
              variant={activeFilterCount > 0 ? 'primary' : 'outline'}
              size="sm"
              onClick={() => setIsFiltersOpen(true)}
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

          <div className="flex items-center gap-3">
            {/* View Mode Toggle: Cards Deck (Swipes) vs Grid */}
            <div className="flex items-center p-0.5 bg-zinc-100 rounded-xl border border-zinc-200 text-xs">
              <button
                type="button"
                onClick={() => setViewMode('swipe')}
                className={`flex items-center gap-1 px-2.5 py-1 rounded-lg font-medium transition-all ${
                  viewMode === 'swipe'
                    ? 'bg-white text-zinc-900 shadow-2xs font-semibold'
                    : 'text-zinc-500 hover:text-zinc-800'
                }`}
                title="Режим колоды карточек (свайпы как в Tinder / Twinby)"
              >
                <Layers className="w-3.5 h-3.5" />
                <span>Свайпы</span>
              </button>
              <button
                type="button"
                onClick={() => setViewMode('grid')}
                className={`flex items-center gap-1 px-2.5 py-1 rounded-lg font-medium transition-all ${
                  viewMode === 'grid'
                    ? 'bg-white text-zinc-900 shadow-2xs font-semibold'
                    : 'text-zinc-500 hover:text-zinc-800'
                }`}
                title="Режим сетки (список анкет)"
              >
                <LayoutGrid className="w-3.5 h-3.5" />
                <span>Сетка</span>
              </button>
            </div>

            <div className="text-xs text-zinc-400 font-mono">
              Найдено: <strong className="text-zinc-800">{totalElements}</strong>
            </div>
          </div>
        </div>

        {premiumNotice && (
          <p className="text-[11px] text-amber-800 bg-amber-50 border border-amber-200 rounded-xl px-3 py-2 flex items-center gap-1.5">
            <Crown className="w-3.5 h-3.5 shrink-0 text-amber-500" />
            {premiumNotice}
          </p>
        )}
      </div>

      {/* Cards Deck or Grid */}
      {isLoading ? (
        <div className="flex items-center justify-center py-12">
          <div className="w-[380px] h-[550px] bg-white rounded-3xl border border-zinc-200 p-6 space-y-4 animate-pulse flex flex-col justify-end shadow-sm">
            <div className="h-6 bg-zinc-200 rounded w-1/2" />
            <div className="h-4 bg-zinc-200 rounded w-1/3" />
            <div className="h-10 bg-zinc-200 rounded-xl w-full" />
          </div>
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
          <h3 className="text-base font-semibold text-zinc-900">Ни одной анкеты не найдено</h3>
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
      ) : viewMode === 'swipe' ? (
        <SwipeDeck
          profiles={profiles}
          onLike={handleLike}
          onSuperlike={handleSuperlike}
          onPass={handlePass}
          onOpenDetails={(p) => setActiveProfileModal(p)}
          isProcessing={reactionProcessingId !== null}
          onReset={() => void fetchPage(0, false)}
          onOpenFilters={() => setIsFiltersOpen(true)}
          hasMore={hasMore}
          onNeedMore={() => {
            if (hasMore && !isLoadingMore) void fetchPage(page + 1, true);
          }}
        />
      ) : (
        <>
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-5">
            {profiles.map((profile) => (
              <ProfileFeedCard
                key={profile.id}
                profile={profile}
                onOpenDetails={(p) => setActiveProfileModal(p)}
                onLike={handleLike}
                onSuperlike={handleSuperlike}
                onPass={handlePass}
                isProcessing={reactionProcessingId === profile.id}
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

      {/* Discovery Filters Modal */}
      {isFiltersOpen && (
        <DiscoveryFiltersModal
          onClose={() => setIsFiltersOpen(false)}
          filters={filters}
          onApply={applyFilters}
          advancedLocked={advancedLocked}
          wordsLocked={wordsLocked}
          onSavedListsInfo={handleSavedListsInfo}
          onSavedListsChanged={() => void fetchPage(0, false)}
          onPremiumRequired={handlePremiumRequired}
        />
      )}

      {/* Profile Detail Modal */}
      <ProfileDetailModal
        profile={activeProfileModal}
        isOpen={!!activeProfileModal}
        onClose={() => setActiveProfileModal(null)}
        onLike={handleLike}
        onSuperlike={handleSuperlike}
        onPass={handlePass}
      />

      {/* Mutual Match Modal */}
      <MatchModal
        isOpen={!!matchedProfile}
        onClose={() => setMatchedProfile(null)}
        myProfile={myProfile}
        targetProfile={matchedProfile}
        matchId={matchId}
        onOpenChat={(id) => {
          setMatchedProfile(null);
          onOpenChat?.(id);
        }}
      />

      {/* Received Likes ("Кому вы понравились") */}
      <ReceivedLikesModal
        isOpen={isReceivedLikesOpen}
        onClose={() => setIsReceivedLikesOpen(false)}
        onMatched={(target, mId) => {
          setMatchedProfile(target);
          setMatchId(mId);
        }}
      />
    </div>
  );
};
