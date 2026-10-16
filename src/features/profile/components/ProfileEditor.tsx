import React, { useEffect, useState } from 'react';
import { useAuth } from '../../../core/context/AuthContext';
import { ApiError, getErrorMessage } from '../../../core/api/client';
import { TagChip } from '../../tags/components/TagChip';
import { TagPicker, MAX_PROFILE_TAGS } from '../../tags/components/TagPicker';
import { PhotoManager } from '../../photos/components/PhotoManager';
import { CompletenessHint, describePublishRequirement } from './CompletenessHint';
import { DailyStatusEditor } from './DailyStatusEditor';
import { Button } from '../../../shared/components/Button';
import { Modal } from '../../../shared/components/Modal';
import { useGoals } from '../../goals/hooks/useGoals';
import { GoalToggleList } from '../../goals/components/GoalChips';
import { PremiumBadge } from '../../premium/components/PremiumBadge';
import {
  Eye,
  EyeOff,
  Plus,
  Save,
  CheckCircle,
  AlertCircle,
  FileText,
  ShieldCheck,
} from 'lucide-react';
import { SelfieVerificationModal } from '../../safety/components/SelfieVerificationModal';
import type { ProfileUpdate, Tag } from '../../../core/types';

// Limits from the API contract (PUT /me/profile).
const NAME_MIN = 2;
const NAME_MAX = 50;
const CITY_MAX = 60;
const ABOUT_MAX = 1000;
const AGE_MIN = 18;
const AGE_MAX = 100;
const HEIGHT_MIN = 120;
const HEIGHT_MAX = 230;
const MAX_GOALS = 3;

type FieldErrors = Record<string, string>;

const FieldError: React.FC<{ message?: string }> = ({ message }) =>
  message ? <p className="text-[11px] text-rose-600 mt-1">{message}</p> : null;

