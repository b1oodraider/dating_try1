import React, { useState } from 'react';
import { getErrorMessage } from '../../../core/api/client';
import { Button } from '../../../shared/components/Button';
import { Clock, Sparkles, X, AlertCircle } from 'lucide-react';

/** Must match the backend limit for dailyStatusText. */
const MAX_STATUS_LENGTH = 200;

interface DailyStatusEditorProps {
  statusText: string | null;
  /** Already null on the server when the status has expired. */
  expiresAt: string | null;
  /** Saves the status; "" clears it. Rejects on failure. */
  onSave: (text: string) => Promise<unknown>;
}

export const DailyStatusEditor: React.FC<DailyStatusEditorProps> = ({
  statusText,
  expiresAt,
  onSave,
}) => {
  const [isEditing, setIsEditing] = useState(false);
  const [text, setText] = useState(statusText ?? '');
  const [isSaving, setIsSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // The server already nulls expired statuses; this guards a page left open past the expiry.
  const isExpired = expiresAt !== null && new Date(expiresAt).getTime() <= Date.now();
  const activeStatus = statusText && !isExpired ? statusText : null;

  const save = async (value: string) => {
    setIsSaving(true);
    setError(null);
    try {
      await onSave(value);
      setIsEditing(false);
    } catch (err: unknown) {
      setError(getErrorMessage(err, 'Не удалось сохранить статус'));
    } finally {
      setIsSaving(false);
    }
  };

  const handleSave = () => {
    void save(text.trim());
  };

  const handleClear = () => {
    setText('');
    void save('');
  };

  return (
    <div className="p-3.5 bg-zinc-50 border border-zinc-200 rounded-xl space-y-2">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-1.5 text-xs font-semibold text-zinc-800">
          <Sparkles className="w-3.5 h-3.5 text-amber-500" />
          <span>Статус на сегодня (24 часа)</span>
        </div>
        {activeStatus && !isEditing && (
          <button
            type="button"
            onClick={handleClear}
            className="text-zinc-400 hover:text-zinc-600 text-xs p-1"
            disabled={isSaving}
            title="Сбросить статус"
          >
            <X className="w-3.5 h-3.5" />
          </button>
        )}
      </div>

      {error && (
        <p role="alert" className="text-[11px] text-rose-600 flex items-center gap-1">
          <AlertCircle className="w-3 h-3 shrink-0" /> {error}
        </p>
      )}

      {!isEditing ? (
        <div>
          {activeStatus ? (
            <div className="flex items-start justify-between gap-2">
              <p className="text-xs text-zinc-800 italic">«{activeStatus}»</p>
              <button
                type="button"
                onClick={() => {
                  setText(activeStatus);
                  setError(null);
                  setIsEditing(true);
                }}
                className="text-[11px] text-zinc-500 hover:text-zinc-900 underline shrink-0"
              >
                Изменить
              </button>
            </div>
          ) : (
            <div className="flex items-center justify-between">
              <p className="text-xs text-zinc-400">
                Поделитесь планами на вечер или настроением
              </p>
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => {
                  setText('');
                  setError(null);
                  setIsEditing(true);
                }}
              >
                Установить
              </Button>
            </div>
          )}
        </div>
      ) : (
        <div className="space-y-2 pt-1">
          <input
            type="text"
            maxLength={MAX_STATUS_LENGTH}
            value={text}
            onChange={(e) => setText(e.target.value)}
            onKeyDown={(e) => {
              // The editor lives inside the profile <form>: Enter must save the status, not submit the form.
              if (e.key === 'Enter') {
                e.preventDefault();
                if (!isSaving) handleSave();
              }
            }}
            placeholder="Например: Иду пить кофе в оранжерею в 18:00"
            className="w-full px-3 py-1.5 text-xs bg-white border border-zinc-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-zinc-900"
          />
          <div className="flex items-center justify-between text-[11px] text-zinc-400">
            <span className="flex items-center gap-1">
              <Clock className="w-3 h-3" /> Автоматически исчезнет через 24 ч
            </span>
            <div className="flex gap-1.5">
              <Button
                type="button"
                variant="ghost"
                size="sm"
                onClick={() => {
                  setIsEditing(false);
                  setError(null);
                }}
                disabled={isSaving}
              >
                Отмена
              </Button>
              <Button
                type="button"
                variant="primary"
                size="sm"
                onClick={handleSave}
                isLoading={isSaving}
              >
                Сохранить
              </Button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
