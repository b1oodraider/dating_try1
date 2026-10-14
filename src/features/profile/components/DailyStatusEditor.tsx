import React, { useState } from 'react';
import { DailyStatus } from '../../../core/types';
import { Button } from '../../../shared/components/Button';
import { Clock, Sparkles, X } from 'lucide-react';

interface DailyStatusEditorProps {
  currentStatus?: DailyStatus | null;
  onSave: (status: DailyStatus | null) => void;
}

export const DailyStatusEditor: React.FC<DailyStatusEditorProps> = ({
  currentStatus,
  onSave,
}) => {
  const [isEditing, setIsEditing] = useState(false);
  const [text, setText] = useState(currentStatus?.text || '');

  // Check if status is expired
  const isExpired =
    currentStatus?.expiresAt && new Date(currentStatus.expiresAt).getTime() < Date.now();
  const activeStatus = isExpired ? null : currentStatus;

  const handleSave = () => {
    if (!text.trim()) {
      onSave(null);
      setIsEditing(false);
      return;
    }
    const expiresAt = new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString();
    onSave({ text: text.trim(), expiresAt });
    setIsEditing(false);
  };

  const handleClear = () => {
    setText('');
    onSave(null);
    setIsEditing(false);
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
            title="Сбросить статус"
          >
            <X className="w-3.5 h-3.5" />
          </button>
        )}
      </div>

      {!isEditing ? (
        <div>
          {activeStatus ? (
            <div className="flex items-start justify-between gap-2">
              <p className="text-xs text-zinc-800 italic">«{activeStatus.text}»</p>
              <button
                type="button"
                onClick={() => {
                  setText(activeStatus.text);
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
                onClick={() => setIsEditing(true)}
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
            maxLength={100}
            value={text}
            onChange={(e) => setText(e.target.value)}
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
                onClick={() => setIsEditing(false)}
              >
                Отмена
              </Button>
              <Button type="button" variant="primary" size="sm" onClick={handleSave}>
                Сохранить
              </Button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
