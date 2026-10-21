import React, { useState } from 'react';
import { api, ApiError, getErrorMessage } from '../../../core/api/client';
import type { PhotoReviewItem } from '../../../core/types';
import { Button } from '../../../shared/components/Button';
import { useToast } from '../../../shared/components/Toast';
import { cardClass, formatTs, LoadState, Pager, useLoad } from './shared';

/** New photos of verified people whose face did not match their reference photos. */
export const PhotoReviewsPanel: React.FC<{ onOpenAccount: (accountId: string) => void }> = ({ onOpenAccount }) => {
  const [page, setPage] = useState(0);
  const list = useLoad((signal) => api.admin.listPhotoReviews({ page }, signal), [page]);

  const onDecided = (photoId: string) => {
    const data = list.data;
    if (!data) return;
    const items = data.items.filter((i) => i.photoId !== photoId);
    if (items.length > 0) {
      list.setData({ ...data, items, totalElements: data.totalElements - 1 });
    } else if (page > 0) {
      setPage(page - 1);
    } else {
      list.reload();
    }
  };

  return (
    <LoadState state={list} empty="Фото на проверке нет." isEmpty={(d) => d.items.length === 0}>
      {(data) => (
        <div className="space-y-3">
          <ul className="space-y-3">
            {data.items.map((item) => (
              <li key={item.photoId}>
                <PhotoReviewCard item={item} onDecided={onDecided} onOpenAccount={onOpenAccount} />
              </li>
            ))}
          </ul>
          <Pager page={data.page} limit={data.limit} total={data.totalElements} onPage={setPage} />
        </div>
      )}
    </LoadState>
  );
};

const imgClass = 'w-full aspect-[3/4] object-cover rounded-xl bg-zinc-100';

const PhotoReviewCard: React.FC<{
  item: PhotoReviewItem;
  onDecided: (photoId: string) => void;
  onOpenAccount: (accountId: string) => void;
}> = ({ item, onDecided, onOpenAccount }) => {
  const { showToast } = useToast();
  const [busy, setBusy] = useState(false);

  const decide = async (approve: boolean) => {
    if (!approve && !window.confirm('Удалить это фото? Отменить будет нельзя.')) return;
    setBusy(true);
    try {
      await api.admin.decidePhotoReview(item.photoId, approve);
      showToast(approve ? 'Фото одобрено' : 'Фото удалено', 'success');
      onDecided(item.photoId);
    } catch (e) {
      showToast(getErrorMessage(e), 'error');
      // Already decided by someone else or deleted: drop it from the queue.
      if (e instanceof ApiError && (e.code === 'PHOTO_NOT_UNDER_REVIEW' || e.code === 'PHOTO_NOT_FOUND')) onDecided(item.photoId);
      else setBusy(false);
    }
  };

  return (
    <div className={cardClass}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="min-w-0">
          <div className="text-sm font-semibold text-zinc-900 truncate">{item.displayName ?? 'Без имени'}</div>
          <div className="text-xs text-zinc-500">Загружено {formatTs(item.uploadedAt)}</div>
        </div>
        <Button type="button" size="sm" variant="outline" onClick={() => onOpenAccount(item.accountId)}>
          Аккаунт
        </Button>
      </div>
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
        <div className="space-y-1">
          <h4 className="text-xs font-semibold text-zinc-500">Новое фото</h4>
          <img src={item.photo.url} alt="Новое фото" className={`${imgClass} ring-2 ring-amber-400`} />
        </div>
        <div className="sm:col-span-2 space-y-1">
          <h4 className="text-xs font-semibold text-zinc-500">Подтверждённые фото</h4>
          {item.references.length === 0 ? (
            <p className="text-xs text-zinc-500">Подтверждённых фото нет.</p>
          ) : (
            <div className="grid grid-cols-2 sm:grid-cols-3 gap-2">
              {item.references.map((p) => (
                <img key={p.id} src={p.url} alt="Подтверждённое фото" className={imgClass} />
              ))}
            </div>
          )}
        </div>
      </div>
      <div className="flex flex-wrap gap-2">
        <Button type="button" isLoading={busy} onClick={() => void decide(true)}>
          Тот же человек
        </Button>
        <Button type="button" variant="danger" isLoading={busy} onClick={() => void decide(false)}>
          Другой человек — удалить
        </Button>
      </div>
    </div>
  );
};
