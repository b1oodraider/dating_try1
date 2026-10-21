import React, { useState } from 'react';
import { Modal } from '../../../shared/components/Modal';
import { Button } from '../../../shared/components/Button';
import { api, getErrorMessage } from '../../../core/api/client';
import type { ReportReason } from '../../../core/types';
import { AlertTriangle, CheckCircle2 } from 'lucide-react';

interface ReportModalProps {
  isOpen: boolean;
  onClose: () => void;
  profileId: string;
  profileName: string;
  messageId?: string;
}

export const REPORT_REASONS: Array<{ value: ReportReason; label: string; desc: string }> = [
  { value: 'SPAM', label: 'Спам или реклама', desc: 'Ссылки, навязчивые предложения или бот-активность' },
  { value: 'FAKE_PROFILE', label: 'Фейковая анкета', desc: 'Чужие фотографии, выдает себя за другого' },
  { value: 'INAPPROPRIATE_CONTENT', label: 'Неприемлемый контент', desc: 'Оскорбления, непристойные фото или текст' },
  { value: 'HARASSMENT', label: 'Домогательства / Угрозы', desc: 'Агрессия, преследование или угрозы' },
  { value: 'SCAM', label: 'Мошенничество', desc: 'Просьбы перевести деньги, вымогательство' },
  { value: 'UNDERAGE', label: 'Несовершеннолетний', desc: 'Пользователю меньше 18 лет' },
  { value: 'NOT_RESPONDING', label: 'Не отвечает на сообщения', desc: 'Молчание в диалоге' },
  { value: 'OTHER', label: 'Другая причина', desc: 'Опишите проблему в комментарии' },
];

export const ReportModal: React.FC<ReportModalProps> = ({
  isOpen,
  onClose,
  profileId,
  profileName,
  messageId,
}) => {
  const [reason, setReason] = useState<ReportReason>('SPAM');
  const [comment, setComment] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [successNote, setSuccessNote] = useState<string | null>(null);

  const close = () => {
    setSuccessNote(null);
    setComment('');
    setError(null);
    onClose();
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (isSubmitting) return;
    setIsSubmitting(true);
    setError(null);
    try {
      const res = await api.reportProfile({
        profileId,
        messageId,
        reason,
        comment: comment.trim() || undefined,
      });

      if (res.status === 'DISMISSED' && res.note) {
        setSuccessNote(res.note);
      } else {
        setSuccessNote('Жалоба отправлена на рассмотрение модераторам.');
      }
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <Modal
      isOpen={isOpen}
      onClose={close}
      title={`Пожаловаться на ${profileName}`}
      description="Модераторы проверят профиль на соответствие правилам сообщества"
      maxWidth="md"
    >
      {successNote ? (
        <div className="py-6 text-center space-y-3">
          <div className="w-12 h-12 bg-emerald-50 text-emerald-600 rounded-full flex items-center justify-center mx-auto">
            <CheckCircle2 className="w-6 h-6" />
          </div>
          <h3 className="text-sm font-bold text-zinc-900">Спасибо за сигнал</h3>
          <p className="text-xs text-zinc-600 max-w-sm mx-auto leading-relaxed">{successNote}</p>
          <Button type="button" variant="outline" size="sm" onClick={close}>
            Готово
          </Button>
        </div>
      ) : (
        <form onSubmit={handleSubmit} className="space-y-4">
          {error && (
            <div className="p-3 bg-rose-50 border border-rose-200 text-rose-700 text-xs rounded-xl flex items-center gap-2">
              <AlertTriangle className="w-4 h-4 shrink-0" />
              <span>{error}</span>
            </div>
          )}

          <div className="space-y-2">
            <label className="block text-xs font-semibold text-zinc-700">Причина жалобы</label>
            <div className="space-y-1.5 max-h-56 overflow-y-auto pr-1">
              {REPORT_REASONS.map((r) => (
                <label
                  key={r.value}
                  className={`flex items-start gap-2.5 p-2.5 rounded-xl border text-xs cursor-pointer transition-colors ${
                    reason === r.value
                      ? 'border-zinc-900 bg-zinc-50/80 font-medium'
                      : 'border-zinc-200 hover:border-zinc-300'
                  }`}
                >
                  <input
                    type="radio"
                    name="reportReason"
                    value={r.value}
                    checked={reason === r.value}
                    onChange={() => setReason(r.value)}
                    className="mt-0.5"
                  />
                  <div>
                    <div className="text-zinc-900 font-semibold">{r.label}</div>
                    <div className="text-[11px] text-zinc-500">{r.desc}</div>
                  </div>
                </label>
              ))}
            </div>
          </div>

          <div>
            <label className="block text-xs font-semibold text-zinc-700 mb-1">
              Комментарий (необязательно)
            </label>
            <textarea
              rows={3}
              maxLength={1000}
              value={comment}
              onChange={(e) => setComment(e.target.value)}
              placeholder="Дополнительные подробности для модератора..."
              className="w-full p-2.5 text-xs bg-zinc-50 border border-zinc-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-zinc-900"
            />
          </div>

          <div className="flex gap-2 justify-end pt-2">
            <Button type="button" variant="outline" size="sm" onClick={close} disabled={isSubmitting}>
              Отмена
            </Button>
            <Button type="submit" variant="danger" size="sm" isLoading={isSubmitting}>
              Отправить жалобу
            </Button>
          </div>
        </form>
      )}
    </Modal>
  );
};
