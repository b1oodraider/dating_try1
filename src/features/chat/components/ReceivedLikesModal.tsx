import React, { useEffect, useState, useCallback } from 'react';
import { Modal } from '../../../shared/components/Modal';
import { Button } from '../../../shared/components/Button';
import { api, ApiError, getErrorMessage } from '../../../core/api/client';
import type { ReceivedLike, PublicProfile } from '../../../core/types';
import { Heart, Star, Crown, Frown, Sparkles, Check, X } from 'lucide-react';
import { useToast } from '../../../shared/components/Toast';

/** Contract maximum for one page. */
const PAGE_SIZE = 50;

interface ReceivedLikesModalProps {
  isOpen: boolean;
  onClose: () => void;
  onMatched: (profile: PublicProfile, matchId?: string) => void;
}

export const ReceivedLikesModal: React.FC<ReceivedLikesModalProps> = ({
  isOpen,
  onClose,
  onMatched,
}) => {
  const { showToast } = useToast();
  const [likes, setLikes] = useState<ReceivedLike[]>([]);
  const [total, setTotal] = useState(0);
  const [isLoading, setIsLoading] = useState(true);
  const [isLoadingMore, setIsLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [isPremiumRequired, setIsPremiumRequired] = useState(false);
  const [processingId, setProcessingId] = useState<string | null>(null);

  const loadLikes = useCallback(async () => {
    setIsLoading(true);
    setError(null);
    setIsPremiumRequired(false);
    try {
      const data = await api.getReceivedLikes(0, PAGE_SIZE);
      setLikes(data.items);
      setTotal(data.totalElements);
    } catch (err: unknown) {
      if (err instanceof ApiError && err.isPremiumRequired) {
        setIsPremiumRequired(true);
      } else {
        setError(getErrorMessage(err));
      }
    } finally {
      setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    if (isOpen) {
      void loadLikes();
    }
  }, [isOpen, loadLikes]);

  /**
   * Answered likes leave the server's list too, so the likes still shown are exactly its first entries: the page
   * holding entry likes.length overlaps them a little (deduplicated) and skips nothing.
   */
  const loadMore = async () => {
    setIsLoadingMore(true);
    try {
      const data = await api.getReceivedLikes(Math.floor(likes.length / PAGE_SIZE), PAGE_SIZE);
      setLikes((prev) => {
        const known = new Set(prev.map((l) => l.profile.id));
        return [...prev, ...data.items.filter((l) => !known.has(l.profile.id))];
      });
      setTotal(data.totalElements);
    } catch (err) {
      showToast(getErrorMessage(err), 'error');
    } finally {
      setIsLoadingMore(false);
    }
  };

  const handleLikeBack = async (profile: PublicProfile) => {
    setProcessingId(profile.id);
    try {
      const result = await api.likeProfile(profile.id);
      setLikes((prev) => prev.filter((item) => item.profile.id !== profile.id));
      setTotal((t) => Math.max(0, t - 1));
      if (result.matched) {
        onMatched(profile, result.matchId);
      }
    } catch (err) {
      showToast(getErrorMessage(err), 'error');
    } finally {
      setProcessingId(null);
    }
  };

  const handlePass = async (profileId: string) => {
    setProcessingId(profileId);
    try {
      await api.passProfile(profileId);
      setLikes((prev) => prev.filter((item) => item.profile.id !== profileId));
      setTotal((t) => Math.max(0, t - 1));
    } catch (err) {
      showToast(getErrorMessage(err), 'error');
    } finally {
      setProcessingId(null);
    }
  };

  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      title="Кому вы понравились"
      description={total > 0 ? `${total} ${total === 1 ? 'симпатия' : 'симпатий'}` : undefined}
      maxWidth="lg"
    >
      <div className="space-y-4">
        {isLoading ? (
          <div className="py-12 flex flex-col items-center justify-center gap-2">
            <div className="w-8 h-8 border-2 border-zinc-900 border-t-transparent rounded-full animate-spin" />
            <p className="text-xs text-zinc-500">Загружаем список симпатий...</p>
          </div>
        ) : isPremiumRequired ? (
          <div className="py-8 text-center space-y-4 bg-amber-50/50 rounded-2xl border border-amber-200/80 p-6">
            <div className="w-12 h-12 rounded-2xl bg-amber-100 text-amber-700 flex items-center justify-center mx-auto">
              <Crown className="w-6 h-6 text-amber-600" />
            </div>
            <div className="space-y-1">
              <h3 className="text-sm font-bold text-zinc-900">Просмотр доступен с Premium</h3>
              <p className="text-xs text-zinc-600 max-w-sm mx-auto leading-relaxed">
                С подпиской Премиум вы сразу видите, кто проявил к вам симпатию, и можете отвечать взаимностью без ожидания в ленте.
              </p>
            </div>
            <Button variant="outline" size="sm" onClick={onClose}>
              Понятно
            </Button>
          </div>
        ) : error ? (
          <div className="p-4 bg-rose-50 border border-rose-200 text-rose-700 text-xs rounded-xl">
            {error}
          </div>
        ) : likes.length === 0 ? (
          <div className="py-12 text-center space-y-2">
            <div className="w-12 h-12 bg-zinc-100 rounded-full flex items-center justify-center mx-auto text-zinc-400">
              <Frown className="w-6 h-6" />
            </div>
            <h3 className="text-sm font-semibold text-zinc-900">Пока новых лайков нет</h3>
            <p className="text-xs text-zinc-500 max-w-xs mx-auto">
              Продолжайте активность в ленте или обновите статус на сегодня, чтобы привлечь больше внимания!
            </p>
          </div>
        ) : (
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 max-h-[60vh] overflow-y-auto pr-1">
            {likes.map(({ profile, superlike }) => {
              const photo = profile.photos.find((p) => p.isPrimary)?.url || profile.photos[0]?.url;
              return (
                <div
                  key={profile.id}
                  className="bg-white border border-zinc-200/80 rounded-2xl p-3 flex gap-3 shadow-2xs hover:shadow-xs transition-shadow"
                >
                  <div className="w-20 h-24 rounded-xl bg-zinc-100 overflow-hidden shrink-0 relative">
                    {photo ? (
                      <img src={photo} alt="" className="w-full h-full object-cover" />
                    ) : (
                      <div className="w-full h-full flex items-center justify-center text-zinc-400 font-bold">
                        {profile.displayName[0]}
                      </div>
                    )}
                    {superlike && (
                      <div className="absolute top-1 left-1 bg-sky-500 text-white p-1 rounded-md shadow-xs">
                        <Star className="w-3 h-3 fill-white" />
                      </div>
                    )}
                  </div>

                  <div className="flex-1 flex flex-col justify-between min-w-0">
                    <div>
                      <div className="flex items-baseline justify-between gap-1">
                        <h4 className="text-xs font-bold text-zinc-900 truncate">
                          {profile.displayName}, {profile.age}
                        </h4>
                        <span className="text-[10px] text-zinc-400 shrink-0">{profile.city}</span>
                      </div>

                      {profile.compatibility !== undefined && profile.compatibility !== null && (
                        <span className="inline-block mt-0.5 text-[10px] font-semibold text-emerald-700 bg-emerald-50 px-1.5 py-0.2 rounded">
                          {profile.compatibility}% совпадение
                        </span>
                      )}

                      {profile.aboutText && (
                        <p className="text-[11px] text-zinc-500 line-clamp-1 mt-1">
                          {profile.aboutText}
                        </p>
                      )}
                    </div>

                    <div className="flex items-center gap-1.5 pt-2">
                      <Button
                        type="button"
                        variant="primary"
                        size="sm"
                        className="flex-1 py-1 text-xs"
                        disabled={processingId === profile.id}
                        onClick={() => void handleLikeBack(profile)}
                      >
                        <Heart className="w-3.5 h-3.5 mr-1 fill-white" /> Ответить
                      </Button>
                      <button
                        type="button"
                        className="p-1.5 text-zinc-400 hover:text-zinc-700 hover:bg-zinc-100 rounded-lg transition-colors"
                        disabled={processingId === profile.id}
                        onClick={() => void handlePass(profile.id)}
                        title="Пропустить"
                      >
                        <X className="w-4 h-4" />
                      </button>
                    </div>
                  </div>
                </div>
              );
            })}
            {likes.length < total && (
              <div className="sm:col-span-2 text-center pt-1">
                <Button type="button" variant="outline" size="sm" onClick={() => void loadMore()} isLoading={isLoadingMore}>
                  Показать ещё
                </Button>
              </div>
            )}
          </div>
        )}
      </div>
    </Modal>
  );
};
