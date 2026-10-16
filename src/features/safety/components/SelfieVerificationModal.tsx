import React, { useEffect, useState, useRef } from 'react';
import { Modal } from '../../../shared/components/Modal';
import { Button } from '../../../shared/components/Button';
import { api, getErrorMessage } from '../../../core/api/client';
import type { VerificationRequest } from '../../../core/types';
import { ShieldCheck, Camera, AlertCircle, Clock, CheckCircle2, RefreshCw } from 'lucide-react';

interface SelfieVerificationModalProps {
  isOpen: boolean;
  onClose: () => void;
  onVerifiedChange?: () => void;
}

export const SelfieVerificationModal: React.FC<SelfieVerificationModalProps> = ({
  isOpen,
  onClose,
  onVerifiedChange,
}) => {
  const [status, setStatus] = useState<{
    verified: boolean;
    verifiedAt: string | null;
    current: VerificationRequest | null;
  } | null>(null);

  const [isLoading, setIsLoading] = useState(true);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  const loadStatus = async () => {
    setIsLoading(true);
    setError(null);
    try {
      const res = await api.getVerification();
      setStatus(res);
    } catch (e) {
      setError(getErrorMessage(e, 'Не удалось загрузить статус верификации'));
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    if (isOpen) {
      void loadStatus();
    }
  }, [isOpen]);

  const handleStart = async () => {
    setIsSubmitting(true);
    setError(null);
    try {
      const req = await api.requestVerification();
      setStatus((prev) => ({ verified: false, verifiedAt: null, ...prev, current: req }));
    } catch (e) {
      setError(getErrorMessage(e, 'Не удалось начать верификацию'));
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleFileChange = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file || !status?.current?.id) return;

    setIsSubmitting(true);
    setError(null);
    try {
      const updated = await api.submitSelfie(status.current.id, file);
      setStatus((prev) => ({ verified: false, verifiedAt: null, ...prev, current: updated }));
      onVerifiedChange?.();
    } catch (err) {
      setError(getErrorMessage(err, 'Ошибка при отправке селфи'));
    } finally {
      setIsSubmitting(false);
      if (fileInputRef.current) fileInputRef.current.value = '';
    }
  };

  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      title="Верификация анкеты"
      description="Подтверждение подлинности фотографий с помощью быстрого селфи"
      maxWidth="md"
    >
      <div className="space-y-4">
        {isLoading ? (
          <div className="py-12 flex flex-col items-center justify-center gap-2">
            <div className="w-8 h-8 border-2 border-zinc-900 border-t-transparent rounded-full animate-spin" />
            <p className="text-xs text-zinc-500">Проверяем статус верификации...</p>
          </div>
        ) : error ? (
          <div className="p-3 bg-rose-50 border border-rose-200 text-rose-700 text-xs rounded-xl flex items-center gap-2">
            <AlertCircle className="w-4 h-4 shrink-0" />
            <span>{error}</span>
          </div>
        ) : status?.verified ? (
          <div className="py-8 text-center space-y-3 bg-emerald-50/60 rounded-2xl border border-emerald-200/80 p-6">
            <div className="w-12 h-12 rounded-2xl bg-emerald-100 text-emerald-700 flex items-center justify-center mx-auto shadow-xs">
              <ShieldCheck className="w-6 h-6 text-emerald-600" />
            </div>
            <h3 className="text-base font-bold text-zinc-900">Ваш профиль верифицирован!</h3>
            <p className="text-xs text-zinc-600 max-w-sm mx-auto leading-relaxed">
              На вашей анкете отображается зелёный значок подлинности. Это повышает доверие других участников и отклики в ленте.
            </p>
            {status.verifiedAt && (
              <p className="text-[11px] text-zinc-400">
                Подтверждено: {new Date(status.verifiedAt).toLocaleDateString('ru-RU')}
              </p>
            )}
            <div className="pt-2">
              <Button variant="outline" size="sm" onClick={onClose}>
                Закрыть
              </Button>
            </div>
          </div>
        ) : status?.current?.status === 'PENDING' ? (
          <div className="py-8 text-center space-y-3 bg-zinc-50 rounded-2xl border border-zinc-200/80 p-6">
            <div className="w-12 h-12 rounded-2xl bg-amber-100 text-amber-700 flex items-center justify-center mx-auto shadow-xs">
              <Clock className="w-6 h-6 text-amber-600" />
            </div>
            <h3 className="text-base font-bold text-zinc-900">Селфи на проверке</h3>
            <p className="text-xs text-zinc-600 max-w-sm mx-auto leading-relaxed">
              Модераторы сравнивают ваше селфи с фотографиями в анкете. Обычно это занимает от 5 до 15 минут.
              Сразу после проверки селфи безвозвратно удаляется с сервера (согласно 152-ФЗ и 572-ФЗ).
            </p>
            <div className="pt-2 flex justify-center gap-2">
              <Button variant="outline" size="sm" onClick={loadStatus}>
                <RefreshCw className="w-3.5 h-3.5 mr-1" /> Обновить статус
              </Button>
              <Button variant="ghost" size="sm" onClick={onClose}>
                Понятно
              </Button>
            </div>
          </div>
        ) : status?.current?.status === 'AWAITING_SELFIE' ? (
          <div className="space-y-4">
            <div className="p-4 bg-sky-50 border border-sky-200 rounded-2xl space-y-2">
              <span className="text-[10px] font-bold uppercase tracking-wider text-sky-800">
                Задание для селфи
              </span>
              <p className="text-sm font-semibold text-zinc-900">
                {status.current.instruction || 'Сделайте селфи, повторяя указанный жест'}
              </p>
              <p className="text-xs text-sky-900/80">
                Это необходимо для защиты от ботов и чужих фото. Селфи не публикуется и удаляется сразу после сверки модератором.
              </p>
            </div>

            <div className="border-2 border-dashed border-zinc-300 rounded-2xl p-6 text-center space-y-3 bg-zinc-50/50">
              <input
                ref={fileInputRef}
                type="file"
                accept="image/jpeg,image/png,image/webp"
                capture="user"
                onChange={handleFileChange}
                className="hidden"
                id="selfie-file-input"
              />
              <div className="w-12 h-12 bg-white rounded-full border border-zinc-200 flex items-center justify-center mx-auto text-zinc-700 shadow-xs">
                <Camera className="w-6 h-6" />
              </div>
              <div>
                <label
                  htmlFor="selfie-file-input"
                  className="inline-block px-4 py-2 bg-zinc-900 text-white rounded-xl text-xs font-semibold hover:bg-zinc-800 transition-colors cursor-pointer shadow-xs"
                >
                  {isSubmitting ? 'Загрузка...' : 'Сделать селфи или выбрать файл'}
                </label>
                <p className="text-[11px] text-zinc-400 mt-1">JPEG, PNG или WebP до 5 МБ</p>
              </div>
            </div>
          </div>
        ) : (
          <div className="space-y-4 text-center py-4">
            <div className="w-14 h-14 rounded-2xl bg-zinc-100 text-zinc-800 flex items-center justify-center mx-auto">
              <ShieldCheck className="w-7 h-7 text-zinc-600" />
            </div>
            <div className="space-y-1.5 max-w-sm mx-auto">
              <h3 className="text-base font-bold text-zinc-900">Получите значок подлинности</h3>
              <p className="text-xs text-zinc-500 leading-relaxed">
                Сделайте одно быстрое селфи с простым жестом (например, открытая ладонь или знак мира).
                Это доказывает, что анкету ведёте именно вы.
              </p>
            </div>

            {status?.current?.status === 'REJECTED' && (
              <div className="p-3 bg-rose-50 border border-rose-200 text-rose-700 text-xs rounded-xl text-left">
                <div className="font-semibold">Предыдущая попытка отклонена:</div>
                <div className="mt-0.5">{status.current.rejectReason || 'Селфи не совпадает с фото анкеты'}</div>
              </div>
            )}

            <div className="pt-2 flex flex-col gap-2 max-w-xs mx-auto">
              <Button
                variant="primary"
                onClick={handleStart}
                isLoading={isSubmitting}
                className="w-full py-2.5"
              >
                Пройти верификацию
              </Button>
              <Button variant="ghost" size="sm" onClick={onClose}>
                Позже
              </Button>
            </div>
          </div>
        )}
      </div>
    </Modal>
  );
};
