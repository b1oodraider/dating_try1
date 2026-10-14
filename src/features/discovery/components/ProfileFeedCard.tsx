import React from 'react';
import { Profile } from '../../../core/types';
import { TagChip } from '../../tags/components/TagChip';
import { MapPin, Sparkles, MessageCircleHeart } from 'lucide-react';

interface ProfileFeedCardProps {
  profile: Profile;
  onOpenDetails: (profile: Profile) => void;
}

export const ProfileFeedCard: React.FC<ProfileFeedCardProps> = ({
  profile,
  onOpenDetails,
}) => {
  const primaryPhoto =
    profile.photos.find((p) => p.isPrimary) || profile.photos[0];

  const hasActiveDailyStatus =
    profile.dailyStatus &&
    new Date(profile.dailyStatus.expiresAt).getTime() > Date.now();

  return (
    <div
      onClick={() => onOpenDetails(profile)}
      className="group bg-white rounded-2xl border border-zinc-200/80 overflow-hidden shadow-xs hover:shadow-md transition-all duration-200 cursor-pointer flex flex-col justify-between"
    >
      <div>
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

          {/* Photo count indicator */}
          {profile.photos.length > 1 && (
            <div className="absolute bottom-2 right-2 px-2 py-0.5 rounded-full bg-zinc-900/60 backdrop-blur-xs text-white text-[10px] font-medium">
              1/{profile.photos.length}
            </div>
          )}

          {/* 24-hour status badge overlay */}
          {hasActiveDailyStatus && (
            <div className="absolute top-2 left-2 right-2 bg-zinc-900/80 backdrop-blur-md text-white text-[11px] p-2 rounded-xl flex items-center gap-1.5 shadow-sm">
              <Sparkles className="w-3.5 h-3.5 text-amber-400 shrink-0" />
              <span className="truncate italic">«{profile.dailyStatus?.text}»</span>
            </div>
          )}
        </div>

        {/* Info */}
        <div className="p-4 space-y-3">
          <div className="flex items-baseline justify-between">
            <h3 className="text-base font-bold text-zinc-900 truncate">
              {profile.displayName}, <span className="font-normal text-zinc-600">{profile.age}</span>
            </h3>
            <span className="text-xs text-zinc-400 flex items-center gap-0.5 shrink-0">
              <MapPin className="w-3 h-3" />
              {profile.city}
            </span>
          </div>

          {/* About text preview */}
          {profile.aboutText && (
            <p className="text-xs text-zinc-600 line-clamp-2 leading-relaxed">
              {profile.aboutText}
            </p>
          )}

          {/* Communication Tags */}
          <div className="flex flex-wrap gap-1.5 pt-1">
            {profile.tags.slice(0, 4).map((tag) => (
              <TagChip key={tag.code} tag={tag} size="sm" />
            ))}
            {profile.tags.length > 4 && (
              <span className="text-[11px] text-zinc-400 self-center pl-1 font-medium">
                +{profile.tags.length - 4} ещё
              </span>
            )}
          </div>
        </div>
      </div>

      <div className="px-4 pb-3 pt-0">
        <button
          type="button"
          className="w-full py-2 text-xs font-semibold text-zinc-700 bg-zinc-50 hover:bg-zinc-100 rounded-xl transition-colors text-center border border-zinc-200/60"
        >
          Посмотреть анкету
        </button>
      </div>
    </div>
  );
};
