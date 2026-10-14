import React, { useState } from 'react';
import { Profile } from '../../../core/types';
import { Modal } from '../../../shared/components/Modal';
import { TagChip } from '../../tags/components/TagChip';
import { MapPin, Sparkles, Clock, MessageSquare, ShieldCheck, Heart } from 'lucide-react';
import { Button } from '../../../shared/components/Button';

interface ProfileDetailModalProps {
  profile: Profile | null;
  isOpen: boolean;
  onClose: () => void;
}

export const ProfileDetailModal: React.FC<ProfileDetailModalProps> = ({
  profile,
  isOpen,
  onClose,
}) => {
  const [activePhotoIdx, setActivePhotoIdx] = useState(0);

  if (!profile) return null;

  const photos = profile.photos.length > 0 ? profile.photos : [];
  const currentPhoto = photos[activePhotoIdx] || photos[0];

  const hasActiveDailyStatus =
    profile.dailyStatus &&
    new Date(profile.dailyStatus.expiresAt).getTime() > Date.now();

  return (
    <Modal isOpen={isOpen} onClose={onClose} maxWidth="xl">
      <div className="space-y-5 -mt-2">
        {/* Photo Gallery */}
        <div className="relative aspect-4/3 w-full bg-zinc-900 rounded-2xl overflow-hidden shadow-inner">
          {currentPhoto ? (
            <img
              src={currentPhoto.url}
              alt={profile.displayName}
              className="w-full h-full object-cover"
            />
          ) : (
            <div className="w-full h-full flex items-center justify-center text-zinc-500">
              Нет фотографий
            </div>
          )}

          {photos.length > 1 && (
            <div className="absolute bottom-3 left-1/2 -translate-x-1/2 flex items-center gap-1.5 bg-black/40 backdrop-blur-md px-3 py-1 rounded-full">
              {photos.map((_, idx) => (
                <button
                  key={idx}
                  onClick={() => setActivePhotoIdx(idx)}
                  className={`w-2 h-2 rounded-full transition-all ${
                    idx === activePhotoIdx ? 'w-5 bg-white' : 'bg-white/50 hover:bg-white/80'
                  }`}
                  aria-label={`Фото ${idx + 1}`}
                />
              ))}
            </div>
          )}
        </div>

        {/* Thumbnail Selector if multiple photos */}
        {photos.length > 1 && (
          <div className="flex gap-2 overflow-x-auto pb-1">
            {photos.map((p, idx) => (
              <button
                key={p.id}
                onClick={() => setActivePhotoIdx(idx)}
                className={`relative w-16 h-16 rounded-xl overflow-hidden shrink-0 border-2 transition-all ${
                  idx === activePhotoIdx ? 'border-zinc-900 scale-95' : 'border-transparent opacity-70 hover:opacity-100'
                }`}
              >
                <img src={p.url} alt="" className="w-full h-full object-cover" />
              </button>
            ))}
          </div>
        )}

        {/* Main Info */}
        <div className="space-y-4">
          <div className="flex items-start justify-between">
            <div>
              <h2 className="text-2xl font-bold text-zinc-900">
                {profile.displayName}, <span className="text-zinc-600 font-normal">{profile.age}</span>
              </h2>
              <p className="text-sm text-zinc-500 flex items-center gap-1 mt-0.5">
                <MapPin className="w-3.5 h-3.5 text-zinc-400" />
                {profile.city}
              </p>
            </div>
          </div>

          {/* 24-hour Daily Status if active */}
          {hasActiveDailyStatus && (
            <div className="p-3.5 bg-amber-50/80 border border-amber-200/80 rounded-2xl flex items-start gap-2.5">
              <Sparkles className="w-4 h-4 text-amber-500 shrink-0 mt-0.5" />
              <div>
                <span className="text-[11px] font-semibold text-amber-900 uppercase tracking-wider block">
                  Статус на сегодня
                </span>
                <p className="text-xs text-amber-950 font-medium mt-0.5">
                  «{profile.dailyStatus?.text}»
                </p>
              </div>
            </div>
          )}

          {/* About text */}
          {profile.aboutText && (
            <div className="p-4 bg-zinc-50 border border-zinc-200/60 rounded-2xl space-y-1">
              <span className="text-[11px] font-semibold text-zinc-400 uppercase tracking-wider">
                О себе
              </span>
              <p className="text-sm text-zinc-800 leading-relaxed whitespace-pre-line">
                {profile.aboutText}
              </p>
            </div>
          )}

          {/* Communication Tags with explicit descriptions */}
          <div className="space-y-2.5">
            <h3 className="text-xs font-semibold text-zinc-900 uppercase tracking-wider">
              Предпочтения в общении ({profile.tags.length})
            </h3>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
              {profile.tags.map((tag) => (
                <div
                  key={tag.code}
                  className="p-3 bg-white border border-zinc-200/80 rounded-xl space-y-1 shadow-2xs"
                >
                  <div className="flex items-center justify-between">
                    <span className="text-xs font-bold text-zinc-900">{tag.label}</span>
                    <span className="text-[10px] px-1.5 py-0.2 bg-zinc-100 text-zinc-500 rounded">
                      {tag.categoryLabel}
                    </span>
                  </div>
                  <p className="text-[11px] text-zinc-500 leading-normal">{tag.description}</p>
                </div>
              ))}
            </div>
          </div>

          <div className="pt-2 border-t border-zinc-100 flex items-center justify-between text-xs text-zinc-400">
            <span className="flex items-center gap-1">
              <ShieldCheck className="w-3.5 h-3.5 text-emerald-600" />
              Проверенная анкета
            </span>
            <Button variant="outline" size="sm" onClick={onClose}>
              Закрыть
            </Button>
          </div>
        </div>
      </div>
    </Modal>
  );
};
