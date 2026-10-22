import { useCallback, useEffect, useMemo, useState } from 'react';
import { api, getErrorMessage } from '../../../core/api/client';
import type { Tag, TagCategory } from '../../../core/types';

export interface TagCategoryGroup {
  id: TagCategory;
  label: string;
  tags: Tag[];
}

// Tags are a small, rarely changing dictionary: fetch once per page load and share.
let cachedTags: Tag[] | null = null;
let inflight: Promise<Tag[]> | null = null;

function loadTags(): Promise<Tag[]> {
  if (cachedTags) return Promise.resolve(cachedTags);
  if (!inflight) {
    inflight = api
      .getTags()
      .then((tags) => {
        cachedTags = [...tags].sort((a, b) => a.sortOrder - b.sortOrder);
        return cachedTags;
      })
      .finally(() => {
        inflight = null;
      });
  }
  return inflight;
}

/** Groups tags by category; categories are ordered by the minimal sortOrder of their tags. */
export function groupTagsByCategory(tags: Tag[]): TagCategoryGroup[] {
  const groups = new Map<TagCategory, TagCategoryGroup & { minSort: number }>();
  for (const tag of tags) {
    const group = groups.get(tag.category);
    if (group) {
      group.tags.push(tag);
      group.minSort = Math.min(group.minSort, tag.sortOrder);
    } else {
      groups.set(tag.category, {
        id: tag.category,
        label: tag.categoryLabel,
        tags: [tag],
        minSort: tag.sortOrder,
      });
    }
  }
  return [...groups.values()]
    .sort((a, b) => a.minSort - b.minSort)
    .map(({ id, label, tags: groupTags }) => ({
      id,
      label,
      // A scale reads from one end to the other; other categories keep the editors' order.
      tags: [...groupTags].sort((a, b) => (a.scalePosition ?? a.sortOrder) - (b.scalePosition ?? b.sortOrder)),
    }));
}

export function useTags() {
  const [tags, setTags] = useState<Tag[]>(cachedTags ?? []);
  const [isLoading, setIsLoading] = useState<boolean>(cachedTags === null);
  const [error, setError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    if (cachedTags) {
      setTags(cachedTags);
      setIsLoading(false);
      return;
    }
    let cancelled = false;
    setIsLoading(true);
    setError(null);
    loadTags()
      .then((loaded) => {
        if (!cancelled) setTags(loaded);
      })
      .catch((e: unknown) => {
        if (!cancelled) setError(getErrorMessage(e, 'Не удалось загрузить карточки общения.'));
      })
      .finally(() => {
        if (!cancelled) setIsLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [attempt]);

  const reload = useCallback(() => setAttempt((n) => n + 1), []);
  const categories = useMemo(() => groupTagsByCategory(tags), [tags]);

  return { tags, categories, isLoading, error, reload };
}
