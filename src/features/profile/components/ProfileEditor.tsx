import React, { useEffect, useState } from 'react';
import { MyCommunitiesCard } from './MyCommunitiesCard';
import { useAuth } from '../../../core/context/AuthContext';
import { api, ApiError, getErrorMessage } from '../../../core/api/client';
import { TagChip } from '../../tags/components/TagChip';
import { TagPicker } from '../../tags/components/TagPicker';
import { PhotoManager } from '../../photos/components/PhotoManager';
import { CompletenessHint, describePublishRequirement } from './CompletenessHint';
import { DailyStatusEditor } from './DailyStatusEditor';
import { Button } from '../../../shared/components/Button';
import { Modal } from '../../../shared/components/Modal';
import { useGoals } from '../../goals/hooks/useGoals';
import { GoalToggleList } from '../../goals/components/GoalChips';
import { PremiumBadge } from '../../premium/components/PremiumBadge';
import { PurchaseModal } from '../../premium/components/PurchaseModal';
import { useToast } from '../../../shared/components/Toast';
import {
  ArtistsPicker,
  CityPicker,
  toFacts,
  commentsOf,
  FactsFields,
  InterestsPicker,
  LIFESTYLE_FACTS,
  MAX_ARTISTS,
  MAX_INTERESTS,
  WORK_FACTS,
} from './ProfileExtras';
import {
  Eye,
  EyeOff,
  Plus,
  Save,
  CheckCircle,
  AlertCircle,
  FileText,
  ShieldCheck,
  Lock,
} from 'lucide-react';
import { SelfieVerificationModal } from '../../safety/components/SelfieVerificationModal';
import type { Artist, Facts, ProfileUpdate, Seeking, Tag } from '../../../core/types';

// Limits from the API contract (PUT /me/profile).
const NAME_MIN = 2;
const NAME_MAX = 50;
const ABOUT_MAX = 1000;
const AGE_MIN = 18;
const AGE_MAX = 100;
const HEIGHT_MIN = 120;
const HEIGHT_MAX = 230;
const MAX_GOALS = 3;
const RADIUS_MIN = 5;
/** The slider's end: no radius limit (clearSearchRadius). */
const RADIUS_NONE = 300;

const genderLabel = (g?: string | null) => (g === 'MALE' ? 'Мужчина' : g === 'FEMALE' ? 'Женщина' : 'Пол не указан');

type FieldErrors = Record<string, string>;

const FieldError: React.FC<{ message?: string }> = ({ message }) =>
  message ? <p className="text-[11px] text-rose-600 mt-1">{message}</p> : null;

const VisibilitySwitch: React.FC<{
  title: string;
  subtitle?: string;
  checked: boolean;
  disabled?: boolean;
  /** Premium-only: a lock instead of the switch state, the click opens the purchase. */
  locked?: boolean;
  onChange: (on: boolean) => void;
}> = ({ title, subtitle, checked, disabled, locked, onChange }) => (
  <button
    type="button"
    role="switch"
    aria-checked={checked}
    disabled={disabled}
    onClick={() => onChange(!checked)}
    className="w-full flex items-center justify-between gap-3 text-left disabled:opacity-60 disabled:cursor-not-allowed"
  >
    <span>
      <span className="block text-xs font-semibold text-zinc-800">{title}</span>
      {subtitle && <span className="block text-[11px] text-zinc-500">{subtitle}</span>}
    </span>
    {locked ? (
      <Lock className="w-4 h-4 text-zinc-400 shrink-0" />
    ) : (
      <span className={`w-9 h-5 rounded-full p-0.5 shrink-0 transition-colors ${checked ? 'bg-zinc-900' : 'bg-zinc-200'}`}>
        <span className={`block w-4 h-4 rounded-full bg-white transition-transform ${checked ? 'translate-x-4' : ''}`} />
      </span>
    )}
  </button>
);

