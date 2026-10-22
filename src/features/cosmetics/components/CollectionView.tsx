import React, { useCallback, useEffect, useState } from 'react';
import { api, getErrorMessage, isAbortError } from '../../../core/api/client';
import { useAuth } from '../../../core/context/AuthContext';
import type { Achievement, Wallet, WalletItem } from '../../../core/types';
import { Button } from '../../../shared/components/Button';
import { PremiumBadge } from '../../premium/components/PremiumBadge';
import {
  attrColor,
  attrString,
  attrStringList,
  getPhotoFrame,
  PhotoFrameOverlay,
  photoFrameFromAttributes,
  photoFrameOuterStyle,
  profileStatusFromAttributes,
  ProfileStatusPill,
  RarityBadge,
  StatusPill,
} from '../decorations';
import { GachaView } from '../../gacha/components/GachaView';
import { PurchaseModal } from '../../premium/components/PurchaseModal';
import {
  AlertCircle,
  Crown,
  Gem,
  MessageCircleHeart,
  Package,
  RefreshCw,
  Rocket,
  Star,
  Ticket,
  Trophy,
  CheckCircle,
  Sparkles,
} from 'lucide-react';

interface CollectionViewProps {
  /** Bumped by the test panel after it changed the economy state: triggers a reload. */
  revision: number;
}

const CONSUMABLE_ICONS: Record<string, React.FC<{ className?: string }>> = {
  star: Star,
  ticket: Ticket,
  rocket: Rocket,
};

/** Known categories first, in this order; unknown ones follow in server order. */
const CATEGORY_TITLES: Record<string, string> = {
  PHOTO_FRAME: 'Рамки для фото',
  PROFILE_STATUS: 'Статусы профиля',
  CHAT_WALLPAPER: 'Обои чата',
  STICKER_PACK: 'Стикерпаки',
};
const CATEGORY_ORDER = Object.keys(CATEGORY_TITLES);

function formatDateTime(iso: string): string {
  return new Date(iso).toLocaleString('ru-RU', { dateStyle: 'medium', timeStyle: 'short' });
}

function groupCosmetics(items: WalletItem[]): [string, WalletItem[]][] {
  const groups = new Map<string, WalletItem[]>();
  for (const item of items) {
    if (item.kind !== 'COSMETIC') continue;
    groups.set(item.category, [...(groups.get(item.category) ?? []), item]);
  }
  const rank = (c: string) => (CATEGORY_ORDER.includes(c) ? CATEGORY_ORDER.indexOf(c) : CATEGORY_ORDER.length);
  return [...groups.entries()].sort(([a], [b]) => rank(a) - rank(b));
}

// ---------------------------------------------------------------------------
// Item previews
// ---------------------------------------------------------------------------

const CosmeticPreview: React.FC<{ item: WalletItem }> = ({ item }) => {
  const box = 'w-14 h-14 rounded-xl shrink-0 relative overflow-hidden';
  if (item.category === 'PHOTO_FRAME') {
    const frame = photoFrameFromAttributes(item.attributes);
    return (
      <div className={`${box} bg-linear-to-br from-zinc-200 to-zinc-300`} style={photoFrameOuterStyle(frame)}>
        <PhotoFrameOverlay frame={frame} />
      </div>
    );
  }
  if (item.category === 'CHAT_WALLPAPER') {
    const background = attrColor(item.attributes, 'background') ?? '#E4E4E7';
    const accent = attrColor(item.attributes, 'accent') ?? '#71717A';
    return (
      <div className={`${box} border border-zinc-200 p-2 flex flex-col justify-end gap-1`} style={{ background }}>
        <span className="block h-2 w-8 rounded-full" style={{ background: accent }} />
        <span className="block h-2 w-5 rounded-full self-end opacity-70" style={{ background: accent }} />
      </div>
    );
  }
  const emoji = attrString(item.attributes, 'emoji');
  return (
    <div className={`${box} bg-zinc-50 border border-zinc-200 flex items-center justify-center text-2xl`}>
      {emoji ?? <Gem className="w-6 h-6 text-zinc-400" />}
    </div>
  );
};

// ---------------------------------------------------------------------------
// View
// ---------------------------------------------------------------------------

