import React, { useState, useEffect, useRef, useCallback } from 'react';
import { api, ApiError, getErrorMessage } from '../../../core/api/client';
import type { Match, Message, PublicProfile, Wallet } from '../../../core/types';
import { useRealtime } from '../../../core/realtime/RealtimeContext';
import { Button } from '../../../shared/components/Button';
import { ReportModal } from '../../safety/components/ReportModal';
import { ProfileDetailModal } from '../../discovery/components/ProfileDetailModal';
import { useToast } from '../../../shared/components/Toast';
import {
  MessageCircle,
  Send,
  Smile,
  ShieldAlert,
  UserX,
  MoreVertical,
  CheckCheck,
  Check,
  ChevronLeft,
  Sparkles,
  ShieldCheck,
  Crown,
  Flag,
} from 'lucide-react';

const PAGE_SIZE = 30;

interface ChatViewProps {
  initialMatchId?: string | null;
  onSelectMatchId?: (matchId: string | null) => void;
  /** Every change of the match list (the unread badge in the navbar). */
  onMatchesChanged?: (matches: Match[]) => void;
}

/** Puts the match with this message on top, as GET /matches orders by the latest activity. */
function withLastMessage(matches: Match[], msg: Message, countAsUnread: boolean): Match[] {
  const match = matches.find((m) => m.id === msg.matchId);
  if (!match) return matches;
  const updated = { ...match, lastMessage: msg, unreadCount: match.unreadCount + (countAsUnread ? 1 : 0) };
  return [updated, ...matches.filter((m) => m.id !== msg.matchId)];
}

