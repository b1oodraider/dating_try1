import React from 'react';
import { Modal } from '../../../shared/components/Modal';
import { Button } from '../../../shared/components/Button';
import type { PublicProfile, MyProfile } from '../../../core/types';
import { Heart, MessageCircle } from 'lucide-react';

interface MatchModalProps {
  isOpen: boolean;
  onClose: () => void;
  myProfile: MyProfile | null;
  targetProfile: PublicProfile | null;
  matchId?: string;
  onOpenChat: (matchId: string) => void;
}

export const MatchModal: React.FC<MatchModalProps> = ({
  isOpen,
  onClose,
  myProfile,
  targetProfile,
  matchId,
  onOpenChat,
}) => {
  if (!targetProfile) return null;

  const myPhoto = myProfile?.photos.find((p) => p.isPrimary)?.url || myProfile?.photos[0]?.url;
  const targetPhoto = targetProfile.photos.find((p) => p.isPrimary)?.url || targetProfile.photos[0]?.url;

  return (
    <Modal isOpen={isOpen} onClose={onClose} maxWidth="sm">
      <div className="text-center py-4 space-y-5">
        <div className="relative inline-flex items-center justify-center my-2">
          {/* Photos pair */}
          <div className="flex items-center -space-x-4">
            <div className="w-20 h-20 rounded-full border-3 border-white shadow-md overflow-hidden bg-zinc-200">
              {myPhoto ? (
                <img src={myPhoto} alt="Вы" className="w-full h-full object-cover" />
              ) : (
                <div className="w-full h-full flex items-center justify-center text-zinc-400 font-bold">Вы</div>
              )}
            </div>
            <div className="w-20 h-20 rounded-full border-3 border-white shadow-md overflow-hidden bg-zinc-200">
              {targetPhoto ? (
                <img src={targetPhoto} alt={targetProfile.displayName} className="w-full h-full object-cover" />
              ) : (
                <div className="w-full h-full flex items-center justify-center text-zinc-400 font-bold">
                  {targetProfile.displayName[0]}
                </div>
              )}
            </div>
          </div>
          <div className="absolute -bottom-2 bg-rose-500 text-white p-2 rounded-full shadow-md animate-bounce">
            <Heart className="w-4 h-4 fill-white" />
          </div>
        </div>

        <div className="space-y-1">
          <h2 className="text-xl font-extrabold text-zinc-900 tracking-tight">Это взаимно!</h2>
          <p className="text-xs text-zinc-600">
            Вы и <span className="font-semibold text-zinc-900">{targetProfile.displayName}</span> понравились друг другу.
          </p>
          {targetProfile.compatibility !== undefined && targetProfile.compatibility !== null && (
            <p className="text-[11px] text-emerald-700 font-medium">
              Совпадение по карточкам общения: {targetProfile.compatibility}%
            </p>
          )}
        </div>

        <div className="space-y-2 pt-2">
          {matchId && (
            <Button
              type="button"
              variant="primary"
              className="w-full py-2.5"
              onClick={() => {
                onClose();
                onOpenChat(matchId);
              }}
            >
              <MessageCircle className="w-4 h-4 mr-1.5" /> Написать сообщение
            </Button>
          )}
          <Button type="button" variant="ghost" size="sm" className="w-full text-xs text-zinc-500" onClick={onClose}>
            Продолжить поиск
          </Button>
        </div>
      </div>
    </Modal>
  );
};
