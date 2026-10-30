import React, { useCallback, useEffect, useState } from 'react';
import { Check, ChevronRight, Plus, Search, SearchCheck } from 'lucide-react';
import type { Community } from '../../core/types';
import { api, getErrorMessage } from '../../core/api/client';
import { Modal } from '../../shared/components/Modal';
import { Button } from '../../shared/components/Button';
import { useToast } from '../../shared/components/Toast';

/** Group kinds in display order (docs/communities.md). */
export const COMMUNITY_KINDS: Record<string, string> = {
  FRANCHISE: 'Вселенные',
  FILM: 'Фильмы',
  SERIES: 'Сериалы',
  BOOK: 'Книги',
  ANIME: 'Аниме',
  GAME: 'Игры',
  MUSIC: 'Музыка',
  OTHER: 'Другое',
};

/** «1 участник», «3 участника», «12 участников». */
export function membersText(n: number): string {
  const last = n % 10;
  const lastTwo = n % 100;
  const word =
    last === 1 && lastTwo !== 11
      ? 'участник'
      : last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14)
        ? 'участника'
        : 'участников';
  return `${n} ${word}`;
}

const display = (c: Community) => (c.emoji ? `${c.emoji} ${c.title}` : c.title);

interface CommunitiesViewProps {
  /** «Начать поиск» on a group: the feed among its members. */
  onSearch: (community: Community) => void;
}

