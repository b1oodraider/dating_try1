import React, { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import {
  PREMIUM_FEATURE,
  type PublicProfile,
  type Wallet,
  type BoostStatus,
  type ReceivedLikesCount,
  type Restriction,
} from '../../../core/types';
import { api, ApiError, formatDateTime, getErrorMessage, isAbortError, RESTRICTIONS_CHANGED } from '../../../core/api/client';
import { PurchaseModal, type PurchaseKind } from '../../premium/components/PurchaseModal';
import { BoostButton } from './BoostButton';
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
import { ProfileDetailModal } from './ProfileDetailModal';
import { SwipeDeck } from './SwipeDeck';
import { MatchModal } from '../../chat/components/MatchModal';
import { ReceivedLikesModal } from '../../chat/components/ReceivedLikesModal';
import { useRealtime } from '../../../core/realtime/RealtimeContext';
import {
  Frown,
  LogIn,
  AlertCircle,
  Compass,
  Crown,
  SlidersHorizontal,
  Lock,
  Heart,
  Undo2,
  History,
} from 'lucide-react';
import { Button } from '../../../shared/components/Button';

const PAGE_SIZE = 20;
/** Fetch more when this few cards are left. */
const REFILL_AT = 5;
/** Views are sent in batches (contract: when cards leave the screen). */
const VIEWS_BATCH = 10;
const MAX_DWELL_MS = 3_600_000;

type Reaction = 'like' | 'superlike' | 'pass';
type ProfileView = { profileId: string; dwellMs: number; openedDetails: boolean };

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
  const queuedHintShownRef = useRef(false);
  const [profiles, setProfiles] = useState<PublicProfile[]>([]);
  const [totalElements, setTotalElements] = useState(0);
  /** The server had nothing beyond the cards already in the deck. */
  const [exhausted, setExhausted] = useState(false);
  /** Reactions still in flight: the next refill waits for them, so the server already hides those profiles. */
  const [pendingReactions, setPendingReactions] = useState(0);
  /** Profiles reacted to in this session, never shown again even if a response raced the reaction. */
  const reactedRef = useRef(new Set<string>());
  const [isLoading, setIsLoading] = useState(true);
  const [isLoadingMore, setIsLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [loadMoreError, setLoadMoreError] = useState<string | null>(null);

  const [activeProfileModal, setActiveProfileModal] = useState<PublicProfile | null>(null);
  const [isFiltersOpen, setIsFiltersOpen] = useState(false);

  // Social Features (Matches, Received Likes, Boost)
  const [matchedProfile, setMatchedProfile] = useState<PublicProfile | null>(null);
  const [matchId, setMatchId] = useState<string | undefined>(undefined);
  const [isReceivedLikesOpen, setIsReceivedLikesOpen] = useState(false);
  const [likesCount, setLikesCount] = useState<ReceivedLikesCount | null>(null);
  const [boostStatus, setBoostStatus] = useState<BoostStatus | null>(null);
  const [isActivatingBoost, setIsActivatingBoost] = useState(false);
  /** The purchase window over the feed (null = closed). */
  const [purchaseKind, setPurchaseKind] = useState<PurchaseKind | null>(null);
  /** Bumped after a purchase: reload the wallet and the boost. */
  const [walletNonce, setWalletNonce] = useState(0);
  /** Active LIKES / SUSPENDED restriction: likes are blocked (the server refuses them anyway). */
  const [likesRestriction, setLikesRestriction] = useState<Restriction | null>(null);

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
  const superlikesLeft = wallet ? (wallet.items.find((i) => i.category === 'SUPERLIKE')?.quantity ?? 0) : null;

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
  }, [loadSocialCounters, revision, walletNonce]);

  // Likes restriction (moderation): reloaded whenever an action was refused.
  useEffect(() => {
    if (!accountId) {
      setLikesRestriction(null);
      return;
    }
    let cancelled = false;
    const load = () => {
      api
        .getRestrictions()
        .then((all) => {
          if (!cancelled) setLikesRestriction(all.find((r) => r.kind === 'LIKES' || r.kind === 'SUSPENDED') ?? null);
        })
        .catch(() => {});
    };
    load();
    window.addEventListener(RESTRICTIONS_CHANGED, load);
    return () => {
      cancelled = true;
      window.removeEventListener(RESTRICTIONS_CHANGED, load);
    };
  }, [accountId]);

  const showLikesBlocked = useCallback(() => {
    const until = likesRestriction?.endsAt;
    showToast(until ? `Лайки недоступны до ${formatDateTime(until)}` : 'Лайки недоступны до решения модератора', 'error');
  }, [likesRestriction, showToast]);

  useRealtime((event) => {
    if (event.name === 'like_received') {
      setLikesCount((prev) => ({
        count: event.data.count,
        superlikeCount: (prev?.superlikeCount ?? 0) + (event.data.superlike ? 1 : 0),
      }));
    } else if (event.name === 'ready') {
      void loadSocialCounters();
    }
  });

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
  }, [accountId, revision, walletNonce]);

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

  /**
   * append = false: a fresh deck. append = true: refill. Paging by offset would skip profiles, because every reaction
   * removes one from the server's result; so a refill asks for page 0 again and keeps only unseen profiles.
   */
  const fetchPage = useCallback(
    async (append: boolean) => {
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
        setExhausted(false);
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
            page: 0,
            limit: PAGE_SIZE,
          },
          controller.signal
        );
        if (controller.signal.aborted) return;

        const fresh = result.items.filter((p) => !reactedRef.current.has(p.id));
        setProfiles((prev) => {
          if (!append) return fresh;
          const seen = new Set(prev.map((p) => p.id));
          const added = fresh.filter((p) => !seen.has(p.id));
          if (added.length === 0) setExhausted(true);
          return [...prev, ...added];
        });
        if (!append && result.totalElements <= fresh.length) setExhausted(true);
        setTotalElements(result.totalElements);
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
      reactedRef.current.clear();
      setIsLoading(false);
      return;
    }
    void fetchPage(false);
    return () => {
      controllerRef.current?.abort();
    };
  }, [accountId, fetchPage, feedNonce]);

  useEffect(() => {
    if (
      profiles.length <= REFILL_AT &&
      !exhausted &&
      pendingReactions === 0 &&
      !isLoading &&
      !isLoadingMore &&
      !loadMoreError &&
      !error
    ) {
      void fetchPage(true);
    }
  }, [profiles.length, exhausted, pendingReactions, isLoading, isLoadingMore, loadMoreError, error, fetchPage]);

  // ---- Views for the like-spam rule: real time on screen of the top card (+ details), sent in batches.
  const topId = profiles[0]?.id ?? null;
  const viewRef = useRef<{ profileId: string; startedAt: number; openedDetails: boolean } | null>(null);
  const viewBuffer = useRef<ProfileView[]>([]);

  const flushViews = useCallback(() => {
    const views = viewBuffer.current.splice(0);
    if (views.length > 0) api.sendProfileViews(views).catch(() => {});
  }, []);

  useEffect(() => {
    viewRef.current = topId ? { profileId: topId, startedAt: Date.now(), openedDetails: false } : null;
  }, [topId]);

  useEffect(() => {
    const onHide = () => {
      if (document.visibilityState === 'hidden') flushViews();
    };
    document.addEventListener('visibilitychange', onHide);
    return () => {
      document.removeEventListener('visibilitychange', onHide);
      flushViews();
    };
  }, [flushViews]);

  const recordView = (profileId: string) => {
    const view = viewRef.current;
    if (!view || view.profileId !== profileId) return;
    viewRef.current = null;
    viewBuffer.current.push({
      profileId,
      dwellMs: Math.min(MAX_DWELL_MS, Date.now() - view.startedAt),
      openedDetails: view.openedDetails,
    });
    if (viewBuffer.current.length >= VIEWS_BATCH) flushViews();
  };

  const openDetails = (profile: PublicProfile) => {
    if (viewRef.current?.profileId === profile.id) viewRef.current.openedDetails = true;
    setActiveProfileModal(profile);
  };

  /** Removes the card from the deck at once; the request runs in the background and a failure puts the card back. */
  const removeCard = (target: PublicProfile) => {
    reactedRef.current.add(target.id);
    setProfiles((prev) => prev.filter((p) => p.id !== target.id));
    setTotalElements((t) => Math.max(0, t - 1));
  };

  const react = async (kind: Reaction, target: PublicProfile) => {
    if (kind !== 'pass' && likesRestriction) {
      showLikesBlocked();
      return;
    }
    if (kind === 'superlike' && superlikesLeft === 0) {
      setPurchaseKind('superlikes');
      return;
    }
    recordView(target.id);
    removeCard(target);
    setPendingReactions((n) => n + 1);
    try {
      const res =
        kind === 'like'
          ? await api.likeProfile(target.id)
          : kind === 'superlike'
          ? await api.superlikeProfile(target.id, crypto.randomUUID())
          : await api.passProfile(target.id);
      if (res.queued && !queuedHintShownRef.current) {
        queuedHintShownRef.current = true;
        showToast('Лайк отправится, когда подтвердите селфи (Профиль → «Верификация по селфи»)', 'success');
      }
      if (res.matched) {
        setMatchedProfile(target);
        setMatchId(res.matchId);
      }
      if (kind === 'superlike') setWalletNonce((n) => n + 1);
    } catch (e) {
      // The profile is gone (hidden, blocked, deleted): nothing to put back.
      if (!(e instanceof ApiError && e.code === 'PROFILE_NOT_FOUND')) {
        reactedRef.current.delete(target.id);
        setProfiles((prev) => [target, ...prev.filter((p) => p.id !== target.id)]);
        setTotalElements((t) => t + 1);
      }
      if (e instanceof ApiError && e.code === 'INSUFFICIENT_BALANCE') {
        // Out of super likes: the card is back on top; the shop opens over the feed.
        setPurchaseKind('superlikes');
        return;
      }
      const fallback =
        kind === 'like'
          ? 'Не удалось поставить лайк'
          : kind === 'superlike'
          ? 'Не удалось поставить суперлайк. Проверьте баланс в Кошельке.'
          : 'Не удалось пропустить анкету';
      showToast(getErrorMessage(e, fallback), 'error');
    } finally {
      setPendingReactions((n) => n - 1);
    }
  };

  const handleLike = (target: PublicProfile) => void react('like', target);
  const handleSuperlike = (target: PublicProfile) => void react('superlike', target);
  const handlePass = (target: PublicProfile) => void react('pass', target);

  /** Premium: puts my last swiped profile back on top of the deck. */
  const handleUndo = async () => {
    if (lockedFeatures.has(PREMIUM_FEATURE.UNDO_SWIPE)) {
      setPurchaseKind('premium');
      return;
    }
    try {
      const restored = await api.undoReaction();
      reactedRef.current.delete(restored.id);
      setProfiles((prev) => [restored, ...prev.filter((p) => p.id !== restored.id)]);
    } catch (e) {
      if (e instanceof ApiError && e.isPremiumRequired) {
        handlePremiumRequired(PREMIUM_FEATURE.UNDO_SWIPE);
        setPurchaseKind('premium');
        return;
      }
      showToast(getErrorMessage(e, 'Не удалось вернуть анкету'), 'error');
    }
  };

  /** Brings passed profiles back: free when the feed ran out (once a day), premium any time. */
  const handleRefreshPasses = async () => {
    const feedEmpty = profiles.length === 0;
    if (!feedEmpty && lockedFeatures.has(PREMIUM_FEATURE.PASS_REFRESH)) {
      setPurchaseKind('premium');
      return;
    }
    try {
      const { restored } = await api.refreshPasses(feedEmpty);
      showToast(restored === 0 ? 'Пропущенных анкет нет' : `Вернули пропущенных: ${restored}`, 'success');
      reactedRef.current.clear(); // likes stay excluded by the server
      void fetchPage(false);
    } catch (e) {
      if (
        e instanceof ApiError &&
        (e.isPremiumRequired || e.code === 'PASS_REFRESH_TOO_SOON' || e.code === 'FEED_NOT_EMPTY')
      ) {
        showToast(e.message, 'error');
        setPurchaseKind('premium');
        return;
      }
      showToast(getErrorMessage(e, 'Не удалось вернуть пропущенных'), 'error');
    }
  };

  const handleActivateBoost = async () => {
    setIsActivatingBoost(true);
    try {
      const updated = await api.activateBoost(crypto.randomUUID());
      setBoostStatus(updated);
      showToast(`Буст включён на ${updated.durationMinutes} минут`, 'success');
    } catch (e) {
      if (e instanceof ApiError && e.code === 'INSUFFICIENT_BALANCE') {
        setPurchaseKind('boosts');
        return;
      }
      showToast(
        e instanceof ApiError && e.code === 'PROFILE_NOT_PUBLISHED'
          ? 'Сначала опубликуйте анкету'
          : getErrorMessage(e, 'Не удалось включить буст'),
        'error'
      );
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
    <div className="space-y-3 sm:space-y-5 max-w-5xl mx-auto pb-6 sm:pb-12">
      {/* Intro and Filters */}
      <div className="space-y-3">
        <div className="flex items-center justify-between gap-3">
          <div>
            <h1 className="text-xl font-bold text-zinc-900">Поиск анкет</h1>
            <p className="hidden sm:block text-xs text-zinc-500">
              Знакомьтесь с людьми, выбирая подходящий формат и ритм общения
            </p>
          </div>

          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => void handleUndo()}
              title="Вернуть последнюю анкету"
              aria-label="Вернуть последнюю анкету"
              className="w-8 h-8 rounded-xl border border-zinc-200 text-zinc-600 hover:bg-zinc-50 flex items-center justify-center"
            >
              <Undo2 className="w-4 h-4" />
            </button>
            <button
              type="button"
              onClick={() => void handleRefreshPasses()}
              title="Показать пропущенных снова"
              aria-label="Показать пропущенных снова"
              className="w-8 h-8 rounded-xl border border-zinc-200 text-zinc-600 hover:bg-zinc-50 flex items-center justify-center"
            >
              <History className="w-4 h-4" />
            </button>

            <BoostButton
              status={boostStatus}
              isActivating={isActivatingBoost}
              onActivate={() => void handleActivateBoost()}
              onBuy={() => setPurchaseKind('boosts')}
              onExpired={loadSocialCounters}
            />

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

          <div className="text-xs text-zinc-400 font-mono">
            Найдено: <strong className="text-zinc-800">{totalElements}</strong>
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
          <div className="w-full max-w-[420px] h-[clamp(380px,calc(100dvh-280px),620px)] bg-white rounded-3xl border border-zinc-200 p-6 space-y-4 animate-pulse flex flex-col justify-end shadow-sm">
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
            <Button variant="outline" size="sm" onClick={() => void fetchPage(false)}>
              Повторить
            </Button>
          </div>
        </div>
      ) : profiles.length === 0 && reactedRef.current.size === 0 && !isLoadingMore ? (
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
            </Button>{' '}
            <Button variant="outline" size="sm" onClick={() => void handleRefreshPasses()}>
              <History className="w-3.5 h-3.5" /> Показать пропущенных снова
            </Button>
          </div>
        </div>
      ) : (
        <SwipeDeck
          profiles={profiles}
          onLike={handleLike}
          onSuperlike={handleSuperlike}
          onPass={handlePass}
          onOpenDetails={openDetails}
          onReset={() => void fetchPage(false)}
          onOpenFilters={() => setIsFiltersOpen(true)}
          isLoadingMore={isLoadingMore || (pendingReactions > 0 && !exhausted)}
          loadMoreError={loadMoreError}
          onRetryLoadMore={() => void fetchPage(true)}
          keyboardDisabled={
            !!activeProfileModal || isFiltersOpen || !!matchedProfile || isReceivedLikesOpen || !!purchaseKind
          }
          likesBlocked={!!likesRestriction}
          onLikeBlocked={showLikesBlocked}
          onRefreshPasses={() => void handleRefreshPasses()}
        />
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
          onSavedListsChanged={() => void fetchPage(false)}
          onPremiumRequired={handlePremiumRequired}
          onBuyPremium={() => setPurchaseKind('premium')}
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
        onBlocked={removeCard}
        onHidden={removeCard}
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

      {/* Last: stacks over whichever window opened it; closing returns there. */}
      <PurchaseModal kind={purchaseKind} onClose={() => setPurchaseKind(null)} onBought={() => setWalletNonce((n) => n + 1)} />
    </div>
  );
};
