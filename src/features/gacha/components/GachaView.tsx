import React, { useState, useEffect, useCallback } from 'react';
import { api, getErrorMessage } from '../../../core/api/client';
import type { Banner, GachaHistoryItem, GachaPullResponse, GachaShardsInfo, PullResult, Rarity } from '../../../core/types';
import { Button } from '../../../shared/components/Button';
import { Modal } from '../../../shared/components/Modal';
import { Sparkles, Ticket, ShieldCheck, History, RefreshCw, Star, Info, Gem, ArrowRightLeft } from 'lucide-react';
import { useToast } from '../../../shared/components/Toast';

interface GachaViewProps {
  revision?: number;
  onWalletUpdated?: () => void;
}

const RARITY_COLORS: Record<Rarity, { border: string; bg: string; text: string; glow: string }> = {
  COMMON: { border: 'border-zinc-300', bg: 'bg-zinc-100', text: 'text-zinc-700', glow: '' },
  RARE: { border: 'border-sky-400', bg: 'bg-sky-50', text: 'text-sky-700', glow: 'shadow-sky-100' },
  EPIC: { border: 'border-purple-500', bg: 'bg-purple-50', text: 'text-purple-700', glow: 'shadow-purple-200' },
  LEGENDARY: { border: 'border-amber-500', bg: 'bg-amber-50', text: 'text-amber-700', glow: 'shadow-amber-200 shadow-md ring-2 ring-amber-400/50' },
};