/** «Интересы»: interest groups — search, kinds, my groups; a group opens its card. */
export const CommunitiesView: React.FC<CommunitiesViewProps> = ({ onSearch }) => {
  const [query, setQuery] = useState('');
  const [kind, setKind] = useState<string | null>(null);
  const [items, setItems] = useState<Community[]>([]);
  const [mine, setMine] = useState<Community[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [nonce, setNonce] = useState(0);
  const [opened, setOpened] = useState<Community | null>(null);
  const [isSuggestOpen, setIsSuggestOpen] = useState(false);

  const reload = useCallback(() => setNonce((n) => n + 1), []);

  useEffect(() => {
    const controller = new AbortController();
    const timer = setTimeout(() => {
      Promise.all([
        api.listCommunities({ q: query, kind: kind ?? undefined }, controller.signal),
        api.getMyCommunities(controller.signal),
      ])
        .then(([found, my]) => {
          setItems(found);
          setMine(my);
          setError(null);
        })
        .catch((e: unknown) => {
          if (!controller.signal.aborted) setError(getErrorMessage(e, 'Не удалось загрузить группы'));
        })
        .finally(() => {
          if (!controller.signal.aborted) setIsLoading(false);
        });
    }, 300);
    return () => {
      clearTimeout(timer);
      controller.abort();
    };
  }, [query, kind, nonce]);

  const browsing = query.trim() === '' && kind === null;
  const chip = (selected: boolean) =>
    `px-3 py-1.5 rounded-full text-xs font-medium border transition-colors whitespace-nowrap ${
      selected ? 'bg-zinc-900 text-white border-zinc-900' : 'bg-white text-zinc-700 border-zinc-200 hover:border-zinc-400'
    }`;

  return (
    <div className="max-w-3xl mx-auto space-y-5 pb-12">
      <div className="flex items-center justify-between gap-3">
        <div>
          <h1 className="text-xl font-bold text-zinc-900">Интересы</h1>
          <p className="text-xs text-zinc-500">Группы по фильмам, книгам, сериалам и играм: подпишитесь и ищите своих.</p>
        </div>
        <Button type="button" variant="outline" size="sm" onClick={() => setIsSuggestOpen(true)}>
          <Plus className="w-3.5 h-3.5" /> Предложить группу
        </Button>
      </div>

      <label className="flex items-center gap-2 bg-white border border-zinc-200 rounded-xl px-3 py-2 focus-within:ring-2 focus-within:ring-zinc-900">
        <Search className="w-4 h-4 text-zinc-400" />
        <input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Фильм, книга, сериал, игра…"
          aria-label="Поиск групп"
          className="flex-1 text-sm bg-transparent focus:outline-none"
        />
      </label>

      <div className="flex flex-wrap gap-1.5">
        {[[null, 'Все'] as const, ...Object.entries(COMMUNITY_KINDS)].map(([code, label]) => (
          <button key={label} type="button" aria-pressed={kind === code} onClick={() => setKind(code)} className={chip(kind === code)}>
            {label}
          </button>
        ))}
      </div>

      {browsing && mine.length > 0 && (
        <section className="space-y-2">
          <h2 className="text-sm font-bold text-zinc-900">Мои группы</h2>
          <div className="flex flex-wrap gap-1.5">
            {mine.map((c) => (
              <button key={c.id} type="button" onClick={() => setOpened(c)} className={chip(false)}>
                {display(c)}
              </button>
            ))}
          </div>
        </section>
      )}

      <section className="space-y-2">
        <h2 className="text-sm font-bold text-zinc-900">{browsing ? 'Популярные' : 'Найдено'}</h2>
        {isLoading ? (
          <p className="text-xs text-zinc-400">Загрузка...</p>
        ) : error ? (
          <div className="p-3 bg-rose-50 border border-rose-200 text-rose-700 text-xs rounded-xl flex items-center justify-between gap-2">
            {error}
            <Button type="button" variant="outline" size="sm" onClick={reload}>
              Повторить
            </Button>
          </div>
        ) : items.length === 0 ? (
          <div className="text-xs text-zinc-500 space-y-2">
            <p>Такой группы пока нет.</p>
            <Button type="button" variant="outline" size="sm" onClick={() => setIsSuggestOpen(true)}>
              <Plus className="w-3.5 h-3.5" /> Предложить группу
            </Button>
          </div>
        ) : (
          <ul className="bg-white border border-zinc-200/80 rounded-2xl divide-y divide-zinc-100 overflow-hidden">
            {items.map((c) => (
              <li key={c.id}>
                <button
                  type="button"
                  onClick={() => setOpened(c)}
                  className="w-full flex items-center gap-3 px-4 py-3 text-left hover:bg-zinc-50 transition-colors"
                >
                  <span className="w-10 h-10 rounded-full bg-zinc-100 flex items-center justify-center text-xl shrink-0">
                    {c.emoji ?? '✨'}
                  </span>
                  <span className="flex-1 min-w-0">
                    <span className="block text-sm font-semibold text-zinc-900 truncate">{c.title}</span>
                    <span className="block text-xs text-zinc-500">
                      {c.kindLabel} · {membersText(c.members)}
                    </span>
                  </span>
                  {c.subscribed ? (
                    <Check className="w-4 h-4 text-emerald-600" aria-label="Вы подписаны" />
                  ) : (
                    <ChevronRight className="w-4 h-4 text-zinc-400" />
                  )}
                </button>
              </li>
            ))}
          </ul>
        )}
      </section>

      {opened && (
        <CommunityModal
          community={opened}
          onClose={() => {
            setOpened(null);
            reload();
          }}
          onSearch={onSearch}
        />
      )}
      {isSuggestOpen && <SuggestModal onClose={() => setIsSuggestOpen(false)} />}
    </div>
  );
};

/** A group: description, subscribe, «Начать поиск» among its members. */
const CommunityModal: React.FC<{
  community: Community;
  onClose: () => void;
  onSearch: (community: Community) => void;
}> = ({ community: initial, onClose, onSearch }) => {
  const { showToast } = useToast();
  const [community, setCommunity] = useState(initial);
  const [isBusy, setIsBusy] = useState(false);

  const toggle = async () => {
    setIsBusy(true);
    try {
      setCommunity(await api.setCommunitySubscription(community.id, !community.subscribed));
    } catch (e) {
      showToast(getErrorMessage(e, 'Не удалось изменить подписку'), 'error');
    } finally {
      setIsBusy(false);
    }
  };

  const c = community;
  return (
    <Modal isOpen onClose={onClose} maxWidth="md">
      <div className="space-y-4">
        <div className="text-5xl">{c.emoji ?? '✨'}</div>
        <div>
          <h2 className="text-xl font-bold text-zinc-900">{c.title}</h2>
          <p className="text-xs text-zinc-500">
            {c.kindLabel} · {membersText(c.members)}
          </p>
        </div>
        {c.description && <p className="text-sm text-zinc-700">{c.description}</p>}
        <p className="text-xs text-zinc-500">
          {c.subscribed
            ? 'Группа видна в вашей анкете, а в фильтрах ленты можно искать среди её участников.'
            : 'Подпишитесь — группа появится в анкете, и люди из неё увидят, что у вас есть общее.'}
        </p>
        <div className="flex flex-col gap-2">
          <Button type="button" variant={c.subscribed ? 'outline' : 'primary'} disabled={isBusy} onClick={() => void toggle()}>
            {c.subscribed ? (
              <>
                <Check className="w-4 h-4" /> Вы подписаны
              </>
            ) : (
              'Подписаться'
            )}
          </Button>
          <Button type="button" variant="secondary" onClick={() => onSearch(c)}>
            <SearchCheck className="w-4 h-4" /> Начать поиск
          </Button>
        </div>
      </div>
    </Modal>
  );
};

/** «Предложить группу»: a title and why; staff decide. */
const SuggestModal: React.FC<{ onClose: () => void }> = ({ onClose }) => {
  const { showToast } = useToast();
  const [title, setTitle] = useState('');
  const [comment, setComment] = useState('');
  const [isSending, setIsSending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const send = async () => {
    if (title.trim().length < 2) {
      setError('Напишите название');
      return;
    }
    setIsSending(true);
    setError(null);
    try {
      await api.suggestCommunity(title.trim(), comment);
      showToast('Спасибо! Мы посмотрим и, возможно, добавим группу', 'success');
      onClose();
    } catch (e) {
      setError(getErrorMessage(e, 'Не удалось отправить'));
    } finally {
      setIsSending(false);
    }
  };

  const input = 'w-full text-sm bg-zinc-50 border border-zinc-200 rounded-xl px-3 py-2 focus:outline-none focus:ring-2 focus:ring-zinc-900';
  return (
    <Modal isOpen onClose={onClose} title="Предложить группу" description="Мы проверим и добавим её в каталог" maxWidth="md">
      <div className="space-y-3">
        <input value={title} onChange={(e) => setTitle(e.target.value)} maxLength={80} placeholder="Название" aria-label="Название" className={input} />
        <textarea
          value={comment}
          onChange={(e) => setComment(e.target.value)}
          maxLength={300}
          rows={3}
          placeholder="Почему её стоит добавить (необязательно)"
          aria-label="Комментарий"
          className={input}
        />
        {error && <p className="text-xs text-rose-600">{error}</p>}
        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={onClose}>
            Отмена
          </Button>
          <Button type="button" disabled={isSending} onClick={() => void send()}>
            Отправить
          </Button>
        </div>
      </div>
    </Modal>
  );
};
