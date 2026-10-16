import React, { useState, useRef, useEffect, useCallback } from 'react';
import type { PublicProfile } from '../../../core/types';
import { TagChip } from '../../tags/components/TagChip';
import { GoalPills } from '../../goals/components/GoalChips';
import { PremiumBadge } from '../../premium/components/PremiumBadge';
import { getPhotoFrame, PhotoFrameOverlay, ProfileStatusPill } from '../../cosmetics/decorations';
import {
  Heart,
  X,
  Star,
  Info,
  MapPin,
  Ruler,
  Sparkles,
  Rocket,
  ShieldCheck,
  RotateCcw,
  SlidersHorizontal,
  ChevronLeft,
  ChevronRight,
  MessageCircleHeart,
  AlertCircle,
} from 'lucide-react';

/**
 * Tinder-style deck: the top card is dragged right (like), left (pass) or up (super like), or the buttons / arrow keys
 * are used. The reaction handlers take the card off the deck at once (the request runs in the background), so the
 * next card is ready under the finger.
 */
interface SwipeDeckProps {
  profiles: PublicProfile[];
  onLike: (profile: PublicProfile) => void;
  onSuperlike: (profile: PublicProfile) => void;
  onPass: (profile: PublicProfile) => void;
  onOpenDetails: (profile: PublicProfile) => void;
  onReset?: () => void;
  onOpenFilters?: () => void;
  /** More cards are being fetched (shown instead of "all viewed" when the deck ran dry). */
  isLoadingMore?: boolean;
  loadMoreError?: string | null;
  onRetryLoadMore?: () => void;
  /** A dialog is open over the deck: arrow keys and space must not swipe behind it. */
  keyboardDisabled?: boolean;
}

type FlyOutDirection = 'left' | 'right' | 'up';