export const ChatView: React.FC<ChatViewProps> = ({
  initialMatchId,
  onSelectMatchId,
  onMatchesChanged,
}) => {
  const { showToast } = useToast();
  const [matches, setMatches] = useState<Match[]>([]);
  const [selectedMatchId, setSelectedMatchId] = useState<string | null>(initialMatchId ?? null);
  /** Messages of the open chat, oldest first (the API pages newest first). */
  const [messages, setMessages] = useState<Message[]>([]);
  /** `before` cursor for older history; null = everything is loaded. */
  const [olderCursor, setOlderCursor] = useState<string | null>(null);
  const [inputText, setInputText] = useState('');
  const [isLoadingMatches, setIsLoadingMatches] = useState(true);
  const [isLoadingMessages, setIsLoadingMessages] = useState(false);
  const [isLoadingOlder, setIsLoadingOlder] = useState(false);
  const [isSending, setIsSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  /** Bumped on every realtime (re)connect: there is no replay, so the open chat is reloaded. */
  const [reloadKey, setReloadKey] = useState(0);

  // Modals & Drawers
  const [isStickerPickerOpen, setIsStickerPickerOpen] = useState(false);
  const [wallet, setWallet] = useState<Wallet | null>(null);
  const [isMenuOpen, setIsMenuOpen] = useState(false);
  const [report, setReport] = useState<{ messageId?: string } | null>(null);
  const [inspectProfile, setInspectProfile] = useState<PublicProfile | null>(null);

  const messagesEndRef = useRef<HTMLDivElement | null>(null);
  const selectedRef = useRef(selectedMatchId);
  selectedRef.current = selectedMatchId;
  const sendingRef = useRef(false);
  const matchesRef = useRef(matches);
  matchesRef.current = matches;
  /** A text that failed to send keeps its clientMessageId, so a retry cannot store it twice. */
  const unsentText = useRef<{ text: string; clientMessageId: string } | null>(null);

  const selectMatch = useCallback(
    (id: string | null) => {
      setSelectedMatchId(id);
      setIsMenuOpen(false);
      setIsStickerPickerOpen(false);
      onSelectMatchId?.(id);
    },
    [onSelectMatchId]
  );

  const loadMatches = useCallback(async () => {
    try {
      setMatches(await api.listMatches());
      setError(null);
    } catch (e) {
      setError(getErrorMessage(e, 'Не удалось загрузить диалоги'));
    } finally {
      setIsLoadingMatches(false);
    }
  }, []);

  useEffect(() => {
    void loadMatches();
  }, [loadMatches]);

  useEffect(() => {
    onMatchesChanged?.(matches);
  }, [matches, onMatchesChanged]);

  // Load wallet for sticker packs
  useEffect(() => {
    api.getWallet().then(setWallet).catch(() => {});
  }, []);

  const selectedMatch = matches.find((m) => m.id === selectedMatchId) ?? null;

  // The other person unmatched or blocked: the chat is gone for both.
  useEffect(() => {
    if (selectedMatchId && !isLoadingMatches && !matches.some((m) => m.id === selectedMatchId)) {
      selectMatch(null);
    }
  }, [selectedMatchId, isLoadingMatches, matches, selectMatch]);

  /** Moves my read pointer to `newest` when it came from the other person. */
  const markRead = useCallback((matchId: string, newest: Message | undefined) => {
    if (!newest || newest.mine) return;
    setMatches((prev) =>
      prev.map((m) => (m.id === matchId ? { ...m, unreadCount: 0, myLastReadMessageId: newest.id } : m))
    );
    api.markMessagesRead(matchId, newest.id).catch(() => {});
  }, []);

  useEffect(() => {
    if (!selectedMatchId) {
      setMessages([]);
      return;
    }
    let cancelled = false;
    setIsLoadingMessages(true);
    api
      .getMessages(selectedMatchId, undefined, PAGE_SIZE)
      .then((res) => {
        if (cancelled) return;
        setMessages([...res.items].reverse());
        setOlderCursor(res.nextBefore);
        markRead(selectedMatchId, res.items[0]);
      })
      .catch((e: unknown) => {
        if (cancelled) return;
        if (e instanceof ApiError && e.code === 'MATCH_NOT_FOUND') {
          void loadMatches();
        } else {
          setError(getErrorMessage(e, 'Не удалось загрузить сообщения'));
        }
      })
      .finally(() => {
        if (!cancelled) setIsLoadingMessages(false);
      });
    return () => {
      cancelled = true;
    };
  }, [selectedMatchId, reloadKey, markRead, loadMatches]);

  const loadOlder = async () => {
    const matchId = selectedMatchId;
    if (!matchId || !olderCursor || isLoadingOlder) return;
    setIsLoadingOlder(true);
    try {
      const res = await api.getMessages(matchId, olderCursor, PAGE_SIZE);
      if (selectedRef.current !== matchId) return;
      setMessages((prev) => {
        const known = new Set(prev.map((m) => m.id));
        return [...[...res.items].reverse().filter((m) => !known.has(m.id)), ...prev];
      });
      setOlderCursor(res.nextBefore);
    } catch (e) {
      showToast(getErrorMessage(e, 'Не удалось загрузить историю'), 'error');
    } finally {
      setIsLoadingOlder(false);
    }
  };

  /** A message from the API or the event stream; it lands only in its own chat. */
  const addMessage = useCallback(
    (msg: Message) => {
      const isOpen = msg.matchId === selectedRef.current;
      if (isOpen) {
        setMessages((prev) => (prev.some((m) => m.id === msg.id) ? prev : [...prev, msg]));
      }
      if (matchesRef.current.some((m) => m.id === msg.matchId)) {
        setMatches((prev) => withLastMessage(prev, msg, !msg.mine && !isOpen));
      } else {
        void loadMatches();
      }
      if (isOpen) markRead(msg.matchId, msg);
    },
    [loadMatches, markRead]
  );

  useRealtime((event) => {
    switch (event.name) {
      case 'ready':
        void loadMatches();
        setReloadKey((k) => k + 1);
        break;
      case 'message':
        addMessage(event.data);
        break;
      case 'match':
        setMatches((prev) => (prev.some((m) => m.id === event.data.id) ? prev : [event.data, ...prev]));
        break;
      case 'match_closed':
        setMatches((prev) => prev.filter((m) => m.id !== event.data.matchId));
        if (selectedRef.current === event.data.matchId) showToast('Диалог закрыт', 'info');
        break;
      case 'read':
        setMatches((prev) =>
          prev.map((m) =>
            m.id === event.data.matchId
              ? { ...m, otherLastReadMessageId: event.data.lastReadMessageId, otherLastReadAt: event.data.lastReadAt }
              : m
          )
        );
        break;
    }
  });

  // Follow the newest message (not when older history is prepended).
  const newestId = messages[messages.length - 1]?.id;
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [newestId]);

  const send = async (payload: Omit<Parameters<typeof api.sendMessage>[1], 'clientMessageId'>, clientMessageId: string) => {
    const matchId = selectedMatchId;
    if (!matchId || sendingRef.current) return false;
    sendingRef.current = true;
    setIsSending(true);
    try {
      addMessage(await api.sendMessage(matchId, { ...payload, clientMessageId }));
      return true;
    } catch (err) {
      showToast(getErrorMessage(err, 'Не удалось отправить сообщение'), 'error');
      return false;
    } finally {
      sendingRef.current = false;
      setIsSending(false);
    }
  };

  const handleSendMessage = async (e?: React.FormEvent) => {
    e?.preventDefault();
    const text = inputText.trim();
    if (!text || sendingRef.current) return;
    const clientMessageId =
      unsentText.current?.text === text ? unsentText.current.clientMessageId : crypto.randomUUID();
    unsentText.current = { text, clientMessageId };
    const matchId = selectedMatchId;
    setInputText('');
    if (await send({ type: 'TEXT', text }, clientMessageId)) {
      unsentText.current = null;
    } else if (selectedRef.current === matchId) {
      setInputText((current) => current || text);
    }
  };

  const handleSendSticker = (stickerPackCode: string, sticker: string) => {
    setIsStickerPickerOpen(false);
    void send({ type: 'STICKER', stickerPackCode, sticker }, crypto.randomUUID());
  };

  const handleUnmatch = async () => {
    if (!selectedMatchId) return;

    try {
      await api.unmatch(selectedMatchId);
      showToast('Пара успешно разорвана', 'info');
      setMatches((prev) => prev.filter((m) => m.id !== selectedMatchId));
      selectMatch(null);
    } catch (err) {
      showToast(getErrorMessage(err, 'Не удалось разорвать пару'), 'error');
    }
  };

  const handleBlock = async () => {
    if (!selectedMatch) return;

    try {
      await api.blockProfile(selectedMatch.profile.id);
      showToast(`Пользователь ${selectedMatch.profile.displayName} заблокирован`, 'success');
      setMatches((prev) => prev.filter((m) => m.id !== selectedMatch.id));
      selectMatch(null);
    } catch (err) {
      showToast(getErrorMessage(err, 'Не удалось заблокировать'), 'error');
    }
  };

  // My messages up to the other person's read pointer are read (double check).
  const otherReadIndex = selectedMatch?.otherLastReadMessageId
    ? messages.findIndex((m) => m.id === selectedMatch.otherLastReadMessageId)
    : -1;

  // Sticker items owned in wallet
  const stickerPacks = (wallet?.items ?? []).filter((i) => i.category === 'STICKER_PACK');
  const packEmoji = new Map(stickerPacks.map((p) => [p.code, String(p.attributes?.emoji ?? '✨')]));

  return (
    <div className="bg-white border border-zinc-200/80 rounded-2xl shadow-xs overflow-hidden flex flex-col md:flex-row h-[750px] max-h-[85vh]">
      {/* Left List of Matches */}
      <div
        className={`w-full md:w-80 border-r border-zinc-200 flex flex-col shrink-0 ${
          selectedMatchId ? 'hidden md:flex' : 'flex'
        }`}
      >
        <div className="p-4 border-b border-zinc-100 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <MessageCircle className="w-5 h-5 text-zinc-900" />
            <h2 className="font-bold text-sm text-zinc-900">Сообщения</h2>
          </div>
          <span className="text-xs bg-zinc-100 text-zinc-600 px-2 py-0.5 rounded-full font-medium">
            {matches.length}
          </span>
        </div>

        <div className="flex-1 overflow-y-auto divide-y divide-zinc-100">
          {error && (
            <div className="p-3 text-[11px] text-rose-700 bg-rose-50 flex items-center justify-between gap-2">
              <span>{error}</span>
              <button type="button" onClick={() => void loadMatches()} className="font-semibold underline shrink-0">
                Повторить
              </button>
            </div>
          )}
          {isLoadingMatches ? (
            <div className="p-8 text-center text-xs text-zinc-400">Загрузка диалогов...</div>
          ) : matches.length === 0 ? (
            <div className="p-8 text-center space-y-2">
              <div className="w-10 h-10 bg-zinc-100 rounded-full flex items-center justify-center mx-auto text-zinc-400">
                <MessageCircle className="w-5 h-5" />
              </div>
              <p className="text-xs font-semibold text-zinc-700">Пока нет пар</p>
              <p className="text-[11px] text-zinc-400">
                Ставьте лайки в ленте анкет — при взаимной симпатии здесь появится диалог!
              </p>
            </div>
          ) : (
            matches.map((m) => {
              const photo = m.profile.photos.find((p) => p.isPrimary)?.url || m.profile.photos[0]?.url;
              const isSelected = m.id === selectedMatchId;
              const isUnread = m.unreadCount > 0;

              return (
                <div
                  key={m.id}
                  onClick={() => selectMatch(m.id)}
                  className={`p-3.5 flex items-center gap-3 cursor-pointer transition-colors ${
                    isSelected
                      ? 'bg-zinc-100/80'
                      : isUnread
                      ? 'bg-rose-50/50 hover:bg-zinc-50'
                      : 'hover:bg-zinc-50'
                  }`}
                >
                  <div className="w-12 h-12 rounded-full bg-zinc-200 overflow-hidden shrink-0 relative">
                    {photo ? (
                      <img src={photo} alt="" className="w-full h-full object-cover" />
                    ) : (
                      <div className="w-full h-full flex items-center justify-center text-zinc-500 font-bold">
                        {m.profile.displayName[0]}
                      </div>
                    )}
                    {m.profile.verified && (
                      <div className="absolute bottom-0 right-0 bg-white rounded-full p-0.5 shadow-xs">
                        <ShieldCheck className="w-3.5 h-3.5 text-emerald-600 fill-emerald-100" />
                      </div>
                    )}
                  </div>

                  <div className="flex-1 min-w-0">
                    <div className="flex items-baseline justify-between gap-1">
                      <div className="flex items-center gap-1.5 truncate">
                        <span className="text-xs font-bold text-zinc-900 truncate">
                          {m.profile.displayName}
                        </span>
                        {m.profile.premium && <Crown className="w-3 h-3 text-amber-500 shrink-0" />}
                      </div>
                      {m.profile.compatibility !== undefined && m.profile.compatibility !== null && (
                        <span className="text-[10px] text-emerald-700 font-semibold bg-emerald-50 px-1.5 py-0.2 rounded shrink-0">
                          {m.profile.compatibility}%
                        </span>
                      )}
                    </div>

                    <div className="flex items-center justify-between gap-1 mt-0.5">
                      <p className={`text-[11px] truncate ${isUnread ? 'font-semibold text-zinc-900' : 'text-zinc-500'}`}>
                        {m.lastMessage
                          ? m.lastMessage.type === 'STICKER'
                            ? 'Стикер'
                            : m.lastMessage.text
                          : 'Взаимная симпатия! Напишите первым'}
                      </p>
                      {isUnread && (
                        <span className="w-4 h-4 rounded-full bg-rose-500 text-white text-[10px] flex items-center justify-center font-bold shrink-0">
                          {m.unreadCount}
                        </span>
                      )}
                    </div>
                  </div>
                </div>
              );
            })
          )}
        </div>
      </div>

      {/* Right Chat Conversation Area */}
      <div className={`flex-1 flex flex-col ${!selectedMatchId ? 'hidden md:flex' : 'flex'}`}>
        {selectedMatch ? (
          <>
            {/* Chat Header */}
            <div className="p-3.5 border-b border-zinc-200 flex items-center justify-between bg-zinc-50/50">
              <div className="flex items-center gap-3">
                <button
                  type="button"
                  onClick={() => selectMatch(null)}
                  aria-label="К списку диалогов"
                  className="md:hidden p-1.5 hover:bg-zinc-200 rounded-lg"
                >
                  <ChevronLeft className="w-5 h-5 text-zinc-600" />
                </button>

                <div
                  onClick={() => setInspectProfile(selectedMatch.profile)}
                  className="flex items-center gap-2.5 cursor-pointer hover:opacity-85"
                >
                  <div className="w-10 h-10 rounded-full bg-zinc-200 overflow-hidden relative">
                    {selectedMatch.profile.photos[0] ? (
                      <img
                        src={selectedMatch.profile.photos[0].url}
                        alt=""
                        className="w-full h-full object-cover"
                      />
                    ) : (
                      <div className="w-full h-full flex items-center justify-center font-bold text-zinc-400">
                        {selectedMatch.profile.displayName[0]}
                      </div>
                    )}
                  </div>
                  <div>
                    <div className="flex items-center gap-1.5">
                      <span className="text-xs font-bold text-zinc-900">
                        {selectedMatch.profile.displayName}, {selectedMatch.profile.age}
                      </span>
                      {selectedMatch.profile.verified && (
                        <ShieldCheck className="w-3.5 h-3.5 text-emerald-600 fill-emerald-100" />
                      )}
                    </div>
                    <span className="text-[10px] text-zinc-400">{selectedMatch.profile.city}</span>
                  </div>
                </div>
              </div>

              {/* Chat Actions Menu */}
              <div className="relative">
                <button
                  type="button"
                  onClick={() => setIsMenuOpen(!isMenuOpen)}
                  className="p-2 text-zinc-400 hover:text-zinc-700 hover:bg-zinc-100 rounded-xl transition-colors"
                >
                  <MoreVertical className="w-4 h-4" />
                </button>

                {isMenuOpen && (
                  <div className="absolute right-0 top-full mt-1 w-48 bg-white border border-zinc-200 rounded-xl shadow-lg py-1 z-30 text-xs">
                    <button
                      type="button"
                      onClick={() => {
                        setIsMenuOpen(false);
                        setInspectProfile(selectedMatch.profile);
                      }}
                      className="w-full px-3 py-2 text-left hover:bg-zinc-50 flex items-center gap-2 text-zinc-700"
                    >
                      Посмотреть анкету
                    </button>
                    <button
                      type="button"
                      onClick={() => {
                        setIsMenuOpen(false);
                        setReport({});
                      }}
                      className="w-full px-3 py-2 text-left hover:bg-zinc-50 flex items-center gap-2 text-amber-700"
                    >
                      <ShieldAlert className="w-3.5 h-3.5" /> Пожаловаться
                    </button>
                    <button
                      type="button"
                      onClick={handleBlock}
                      className="w-full px-3 py-2 text-left hover:bg-zinc-50 flex items-center gap-2 text-rose-600"
                    >
                      <UserX className="w-3.5 h-3.5" /> Заблокировать
                    </button>
                    <div className="border-t border-zinc-100 my-1" />
                    <button
                      type="button"
                      onClick={handleUnmatch}
                      className="w-full px-3 py-2 text-left hover:bg-zinc-50 text-zinc-500"
                    >
                      Разорвать пару
                    </button>
                  </div>
                )}
              </div>
            </div>

            {/* Messages Scroll Area */}
            <div className="flex-1 p-4 overflow-y-auto space-y-3 bg-stone-50/40">
              {isLoadingMessages && messages.length === 0 ? (
                <div className="py-12 text-center text-xs text-zinc-400">Загрузка переписки...</div>
              ) : messages.length === 0 ? (
                <div className="py-16 text-center space-y-2">
                  <div className="w-12 h-12 bg-rose-50 text-rose-500 rounded-2xl flex items-center justify-center mx-auto">
                    <Sparkles className="w-6 h-6" />
                  </div>
                  <h3 className="text-xs font-bold text-zinc-900">У вас совпадение!</h3>
                  <p className="text-[11px] text-zinc-500 max-w-xs mx-auto">
                    Начните диалог с лёгкого вопроса о предпочтениях или книгах.
                  </p>
                </div>
              ) : (
                <>
                {olderCursor && (
                  <div className="text-center">
                    <button
                      type="button"
                      onClick={() => void loadOlder()}
                      disabled={isLoadingOlder}
                      className="text-[11px] text-zinc-500 hover:text-zinc-800 px-3 py-1 rounded-full bg-zinc-100 disabled:opacity-50"
                    >
                      {isLoadingOlder ? 'Загрузка…' : 'Показать более ранние'}
                    </button>
                  </div>
                )}
                {messages.map((msg, index) => {
                  const isMine = msg.mine;
                  const isRead = isMine && index <= otherReadIndex;

                  return (
                    <div
                      key={msg.id}
                      className={`group flex items-center gap-1 ${isMine ? 'justify-end' : 'justify-start'}`}
                    >
                      <div
                        className={`max-w-[75%] rounded-2xl p-3 text-xs leading-relaxed shadow-2xs ${
                          isMine
                            ? 'bg-zinc-900 text-white rounded-br-xs'
                            : 'bg-white text-zinc-900 border border-zinc-200/80 rounded-bl-xs'
                        }`}
                      >
                        {msg.type === 'STICKER' ? (
                          <div className="text-3xl py-1 text-center font-emoji" title={msg.sticker ?? undefined}>
                            {packEmoji.get(msg.stickerPackCode ?? '') ?? '✨'}
                          </div>
                        ) : (
                          <p className="whitespace-pre-wrap">{msg.text}</p>
                        )}

                        <div
                          className={`flex items-center justify-end gap-1 mt-1 text-[9px] ${
                            isMine ? 'text-zinc-400' : 'text-zinc-400'
                          }`}
                        >
                          <span>
                            {new Date(msg.createdAt).toLocaleTimeString([], {
                              hour: '2-digit',
                              minute: '2-digit',
                            })}
                          </span>
                          {isMine && (
                            <span>
                              {isRead ? (
                                <CheckCheck className="w-3 h-3 text-sky-400" />
                              ) : (
                                <Check className="w-3 h-3 text-zinc-400" />
                              )}
                            </span>
                          )}
                        </div>
                      </div>
                      {!isMine && (
                        <button
                          type="button"
                          onClick={() => setReport({ messageId: msg.id })}
                          title="Пожаловаться на сообщение"
                          aria-label="Пожаловаться на сообщение"
                          className="p-1 text-zinc-300 hover:text-amber-600 opacity-0 group-hover:opacity-100 focus:opacity-100 transition-opacity"
                        >
                          <Flag className="w-3.5 h-3.5" />
                        </button>
                      )}
                    </div>
                  );
                })}
                </>
              )}
              <div ref={messagesEndRef} />
            </div>

            {/* Sticker Drawer */}
            {isStickerPickerOpen && (
              <div className="p-3 border-t border-zinc-200 bg-white space-y-2">
                <div className="flex items-center justify-between text-xs font-semibold text-zinc-700">
                  <span>Стикеры</span>
                  <button
                    type="button"
                    onClick={() => setIsStickerPickerOpen(false)}
                    className="text-zinc-400 hover:text-zinc-600"
                  >
                    Закрыть
                  </button>
                </div>
                {stickerPacks.length === 0 ? (
                  <p className="text-[11px] text-zinc-400 py-2">
                    У вас пока нет стикер-паков. Их можно получить в Кошельке или в Алтаре удачи!
                  </p>
                ) : (
                  <div className="space-y-2 max-h-36 overflow-y-auto">
                    {stickerPacks.map((pack) => {
                      const stickers = (pack.attributes?.stickers as string[]) || [];
                      return (
                        <div key={pack.code} className="space-y-1">
                          <span className="text-[10px] text-zinc-400 font-medium">
                            {pack.title}
                          </span>
                          <div className="flex gap-2">
                            {stickers.map((stk) => (
                              <button
                                key={stk}
                                type="button"
                                onClick={() => handleSendSticker(pack.code, stk)}
                                disabled={isSending}
                                title={stk}
                                className="flex flex-col items-center p-1.5 hover:bg-zinc-100 rounded-xl transition-transform hover:scale-110"
                              >
                                <span className="text-2xl">{packEmoji.get(pack.code)}</span>
                                <span className="text-[9px] text-zinc-400">{stk}</span>
                              </button>
                            ))}
                          </div>
                        </div>
                      );
                    })}
                  </div>
                )}
              </div>
            )}

            {/* Message Input Box */}
            <form
              onSubmit={handleSendMessage}
              className="p-3 border-t border-zinc-200 bg-white flex items-center gap-2"
            >
              <button
                type="button"
                onClick={() => setIsStickerPickerOpen(!isStickerPickerOpen)}
                className="p-2 text-zinc-400 hover:text-zinc-700 hover:bg-zinc-100 rounded-xl transition-colors"
                title="Отправить стикер"
              >
                <Smile className="w-5 h-5" />
              </button>

              <input
                type="text"
                value={inputText}
                onChange={(e) => setInputText(e.target.value)}
                placeholder="Напишите сообщение..."
                maxLength={2000}
                className="flex-1 px-3.5 py-2 text-xs bg-zinc-50 border border-zinc-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-zinc-900"
              />

              <Button
                type="submit"
                variant="primary"
                size="sm"
                disabled={!inputText.trim() || isSending}
                className="px-3"
              >
                <Send className="w-4 h-4" />
              </Button>
            </form>
          </>
        ) : (
          <div className="flex-1 flex flex-col items-center justify-center p-8 text-center text-zinc-400 space-y-2">
            <div className="w-12 h-12 bg-zinc-100 rounded-2xl flex items-center justify-center text-zinc-400">
              <MessageCircle className="w-6 h-6" />
            </div>
            <p className="text-xs font-semibold text-zinc-700">Выберите диалог</p>
            <p className="text-[11px] text-zinc-400">
              Выберите человека из списка слева, чтобы начать переписку.
            </p>
          </div>
        )}
      </div>

      {/* Safety & Profile modals */}
      {selectedMatch && (
        <ReportModal
          isOpen={report !== null}
          onClose={() => setReport(null)}
          profileId={selectedMatch.profile.id}
          profileName={selectedMatch.profile.displayName}
          messageId={report?.messageId}
        />
      )}

      <ProfileDetailModal
        isOpen={!!inspectProfile}
        onClose={() => setInspectProfile(null)}
        profile={inspectProfile}
      />
    </div>
  );
};
