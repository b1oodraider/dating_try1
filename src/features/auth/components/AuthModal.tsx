import React, { useState } from 'react';
import { Modal } from '../../../shared/components/Modal';
import { Button } from '../../../shared/components/Button';
import { useAuth } from '../../../core/context/AuthContext';
import { getErrorMessage } from '../../../core/api/client';
import { Smartphone, KeyRound, ShieldCheck, ArrowRight, ArrowLeft } from 'lucide-react';

interface AuthModalProps {
  isOpen: boolean;
  onClose: () => void;
}

export const AuthModal: React.FC<AuthModalProps> = ({ isOpen, onClose }) => {
  const { activeChallenge, requestOtp, verifyOtp, cancelChallenge } = useAuth();

  const [phone, setPhone] = useState('');
  const [code, setCode] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(false);

  const handleRequestOtp = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setIsLoading(true);
    try {
      await requestOtp(phone);
      setCode('');
    } catch (err: unknown) {
      setError(getErrorMessage(err, 'Не удалось отправить код. Попробуйте ещё раз.'));
    } finally {
      setIsLoading(false);
    }
  };

  const handleVerifyOtp = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setIsLoading(true);
    try {
      await verifyOtp(code);
      setCode('');
      onClose();
    } catch (err: unknown) {
      setError(getErrorMessage(err, 'Не удалось подтвердить код. Попробуйте ещё раз.'));
    } finally {
      setIsLoading(false);
    }
  };

  const handleBack = () => {
    cancelChallenge();
    setCode('');
    setError(null);
  };

  const handleClose = () => {
    handleBack();
    onClose();
  };

  return (
    <Modal
      isOpen={isOpen}
      onClose={handleClose}
      title={activeChallenge ? 'Ввод кода подтверждения' : 'Вход в Dating MVP'}
      description={
        activeChallenge
          ? `Код отправлен на номер ${activeChallenge.maskedPhone}`
          : 'Авторизация по номеру телефона через одноразовый OTP'
      }
      maxWidth="md"
    >
      <div className="space-y-4">
        {error && (
          <div className="p-3 bg-rose-50 border border-rose-200 text-rose-700 text-xs rounded-xl flex items-center gap-2">
            <span>{error}</span>
          </div>
        )}

        {!activeChallenge ? (
          <form onSubmit={handleRequestOtp} className="space-y-4">
            <div>
              <label className="block text-xs font-semibold text-zinc-700 mb-1.5">
                Номер телефона
              </label>
              <div className="relative">
                <Smartphone className="w-4 h-4 text-zinc-400 absolute left-3 top-3" />
                <input
                  type="tel"
                  required
                  autoComplete="tel"
                  inputMode="tel"
                  value={phone}
                  onChange={(e) => setPhone(e.target.value)}
                  placeholder="+7 (999) 000-00-00"
                  className="w-full pl-9 pr-3 py-2.5 text-sm bg-zinc-50 border border-zinc-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-zinc-900 focus:bg-white transition-all font-mono"
                />
              </div>
              <p className="text-[11px] text-zinc-500 mt-1.5 flex items-center gap-1">
                <ShieldCheck className="w-3.5 h-3.5 text-emerald-600 shrink-0" />
                Номер не передаётся третьим лицам и не виден в анкете.
              </p>
            </div>

            <Button
              type="submit"
              variant="primary"
              className="w-full py-2.5"
              isLoading={isLoading}
            >
              Получить код <ArrowRight className="w-4 h-4 ml-1" />
            </Button>
          </form>
        ) : (
          <form onSubmit={handleVerifyOtp} className="space-y-4">
            <div>
              <label className="block text-xs font-semibold text-zinc-700 mb-1.5">
                Код из SMS (6 цифр)
              </label>
              <div className="relative">
                <KeyRound className="w-4 h-4 text-zinc-400 absolute left-3 top-3" />
                <input
                  type="text"
                  inputMode="numeric"
                  autoComplete="one-time-code"
                  pattern="\d{6}"
                  title="Введите 6 цифр из SMS"
                  minLength={6}
                  maxLength={6}
                  required
                  autoFocus
                  value={code}
                  onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
                  placeholder="••••••"
                  className="w-full pl-9 pr-3 py-2.5 text-center text-lg tracking-widest font-mono font-semibold bg-zinc-50 border border-zinc-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-zinc-900 focus:bg-white transition-all"
                />
              </div>
            </div>

            {activeChallenge.devMockCode && (
              <div className="p-3 bg-amber-50/80 border border-amber-200 rounded-xl text-amber-900 text-xs leading-relaxed">
                <span className="font-semibold block mb-0.5">Тестовый режим (Mock OTP):</span>
                SMS не отправляется, сервер вернул код:{' '}
                <strong className="font-mono text-zinc-900 bg-amber-100 px-1 py-0.5 rounded">
                  {activeChallenge.devMockCode}
                </strong>
              </div>
            )}

            <div className="flex gap-2">
              <Button
                type="button"
                variant="outline"
                className="w-1/3"
                onClick={handleBack}
              >
                <ArrowLeft className="w-3.5 h-3.5 mr-1" /> Назад
              </Button>
              <Button
                type="submit"
                variant="primary"
                className="w-2/3 py-2.5"
                isLoading={isLoading}
              >
                Войти в аккаунт
              </Button>
            </div>
          </form>
        )}
      </div>
    </Modal>
  );
};
