import React, { useState } from 'react';
import { useAuth } from '../../../core/context/AuthContext';
import { TagChip } from '../../tags/components/TagChip';
import { TagPicker } from '../../tags/components/TagPicker';
import { PhotoManager } from '../../photos/components/PhotoManager';
import { CompletenessHint } from './CompletenessHint';
import { DailyStatusEditor } from './DailyStatusEditor';
import { Button } from '../../../shared/components/Button';
import { Modal } from '../../../shared/components/Modal';
import {
  Eye,
  EyeOff,
  Plus,
  Save,
  CheckCircle,
  AlertCircle,
  FileText,
} from 'lucide-react';
import { DailyStatus } from '../../../core/types';

export const ProfileEditor: React.FC = () => {
  const { profile, updateProfile, publishProfile, hideProfile, refreshProfile } = useAuth();

  if (!profile) return null;

  const [displayName, setDisplayName] = useState(profile.displayName || '');
  const [age, setAge] = useState(profile.age || 20);
  const [city, setCity] = useState(profile.city || '');
  const [aboutText, setAboutText] = useState(profile.aboutText || '');
  const [isTagModalOpen, setIsTagModalOpen] = useState(false);
  const [isSaving, setIsSaving] = useState(false);
  const [saveSuccess, setSaveSuccess] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setIsSaving(true);
    setSaveSuccess(false);

    try {
      if (age < 18) {
        throw new Error('Возраст должен быть не менее 18 лет');
      }
      await updateProfile({
        displayName: displayName.trim(),
        age: Number(age),
        city: city.trim(),
        aboutText: aboutText.trim(),
      });
      setSaveSuccess(true);
      setTimeout(() => setSaveSuccess(false), 3000);
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Ошибка сохранения профиля');
    } finally {
      setIsSaving(false);
    }
  };

  const handleTagsChange = async (newTags: typeof profile.tags) => {
    await updateProfile({ tags: newTags });
  };

  const handleDailyStatusSave = async (dailyStatus: DailyStatus | null) => {
    await updateProfile({ dailyStatus });
  };

  const handleTogglePublication = async () => {
    if (profile.publicationStatus === 'PUBLISHED') {
      await hideProfile();
    } else {
      await publishProfile();
    }
  };

  const isPublished = profile.publicationStatus === 'PUBLISHED';

  return (
    <div className="max-w-2xl mx-auto space-y-6 pb-12">
      {/* Header & Publication controls */}
      <div className="bg-white p-5 rounded-2xl border border-zinc-200/80 shadow-xs flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <h2 className="text-lg font-bold text-zinc-900">Моя анкета</h2>
          <p className="text-xs text-zinc-500 mt-0.5">
            Управляйте видимостью профиля и предпочтениями общения
          </p>
        </div>

        <div className="flex items-center gap-2">
          <Button
            type="button"
            variant={isPublished ? 'outline' : 'primary'}
            size="sm"
            onClick={handleTogglePublication}
          >
            {isPublished ? (
              <>
                <EyeOff className="w-3.5 h-3.5 mr-1 text-zinc-500" /> Скрыть анкету
              </>
            ) : (
              <>
                <Eye className="w-3.5 h-3.5 mr-1" /> Опубликовать
              </>
            )}
          </Button>

          <span
            className={`text-xs px-2.5 py-1 rounded-full font-medium border ${
              isPublished
                ? 'bg-emerald-50 text-emerald-700 border-emerald-200'
                : 'bg-zinc-100 text-zinc-600 border-zinc-200'
            }`}
          >
            {isPublished ? 'Опубликована в поиске' : 'Черновик / Скрыта'}
          </span>
        </div>
      </div>

      {/* Completeness Hint */}
      <CompletenessHint profile={profile} />

      {/* Main Edit Form */}
      <form onSubmit={handleSave} className="bg-white p-6 rounded-2xl border border-zinc-200/80 shadow-xs space-y-5">
        {error && (
          <div className="p-3 bg-rose-50 border border-rose-200 text-rose-700 text-xs rounded-xl flex items-center gap-2">
            <AlertCircle className="w-4 h-4 shrink-0" />
            <span>{error}</span>
          </div>
        )}

        {saveSuccess && (
          <div className="p-3 bg-emerald-50 border border-emerald-200 text-emerald-800 text-xs rounded-xl flex items-center gap-2">
            <CheckCircle className="w-4 h-4 text-emerald-600 shrink-0" />
            <span>Анкета успешно сохранена</span>
          </div>
        )}

        {/* Basic Fields */}
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
          <div className="sm:col-span-2">
            <label className="block text-xs font-semibold text-zinc-700 mb-1">
              Имя или псевдоним
            </label>
            <input
              type="text"
              required
              maxLength={40}
              value={displayName}
              onChange={(e) => setDisplayName(e.target.value)}
              placeholder="Как к вам обращаться"
              className="w-full px-3.5 py-2 text-sm bg-zinc-50 border border-zinc-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-zinc-900 focus:bg-white transition-all"
            />
          </div>

          <div>
            <label className="block text-xs font-semibold text-zinc-700 mb-1">
              Возраст (18+)
            </label>
            <input
              type="number"
              min={18}
              max={100}
              required
              value={age}
              onChange={(e) => setAge(Number(e.target.value))}
              className="w-full px-3.5 py-2 text-sm bg-zinc-50 border border-zinc-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-zinc-900 focus:bg-white transition-all font-mono"
            />
          </div>
        </div>

        <div>
          <label className="block text-xs font-semibold text-zinc-700 mb-1">
            Город
          </label>
          <input
            type="text"
            required
            maxLength={60}
            value={city}
            onChange={(e) => setCity(e.target.value)}
            placeholder="Москва, Санкт-Петербург, Казань..."
            className="w-full px-3.5 py-2 text-sm bg-zinc-50 border border-zinc-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-zinc-900 focus:bg-white transition-all"
          />
        </div>

        {/* About Text */}
        <div>
          <div className="flex items-center justify-between mb-1">
            <label className="text-xs font-semibold text-zinc-700">
              О себе (свободный текст)
            </label>
            <span className="text-[11px] text-zinc-400">{aboutText.length}/500</span>
          </div>
          <textarea
            rows={4}
            maxLength={500}
            value={aboutText}
            onChange={(e) => setAboutText(e.target.value)}
            placeholder="Расскажите о ваших интересах, ценностях или том, что приносит радость в обычный день..."
            className="w-full p-3.5 text-sm bg-zinc-50 border border-zinc-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-zinc-900 focus:bg-white transition-all resize-none leading-relaxed"
          />
          <p className="text-[11px] text-zinc-400 mt-1 flex items-center gap-1">
            <FileText className="w-3.5 h-3.5 shrink-0" />
            Текст анкеты защищён: он не участвует в машинном обучении и не индексируется поисковыми системами.
          </p>
        </div>

        {/* 24-hour Daily Status */}
        <DailyStatusEditor
          currentStatus={profile.dailyStatus}
          onSave={handleDailyStatusSave}
        />

        {/* Communication tags */}
        <div className="space-y-2 pt-2 border-t border-zinc-100">
          <div className="flex items-center justify-between">
            <div>
              <h3 className="text-xs font-semibold text-zinc-900">
                Карточки комфортного общения ({profile.tags.length}/8)
              </h3>
              <p className="text-[11px] text-zinc-500">
                Помогают быстро сойтись людям с похожим темпом и стилем диалога
              </p>
            </div>
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => setIsTagModalOpen(true)}
            >
              <Plus className="w-3.5 h-3.5 mr-1" /> Выбрать карточки
            </Button>
          </div>

          <div className="flex flex-wrap gap-1.5 min-h-10 p-3 bg-zinc-50/70 border border-zinc-200/80 rounded-xl">
            {profile.tags.length === 0 ? (
              <span className="text-xs text-zinc-400 self-center">
                Карточки пока не выбраны. Нажмите «Выбрать карточки», чтобы настроить свои предпочтения.
              </span>
            ) : (
              profile.tags.map((tag) => (
                <TagChip
                  key={tag.code}
                  tag={tag}
                  onRemove={(t) =>
                    handleTagsChange(profile.tags.filter((item) => item.code !== t.code))
                  }
                />
              ))
            )}
          </div>
        </div>

        {/* Photos section */}
        <div className="pt-2 border-t border-zinc-100">
          <PhotoManager
            accountId={profile.accountId}
            photos={profile.photos}
            onPhotosChange={refreshProfile}
          />
        </div>

        {/* Submit */}
        <div className="pt-4 border-t border-zinc-100 flex justify-end">
          <Button type="submit" variant="primary" isLoading={isSaving} className="px-6">
            <Save className="w-4 h-4 mr-1.5" /> Сохранить изменения
          </Button>
        </div>
      </form>

      {/* Tag Picker Modal */}
      <Modal
        isOpen={isTagModalOpen}
        onClose={() => setIsTagModalOpen(false)}
        title="Функциональные карточки общения"
        description="Выберите до 8 карточек, описывающих ваш комфортный ритм и формат диалога"
        maxWidth="lg"
      >
        <TagPicker
          selectedTags={profile.tags}
          onChange={handleTagsChange}
          maxTags={8}
        />
        <div className="mt-5 flex justify-end">
          <Button
            type="button"
            variant="primary"
            onClick={() => setIsTagModalOpen(false)}
          >
            Готово
          </Button>
        </div>
      </Modal>
    </div>
  );
};
