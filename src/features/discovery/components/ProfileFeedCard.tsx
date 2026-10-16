import React from 'react';
import type { PublicProfile } from '../../../core/types';
import { TagChip } from '../../tags/components/TagChip';
import { MapPin, Sparkles, MessageCircleHeart, Ruler, Heart, Star, X, ShieldCheck, Rocket } from 'lucide-react';
import { getPhotoFrame, PhotoFrameOverlay, ProfileStatusPill } from '../../cosmetics/decorations';
import { GoalPills } from '../../goals/components/GoalChips';
import { PremiumBadge } from '../../premium/components/PremiumBadge';

interface ProfileFeedCardProps {
  profile: PublicProfile;
  onOpenDetails: (profile: PublicProfile) => void;
  onLike?: (profile: PublicProfile) => void;
  onSuperlike?: (profile: PublicProfile) => void;
  onPass?: (profile: PublicProfile) => void;
  isProcessing?: boolean;
}

export const ProfileFeedCard: React.FC<ProfileFeedCardProps> = ({
  profile,
  onOpenDetails,
  onLike,
  onSuperlike,
  onPass,
  isProcessing = false,
}) => {
  const photos = profile.photos ?? [];
  const tags = profile.tags ?? [];
  const primaryPhoto = photos.find((p) => p.isPrimary) ?? photos[0];
  const dailyStatusText = profile.dailyStatusText?.trim() || null;
  const frame = getPhotoFrame(profile.decorations);

  return (
    <div className="group bg-white rounded-2xl border border-zinc-200/80 overflow-hidden shadow-xs hover:shadow-md transition-all duration-200 flex flex-col justify-between">
      <div onClick={() => onOpenDetails(profile)} className="cursor-pointer">
        {/* Photo Container */}
        <div className="relative aspect-4/5 w-full bg-zinc-100 overflow-hidden">
          {primaryPhoto ? (
            <img
              src={primaryPhoto.url}
              alt={profile.displayName}
              className="w-full h-full object-cover group-hover:scale-102 transition-transform duration-300"
              loading="lazy"
            />
          ) : (
            <div className="w-full h-full flex items-center justify-center text-zinc-300">
              <MessageCircleHeart className="w-12 h-12" />
            </div>
          )}

          {/* Badges on top of photo */}
          <div className="absolute top-2 left-2 flex flex-col gap-1 z-10">
            {profile.boosted && (
              <span className="inline-flex items-center gap-1 bg-amber-500 text-white text-[10px] font-bold px-2 py-0.5 rounded-lg shadow-sm">
                <Rocket className="w-3 h-3 fill-white" /> Буст
              </span>
            )}
            {profile.verified && (
              <span className="inline-flex items-center gap-1 bg-emerald-600/90 backdrop-blur-xs text-white text-[10px] font-semibold px-2 py-0.5 rounded-lg shadow-sm">
                <ShieldCheck className="w-3 h-3" /> Проверен
              </span>
            )}
          </div>

          {/* Compatibility badge */}
          {profile.compatibility !== undefined && profile.compatibility !== null && (
            <div className="absolute top-2 right-2 bg-zinc-900/80 backdrop-blur-md text-white text-[11px] font-bold px-2 py-0.5 rounded-lg shadow-sm">
              {profile.compatibility}% совпадение
            </div>
          )}

          {/* Photo count indicator */}
          {photos.length > 1 && (
            <div className="absolute bottom-2 right-2 px-2 py-0.5 rounded-full bg-zinc-900/60 backdrop-blur-xs text-white text-[10px] font-medium z-10">
              1/{photos.length}
            </div>
          )}

          <PhotoFrameOverlay frame={frame} />

          {/* 24-hour status badge overlay */}
          {dailyStatusText && (
            <div className="absolute bottom-2 left-2 right-12 bg-zinc-900/80 backdrop-blur-md text-white text-[11px] p-2 rounded-xl flex items-center gap-1.5 shadow-sm z-10">
              <Sparkles className="w-3.5 h-3.5 text-amber-400 shrink-0" />
              <span className="truncate italic">«{dailyStatusText}»</span>
            </div>
          )}
        </div>

        {/* Info */}
        <div className="p-4 space-y-3">
          <div className="flex items-baseline justify-between gap-2">
            <div className="flex flex-wrap items-center gap-x-1.5 gap-y-1 min-w-0">
              <h3 className="text-base font-bold text-zinc-900 truncate max-w-full">
                {profile.displayName}, <span className="font-normal text-zinc-600">{profile.age}</span>
              </h3>
              {profile.premium && <PremiumBadge />}
              <ProfileStatusPill decorations={profile.decorations} />
            </div>
            <span className="text-xs text-zinc-400 flex items-center gap-0.5 shrink-0">
              <MapPin className="w-3 h-3" />
              {profile.city}
            </span>
          </div>

          {/* Height and relationship goals */}
          {(profile.heightCm != null || (profile.goals ?? []).length > 0) && (
            <div className="flex flex-wrap items-center gap-1.5">
              {profile.heightCm != null && (
                <span className="inline-flex items-center gap-0.5 text-[11px] text-zinc-500 font-medium">
                  <Ruler className="w-3 h-3" aria-hidden="true" />
                  {profile.heightCm} см
                </span>
              )}
              <GoalPills goals={profile.goals} max={2} />
            </div>
          )}

          {/* About text preview */}
          {profile.aboutText && (
            <p className="text-xs text-zinc-600 line-clamp-2 leading-relaxed">
              {profile.aboutText}
            </p>
          )}

          {/* Communication Tags */}
          <div className="flex flex-wrap gap-1.5 pt-1">
            {tags.slice(0, 4).map((tag) => (
              <TagChip key={tag.code} tag={tag} size="sm" />
            ))}
            {tags.length > 4 && (
              <span className="text-[11px] text-zinc-400 self-center pl-1 font-medium">
                +{tags.length - 4} ещё
              </span>
            )}
          </div>
        </div>
      </div>

      {/* Action Buttons: Pass, Superlike, Like */}
      <div className="px-4 pb-4 pt-1 flex items-center justify-between gap-2 border-t border-zinc-100/80">
        {onPass && (
          <button
            type="button"
            disabled={isProcessing}
            onClick={(e) => {
              e.stopPropagation();
              onPass(profile);
            }}
            title="Пропустить анкету"
            className="flex-1 py-2 rounded-xl border border-zinc-200 text-zinc-500 hover:text-zinc-800 hover:bg-zinc-50 flex items-center justify-center transition-colors disabled:opacity-50"
          >
            <X className="w-4 h-4" />
          </button>
        )}

        {onSuperlike && (
          <button
            type="button"
            disabled={isProcessing}
            onClick={(e) => {
              e.stopPropagation();
              onSuperlike(profile);
            }}
            title="Суперлайк (выделит вашу симпатию)"
            className="flex-1 py-2 rounded-xl border border-sky-200 bg-sky-50 text-sky-600 hover:bg-sky-100 flex items-center justify-center transition-colors disabled:opacity-50"
          >
            <Star className="w-4 h-4 fill-sky-500 text-sky-500" />
          </button>
        )}

        {onLike && (
          <button
            type="button"
            disabled={isProcessing}
            onClick={(e) => {
              e.stopPropagation();
              onLike(profile);
            }}
            title="Нравится"
            className="flex-2 py-2 rounded-xl bg-rose-500 hover:bg-rose-600 text-white font-semibold text-xs flex items-center justify-center gap-1.5 transition-colors shadow-xs disabled:opacity-50"
          >
            <Heart className="w-4 h-4 fill-white" />
            <span>Нравится</span>
          </button>
        )}
      </div>
    </div>
  );
};