export const GachaView: React.FC<GachaViewProps> = ({ revision = 0, onWalletUpdated }) => {
  const { showToast } = useToast();
  const [banners, setBanners] = useState<Banner[]>([]);
  const [shards, setShards] = useState<GachaShardsInfo | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Pulling
  const [isPulling, setIsPulling] = useState(false);
  const [pullResult, setPullResult] = useState<GachaPullResponse | null>(null);

  // Sub-modals
  const [isRatesModalOpen, setIsRatesModalOpen] = useState(false);
  const [isHistoryModalOpen, setIsHistoryModalOpen] = useState(false);
  const [historyItems, setHistoryItems] = useState<GachaHistoryItem[]>([]);
  const [isLoadingHistory, setIsLoadingHistory] = useState(false);

  // Shard exchange
  const [isExchanging, setIsExchanging] = useState(false);

  const loadGachaData = useCallback(async () => {
    setIsLoading(true);
    setError(null);
    try {
      const [bannersList, shardsInfo] = await Promise.all([
        api.getGachaBanners(),
        api.getGachaShards(),
      ]);
      setBanners(bannersList);
      setShards(shardsInfo);
    } catch (e) {
      setError(getErrorMessage(e, 'Не удалось загрузить данные Алтаря'));
    } finally {
      setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadGachaData();
  }, [loadGachaData, revision]);

  const handlePull = async (bannerCode: string, count: number) => {
    setIsPulling(true);
    try {
      const res = await api.pullGacha(bannerCode, count, crypto.randomUUID());
      setPullResult(res);
      void loadGachaData();
      onWalletUpdated?.();
      showToast(count === 1 ? 'Призыв совершен!' : '10 призывов совершены!', 'success');
    } catch (e) {
      showToast(getErrorMessage(e, 'Не удалось совершить призыв'), 'error');
    } finally {
      setIsPulling(false);
    }
  };

  const handleExchangeShards = async (tickets: number) => {
    setIsExchanging(true);
    try {
      await api.exchangeGachaShards(tickets, crypto.randomUUID());
      showToast(`Успешно обменяно на ${tickets} билетов!`, 'success');
      void loadGachaData();
      onWalletUpdated?.();
    } catch (e) {
      showToast(getErrorMessage(e, 'Ошибка обмена осколков'), 'error');
    } finally {
      setIsExchanging(false);
    }
  };

  const loadHistory = async () => {
    setIsHistoryModalOpen(true);
    setIsLoadingHistory(true);
    try {
      const data = await api.getGachaHistory(30);
      setHistoryItems(data.items);
    } catch (e) {
      showToast(getErrorMessage(e), 'error');
    } finally {
      setIsLoadingHistory(false);
    }
  };

  const activeBanner = banners[0] ?? null;

  return (
    <div className="space-y-6">
      {/* Top Header / Balance Summary */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 bg-gradient-to-r from-violet-900 to-indigo-900 text-white p-5 rounded-2xl shadow-sm">
        <div className="space-y-1">
          <div className="flex items-center gap-2">
            <Sparkles className="w-5 h-5 text-amber-300" />
            <h2 className="text-lg font-bold tracking-tight">Алтарь удачи</h2>
          </div>
          <p className="text-xs text-indigo-200">
            Получайте эксклюзивные рамки, статусы, стикеры и дни премиума с прозрачными шансами
          </p>
        </div>

        {shards && (
          <div className="flex items-center gap-3 bg-white/10 backdrop-blur-md px-3.5 py-2 rounded-xl text-xs border border-white/15">
            <div className="flex items-center gap-1.5" title="Осколки от дубликатов">
              <Gem className="w-4 h-4 text-violet-300" />
              <span>
                Осколки: <strong>{shards.balance.quantity}</strong>
              </span>
            </div>
            {shards.balance.quantity >= shards.shardsPerTicket && (
              <Button
                variant="primary"
                size="sm"
                className="py-1 px-2.5 text-[11px] bg-amber-500 hover:bg-amber-400 text-zinc-950 font-bold"
                onClick={() => void handleExchangeShards(1)}
                isLoading={isExchanging}
              >
                <ArrowRightLeft className="w-3 h-3 mr-1" />
                Обменять на билет
              </Button>
            )}
          </div>
        )}
      </div>

      {isLoading ? (
        <div className="py-16 text-center text-xs text-zinc-400">Загрузка баннеров...</div>
      ) : error ? (
        <div className="p-6 bg-rose-50 border border-rose-200 text-rose-700 text-xs rounded-2xl text-center">
          {error}
        </div>
      ) : !activeBanner ? (
        <div className="p-8 text-center text-xs text-zinc-500">Баннеры временно недоступны</div>
      ) : (
        <div className="space-y-6">
          {/* Main Active Banner Card */}
          <div className="bg-white border border-zinc-200/80 rounded-2xl p-6 shadow-xs space-y-6">
            <div className="flex flex-col sm:flex-row sm:items-start justify-between gap-3">
              <div>
                <span className="text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded bg-violet-100 text-violet-800">
                  {activeBanner.type === 'PERMANENT' ? 'Постоянный призыв' : 'Ограниченное событие'}
                </span>
                <h3 className="text-xl font-extrabold text-zinc-900 mt-1">{activeBanner.title}</h3>
                <p className="text-xs text-zinc-500 mt-0.5">{activeBanner.description}</p>
              </div>

              <div className="flex items-center gap-2">
                <Button variant="outline" size="sm" onClick={() => setIsRatesModalOpen(true)}>
                  <Info className="w-3.5 h-3.5 mr-1" /> Шансы
                </Button>
                <Button variant="ghost" size="sm" onClick={loadHistory}>
                  <History className="w-3.5 h-3.5 mr-1" /> История
                </Button>
              </div>
            </div>

            {/* Pity Progress */}
            <div className="p-4 bg-zinc-50 border border-zinc-200/60 rounded-xl space-y-2">
              <div className="flex items-center justify-between text-xs font-semibold text-zinc-800">
                <span className="flex items-center gap-1.5">
                  <Star className="w-4 h-4 text-purple-600 fill-purple-100" />
                  Система гарантов (Pity)
                </span>
                <span className="text-[11px] font-normal text-zinc-500">
                  Всего круток: {activeBanner.myPity.totalPulls}
                </span>
              </div>

              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 text-xs">
                <div className="bg-white p-2.5 rounded-lg border border-zinc-200/80">
                  <div className="text-[11px] text-zinc-500">Гарантированный Epic через:</div>
                  <div className="font-bold text-purple-700 text-sm">
                    {activeBanner.myPity.epicGuaranteedIn ?? 'Достигнут!'} круток
                  </div>
                </div>

                <div className="bg-white p-2.5 rounded-lg border border-zinc-200/80">
                  <div className="text-[11px] text-zinc-500">Гарантированный Legendary через:</div>
                  <div className="font-bold text-amber-600 text-sm">
                    {activeBanner.myPity.legendaryGuaranteedIn ?? 'Достигнут!'} круток
                  </div>
                </div>
              </div>
            </div>

            {/* Featured Items Preview */}
            {activeBanner.featured.length > 0 && (
              <div className="space-y-2">
                <h4 className="text-xs font-bold text-zinc-700 uppercase tracking-wider">
                  Главные награды баннера
                </h4>
                <div className="grid grid-cols-2 sm:grid-cols-4 gap-2.5">
                  {activeBanner.featured.map((item) => {
                    const rarityStyle = RARITY_COLORS[item.rarity];
                    return (
                      <div
                        key={item.entryId}
                        className={`p-3 rounded-xl border ${rarityStyle.border} ${rarityStyle.bg} flex flex-col justify-between`}
                      >
                        <span className={`text-[10px] font-bold ${rarityStyle.text}`}>
                          {item.rarity}
                        </span>
                        <div className="font-semibold text-xs text-zinc-900 mt-1 truncate">
                          {item.reward.item?.title || `${item.reward.days} дн. премиума`}
                        </div>
                        <span className="text-[10px] text-zinc-400 mt-1">
                          Вероятность: {item.percent}%
                        </span>
                      </div>
                    );
                  })}
                </div>
              </div>
            )}

            {/* Action Pull Buttons */}
            <div className="flex flex-col sm:flex-row items-center gap-3 pt-3 border-t border-zinc-100">
              <Button
                variant="outline"
                className="w-full sm:w-1/2 py-3 border-zinc-300 font-bold"
                onClick={() => void handlePull(activeBanner.code, 1)}
                isLoading={isPulling}
              >
                <Ticket className="w-4 h-4 mr-1.5 text-violet-600" />
                Призвать 1 раз ({activeBanner.cost.perPull} билет)
              </Button>

              <Button
                variant="primary"
                className="w-full sm:w-1/2 py-3 bg-zinc-900 text-white font-bold"
                onClick={() => void handlePull(activeBanner.code, activeBanner.cost.multiPullSize)}
                isLoading={isPulling}
              >
                <Sparkles className="w-4 h-4 mr-1.5 text-amber-400" />
                Призвать {activeBanner.cost.multiPullSize} раз ({activeBanner.cost.multiPullCost} билетов)
              </Button>
            </div>
          </div>
        </div>
      )}

      {/* Pull Results Modal */}
      {pullResult && (
        <Modal
          isOpen={true}
          onClose={() => setPullResult(null)}
          title="Результаты призыва"
          maxWidth="lg"
        >
          <div className="space-y-5 text-center py-2">
            <div className="grid grid-cols-2 sm:grid-cols-5 gap-3 max-h-[60vh] overflow-y-auto p-1">
              {pullResult.results.map((res, idx) => {
                const style = RARITY_COLORS[res.rarity];
                return (
                  <div
                    key={idx}
                    className={`p-3 rounded-2xl border ${style.border} ${style.bg} ${style.glow} flex flex-col justify-between items-center text-center`}
                  >
                    <span className={`text-[9px] font-bold tracking-wider ${style.text}`}>
                      {res.rarity}
                    </span>

                    <div className="my-2">
                      <div className="w-12 h-12 bg-white rounded-xl shadow-xs flex items-center justify-center mx-auto text-xl font-bold">
                        {res.reward.item?.attributes?.emoji
                          ? String(res.reward.item.attributes.emoji)
                          : res.reward.type === 'PREMIUM_DAYS'
                          ? '👑'
                          : '🎁'}
                      </div>
                    </div>

                    <div className="font-bold text-xs text-zinc-900 line-clamp-1">
                      {res.reward.item?.title || `Премиум: ${res.reward.days} дн.`}
                    </div>

                    {res.isNew ? (
                      <span className="mt-1 text-[9px] font-bold text-emerald-700 bg-emerald-100 px-1.5 py-0.2 rounded-full">
                        НОВОЕ
                      </span>
                    ) : (
                      <span className="mt-1 text-[9px] text-zinc-500 font-medium">
                        Повтор: +{res.shardsGranted} осколков
                      </span>
                    )}
                  </div>
                );
              })}
            </div>

            {pullResult.shardsGranted > 0 && (
              <p className="text-xs text-violet-700 bg-violet-50 p-2.5 rounded-xl border border-violet-200 inline-block">
                За повторные предметы начислено: <strong>+{pullResult.shardsGranted} осколков</strong>
              </p>
            )}

            <div>
              <Button variant="primary" onClick={() => setPullResult(null)} className="px-8">
                Отлично!
              </Button>
            </div>
          </div>
        </Modal>
      )}

      {/* Disclosed Rates Modal */}
      {activeBanner && (
        <Modal
          isOpen={isRatesModalOpen}
          onClose={() => setIsRatesModalOpen(false)}
          title="Вероятности призыва (Rates)"
          description="Официальные шансы выпадения предметов по закону и открытому стандарту"
          maxWidth="md"
        >
          <div className="space-y-4 text-xs">
            <div className="divide-y divide-zinc-200 border border-zinc-200 rounded-xl overflow-hidden">
              {activeBanner.rates.rarities.map((r) => {
                const style = RARITY_COLORS[r.rarity];
                return (
                  <div key={r.rarity} className="flex items-center justify-between p-3 bg-white">
                    <span className={`font-bold ${style.text}`}>{r.rarity}</span>
                    <span className="font-mono font-semibold text-zinc-700">{r.percent}%</span>
                  </div>
                );
              })}
            </div>

            <div className="p-3 bg-zinc-50 border border-zinc-200 rounded-xl space-y-1.5 text-zinc-600">
              <p>
                <strong>Гаранты:</strong> на каждые 10 призывов гарантирован минимум 1 предмет категории Epic или выше.
                На каждые 50 призывов гарантирован легендарный предмет.
              </p>
              <p>
                <strong>Дубликаты:</strong> при повторном получении косметики начисляются осколки, которые можно снова обменять на билеты.
              </p>
            </div>

            <div className="pt-2 text-right">
              <Button variant="outline" size="sm" onClick={() => setIsRatesModalOpen(false)}>
                Закрыть
              </Button>
            </div>
          </div>
        </Modal>
      )}

      {/* Pull History Modal */}
      <Modal
        isOpen={isHistoryModalOpen}
        onClose={() => setIsHistoryModalOpen(false)}
        title="История призывов"
        maxWidth="lg"
      >
        <div className="space-y-4">
          {isLoadingHistory ? (
            <div className="py-8 text-center text-xs text-zinc-400">Загрузка истории...</div>
          ) : historyItems.length === 0 ? (
            <div className="py-8 text-center text-xs text-zinc-500">История призывов пуста</div>
          ) : (
            <div className="max-h-80 overflow-y-auto divide-y divide-zinc-100 text-xs">
              {historyItems.map((h) => {
                const style = RARITY_COLORS[h.rarity];
                return (
                  <div key={h.id} className="py-2.5 flex items-center justify-between gap-3">
                    <div className="flex items-center gap-2">
                      <span className={`font-bold text-[10px] px-1.5 py-0.2 rounded ${style.bg} ${style.text}`}>
                        {h.rarity}
                      </span>
                      <span className="font-semibold text-zinc-900">
                        {h.reward.item?.title || `${h.reward.days} дн. премиума`}
                      </span>
                      {h.pityTrigger && (
                        <span className="text-[9px] text-purple-700 bg-purple-50 px-1 rounded">
                          Гарант
                        </span>
                      )}
                    </div>
                    <span className="text-[10px] text-zinc-400">
                      {new Date(h.createdAt).toLocaleDateString('ru-RU')}
                    </span>
                  </div>
                );
              })}
            </div>
          )}

          <div className="pt-2 text-right">
            <Button variant="outline" size="sm" onClick={() => setIsHistoryModalOpen(false)}>
              Закрыть
            </Button>
          </div>
        </div>
      </Modal>
    </div>
  );
};