export const ProfileEditor: React.FC = () => {
  const { profile, updateProfile, publishProfile, hideProfile, refreshProfile } = useAuth();

  // All hooks must run before any early return (Rules of Hooks).
  const [displayName, setDisplayName] = useState(profile?.displayName ?? '');
  const [age, setAge] = useState(profile?.age != null ? String(profile.age) : '');
  const [city, setCity] = useState(profile?.city ?? '');
  const [aboutText, setAboutText] = useState(profile?.aboutText ?? '');
  const [height, setHeight] = useState(profile?.heightCm != null ? String(profile.heightCm) : '');
  const [goalCodes, setGoalCodes] = useState<string[]>(profile?.goals?.map((g) => g.code) ?? []);
  const { goals: allGoals, isLoading: isGoalsLoading, error: goalsError, reload: reloadGoals } = useGoals();
  const [isTagModalOpen, setIsTagModalOpen] = useState(false);
  const [isSaving, setIsSaving] = useState(false);
  const [saveSuccess, setSaveSuccess] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({});
  const [isTogglingPublication, setIsTogglingPublication] = useState(false);
  const [isSavingTags, setIsSavingTags] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [publishMissing, setPublishMissing] = useState<string[]>([]);
  const [isVerificationModalOpen, setIsVerificationModalOpen] = useState(false);

  // Re-sync the form whenever the server copy of these fields changes (other account, reload, save).
  const serverDisplayName = profile?.displayName ?? '';
  const serverAge = profile?.age != null ? String(profile.age) : '';
  const serverCity = profile?.city ?? '';
  const serverAbout = profile?.aboutText ?? '';
  const serverHeight = profile?.heightCm != null ? String(profile.heightCm) : '';
  // A string key so the effect re-runs only when the goal set really changed.
  const serverGoalKey = (profile?.goals ?? []).map((g) => g.code).join(',');
  const profileId = profile?.id;
  useEffect(() => {
    setDisplayName(serverDisplayName);
    setAge(serverAge);
    setCity(serverCity);
    setAboutText(serverAbout);
    setHeight(serverHeight);
    setGoalCodes(serverGoalKey ? serverGoalKey.split(',') : []);
    setFieldErrors({});
    setFormError(null);
  }, [profileId, serverDisplayName, serverAge, serverCity, serverAbout, serverHeight, serverGoalKey]);

  // Once the server says nothing is missing, drop the stale "cannot publish" hint.
  const missingCount = profile?.missingForPublish?.length ?? 0;
  useEffect(() => {
    if (missingCount === 0) setPublishMissing([]);
  }, [missingCount]);

  useEffect(() => {
    if (!saveSuccess) return;
    const timer = window.setTimeout(() => setSaveSuccess(false), 3000);
    return () => window.clearTimeout(timer);
  }, [saveSuccess]);

  if (!profile) {
    return (
      <div className="max-w-2xl mx-auto py-16 text-center text-xs text-zinc-500">
        Анкета не загружена.
      </div>
    );
  }

  const tags = profile.tags ?? [];
  const photos = profile.photos ?? [];

  const validateForm = (): { update: ProfileUpdate; errors: FieldErrors } => {
    const errors: FieldErrors = {};
    const update: ProfileUpdate = {};

    const name = displayName.trim();
    if (name) {
      if (name.length < NAME_MIN || name.length > NAME_MAX) {
        errors.displayName = `Имя должно содержать от ${NAME_MIN} до ${NAME_MAX} символов`;
      } else {
        update.displayName = name;
      }
    } else if (profile.displayName) {
      errors.displayName = 'Имя нельзя оставить пустым';
    }

    const ageText = age.trim();
    if (ageText) {
      const parsed = Number(ageText);
      if (!Number.isInteger(parsed) || parsed < AGE_MIN || parsed > AGE_MAX) {
        errors.age = `Возраст — целое число от ${AGE_MIN} до ${AGE_MAX}`;
      } else {
        update.age = parsed;
      }
    } else if (profile.age != null) {
      errors.age = 'Возраст нельзя оставить пустым';
    }

    const cityValue = city.trim();
    if (cityValue) {
      if (cityValue.length > CITY_MAX) {
        errors.city = `Название города — не длиннее ${CITY_MAX} символов`;
      } else {
        update.city = cityValue;
      }
    } else if (profile.city) {
      errors.city = 'Город нельзя оставить пустым';
    }

    const about = aboutText.trim();
    if (about.length > ABOUT_MAX) {
      errors.aboutText = `Текст «О себе» — не длиннее ${ABOUT_MAX} символов`;
    } else {
      update.aboutText = about; // "" clears
    }

    const heightText = height.trim();
    if (heightText) {
      const parsed = Number(heightText);
      if (!Number.isInteger(parsed) || parsed < HEIGHT_MIN || parsed > HEIGHT_MAX) {
        errors.heightCm = `Рост — целое число от ${HEIGHT_MIN} до ${HEIGHT_MAX} см`;
      } else {
        update.heightCm = parsed;
      }
    } else if (profile.heightCm != null) {
      // An emptied field removes the height (omitted heightCm would mean "unchanged").
      update.clearHeight = true;
    }

    if (goalCodes.length > MAX_GOALS) {
      errors.goalCodes = `Можно выбрать не больше ${MAX_GOALS} целей`;
    } else {
      update.goalCodes = goalCodes; // [] clears
    }

    return { update, errors };
  };

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault();
    setFormError(null);
    setFieldErrors({});
    setSaveSuccess(false);

    const { update, errors } = validateForm();
    if (Object.keys(errors).length > 0) {
      setFieldErrors(errors);
      setFormError('Проверьте выделенные поля');
      return;
    }

    setIsSaving(true);
    try {
      await updateProfile(update);
      setSaveSuccess(true);
    } catch (err: unknown) {
      setFormError(getErrorMessage(err, 'Ошибка сохранения профиля'));
      if (err instanceof ApiError && err.fieldErrors) {
        setFieldErrors(err.fieldErrors);
      }
    } finally {
      setIsSaving(false);
    }
  };

  const toggleGoal = (code: string) => {
    setFieldErrors((prev) => {
      if (!prev.goalCodes) return prev;
      const next = { ...prev };
      delete next.goalCodes;
      return next;
    });
    setGoalCodes((prev) => (prev.includes(code) ? prev.filter((c) => c !== code) : [...prev, code]));
  };

  const handleTagsChange = async (newTags: Tag[]) => {
    setActionError(null);
    setIsSavingTags(true);
    try {
      await updateProfile({ tagCodes: newTags.map((t) => t.code) });
    } catch (err: unknown) {
      setActionError(getErrorMessage(err, 'Не удалось сохранить карточки общения'));
    } finally {
      setIsSavingTags(false);
    }
  };

  const handleDailyStatusSave = (text: string) => updateProfile({ dailyStatusText: text });

  const handleTogglePublication = async () => {
    setActionError(null);
    setPublishMissing([]);
    setIsTogglingPublication(true);
    try {
      if (profile.publicationStatus === 'PUBLISHED') {
        await hideProfile();
      } else {
        await publishProfile();
      }
    } catch (err: unknown) {
      if (err instanceof ApiError && err.code === 'PROFILE_INCOMPLETE') {
        const missing = err.fieldErrors ? Object.keys(err.fieldErrors) : profile.missingForPublish;
        setPublishMissing(missing.length > 0 ? missing : profile.missingForPublish);
        setActionError(err.message || 'Анкета заполнена не полностью');
      } else {
        setActionError(getErrorMessage(err, 'Не удалось изменить статус публикации'));
      }
    } finally {
      setIsTogglingPublication(false);
    }
  };

  const isPublished = profile.publicationStatus === 'PUBLISHED';

  const inputClass = (field: string) =>
    `w-full px-3.5 py-2 text-sm bg-zinc-50 border rounded-xl focus:outline-none focus:ring-2 focus:ring-zinc-900 focus:bg-white transition-all ${
      fieldErrors[field] ? 'border-rose-300' : 'border-zinc-200'
    }`;

  const actionErrorBlock = actionError && (
    <div className="p-3 bg-rose-50 border border-rose-200 text-rose-700 text-xs rounded-xl flex items-start gap-2">
      <AlertCircle className="w-4 h-4 shrink-0 mt-0.5" />
      <div>
        <span>{actionError}</span>
        {publishMissing.length > 0 && (
          <span className="block mt-0.5">
            Заполните: {publishMissing.map(describePublishRequirement).join(', ')}.
          </span>
        )}
      </div>
    </div>
  );

  return (
    <div className="max-w-2xl mx-auto space-y-6 pb-12">
      {/* Header & Publication controls */}
      <div className="bg-white p-5 rounded-2xl border border-zinc-200/80 shadow-xs flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <h2 className="text-lg font-bold text-zinc-900 flex items-center gap-2">
            Моя анкета
            {profile.premium && <PremiumBadge size="md" />}
          </h2>
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
            isLoading={isTogglingPublication}
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

      {!isTagModalOpen && actionErrorBlock}

      {/* Completeness Hint */}
      <CompletenessHint profile={profile} />

      {/* Selfie Verification Card */}
      <div className="p-4 bg-white border border-zinc-200/80 rounded-2xl flex items-center justify-between gap-3 shadow-xs">
        <div className="flex items-center gap-3">
          <div
            className={`w-10 h-10 rounded-xl flex items-center justify-center shrink-0 ${
              profile.verified ? 'bg-emerald-50 text-emerald-600' : 'bg-zinc-100 text-zinc-500'
            }`}
          >
            <ShieldCheck className="w-5 h-5" />
          </div>
          <div>
            <div className="flex items-center gap-2">
              <h3 className="text-xs font-bold text-zinc-900">
                {profile.verified ? 'Анкета верифицирована по селфи' : 'Верификация по селфи'}
              </h3>
              {profile.verified && (
                <span className="text-[10px] font-semibold text-emerald-700 bg-emerald-50 px-1.5 py-0.2 rounded border border-emerald-200">
                  Подтверждено
                </span>
              )}
            </div>
            <p className="text-[11px] text-zinc-500 mt-0.5">
              {profile.verified
                ? 'Зелёный значок подлинности отображается в ленте и диалогах'
                : 'Сделайте быстрое селфи с простым жестом для подтверждения анкеты'}
            </p>
          </div>
        </div>

        <Button
          type="button"
          variant={profile.verified ? 'outline' : 'primary'}
          size="sm"
          onClick={() => setIsVerificationModalOpen(true)}
        >
          {profile.verified ? 'Статус' : 'Пройти'}
        </Button>
      </div>

      {/* Main Edit Form */}
      <form onSubmit={handleSave} noValidate className="bg-white p-6 rounded-2xl border border-zinc-200/80 shadow-xs space-y-5">
        {formError && (
          <div className="p-3 bg-rose-50 border border-rose-200 text-rose-700 text-xs rounded-xl flex items-center gap-2">
            <AlertCircle className="w-4 h-4 shrink-0" />
            <span>{formError}</span>
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
              maxLength={NAME_MAX}
              value={displayName}
              onChange={(e) => setDisplayName(e.target.value)}
              placeholder="Как к вам обращаться"
              aria-invalid={!!fieldErrors.displayName}
              className={inputClass('displayName')}
            />
            <FieldError message={fieldErrors.displayName} />
          </div>

          <div>
            <label className="block text-xs font-semibold text-zinc-700 mb-1">
              Возраст (18+)
            </label>
            <input
              type="number"
              inputMode="numeric"
              min={AGE_MIN}
              max={AGE_MAX}
              step={1}
              value={age}
              onChange={(e) => setAge(e.target.value)}
              placeholder="—"
              aria-invalid={!!fieldErrors.age}
              className={`${inputClass('age')} font-mono`}
            />
            <FieldError message={fieldErrors.age} />
          </div>
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
          <div className="sm:col-span-2">
            <label className="block text-xs font-semibold text-zinc-700 mb-1">
              Город
            </label>
            <input
              type="text"
              maxLength={CITY_MAX}
              value={city}
              onChange={(e) => setCity(e.target.value)}
              placeholder="Москва, Санкт-Петербург, Казань..."
              aria-invalid={!!fieldErrors.city}
              className={inputClass('city')}
            />
            <FieldError message={fieldErrors.city} />
          </div>

          <div>
            <label className="block text-xs font-semibold text-zinc-700 mb-1">
              Рост, см
            </label>
            <input
              type="number"
              inputMode="numeric"
              min={HEIGHT_MIN}
              max={HEIGHT_MAX}
              step={1}
              value={height}
              onChange={(e) => setHeight(e.target.value)}
              placeholder={`${HEIGHT_MIN}–${HEIGHT_MAX}`}
              aria-invalid={!!fieldErrors.heightCm}
              className={`${inputClass('heightCm')} font-mono`}
            />
            <FieldError message={fieldErrors.heightCm} />
          </div>
        </div>

        {/* Relationship goals */}
        <div>
          <div className="flex items-center justify-between mb-1">
            <label className="text-xs font-semibold text-zinc-700">Цели знакомства</label>
            <span className="text-[11px] text-zinc-400">
              {goalCodes.length}/{MAX_GOALS}
            </span>
          </div>
          {isGoalsLoading && <p className="text-xs text-zinc-400">Загрузка целей...</p>}
          {goalsError && (
            <div className="p-2.5 bg-rose-50 border border-rose-200 text-rose-700 text-xs rounded-xl flex items-center justify-between gap-2">
              <span className="flex items-center gap-2">
                <AlertCircle className="w-4 h-4 shrink-0" />
                {goalsError}
              </span>
              <Button type="button" variant="outline" size="sm" onClick={reloadGoals}>
                Повторить
              </Button>
            </div>
          )}
          <GoalToggleList
            goals={allGoals}
            selectedCodes={goalCodes}
            onToggle={toggleGoal}
            maxSelected={MAX_GOALS}
            disabled={isSaving}
          />
          <FieldError message={fieldErrors.goalCodes} />
          <p className="text-[11px] text-zinc-400 mt-1">
            До {MAX_GOALS} целей. Сохраняются вместе с анкетой кнопкой «Сохранить изменения».
          </p>
        </div>

        {/* About Text */}
        <div>
          <div className="flex items-center justify-between mb-1">
            <label className="text-xs font-semibold text-zinc-700">
              О себе (свободный текст)
            </label>
            <span className="text-[11px] text-zinc-400">{aboutText.length}/{ABOUT_MAX}</span>
          </div>
          <textarea
            rows={4}
            maxLength={ABOUT_MAX}
            value={aboutText}
            onChange={(e) => setAboutText(e.target.value)}
            placeholder="Расскажите о ваших интересах, ценностях или том, что приносит радость в обычный день..."
            aria-invalid={!!fieldErrors.aboutText}
            className={`w-full p-3.5 text-sm bg-zinc-50 border rounded-xl focus:outline-none focus:ring-2 focus:ring-zinc-900 focus:bg-white transition-all resize-none leading-relaxed ${
              fieldErrors.aboutText ? 'border-rose-300' : 'border-zinc-200'
            }`}
          />
          <FieldError message={fieldErrors.aboutText} />
          <p className="text-[11px] text-zinc-400 mt-1 flex items-center gap-1">
            <FileText className="w-3.5 h-3.5 shrink-0" />
            Текст анкеты защищён: он не участвует в машинном обучении и не индексируется поисковыми системами.
          </p>
        </div>

        {/* 24-hour Daily Status */}
        <DailyStatusEditor
          key={`${profile.id}:${profile.dailyStatusText ?? ''}`}
          statusText={profile.dailyStatusText}
          expiresAt={profile.dailyStatusExpiresAt}
          onSave={handleDailyStatusSave}
        />

        {/* Communication tags */}
        <div className="space-y-2 pt-2 border-t border-zinc-100">
          <div className="flex items-center justify-between">
            <div>
              <h3 className="text-xs font-semibold text-zinc-900">
                Карточки комфортного общения ({tags.length}/{MAX_PROFILE_TAGS})
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
            {tags.length === 0 ? (
              <span className="text-xs text-zinc-400 self-center">
                Карточки пока не выбраны. Нажмите «Выбрать карточки», чтобы настроить свои предпочтения.
              </span>
            ) : (
              tags.map((tag) => (
                <TagChip
                  key={tag.code}
                  tag={tag}
                  onRemove={(t) =>
                    void handleTagsChange(tags.filter((item) => item.code !== t.code))
                  }
                />
              ))
            )}
          </div>
        </div>

        {/* Photos section */}
        <div className="pt-2 border-t border-zinc-100">
          <PhotoManager photos={photos} onPhotosChange={refreshProfile} />
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
        description={`Выберите до ${MAX_PROFILE_TAGS} карточек, описывающих ваш комфортный ритм и формат диалога`}
        maxWidth="lg"
      >
        {actionErrorBlock && <div className="mb-4">{actionErrorBlock}</div>}
        <TagPicker
          selectedTags={tags}
          onChange={(next) => void handleTagsChange(next)}
          maxTags={MAX_PROFILE_TAGS}
          disabled={isSavingTags}
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

      <SelfieVerificationModal
        isOpen={isVerificationModalOpen}
        onClose={() => setIsVerificationModalOpen(false)}
        onVerifiedChange={refreshProfile}
      />
    </div>
  );
};