export const ProfileEditor: React.FC = () => {
  const { profile, updateProfile, publishProfile, hideProfile, refreshProfile } = useAuth();

  // All hooks must run before any early return (Rules of Hooks).
  const [displayName, setDisplayName] = useState(profile?.displayName ?? '');
  const [birthDate, setBirthDate] = useState(profile?.birthDate ?? '');
  const [gender, setGender] = useState(profile?.gender ?? '');
  const [seeking, setSeeking] = useState(profile?.seeking ?? '');
  const [ageFrom, setAgeFrom] = useState(String(profile?.ageFrom ?? AGE_MIN));
  const [ageTo, setAgeTo] = useState(profile?.ageTo != null ? String(profile.ageTo) : '');
  const [city, setCity] = useState(profile?.city ?? '');
  const [cityId, setCityId] = useState<number | null>(profile?.cityId ?? null);
  const [radius, setRadius] = useState(Math.min(profile?.searchRadiusKm ?? RADIUS_NONE, RADIUS_NONE));
  const [interestCodes, setInterestCodes] = useState<string[]>(profile?.interests?.map((i) => i.code) ?? []);
  const [interestComments, setInterestComments] = useState<Record<string, string>>(() => commentsOf(profile?.interests));
  const [artists, setArtists] = useState<Artist[]>(profile?.artists ?? []);
  const [facts, setFacts] = useState<Facts>(() => toFacts(profile?.facts));
  const [purchaseKind, setPurchaseKind] = useState<'premium' | null>(null);
  const [isLocating, setIsLocating] = useState(false);
  const [isSavingVisibility, setIsSavingVisibility] = useState(false);
  const { showToast } = useToast();
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
  const serverIdentity = `${profile?.birthDate ?? ''}|${profile?.gender ?? ''}|${profile?.seeking ?? ''}|${profile?.ageFrom ?? AGE_MIN}|${profile?.ageTo ?? ''}`;
  const serverCity = profile?.city ?? '';
  const serverAbout = profile?.aboutText ?? '';
  const serverHeight = profile?.heightCm != null ? String(profile.heightCm) : '';
  // A string key so the effect re-runs only when the goal set really changed.
  const serverGoalKey = (profile?.goals ?? []).map((g) => g.code).join(',');
  const serverExtras = JSON.stringify([
    profile?.cityId ?? null,
    profile?.searchRadiusKm ?? null,
    (profile?.interests ?? []).map((i) => i.code),
    profile?.artists ?? [],
    toFacts(profile?.facts),
    commentsOf(profile?.interests),
  ]);
  const profileId = profile?.id;
  useEffect(() => {
    setDisplayName(serverDisplayName);
    const [b, g, s, from, to] = serverIdentity.split('|');
    setBirthDate(b);
    setGender(g);
    setSeeking(s);
    setAgeFrom(from);
    setAgeTo(to);
    setCity(serverCity);
    setAboutText(serverAbout);
    setHeight(serverHeight);
    setGoalCodes(serverGoalKey ? serverGoalKey.split(',') : []);
    const [cId, r, i, a, f, ic] = JSON.parse(serverExtras) as [
      number | null,
      number | null,
      string[],
      Artist[],
      Facts,
      Record<string, string>,
    ];
    setCityId(cId);
    setRadius(Math.min(r ?? RADIUS_NONE, RADIUS_NONE));
    setInterestCodes(i);
    setInterestComments(ic);
    setArtists(a);
    setFacts(f);
    setFieldErrors({});
    setFormError(null);
  }, [profileId, serverDisplayName, serverIdentity, serverCity, serverAbout, serverHeight, serverGoalKey, serverExtras]);

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
  // Gender and birth date lock after the first publish (support changes them).
  const identityLocked = profile.identityLocked ?? profile.publicationStatus !== 'DRAFT';
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

    // Gender and birth date lock after the first publish (support changes them).
    if (!identityLocked) {
      if (birthDate) update.birthDate = birthDate;
      if (gender) update.gender = gender as 'MALE' | 'FEMALE';
    }
    if (seeking) update.seeking = seeking as Seeking;
    const from = Number(ageFrom);
    if (!Number.isInteger(from) || from < AGE_MIN || from > AGE_MAX) {
      errors.ageFrom = `Возраст — от ${AGE_MIN} до ${AGE_MAX}`;
    } else {
      update.ageFrom = from;
    }
    if (ageTo.trim()) {
      const to = Number(ageTo);
      if (!Number.isInteger(to) || to < from || to > AGE_MAX) {
        errors.ageTo = `Не меньше нижней границы и не больше ${AGE_MAX}`;
      } else {
        update.ageTo = to;
      }
    } else if (profile.ageTo != null) {
      update.clearAgeTo = true;
    }

    if (cityId != null && cityId !== profile.cityId) update.cityId = cityId;
    if (radius < RADIUS_NONE) update.searchRadiusKm = radius;
    else if (profile.searchRadiusKm != null) update.clearSearchRadius = true;
    update.interestCodes = interestCodes; // [] clears
    // Every chosen interest: "" removes its comment; a dropped interest loses its comment on the server.
    update.interestComments = Object.fromEntries(interestCodes.map((c) => [c, (interestComments[c] ?? '').trim()]));
    update.artistIds = artists.map((a) => a.id);
    // Facts: "" / [] clear.
    update.kidsHas = facts.kidsHas ?? '';
    update.kidsPlans = facts.kidsPlans ?? '';
    update.smoking = facts.smoking ?? '';
    update.alcohol = facts.alcohol ?? '';
    update.sport = facts.sport ?? '';
    update.pets = facts.pets;
    update.profession = (facts.profession ?? '').trim();
    update.educationLevel = facts.educationLevel ?? '';
    update.university = (facts.university ?? '').trim();
    update.languages = facts.languages;

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

  // Visibility switches apply at once, like on mobile.
  const setVisibility = async (patch: Pick<ProfileUpdate, 'showZodiac' | 'invisible' | 'showPremiumBadge'>) => {
    setIsSavingVisibility(true);
    try {
      await updateProfile(patch);
    } catch (err: unknown) {
      showToast(getErrorMessage(err, 'Не удалось сохранить настройку'), 'error');
    } finally {
      setIsSavingVisibility(false);
    }
  };

  const toggleLocation = async () => {
    setIsLocating(true);
    try {
      if (profile.locationShared) {
        await api.forgetLocation();
      } else {
        if (!navigator.geolocation) throw new Error('Браузер не умеет определять местоположение');
        const position = await new Promise<GeolocationPosition>((resolve, reject) =>
          navigator.geolocation.getCurrentPosition(resolve, reject, { enableHighAccuracy: false, timeout: 15000 }),
        ).catch(() => {
          throw new Error('Нет доступа к местоположению — расстояния будут от центра города');
        });
        await api.shareLocation(position.coords.latitude, position.coords.longitude);
      }
      await refreshProfile();
    } catch (err: unknown) {
      showToast(getErrorMessage(err, 'Не удалось определить местоположение'), 'error');
    } finally {
      setIsLocating(false);
    }
  };

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

      <MyCommunitiesCard />

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

        {/* Name */}
        <div>
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

        {/* Gender, age and height */}
        <div>
          <h3 className="text-xs font-semibold text-zinc-900 mb-2">Пол, возраст и рост</h3>
          {identityLocked ? (
            <div className="mb-3 p-3 bg-zinc-50 border border-zinc-200 rounded-xl flex items-start gap-2">
              <Lock className="w-4 h-4 text-zinc-500 shrink-0 mt-0.5" />
              <div className="text-xs">
                <p className="font-medium text-zinc-900">
                  {[genderLabel(profile.gender), profile.birthDate ? profile.birthDate.split('-').reverse().join('.') : null]
                    .filter(Boolean)
                    .join(' · ')}
                  {profile.age != null ? ` (${profile.age})` : ''}
                </p>
                <p className="text-zinc-500 mt-0.5">После публикации пол и возраст меняются через поддержку</p>
                {profile.zodiac && <p className="text-zinc-700 mt-1">Знак зодиака: {profile.zodiac}</p>}
              </div>
            </div>
          ) : (
            <p className="text-[11px] text-zinc-500 mb-2">
              Пол и возраст нельзя будет поменять после публикации без поддержки.
            </p>
          )}
          <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
            {!identityLocked && (
              <>
                <div>
                  <label className="block text-xs font-semibold text-zinc-700 mb-1">Я</label>
                  <select value={gender} onChange={(e) => setGender(e.target.value)} className={inputClass('gender')}>
                    <option value="">—</option>
                    <option value="FEMALE">Женщина</option>
                    <option value="MALE">Мужчина</option>
                  </select>
                  <FieldError message={fieldErrors.gender} />
                </div>
                <div>
                  <label className="block text-xs font-semibold text-zinc-700 mb-1">
                    Дата рождения (18+){profile.age != null ? ` — ${profile.age}` : ''}
                  </label>
                  <input
                    type="date"
                    value={birthDate}
                    onChange={(e) => setBirthDate(e.target.value)}
                    aria-invalid={!!fieldErrors.birthDate}
                    className={inputClass('birthDate')}
                  />
                  <FieldError message={fieldErrors.birthDate} />
                  <p className="text-[11px] text-zinc-400 mt-1">
                    Другим покажем только возраст{profile.zodiac ? ` · ${profile.zodiac}` : ''}
                  </p>
                </div>
              </>
            )}
            <div>
              <label className="block text-xs font-semibold text-zinc-700 mb-1">Рост, см</label>
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
        </div>

        {/* Seeking */}
        <div>
          <h3 className="text-xs font-semibold text-zinc-900 mb-2">Кого ищу</h3>
          <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
            <div>
              <label className="block text-xs font-semibold text-zinc-700 mb-1">Ищу</label>
              <select value={seeking} onChange={(e) => setSeeking(e.target.value)} className={inputClass('seeking')}>
                <option value="">—</option>
                <option value="MALE">Мужчину</option>
                <option value="FEMALE">Женщину</option>
                <option value="ANY">Всех</option>
              </select>
              <FieldError message={fieldErrors.seeking} />
            </div>
            <div>
              <label className="block text-xs font-semibold text-zinc-700 mb-1">Возраст от</label>
              <input
                type="number"
                min={AGE_MIN}
                max={AGE_MAX}
                value={ageFrom}
                onChange={(e) => setAgeFrom(e.target.value)}
                className={`${inputClass('ageFrom')} font-mono`}
              />
              <FieldError message={fieldErrors.ageFrom} />
            </div>
            <div>
              <label className="block text-xs font-semibold text-zinc-700 mb-1">до (пусто — любой)</label>
              <input
                type="number"
                min={AGE_MIN}
                max={AGE_MAX}
                value={ageTo}
                onChange={(e) => setAgeTo(e.target.value)}
                placeholder="—"
                className={`${inputClass('ageTo')} font-mono`}
              />
              <FieldError message={fieldErrors.ageTo} />
            </div>
          </div>
        </div>

        {/* Place */}
        <div className="space-y-3">
          <h3 className="text-xs font-semibold text-zinc-900">Где искать</h3>
          <div>
            <label className="block text-xs font-semibold text-zinc-700 mb-1">
              Город{city ? `: ${city}` : ''}
            </label>
            <CityPicker
              cityName={city}
              onPick={(c) => {
                setCity(c.name);
                setCityId(c.id);
              }}
              error={fieldErrors.cityId ?? fieldErrors.city}
            />
            <FieldError message={fieldErrors.cityId ?? fieldErrors.city} />
          </div>
          <div>
            <label className="block text-xs font-semibold text-zinc-700 mb-1">
              Искать в радиусе: {radius >= RADIUS_NONE ? 'без ограничения' : `${radius} км`}
            </label>
            <input
              type="range"
              min={RADIUS_MIN}
              max={RADIUS_NONE}
              step={5}
              value={radius}
              onChange={(e) => setRadius(Number(e.target.value))}
              aria-label="Радиус поиска"
              className="w-full accent-zinc-900"
            />
            <FieldError message={fieldErrors.searchRadiusKm} />
          </div>
          <label className="flex items-start gap-3 cursor-pointer">
            <input
              type="checkbox"
              checked={!!profile.locationShared}
              disabled={isLocating}
              onChange={() => void toggleLocation()}
              className="mt-0.5 w-4 h-4 accent-zinc-900"
            />
            <span>
              <span className="block text-xs font-semibold text-zinc-700">
                Учитывать моё местоположение{isLocating ? '…' : ''}
              </span>
              <span className="block text-[11px] text-zinc-500">
                Приблизительно, до ~1 км. Без него расстояния считаются от центра города
              </span>
            </span>
          </label>
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

        {/* Interests */}
        <div className="pt-2 border-t border-zinc-100">
          <div className="flex items-center justify-between mb-1">
            <h3 className="text-xs font-semibold text-zinc-900">Увлечения</h3>
            <span className="text-[11px] text-zinc-400">
              {interestCodes.length}/{MAX_INTERESTS}
            </span>
          </div>
          <p className="text-[11px] text-zinc-500 mb-2">Общие увлечения поднимают анкеты выше в ленте.</p>
          <InterestsPicker
            selected={interestCodes}
            onChange={setInterestCodes}
            comments={interestComments}
            onCommentsChange={setInterestComments}
          />
          <FieldError message={fieldErrors.interestCodes} />
          <FieldError message={fieldErrors.interestComments} />
        </div>

        {/* Music */}
        <div className="pt-2 border-t border-zinc-100">
          <h3 className="text-xs font-semibold text-zinc-900 mb-1">Музыка</h3>
          <p className="text-[11px] text-zinc-500 mb-2">
            До {MAX_ARTISTS} исполнителей. Не нашли — попробуйте написать по-другому.
          </p>
          <ArtistsPicker selected={artists} onChange={setArtists} />
          <FieldError message={fieldErrors.artistIds} />
        </div>

        {/* Lifestyle */}
        <div className="pt-2 border-t border-zinc-100">
          <h3 className="text-xs font-semibold text-zinc-900 mb-1">Образ жизни</h3>
          <p className="text-[11px] text-zinc-500 mb-2">
            Всё по желанию. Указанное видно в анкете, по образу жизни можно фильтровать ленту.
          </p>
          <FactsFields keys={LIFESTYLE_FACTS} facts={facts} onChange={setFacts} errors={fieldErrors} />
        </div>

        {/* Work */}
        <div className="pt-2 border-t border-zinc-100">
          <h3 className="text-xs font-semibold text-zinc-900 mb-2">Работа и учёба</h3>
          <FactsFields keys={WORK_FACTS} facts={facts} onChange={setFacts} errors={fieldErrors} />
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
                Карточки общения ({tags.length})
              </h3>
              <p className="text-[11px] text-zinc-500">
                По одной в каждой категории. Помогают сойтись людям с похожим стилем диалога
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

      {/* Visibility: applied at once */}
      <div className="bg-white p-5 rounded-2xl border border-zinc-200/80 shadow-xs space-y-3">
        <h3 className="text-xs font-semibold text-zinc-900">Видимость</h3>
        <VisibilitySwitch
          title="Показывать знак зодиака"
          subtitle={profile.zodiac ?? 'Укажите дату рождения'}
          checked={!!profile.showZodiac}
          disabled={isSavingVisibility || !profile.zodiac}
          onChange={(on) => void setVisibility({ showZodiac: on })}
        />
        <VisibilitySwitch
          title="Невидимка"
          subtitle={profile.premium ? 'Анкету видят только те, кого вы лайкнули' : 'Доступно с премиумом'}
          checked={!!profile.invisible && profile.premium}
          locked={!profile.premium}
          disabled={isSavingVisibility}
          onChange={(on) => (profile.premium ? void setVisibility({ invisible: on }) : setPurchaseKind('premium'))}
        />
        {profile.premium && (
          <VisibilitySwitch
            title="Показывать значок премиума"
            checked={!!profile.showPremiumBadge}
            disabled={isSavingVisibility}
            onChange={(on) => void setVisibility({ showPremiumBadge: on })}
          />
        )}
      </div>

      {/* Tag Picker Modal */}
      <Modal
        isOpen={isTagModalOpen}
        onClose={() => setIsTagModalOpen(false)}
        title="Функциональные карточки общения"
        description="Как вам удобнее общаться?"
        maxWidth="lg"
      >
        {actionErrorBlock && <div className="mb-4">{actionErrorBlock}</div>}
        <TagPicker
          selectedTags={tags}
          onChange={(next) => void handleTagsChange(next)}
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

      {/* Last, so it stacks on top. */}
      <PurchaseModal kind={purchaseKind} onClose={() => setPurchaseKind(null)} onBought={() => void refreshProfile()} />
    </div>
  );
};
