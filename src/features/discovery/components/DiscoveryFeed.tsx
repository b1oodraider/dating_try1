import React, { useState, useEffect, useCallback } from 'react';
import { Profile } from '../../../core/types';
import { api } from '../../../core/api/client';
import { useAuth } from '../../../core/context/AuthContext';
import { DiscoveryFilters } from './DiscoveryFilters';
import { ProfileFeedCard } from './ProfileFeedCard';
import { ProfileDetailModal } from './ProfileDetailModal';
import { Users, Frown } from 'lucide-react';
import { Button } from '../../../shared/components/Button';

export const DiscoveryFeed: React.FC = () => {
  const { account } = useAuth();

  const [profiles, setProfiles] = useState<Profile[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [selectedTagCodes, setSelectedTagCodes] = useState<string[]>([]);
  const [tagFilterLogic, setTagFilterLogic] = useState<'AND' | 'OR'>('OR');
  const [selectedCity, setSelectedCity] = useState<string>('ALL');
  const [activeProfileModal, setActiveProfileModal] = useState<Profile | null>(null);

  const loadFeed = useCallback(async () => {
    setIsLoading(true);
    try {
      const result = await api.getDiscoveryProfiles(account?.id || null, {
        selectedTagCodes,
        tagFilterLogic,
        city: selectedCity,
      });
      setProfiles(result.profiles);
    } catch (e) {
      console.error('Failed to load profiles', e);
    } finally {
      setIsLoading(false);
    }
  }, [account?.id, selectedTagCodes, tagFilterLogic, selectedCity]);

  useEffect(() => {
    loadFeed();
  }, [loadFeed]);

  const handleResetFilters = () => {
    setSelectedTagCodes([]);
    setSelectedCity('ALL');
    setTagFilterLogic('OR');
  };

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
            Найдено: <strong className="text-zinc-800">{profiles.length}</strong> анкет
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
      </div>

      {/* Grid or Empty/Loading State */}
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
          </p>
          <div className="pt-2">
            <Button variant="outline" size="sm" onClick={handleResetFilters}>
              Сбросить все фильтры
            </Button>
          </div>
        </div>
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-5">
          {profiles.map((profile) => (
            <ProfileFeedCard
              key={profile.id}
              profile={profile}
              onOpenDetails={(p) => setActiveProfileModal(p)}
            />
          ))}
        </div>
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
