import React, { useRef, useState } from 'react';
import type { Photo } from '../../../core/types';
import { api, getErrorMessage } from '../../../core/api/client';
import { Button } from '../../../shared/components/Button';
import { ImagePlus, Trash2, Star, AlertCircle } from 'lucide-react';

const MAX_PHOTOS = 6;
const MAX_PHOTO_BYTES = 5 * 1024 * 1024;
// UX pre-check only: the server validates the real content type by magic bytes.
const ALLOWED_TYPES = ['image/jpeg', 'image/png', 'image/webp'];

interface PhotoManagerProps {
  photos: Photo[];
  /** Re-fetches the profile so the photo list (and its signed URLs) is up to date. */
  onPhotosChange: () => Promise<unknown>;
}

export const PhotoManager: React.FC<PhotoManagerProps> = ({ photos: photosProp, onPhotosChange }) => {
  const photos = photosProp ?? [];
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [error, setError] = useState<string | null>(null);
  const [isUploading, setIsUploading] = useState(false);
  const [busyPhotoId, setBusyPhotoId] = useState<string | null>(null);

  const resetFileInput = () => {
    if (fileInputRef.current) fileInputRef.current.value = '';
  };

  const handleFileChange = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;

    setError(null);

    if (!ALLOWED_TYPES.includes(file.type)) {
      setError('Поддерживаются только форматы JPEG, PNG и WebP');
      resetFileInput();
      return;
    }
    if (file.size > MAX_PHOTO_BYTES) {
      setError('Максимальный размер файла — 5 МБ');
      resetFileInput();
      return;
    }

    setIsUploading(true);
    try {
      await api.uploadPhoto(file);
      await onPhotosChange();
    } catch (err: unknown) {
      setError(getErrorMessage(err, 'Ошибка при добавлении фото'));
    } finally {
      setIsUploading(false);
      resetFileInput();
    }
  };

  const handleDelete = async (photoId: string) => {
    setError(null);
    setBusyPhotoId(photoId);
    try {
      await api.deletePhoto(photoId);
      await onPhotosChange();
    } catch (err: unknown) {
      setError(getErrorMessage(err, 'Ошибка удаления фото'));
    } finally {
      setBusyPhotoId(null);
    }
  };

  const handleSetPrimary = async (photoId: string) => {
    setError(null);
    setBusyPhotoId(photoId);
    try {
      await api.setPrimaryPhoto(photoId);
      await onPhotosChange();
    } catch (err: unknown) {
      setError(getErrorMessage(err, 'Ошибка обновления главного фото'));
    } finally {
      setBusyPhotoId(null);
    }
  };

  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <div>
          <h3 className="text-xs font-semibold text-zinc-900">
            Фотографии анкеты ({photos.length}/{MAX_PHOTOS})
          </h3>
          <p className="text-[11px] text-zinc-500">
            JPEG, PNG или WebP до 5 МБ. Главное фото показывается в ленте.
          </p>
        </div>
        {photos.length < MAX_PHOTOS && (
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
          onClick={() => {
            if (!isUploading) fileInputRef.current?.click();
          }}
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
              className={`group relative aspect-3/4 rounded-xl overflow-hidden border border-zinc-200 bg-zinc-100 shadow-xs ${
                busyPhotoId === photo.id ? 'opacity-60 pointer-events-none' : ''
              }`}
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

              {photo.underReview && (
                <div
                  title="Лицо не совпало с подтверждёнными фото. Другие увидят фото после проверки модератором"
                  className="absolute bottom-0 inset-x-0 bg-amber-500/90 text-white text-[10px] leading-tight px-2 py-1"
                >
                  <div className="font-semibold">На проверке</div>
                  <div className="hidden sm:block">Лицо не совпало с подтверждёнными фото. Другие увидят фото после проверки модератором</div>
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
