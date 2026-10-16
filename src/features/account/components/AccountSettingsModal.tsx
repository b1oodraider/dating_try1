import React, { useState } from 'react';
import { Modal } from '../../../shared/components/Modal';
import { Button } from '../../../shared/components/Button';
import { useAuth } from '../../../core/context/AuthContext';
import { getErrorMessage } from '../../../core/api/client';
import { ShieldCheck, Trash2, LogOut, AlertTriangle, Key, Calendar } from 'lucide-react';

interface AccountSettingsModalProps {
  isOpen: boolean;
  onClose: () => void;
}

export const AccountSettingsModal: React.FC<AccountSettingsModalProps> = ({
  isOpen,
  onClose,
}) => {
  const { account, logout, deleteAccount } = useAuth();
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false);
  const [isDeleting, setIsDeleting] = useState(false);
  const [isLoggingOut, setIsLoggingOut] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  if (!account) return null;

  const handleClose = () => {
    setShowDeleteConfirm(false);
    setDeleteError(null);
    onClose();
  };

  const handleLogout = async () => {
    setIsLoggingOut(true);
    try {
      // logout() never throws: local state is cleared even if the server is unreachable.
      await logout();
    } finally {
      setIsLoggingOut(false);
      handleClose();
    }
  };

  const handleDeleteAccount = async () => {
    setIsDeleting(true);
    setDeleteError(null);
    try {
      await deleteAccount();
      handleClose();
    } catch (err: unknown) {
      setDeleteError(getErrorMessage(err, 'Не удалось удалить аккаунт. Попробуйте ещё раз.'));
    } finally {
      setIsDeleting(false);
    }
  };

  const statusLabel =
    account.status === 'ACTIVE'
      ? 'Подтверждён (Активен)'
      : account.status === 'SUSPENDED'
      ? 'Приостановлен'
      : 'Удалён';

  return (
    <Modal
      isOpen={isOpen}
      onClose={handleClose}
      title="Настройки аккаунта и безопасность"
      description="Управление сессией, приватностью и данными"
      maxWidth="md"
    >
      <div className="space-y-5">
        {/* Account Info */}
        <div className="p-4 bg-zinc-50 border border-zinc-200/80 rounded-xl space-y-3">
          <div className="flex items-center justify-between text-xs">
            <span className="text-zinc-500">Номер телефона:</span>
            <span className="font-mono font-bold text-zinc-900 bg-white px-2 py-0.5 rounded border border-zinc-200">
              {account.phoneMasked}
            </span>
          </div>

          <div className="flex items-center justify-between text-xs">
            <span className="text-zinc-500">Статус аккаунта:</span>
            <span className="text-emerald-700 font-semibold flex items-center gap-1">
              <ShieldCheck className="w-3.5 h-3.5" /> {statusLabel}
            </span>
          </div>

          <div className="flex items-center justify-between text-xs">
            <span className="text-zinc-500">Дата регистрации:</span>
            <span className="text-zinc-600 font-mono">
              {new Date(account.createdAt).toLocaleDateString('ru-RU')}
            </span>
          </div>
        </div>

        {/* Security & Data Retention Notice */}
        <div className="p-3.5 bg-stone-50 border border-stone-200 rounded-xl text-xs text-stone-600 space-y-1.5 leading-relaxed">
          <div className="font-semibold text-stone-800 flex items-center gap-1.5">
            <Key className="w-3.5 h-3.5 text-stone-600" />
            Политика данных MVP:
          </div>
          <p>
            • Ваш номер телефона замаскирован и не отображается в профиле.<br />
            • Тексты анкеты защищены и не используются для обучения AI.<br />
            • Фотографии выдаются только по временным подписанным ссылкам.
          </p>
        </div>

        {/* Actions */}
        <div className="pt-2 border-t border-zinc-100 flex flex-col gap-2.5">
          <Button
            type="button"
            variant="outline"
            className="w-full justify-start text-xs"
            onClick={handleLogout}
            isLoading={isLoggingOut}
          >
            <LogOut className="w-3.5 h-3.5 mr-2" /> Завершить сеанс (Выйти)
          </Button>

          {!showDeleteConfirm ? (
            <button
              type="button"
              onClick={() => setShowDeleteConfirm(true)}
              className="text-xs text-rose-600 hover:text-rose-700 py-2 text-left transition-colors flex items-center gap-1.5"
            >
              <Trash2 className="w-3.5 h-3.5" /> Запросить удаление аккаунта
            </button>
          ) : (
            <div className="p-3 bg-rose-50 border border-rose-200 rounded-xl space-y-2.5">
              <div className="flex items-start gap-2 text-rose-800 text-xs font-semibold">
                <AlertTriangle className="w-4 h-4 text-rose-600 shrink-0 mt-0.5" />
                <span>Вы уверены, что хотите удалить аккаунт?</span>
              </div>
              <p className="text-[11px] text-rose-700 leading-relaxed">
                Все ваши данные: анкета, фотографии, карточки предпочтений и история сессий будут безвозвратно удалены.
              </p>
              {deleteError && (
                <p role="alert" className="text-[11px] font-semibold text-rose-800">
                  {deleteError}
                </p>
              )}
              <div className="flex gap-2 pt-1">
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => {
                    setShowDeleteConfirm(false);
                    setDeleteError(null);
                  }}
                >
                  Отмена
                </Button>
                <Button
                  type="button"
                  variant="danger"
                  size="sm"
                  isLoading={isDeleting}
                  onClick={handleDeleteAccount}
                >
                  Да, удалить всё
                </Button>
              </div>
            </div>
          )}
        </div>
      </div>
    </Modal>
  );
};
