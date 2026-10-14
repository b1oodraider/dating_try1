import React, { useState, useEffect, useCallback, useRef } from 'react';
import type { PublicProfile, TagLogic } from '../../../core/types';
import { api, getErrorMessage, isAbortError } from '../../../core/api/client';
import { useAuth } from '../../../core/context/AuthContext';
import { DiscoveryFilters } from './DiscoveryFilters';
import { ExclusionWordsEditor } from './ExclusionWordsEditor';
import { ProfileFeedCard } from './ProfileFeedCard';
import { ProfileDetailModal } from './ProfileDetailModal';
import { Frown, LogIn, AlertCircle, Compass } from 'lucide-react';
import { Button } from '../../../shared/components/Button';

const PAGE_SIZE = 12;

interface DiscoveryFeedProps {
  onRequestAuth: () => void;
}

export const DiscoveryFeed: React.FC<DiscoveryFeedProps> = ({ onRequestAuth }) => {
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

  const [selectedTagCodes, setSelectedTagCodes] = useState<string[]>([]);
  const [tagFilterLogic, setTagFilterLogic] = useState<TagLogic>('OR');
  const [selectedCity, setSelectedCity] = useState<string>('ALL');
  const [activeProfileModal, setActiveProfileModal] = useState<PublicProfile | null>(null);
  /** Saved exclusion words count: only used to explain an empty feed. */
  const [exclusionWordCount, setExclusionWordCount] = useState(0);

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
            city: selectedCity === 'ALL' ? undefined : selectedCity,
            tagCodes: selectedTagCodes,
            tagLogic: tagFilterLogic,
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
    [selectedCity, selectedTagCodes, tagFilterLogic]
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
  }, [accountId, fetchPage]);

  const handleResetFilters = () => {
    setSelectedTagCodes([]);
    setSelectedCity('ALL');
    setTagFilterLogic('OR');
  };

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

        <DiscoveryFilters
          selectedTagCodes={selectedTagCodes}
          tagFilterLogic={tagFilterLogic}
          selectedCity={selectedCity}
          onFilterChange={(f) => {
            setSelectedTagCodes(f.selectedTagCodes);
            setTagFilterLogic(f.tagFilterLogic);
            setSelectedCity(f.selectedCity);
          }}
          onReset={handleResetFilters}
        />

        <ExclusionWordsEditor
          key={accountId}
          onLoaded={(words) => setExclusionWordCount(words.length)}
          onSaved={(words) => {
            setExclusionWordCount(words.length);
            // The server applies the words before pagination: start the feed over from page 0.
            void fetchPage(0, false);
          }}
        />
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
            Попробуйте переключить логику фильтра на «Любая (ИЛИ)» или сбросить фильтры по карточкам и городу.
            {exclusionWordCount > 0 && ' Часть анкет также скрыта вашими словами-исключениями.'}
          </p>
          <div className="pt-2">
            <Button variant="outline" size="sm" onClick={handleResetFilters}>
              Сбросить все фильтры
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

      {/* Profile Detail Modal */}
      <ProfileDetailModal
        profile={activeProfileModal}
        isOpen={!!activeProfileModal}
        onClose={() => setActiveProfileModal(null)}
      />
    </div>
  );
};
