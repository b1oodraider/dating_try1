import React, { useRef, useState } from 'react';
import { ProfilePhoto } from '../../../core/types';
import { api } from '../../../core/api/client';
import { Button } from '../../../shared/components/Button';
import { ImagePlus, Trash2, Star, AlertCircle } from 'lucide-react';

interface PhotoManagerProps {
  accountId: string;
  photos: ProfilePhoto[];
  onPhotosChange: () => void;
}

export const PhotoManager: React.FC<PhotoManagerProps> = ({
  accountId,
  photos,
  onPhotosChange,
}) => {
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [error, setError] = useState<string | null>(null);
  const [isUploading, setIsUploading] = useState(false);

  const handleFileChange = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;

    setError(null);
    setIsUploading(true);

    try {
      // Validate format
      const allowedTypes = ['image/jpeg', 'image/png', 'image/webp'];
      if (!allowedTypes.includes(file.type)) {
        throw new Error('Поддерживаются только форматы JPEG, PNG и WebP');
      }

      // Validate size (max 5MB)
      if (file.size > 5 * 1024 * 1024) {
        throw new Error('Максимальный размер файла — 5 МБ');
      }

      // Convert to Data URL
      const reader = new FileReader();
      reader.onload = async () => {
        try {
          const dataUrl = reader.result as string;
          await api.addPhoto(accountId, dataUrl, file.type, file.size);
          onPhotosChange();
        } catch (err: unknown) {
          setError(err instanceof Error ? err.message : 'Ошибка при добавлении фото');
        } finally {
          setIsUploading(false);
          if (fileInputRef.current) fileInputRef.current.value = '';
        }
      };
      reader.readAsDataURL(file);
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Ошибка загрузки файла');
      setIsUploading(false);
      if (fileInputRef.current) fileInputRef.current.value = '';
    }
  };

  const handleDelete = async (photoId: string) => {
    try {
      await api.deletePhoto(accountId, photoId);
      onPhotosChange();
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Ошибка удаления фото');
    }
  };

  const handleSetPrimary = async (photoId: string) => {
    try {
      await api.setPrimaryPhoto(accountId, photoId);
      onPhotosChange();
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Ошибка обновления главного фото');
    }
  };

  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <div>
          <h3 className="text-xs font-semibold text-zinc-900">
            Фотографии анкеты ({photos.length}/6)
          </h3>
          <p className="text-[11px] text-zinc-500">
            JPEG, PNG или WebP до 5 МБ. Первое фото используется как основное.
          </p>
        </div>
        {photos.length < 6 && (
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={() => fileInputRef.current?.click()}
            isLoading={isUploading}
          >
            <ImagePlus className="w-3.5 h-3.5 mr-1" /> Добавить
          </Button>
        )}
      </div>

      <input
        ref={fileInputRef}
        type="file"
        accept="image/jpeg,image/png,image/webp"
        className="hidden"
        onChange={handleFileChange}
      />

      {error && (
        <div className="p-2.5 bg-rose-50 border border-rose-200 text-rose-700 text-xs rounded-xl flex items-center gap-2">
          <AlertCircle className="w-4 h-4 shrink-0" />
          <span>{error}</span>
        </div>
      )}

      {photos.length === 0 ? (
        <div
          onClick={() => fileInputRef.current?.click()}
          className="border-2 border-dashed border-zinc-200 hover:border-zinc-300 rounded-2xl p-6 text-center cursor-pointer transition-colors bg-zinc-50/50"
        >
          <ImagePlus className="w-8 h-8 text-zinc-400 mx-auto mb-2" />
          <p className="text-xs font-medium text-zinc-700">Загрузить первую фотографию</p>
          <p className="text-[11px] text-zinc-400 mt-0.5">Лицо или образ без посторонних</p>
        </div>
      ) : (
        <div className="grid grid-cols-2 sm:grid-cols-3 gap-3">
          {photos.map((photo) => (
            <div
              key={photo.id}
              className="group relative aspect-3/4 rounded-xl overflow-hidden border border-zinc-200 bg-zinc-100 shadow-xs"
            >
              <img
                src={photo.url}
                alt="Фото анкеты"
                className="w-full h-full object-cover"
              />

              {photo.isPrimary && (
                <div className="absolute top-2 left-2 bg-zinc-900/80 backdrop-blur-xs text-white text-[10px] font-medium px-2 py-0.5 rounded-full flex items-center gap-1 shadow-sm">
                  <Star className="w-3 h-3 fill-amber-400 text-amber-400" />
                  Главное
                </div>
              )}

              {/* Hover actions */}
              <div className="absolute inset-0 bg-zinc-900/40 opacity-0 group-hover:opacity-100 transition-opacity flex items-center justify-center gap-2 p-2">
                {!photo.isPrimary && (
                  <button
                    type="button"
                    onClick={() => handleSetPrimary(photo.id)}
                    title="Сделать главным фото"
                    className="p-2 bg-white text-zinc-700 hover:text-amber-600 rounded-lg shadow-sm transition-colors"
                  >
                    <Star className="w-4 h-4" />
                  </button>
                )}
                <button
                  type="button"
                  onClick={() => handleDelete(photo.id)}
                  title="Удалить фото"
                  className="p-2 bg-white text-zinc-700 hover:text-rose-600 rounded-lg shadow-sm transition-colors"
                >
                  <Trash2 className="w-4 h-4" />
                </button>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
};