export const SwipeDeck: React.FC<SwipeDeckProps> = ({
  profiles,
  onLike,
  onSuperlike,
  onPass,
  onOpenDetails,
  onReset,
  onOpenFilters,
  isLoadingMore = false,
  loadMoreError = null,
  onRetryLoadMore,
  keyboardDisabled = false,
}) => {
  const currentProfile = profiles[0] ?? null;
  const nextProfile = profiles[1] ?? null;

  // Photo gallery index for the active card
  const [photoIndex, setPhotoIndex] = useState(0);

  // Drag interaction state
  const [dragOffset, setDragOffset] = useState<{ x: number; y: number }>({ x: 0, y: 0 });
  const [isDragging, setIsDragging] = useState(false);
  const dragStartRef = useRef<{ x: number; y: number; time: number } | null>(null);

  // Flying out animation state
  const [flyOut, setFlyOut] = useState<{ id: string; direction: FlyOutDirection } | null>(null);
  const flyOutTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  useEffect(() => () => clearTimeout(flyOutTimer.current), []);

  // Reset photo index when top profile changes
  useEffect(() => {
    setPhotoIndex(0);
    setDragOffset({ x: 0, y: 0 });
    setIsDragging(false);
  }, [currentProfile?.id]);

  // Execute fly-out and trigger callback
  const triggerSwipe = useCallback(
    (direction: FlyOutDirection) => {
      if (!currentProfile || flyOut) return;

      setFlyOut({ id: currentProfile.id, direction });

      flyOutTimer.current = setTimeout(() => {
        if (direction === 'right') {
          onLike(currentProfile);
        } else if (direction === 'left') {
          onPass(currentProfile);
        } else {
          onSuperlike(currentProfile);
        }
        setFlyOut(null);
        setDragOffset({ x: 0, y: 0 });
      }, 240);
    },
    [currentProfile, flyOut, onLike, onPass, onSuperlike]
  );

  // Keyboard navigation shortcuts
  useEffect(() => {
    if (keyboardDisabled) return;
    const handleKeyDown = (e: KeyboardEvent) => {
      // Don't trigger if user is typing in an input
      if (['INPUT', 'TEXTAREA'].includes((e.target as HTMLElement)?.tagName)) return;

      if (e.key === 'ArrowRight') {
        e.preventDefault();
        triggerSwipe('right');
      } else if (e.key === 'ArrowLeft') {
        e.preventDefault();
        triggerSwipe('left');
      } else if (e.key === 'ArrowUp') {
        e.preventDefault();
        triggerSwipe('up');
      } else if (e.key === ' ' && currentProfile) {
        e.preventDefault();
        onOpenDetails(currentProfile);
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [triggerSwipe, currentProfile, onOpenDetails, keyboardDisabled]);

  // Drag handlers (Pointer events support both touch and mouse)
  const handlePointerDown = (e: React.PointerEvent) => {
    if (flyOut || !currentProfile) return;
    dragStartRef.current = { x: e.clientX, y: e.clientY, time: Date.now() };
    setIsDragging(true);
    (e.target as HTMLElement).setPointerCapture?.(e.pointerId);
  };

  const handlePointerMove = (e: React.PointerEvent) => {
    if (!isDragging || !dragStartRef.current) return;
    const dx = e.clientX - dragStartRef.current.x;
    const dy = e.clientY - dragStartRef.current.y;
    setDragOffset({ x: dx, y: dy });
  };

  const handlePointerUp = (e: React.PointerEvent) => {
    if (!isDragging || !dragStartRef.current) return;
    const dx = dragOffset.x;
    const dy = dragOffset.y;
    const dt = Date.now() - dragStartRef.current.time;
    setIsDragging(false);
    dragStartRef.current = null;

    // Fast flick or distance past threshold
    const velocityX = Math.abs(dx) / Math.max(dt, 1);
    const isHorizontalSwipe = Math.abs(dx) > 100 || (Math.abs(dx) > 40 && velocityX > 0.4);
    const isVerticalSwipe = dy < -90;

    if (isVerticalSwipe && Math.abs(dx) < 80) {
      triggerSwipe('up');
    } else if (isHorizontalSwipe) {
      triggerSwipe(dx > 0 ? 'right' : 'left');
    } else {
      // Snap back to center
      setDragOffset({ x: 0, y: 0 });
    }
  };

  // Next/prev photo for top card
  const photos = currentProfile?.photos ?? [];
  const handlePrevPhoto = (e: React.MouseEvent) => {
    e.stopPropagation();
    setPhotoIndex((prev) => (prev > 0 ? prev - 1 : photos.length - 1));
  };
  const handleNextPhoto = (e: React.MouseEvent) => {
    e.stopPropagation();
    setPhotoIndex((prev) => (prev < photos.length - 1 ? prev + 1 : 0));
  };

  if (!currentProfile && (isLoadingMore || loadMoreError)) {
    return (
      <div className="max-w-md mx-auto py-16 px-4 text-center space-y-4 bg-white rounded-3xl border border-zinc-200 shadow-sm">
        {loadMoreError ? (
          <>
            <AlertCircle className="w-8 h-8 text-rose-500 mx-auto" />
            <p className="text-xs text-zinc-600">{loadMoreError}</p>
            <button
              type="button"
              onClick={onRetryLoadMore}
              className="px-4 py-2.5 rounded-xl bg-zinc-900 text-white font-semibold text-xs hover:bg-zinc-800 transition-colors"
            >
              Повторить
            </button>
          </>
        ) : (
          <>
            <div className="w-8 h-8 border-2 border-rose-500 border-t-transparent rounded-full animate-spin mx-auto" />
            <p className="text-xs text-zinc-500">Подбираем новые анкеты…</p>
          </>
        )}
      </div>
    );
  }

  if (!currentProfile) {
    return (
      <div className="max-w-md mx-auto py-16 px-4 text-center space-y-5 bg-white rounded-3xl border border-zinc-200 shadow-sm">
        <div className="w-16 h-16 bg-rose-50 text-rose-500 rounded-3xl flex items-center justify-center mx-auto shadow-inner">
          <MessageCircleHeart className="w-8 h-8" />
        </div>
        <div className="space-y-1.5">
          <h3 className="text-lg font-bold text-zinc-900">Вы просмотрели все анкеты!</h3>
          <p className="text-xs text-zinc-500 max-w-xs mx-auto leading-relaxed">
            В текущей подборке больше нет новых профилей. Измените параметры поиска или обновите ленту, чтобы посмотреть анкеты снова.
          </p>
        </div>
        <div className="flex flex-col sm:flex-row items-center justify-center gap-2 pt-2">
          {onReset && (
            <button
              type="button"
              onClick={onReset}
              className="w-full sm:w-auto px-4 py-2.5 rounded-xl bg-zinc-900 text-white font-semibold text-xs flex items-center justify-center gap-1.5 hover:bg-zinc-800 transition-colors shadow-sm"
            >
              <RotateCcw className="w-3.5 h-3.5" /> Обновить колоду
            </button>
          )}
          {onOpenFilters && (
            <button
              type="button"
              onClick={onOpenFilters}
              className="w-full sm:w-auto px-4 py-2.5 rounded-xl border border-zinc-300 text-zinc-700 font-semibold text-xs flex items-center justify-center gap-1.5 hover:bg-zinc-50 transition-colors"
            >
              <SlidersHorizontal className="w-3.5 h-3.5" /> Настроить фильтры
            </button>
          )}
        </div>
      </div>
    );
  }

  // Active photo URL
  const currentPhoto = photos[photoIndex] ?? photos[0];
  const nextPhoto = nextProfile?.photos?.[0];
  const frame = getPhotoFrame(currentProfile.decorations);

  // Drag stamp opacities
  const likeOpacity = Math.max(0, Math.min(1, (dragOffset.x - 30) / 70));
  const passOpacity = Math.max(0, Math.min(1, (-dragOffset.x - 30) / 70));
  const superlikeOpacity = Math.max(0, Math.min(1, (-dragOffset.y - 40) / 70)) * (Math.abs(dragOffset.x) < 60 ? 1 : 0);

  // Rotation and transform calculation
  let transformStyle = '';
  let transitionStyle = 'transform 0.05s ease-out';

  if (flyOut) {
    transitionStyle = 'transform 0.25s cubic-bezier(0.2, 0.9, 0.3, 1), opacity 0.25s ease-in';
    if (flyOut.direction === 'right') {
      transformStyle = 'translate3d(140%, 15px, 0) rotate(22deg)';
    } else if (flyOut.direction === 'left') {
      transformStyle = 'translate3d(-140%, 15px, 0) rotate(-22deg)';
    } else {
      transformStyle = 'translate3d(0, -140%, 0) scale(0.95)';
    }
  } else if (isDragging) {
    transitionStyle = 'none';
    const rot = dragOffset.x * 0.08;
    transformStyle = `translate3d(${dragOffset.x}px, ${dragOffset.y}px, 0) rotate(${rot}deg)`;
  } else {
    transitionStyle = 'transform 0.2s cubic-bezier(0.175, 0.885, 0.32, 1.275)';
    transformStyle = 'translate3d(0, 0, 0) rotate(0deg)';
  }

  return (
    <div className="flex flex-col items-center select-none py-2">
      {/* Cards Deck Container */}
      <div className="relative w-full max-w-[420px] h-[clamp(380px,calc(100dvh-280px),620px)] flex items-center justify-center">
        {/* Background Card (the next profile in the deck) */}
        {nextProfile && (
          <div
            className="absolute inset-0 rounded-3xl bg-white border border-zinc-200 overflow-hidden shadow-sm pointer-events-none transition-all duration-300"
            style={{
              transform: isDragging || flyOut ? 'scale(0.98) translateY(6px)' : 'scale(0.95) translateY(12px)',
              opacity: 0.9,
              zIndex: 1,
            }}
          >
            <div className="relative w-full h-full bg-zinc-100">
              {nextPhoto ? (
                <img
                  src={nextPhoto.url}
                  alt={nextProfile.displayName}
                  className="w-full h-full object-cover brightness-95"
                />
              ) : (
                <div className="w-full h-full flex items-center justify-center text-zinc-300">
                  <MessageCircleHeart className="w-16 h-16" />
                </div>
              )}
              {/* Bottom gradient info peek */}
              <div className="absolute inset-x-0 bottom-0 h-40 bg-gradient-to-t from-black/80 via-black/40 to-transparent p-5 flex flex-col justify-end text-white">
                <h3 className="text-xl font-bold">
                  {nextProfile.displayName}, {nextProfile.age}
                </h3>
                <span className="text-xs text-white/80">{nextProfile.city}</span>
              </div>
            </div>
          </div>
        )}

        {/* Foreground / Active Card */}
        <div
          onPointerDown={handlePointerDown}
          onPointerMove={handlePointerMove}
          onPointerUp={handlePointerUp}
          onPointerCancel={handlePointerUp}
          className="absolute inset-0 rounded-3xl bg-white border border-zinc-200/90 overflow-hidden shadow-xl cursor-grab active:cursor-grabbing touch-none z-10 flex flex-col justify-between"
          style={{
            transform: transformStyle,
            transition: transitionStyle,
            opacity: flyOut ? 0 : 1,
          }}
        >
          {/* Top Stamp Overlays (LIKE / NOPE / SUPERLIKE) */}
          {likeOpacity > 0 && (
            <div
              className="absolute top-8 left-6 z-30 pointer-events-none transform -rotate-15 border-4 border-emerald-500 text-emerald-500 font-extrabold text-2xl tracking-wider px-4 py-1 rounded-xl bg-white/20 backdrop-blur-xs uppercase shadow-lg"
              style={{ opacity: likeOpacity }}
            >
              ЛАЙК
            </div>
          )}

          {passOpacity > 0 && (
            <div
              className="absolute top-8 right-6 z-30 pointer-events-none transform rotate-15 border-4 border-rose-500 text-rose-500 font-extrabold text-2xl tracking-wider px-4 py-1 rounded-xl bg-white/20 backdrop-blur-xs uppercase shadow-lg"
              style={{ opacity: passOpacity }}
            >
              ПРОПУСК
            </div>
          )}

          {superlikeOpacity > 0 && (
            <div
              className="absolute top-12 inset-x-0 mx-auto w-max z-30 pointer-events-none border-4 border-sky-400 text-sky-400 font-extrabold text-2xl tracking-wider px-4 py-1 rounded-xl bg-white/20 backdrop-blur-xs uppercase shadow-lg"
              style={{ opacity: superlikeOpacity }}
            >
              СУПЕРЛАЙК
            </div>
          )}

          {/* Card Media and Upper Area */}
          <div className="relative w-full h-full bg-zinc-950 overflow-hidden">
            {currentPhoto ? (
              <img
                src={currentPhoto.url}
                alt={currentProfile.displayName}
                className="w-full h-full object-cover pointer-events-none"
                draggable={false}
              />
            ) : (
              <div className="w-full h-full flex items-center justify-center text-zinc-600 bg-zinc-900">
                <MessageCircleHeart className="w-16 h-16" />
              </div>
            )}

            {/* Cosmetic photo frame */}
            <PhotoFrameOverlay frame={frame} />

            {/* Photo segments bar (Tinder style progress bars) */}
            {photos.length > 1 && (
              <div className="absolute top-3 inset-x-3 flex gap-1.5 z-20 pointer-events-none">
                {photos.map((_, idx) => (
                  <div
                    key={idx}
                    className={`h-1 flex-1 rounded-full transition-all duration-200 ${
                      idx === photoIndex ? 'bg-white shadow-xs' : 'bg-white/40'
                    }`}
                  />
                ))}
              </div>
            )}

            {/* Click zones for switching photos */}
            {photos.length > 1 && (
              <>
                <button
                  type="button"
                  aria-label="Предыдущее фото"
                  onClick={handlePrevPhoto}
                  className="absolute left-0 top-0 bottom-36 w-1/3 z-15 focus:outline-none flex items-center justify-start pl-2 opacity-0 hover:opacity-100 transition-opacity"
                >
                  <span className="w-7 h-7 rounded-full bg-black/40 text-white flex items-center justify-center backdrop-blur-xs">
                    <ChevronLeft className="w-4 h-4" />
                  </span>
                </button>
                <button
                  type="button"
                  aria-label="Следующее фото"
                  onClick={handleNextPhoto}
                  className="absolute right-0 top-0 bottom-36 w-1/3 z-15 focus:outline-none flex items-center justify-end pr-2 opacity-0 hover:opacity-100 transition-opacity"
                >
                  <span className="w-7 h-7 rounded-full bg-black/40 text-white flex items-center justify-center backdrop-blur-xs">
                    <ChevronRight className="w-4 h-4" />
                  </span>
                </button>
              </>
            )}

            {/* Badges on top: Boost, Verified, Compatibility */}
            <div className="absolute top-6 left-3 flex flex-col gap-1.5 z-20 pointer-events-none">
              {currentProfile.boosted && (
                <span className="inline-flex items-center gap-1 bg-amber-500 text-white text-[11px] font-bold px-2.5 py-0.5 rounded-lg shadow-md">
                  <Rocket className="w-3.5 h-3.5 fill-white" /> Буст
                </span>
              )}
              {currentProfile.verified && (
                <span className="inline-flex items-center gap-1 bg-emerald-600/95 backdrop-blur-sm text-white text-[11px] font-semibold px-2.5 py-0.5 rounded-lg shadow-md">
                  <ShieldCheck className="w-3.5 h-3.5" /> Проверен
                </span>
              )}
            </div>

            {/* Compatibility pill (top right) */}
            {currentProfile.compatibility !== undefined && currentProfile.compatibility !== null && (
              <div className="absolute top-6 right-3 bg-zinc-900/85 backdrop-blur-md text-white text-xs font-bold px-2.5 py-1 rounded-xl shadow-md z-20 pointer-events-none">
                {currentProfile.compatibility}% совпадение
              </div>
            )}

            {/* 24-hour Status Pill Overlay */}
            {currentProfile.dailyStatusText && (
              <div className="absolute bottom-[170px] left-4 right-4 z-20 pointer-events-none">
                <div className="inline-flex items-center gap-1.5 bg-zinc-900/85 backdrop-blur-md text-amber-300 border border-amber-400/30 text-xs px-3 py-1.5 rounded-xl shadow-md max-w-full">
                  <Sparkles className="w-3.5 h-3.5 text-amber-400 shrink-0" />
                  <span className="truncate italic">«{currentProfile.dailyStatusText}»</span>
                </div>
              </div>
            )}

            {/* Gradient Scrim & Profile Summary */}
            <div className="absolute inset-x-0 bottom-0 h-52 bg-gradient-to-t from-black via-black/70 to-transparent p-5 flex flex-col justify-end text-white z-20">
              <div className="flex items-end justify-between gap-2">
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-1.5">
                    <h2 className="text-2xl font-bold tracking-tight text-white drop-shadow-sm truncate">
                      {currentProfile.displayName}, <span className="font-normal">{currentProfile.age}</span>
                    </h2>
                    {currentProfile.premium && <PremiumBadge />}
                    <ProfileStatusPill decorations={currentProfile.decorations} />
                  </div>

                  <div className="flex items-center gap-3 text-xs text-zinc-300 mt-1">
                    <span className="flex items-center gap-1">
                      <MapPin className="w-3.5 h-3.5 text-zinc-400" />
                      {currentProfile.city}
                    </span>
                    {currentProfile.heightCm != null && (
                      <span className="flex items-center gap-1">
                        <Ruler className="w-3.5 h-3.5 text-zinc-400" />
                        {currentProfile.heightCm} см
                      </span>
                    )}
                  </div>
                </div>

                {/* Info button */}
                <button
                  type="button"
                  onClick={(e) => {
                    e.stopPropagation();
                    onOpenDetails(currentProfile);
                  }}
                  className="w-9 h-9 rounded-full bg-white/20 hover:bg-white/30 backdrop-blur-md text-white flex items-center justify-center shrink-0 transition-colors shadow-sm"
                  title="Подробная информация об анкете"
                >
                  <Info className="w-4 h-4" />
                </button>
              </div>

              {/* Goals */}
              {currentProfile.goals && currentProfile.goals.length > 0 && (
                <div className="mt-2.5 flex flex-wrap gap-1.5">
                  <GoalPills goals={currentProfile.goals} max={2} />
                </div>
              )}

              {/* Communication Tags preview */}
              {currentProfile.tags && currentProfile.tags.length > 0 && (
                <div className="mt-2 flex flex-wrap gap-1">
                  {currentProfile.tags.slice(0, 3).map((tag) => (
                    <TagChip key={tag.code} tag={tag} size="sm" />
                  ))}
                  {currentProfile.tags.length > 3 && (
                    <span className="text-[10px] text-zinc-300 self-center pl-1 font-medium">
                      +{currentProfile.tags.length - 3}
                    </span>
                  )}
                </div>
              )}
            </div>
          </div>
        </div>
      </div>

      {/* Satisfying Action Buttons Bar (Tinder / Twinby style) */}
      <div className="flex items-center justify-center gap-4 sm:gap-6 mt-5 z-20">
        {/* Pass / Dislike */}
        <button
          type="button"
          disabled={!!flyOut}
          onClick={() => triggerSwipe('left')}
          title="Отклонить (стрелка влево)"
          className="w-14 h-14 rounded-full bg-white border-2 border-zinc-200 text-rose-500 hover:border-rose-400 hover:bg-rose-50 hover:scale-108 active:scale-95 flex items-center justify-center transition-all duration-150 shadow-md disabled:opacity-50 group cursor-pointer"
        >
          <X className="w-6 h-6 stroke-[2.5] group-hover:rotate-12 transition-transform" />
        </button>

        {/* Superlike */}
        <button
          type="button"
          disabled={!!flyOut}
          onClick={() => triggerSwipe('up')}
          title="Суперлайк (стрелка вверх)"
          className="w-12 h-12 rounded-full bg-white border-2 border-sky-200 text-sky-500 hover:border-sky-400 hover:bg-sky-50 hover:scale-108 active:scale-95 flex items-center justify-center transition-all duration-150 shadow-md disabled:opacity-50 group cursor-pointer"
        >
          <Star className="w-5 h-5 fill-sky-500 group-hover:scale-115 transition-transform" />
        </button>

        {/* Like */}
        <button
          type="button"
          disabled={!!flyOut}
          onClick={() => triggerSwipe('right')}
          title="Лайкнуть (стрелка вправо)"
          className="w-14 h-14 rounded-full bg-rose-500 hover:bg-rose-600 text-white hover:scale-108 active:scale-95 flex items-center justify-center transition-all duration-150 shadow-lg shadow-rose-500/25 disabled:opacity-50 group cursor-pointer"
        >
          <Heart className="w-6 h-6 fill-white stroke-[2.5] group-hover:scale-115 transition-transform" />
        </button>
      </div>

      {/* Keyboard Shortcuts Helper Pill */}
      <div className="hidden sm:flex items-center gap-3 text-[11px] text-zinc-400 mt-4 bg-zinc-50 border border-zinc-200/60 px-3.5 py-1.5 rounded-full font-mono">
        <span>← Пропустить</span>
        <span className="text-zinc-300">•</span>
        <span>↑ Суперлайк</span>
        <span className="text-zinc-300">•</span>
        <span>→ Лайк</span>
        <span className="text-zinc-300">•</span>
        <span>Пробел: Детали</span>
      </div>
    </div>
  );
};