export const CollectionView: React.FC<CollectionViewProps> = ({ revision }) => {
  const { profile, refreshProfile } = useAuth();
  const [wallet, setWallet] = useState<Wallet | null>(null);
  const [achievements, setAchievements] = useState<Achievement[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [pendingCode, setPendingCode] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);
  const [subTab, setSubTab] = useState<'inventory' | 'gacha'>('inventory');
  const [isPurchaseOpen, setIsPurchaseOpen] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    setIsLoading(true);
    setLoadError(null);
    Promise.all([api.getWallet(controller.signal), api.getAchievements(controller.signal)])
      .then(([w, a]) => {
        setWallet(w);
        setAchievements(a);
      })
      .catch((e: unknown) => {
        if (!isAbortError(e)) setLoadError(getErrorMessage(e, 'Не удалось загрузить коллекцию'));
      })
      .finally(() => {
        if (!controller.signal.aborted) setIsLoading(false);
      });
    return () => controller.abort();
  }, [revision, reloadKey]);

  const handleToggleEquip = useCallback(
    async (item: WalletItem, equipped: boolean) => {
      if (!item.equipSlot) return;
      setActionError(null);
      setPendingCode(item.code);
      try {
        const updated = equipped
          ? await api.unequipCosmetic(item.equipSlot)
          : await api.equipCosmetic(item.equipSlot, item.code);
        setWallet(updated);
        // MyProfile.decorations changed: refresh so the preview shows the new frame / status.
        await refreshProfile();
      } catch (e: unknown) {
        setActionError(getErrorMessage(e, 'Не удалось изменить косметику'));
      } finally {
        setPendingCode(null);
      }
    },
    [refreshProfile]
  );

  if (isLoading && !wallet) {
    return (
      <div className="max-w-3xl mx-auto py-16 flex justify-center">
        <div className="w-7 h-7 border-2 border-zinc-900 border-t-transparent rounded-full animate-spin" />
      </div>
    );
  }

  if (!wallet) {
    return (
      <div className="max-w-md mx-auto py-16 text-center space-y-3">
        <p className="text-sm text-zinc-600">{loadError ?? 'Коллекция не загружена.'}</p>
        <Button type="button" variant="outline" size="sm" onClick={() => setReloadKey((k) => k + 1)}>
          <RefreshCw className="w-3.5 h-3.5" /> Повторить
        </Button>
      </div>
    );
  }

  const consumables = wallet.items.filter((i) => i.kind === 'CONSUMABLE');
  const cosmeticGroups = groupCosmetics(wallet.items);
  const photos = profile?.photos ?? [];
  const primaryPhoto = photos.find((p) => p.isPrimary) ?? photos[0];
  const ownFrame = getPhotoFrame(profile?.decorations);
  const premiumUntil = wallet.premium.until ? formatDateTime(wallet.premium.until) : null;

  const card = 'bg-white p-5 rounded-2xl border border-zinc-200/80 shadow-xs';
  const sectionTitle = 'text-sm font-bold text-zinc-900 flex items-center gap-1.5';

  return (
    <div className="max-w-3xl mx-auto space-y-6 pb-12">
      {/* Sub-tab switcher */}
      <div className="flex items-center gap-1 bg-zinc-200/60 p-1 rounded-xl w-fit">
        <button
          type="button"
          onClick={() => setSubTab('inventory')}
          className={`flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold transition-all ${
            subTab === 'inventory' ? 'bg-white text-zinc-900 shadow-xs' : 'text-zinc-600 hover:text-zinc-900'
          }`}
        >
          <Gem className="w-3.5 h-3.5" />
          <span>Гардероб и награды</span>
        </button>

        <button
          type="button"
          onClick={() => setSubTab('gacha')}
          className={`flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold transition-all ${
            subTab === 'gacha' ? 'bg-white text-zinc-900 shadow-xs' : 'text-zinc-600 hover:text-zinc-900'
          }`}
        >
          <Sparkles className="w-3.5 h-3.5 text-amber-500" />
          <span>Алтарь удачи</span>
        </button>
      </div>

      {subTab === 'gacha' ? (
        <GachaView revision={revision} onWalletUpdated={() => setReloadKey((k) => k + 1)} />
      ) : (
        <>
          <div className={`${card} flex flex-col sm:flex-row gap-5`}>
        {/* Own profile preview: how others see the frame and status */}
        <div className="w-full sm:w-40 shrink-0 space-y-2">
          <div
            className="relative aspect-4/5 w-full bg-zinc-100 rounded-xl overflow-hidden"
            style={photoFrameOuterStyle(ownFrame)}
          >
            {primaryPhoto ? (
              <img src={primaryPhoto.url} alt="" className="w-full h-full object-cover" />
            ) : (
              <div className="w-full h-full flex items-center justify-center text-zinc-300">
                <MessageCircleHeart className="w-10 h-10" />
              </div>
            )}
            <PhotoFrameOverlay frame={ownFrame} />
          </div>
          <div className="flex flex-wrap items-center gap-1.5">
            <span className="text-sm font-bold text-zinc-900 truncate">{profile?.displayName ?? 'Без имени'}</span>
            {(wallet.premium.active || profile?.premium) && <PremiumBadge />}
            <ProfileStatusPill decorations={profile?.decorations} />
          </div>
          <p className="text-[11px] text-zinc-400">Так вашу анкету видят другие</p>
        </div>

        {/* Premium */}
        <div className="flex-1 space-y-3">
          <div>
            <h2 className="text-lg font-bold text-zinc-900">Кошелёк и косметика</h2>
            <p className="text-xs text-zinc-500 mt-0.5">Премиум, расходники и предметы оформления анкеты</p>
          </div>
          <div
            className={`p-3.5 rounded-xl border flex items-start gap-2.5 ${
              wallet.premium.active ? 'bg-amber-50 border-amber-200' : 'bg-zinc-50 border-zinc-200'
            }`}
          >
            <Crown className={`w-4 h-4 shrink-0 mt-0.5 ${wallet.premium.active ? 'text-amber-500' : 'text-zinc-400'}`} />
            <div className="text-xs space-y-1">
              <div className="font-semibold text-zinc-900">
                {wallet.premium.active ? `Премиум активен до ${premiumUntil}` : 'Премиум не активен'}
              </div>
              {!wallet.premium.active && premiumUntil && (
                <div className="text-zinc-500">Закончился {premiumUntil}</div>
              )}
              {!wallet.premium.active && (
                <Button type="button" size="sm" onClick={() => setIsPurchaseOpen(true)}>
                  <Crown className="w-3.5 h-3.5" /> Купить премиум
                </Button>
              )}
              {wallet.premiumFeatures.length > 0 && (
                <div className="flex flex-wrap gap-1 pt-0.5">
                  <span className="text-zinc-500">Возможности премиума:</span>
                  {wallet.premiumFeatures.map((f) => (
                    <span key={f} className="px-1.5 py-0.5 rounded bg-white border border-zinc-200 font-mono text-[10px]">
                      {f}
                    </span>
                  ))}
                </div>
              )}
            </div>
          </div>
        </div>
      </div>

      {(actionError || (loadError && wallet)) && (
        <div role="alert" className="p-3 bg-rose-50 border border-rose-200 text-rose-700 text-xs rounded-xl flex items-start gap-2">
          <AlertCircle className="w-4 h-4 shrink-0 mt-0.5" />
          <span>{actionError ?? loadError}</span>
        </div>
      )}

      {/* Consumables */}
      <section className={`${card} space-y-3`}>
        <h3 className={sectionTitle}>
          <Package className="w-4 h-4 text-zinc-500" /> Расходники
        </h3>
        {consumables.length === 0 ? (
          <p className="text-xs text-zinc-400">Пока пусто.</p>
        ) : (
          <div className="grid grid-cols-1 sm:grid-cols-3 gap-2">
            {consumables.map((item) => {
              const Icon = CONSUMABLE_ICONS[attrString(item.attributes, 'icon') ?? ''] ?? Package;
              return (
                <div key={item.code} className="p-3 rounded-xl border border-zinc-200/80 bg-zinc-50/60 flex items-center gap-3">
                  <div className="w-9 h-9 rounded-lg bg-white border border-zinc-200 flex items-center justify-center shrink-0">
                    <Icon className="w-4 h-4 text-rose-500" />
                  </div>
                  <div className="min-w-0">
                    <div className="text-xs font-semibold text-zinc-900 truncate">
                      {item.title} <span className="font-mono text-zinc-600">×{item.quantity}</span>
                    </div>
                    {item.periodicQuantity > 0 && (
                      <div className="text-[11px] text-zinc-500">из них еженедельных: {item.periodicQuantity}</div>
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </section>

      {/* Cosmetics grouped by category */}
      <section className={`${card} space-y-4`}>
        <h3 className={sectionTitle}>
          <Gem className="w-4 h-4 text-zinc-500" /> Косметика
        </h3>
        {cosmeticGroups.length === 0 && <p className="text-xs text-zinc-400">Пока пусто.</p>}
        {cosmeticGroups.map(([category, items]) => (
          <div key={category} className="space-y-2">
            <h4 className="text-[11px] font-semibold text-zinc-500 uppercase tracking-wider">
              {CATEGORY_TITLES[category] ?? category}
            </h4>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
              {items.map((item) => {
                const equipped = !!item.equipSlot && wallet.equipped[item.equipSlot] === item.code;
                const stickers = category === 'STICKER_PACK' ? attrStringList(item.attributes, 'stickers') : [];
                const status = category === 'PROFILE_STATUS' ? profileStatusFromAttributes(item.attributes) : null;
                return (
                  <div
                    key={item.code}
                    className={`p-3 rounded-xl border flex items-center gap-3 ${
                      equipped ? 'border-zinc-900 bg-zinc-50' : 'border-zinc-200/80 bg-white'
                    }`}
                  >
                    <CosmeticPreview item={item} />
                    <div className="flex-1 min-w-0 space-y-1">
                      <div className="flex flex-wrap items-center gap-1.5">
                        <span className="text-xs font-semibold text-zinc-900">{item.title}</span>
                        <RarityBadge rarity={item.rarity} />
                      </div>
                      {status && <StatusPill status={status} />}
                      {stickers.length > 0 && (
                        <div className="flex flex-wrap gap-1">
                          {stickers.map((s) => (
                            <span key={s} className="px-1.5 py-0.5 rounded bg-zinc-100 text-zinc-600 font-mono text-[10px]">
                              {s}
                            </span>
                          ))}
                        </div>
                      )}
                    </div>
                    {item.equipSlot && (
                      <Button
                        type="button"
                        size="sm"
                        variant={equipped ? 'outline' : 'primary'}
                        isLoading={pendingCode === item.code}
                        disabled={pendingCode !== null}
                        onClick={() => void handleToggleEquip(item, equipped)}
                      >
                        {equipped ? 'Снять' : 'Надеть'}
                      </Button>
                    )}
                  </div>
                );
              })}
            </div>
          </div>
        ))}
      </section>

      {/* Achievements */}
      <section className={`${card} space-y-3`}>
        <h3 className={sectionTitle}>
          <Trophy className="w-4 h-4 text-zinc-500" /> Достижения
        </h3>
        {achievements.length === 0 ? (
          <p className="text-xs text-zinc-400">Достижений пока нет.</p>
        ) : (
          <ul className="space-y-2">
            {achievements.map((a) => (
              <li key={a.code} className="p-3 rounded-xl border border-zinc-200/80 flex items-start gap-2.5">
                <CheckCircle className={`w-4 h-4 shrink-0 mt-0.5 ${a.completed ? 'text-emerald-600' : 'text-zinc-300'}`} />
                <div className="text-xs">
                  <div className="font-semibold text-zinc-900">{a.title}</div>
                  {a.description && <div className="text-zinc-500">{a.description}</div>}
                  {a.completed && a.completedAt && (
                    <div className="text-[11px] text-emerald-700 mt-0.5">Получено {formatDateTime(a.completedAt)}</div>
                  )}
                </div>
              </li>
            ))}
          </ul>
        )}
      </section>
        </>
      )}
      <PurchaseModal
        kind={isPurchaseOpen ? 'premium' : null}
        onClose={() => setIsPurchaseOpen(false)}
        onBought={() => {
          setReloadKey((k) => k + 1);
          refreshProfile().catch((e: unknown) => console.warn('Profile refresh failed', e));
        }}
      />
    </div>
  );
};
