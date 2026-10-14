import React, { useCallback, useEffect, useRef, useState } from 'react';
import { api, ApiError, getErrorMessage, isAbortError } from '../../../core/api/client';
import { Button } from '../../../shared/components/Button';
import { AlertCircle, Check, ChevronDown, EyeOff, Lock, X } from 'lucide-react';

/** Fallback until the server reports its own limit (contract: maxWords = 20). */
const DEFAULT_MAX_WORDS = 20;
/** Must match the backend rules for a single exclusion word. */
const MIN_WORD_LENGTH = 2;
const MAX_WORD_LENGTH = 30;
const WORD_RE = /^[\p{L}\p{N}]+$/u;

/** Same normalization as the server: trim + lowercase (NFC so composed/decomposed letters compare equal). */
function normalizeWord(raw: string): string {
  return raw.normalize('NFC').trim().toLowerCase();
}

function validateWord(word: string): string | null {
  if (/\s/.test(word)) {
    return `«${word}»: только одно слово, без пробелов`;
  }
  if (!WORD_RE.test(word)) {
    return `«${word}»: допустимы только буквы и цифры`;
  }
  if (word.length < MIN_WORD_LENGTH || word.length > MAX_WORD_LENGTH) {
    return `«${word}»: от ${MIN_WORD_LENGTH} до ${MAX_WORD_LENGTH} символов`;
  }
  return null;
}

interface AddResult {
  words: string[];
  /** Text that was not added (the failing piece and everything after it). */
  rest: string;
  error: string | null;
}

/** Adds comma-separated words from `raw` to `current`, stopping at the first invalid one. */
function addWords(current: string[], raw: string, maxWords: number): AddResult {
  const pieces = raw.split(',');
  const words = [...current];
  for (let i = 0; i < pieces.length; i++) {
    const word = normalizeWord(pieces[i]);
    if (word === '') continue;
    const rest = pieces.slice(i).join(',').trim();
    const error = validateWord(word);
    if (error) return { words, rest, error };
    if (words.includes(word)) {
      return { words, rest, error: `Слово «${word}» уже есть в списке` };
    }
    if (words.length >= maxWords) {
      return { words, rest, error: `Можно добавить не больше ${maxWords} слов` };
    }
    words.push(word);
  }
  return { words, rest: '', error: null };
}

const sameList = (a: string[], b: string[]) =>
  a.length === b.length && a.every((w, i) => w === b[i]);

interface ExclusionWordsEditorProps {
  /** Called with the server list after the initial load. */
  onLoaded?: (words: string[]) => void;
  /** Called with the server list after a successful save; the feed should reload from page 0. */
  onSaved: (words: string[]) => void;
}

type LoadState = { status: 'loading' } | { status: 'ready' } | { status: 'error'; message: string };

export const ExclusionWordsEditor: React.FC<ExclusionWordsEditorProps> = ({ onLoaded, onSaved }) => {
  const [isOpen, setIsOpen] = useState(false);
  const [loadState, setLoadState] = useState<LoadState>({ status: 'loading' });
  const [maxWords, setMaxWords] = useState(DEFAULT_MAX_WORDS);
  const [savedWords, setSavedWords] = useState<string[]>([]);
  const [draft, setDraft] = useState<string[]>([]);
  const [input, setInput] = useState('');
  const [inputError, setInputError] = useState<string | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [isSaving, setIsSaving] = useState(false);
  const [justSaved, setJustSaved] = useState(false);

  const controllerRef = useRef<AbortController | null>(null);
  // Keep the latest callback without re-running the load effect when the parent re-renders.
  const onLoadedRef = useRef(onLoaded);
  useEffect(() => {
    onLoadedRef.current = onLoaded;
  }, [onLoaded]);

  const load = useCallback(async () => {
    controllerRef.current?.abort();
    const controller = new AbortController();
    controllerRef.current = controller;
    setLoadState({ status: 'loading' });
    try {
      const result = await api.getExclusionWords(controller.signal);
      if (controller.signal.aborted) return;
      setMaxWords(result.maxWords || DEFAULT_MAX_WORDS);
      setSavedWords(result.words);
      setDraft(result.words);
      setLoadState({ status: 'ready' });
      onLoadedRef.current?.(result.words);
    } catch (e: unknown) {
      if (controller.signal.aborted || isAbortError(e)) return;
      setLoadState({
        status: 'error',
        message: getErrorMessage(e, 'Не удалось загрузить слова-исключения.'),
      });
    }
  }, []);

  useEffect(() => {
    void load();
    return () => {
      controllerRef.current?.abort();
    };
  }, [load]);

  const isReady = loadState.status === 'ready';
  const isDirty = !sameList(draft, savedWords);
  const isFull = draft.length >= maxWords;

  const markEdited = () => {
    setJustSaved(false);
    setSaveError(null);
  };

  /** Turns the given text into chips. Returns the new list, or null when something was rejected. */
  const commit = (raw: string): string[] | null => {
    const result = addWords(draft, raw, maxWords);
    if (result.words.length !== draft.length) {
      setDraft(result.words);
      markEdited();
    }
    setInput(result.rest);
    setInputError(result.error);
    return result.error ? null : result.words;
  };

  const handleInputChange = (value: string) => {
    setInputError(null);
    // Typed or pasted commas: everything before the last comma becomes chips, the tail stays editable.
    const lastComma = value.lastIndexOf(',');
    if (lastComma === -1) {
      setInput(value);
      return;
    }
    const tail = value.slice(lastComma + 1);
    const result = addWords(draft, value.slice(0, lastComma), maxWords);
    if (result.words.length !== draft.length) {
      setDraft(result.words);
      markEdited();
    }
    setInputError(result.error);
    setInput(result.error ? [result.rest, tail].filter(Boolean).join(', ') : tail.trimStart());
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      if (input.trim()) commit(input);
    } else if (e.key === 'Backspace' && input === '' && draft.length > 0) {
      e.preventDefault();
      setDraft(draft.slice(0, -1));
      setInputError(null);
      markEdited();
    }
  };

  const removeWord = (word: string) => {
    setDraft(draft.filter((w) => w !== word));
    setInputError(null);
    markEdited();
  };

  const handleCancel = () => {
    setDraft(savedWords);
    setInput('');
    setInputError(null);
    setSaveError(null);
  };

  const handleSave = async () => {
    let words = draft;
    if (input.trim()) {
      // Don't silently drop a half-typed word: add it first, or stop if it's invalid.
      const committed = commit(input);
      if (!committed) return;
      words = committed;
    }
    if (sameList(words, savedWords)) return;

    setIsSaving(true);
    setSaveError(null);
    setJustSaved(false);
    try {
      const result = await api.updateExclusionWords(words);
      setMaxWords(result.maxWords || DEFAULT_MAX_WORDS);
      setSavedWords(result.words);
      setDraft(result.words);
      setJustSaved(true);
      onSaved(result.words);
    } catch (e: unknown) {
      const fieldError = e instanceof ApiError ? e.fieldErrors?.words : undefined;
      setSaveError(fieldError ?? getErrorMessage(e, 'Не удалось сохранить слова-исключения.'));
    } finally {
      setIsSaving(false);
    }
  };

  const canSave = isReady && !isSaving && (isDirty || input.trim() !== '');

  return (
    <div className="bg-white rounded-2xl border border-zinc-200/80 p-4 shadow-xs space-y-3">
      <button
        type="button"
        onClick={() => setIsOpen(!isOpen)}
        aria-expanded={isOpen}
        className="w-full flex items-center justify-between gap-2 text-left cursor-pointer"
      >
        <span className="flex items-center gap-2 text-xs font-semibold text-zinc-800">
          <EyeOff className="w-3.5 h-3.5 text-zinc-500" />
          Слова-исключения
          {isReady && savedWords.length > 0 && (
            <span className="px-1.5 py-0.5 leading-none bg-amber-500 text-white rounded-full text-[10px] font-bold">
              {savedWords.length}
            </span>
          )}
          {isReady && isDirty && (
            <span className="text-[11px] font-normal text-amber-600">не сохранено</span>
          )}
        </span>
        <span className="flex items-center gap-2 text-[11px] text-zinc-400">
          <span className="hidden sm:inline">Скрывать анкеты с этими словами</span>
          <ChevronDown
            className={`w-4 h-4 transition-transform ${isOpen ? 'rotate-180' : ''}`}
          />
        </span>
      </button>

      {isOpen && (
        <div className="pt-3 border-t border-zinc-100 space-y-2.5 animate-in fade-in duration-150">
          <p className="text-[11px] text-zinc-500 flex items-start gap-1.5">
            <Lock className="w-3 h-3 mt-0.5 shrink-0" />
            <span>
              Список видите только вы. Скрываем анкеты, где слово встречается в «О себе» или в статусе
              на сегодня — в любой форме («курю» скроет и «курить», «курит»). Имя, город и карточки не
              проверяются.
            </span>
          </p>

          {loadState.status === 'loading' && (
            <p className="text-xs text-zinc-400">Загрузка списка...</p>
          )}

          {loadState.status === 'error' && (
            <div className="p-2.5 bg-rose-50 border border-rose-200 text-rose-700 text-xs rounded-xl flex items-center justify-between gap-2">
              <span className="flex items-center gap-2">
                <AlertCircle className="w-4 h-4 shrink-0" />
                {loadState.message}
              </span>
              <Button type="button" variant="outline" size="sm" onClick={() => void load()}>
                Повторить
              </Button>
            </div>
          )}

          {isReady && (
            <>
              <div className="flex flex-wrap items-center gap-1.5 px-2 py-1.5 bg-zinc-50 border border-zinc-200 rounded-xl focus-within:ring-2 focus-within:ring-zinc-900">
                {draft.map((word) => (
                  <span
                    key={word}
                    className="inline-flex items-center gap-1 text-xs pl-2.5 pr-1 py-0.5 rounded-full bg-white text-zinc-800 border border-zinc-200"
                  >
                    {word}
                    <button
                      type="button"
                      onClick={() => removeWord(word)}
                      disabled={isSaving}
                      aria-label={`Удалить слово «${word}»`}
                      className="p-0.5 rounded-full text-zinc-400 hover:text-rose-600 hover:bg-rose-50 transition-colors"
                    >
                      <X className="w-3 h-3" />
                    </button>
                  </span>
                ))}
                <input
                  type="text"
                  value={input}
                  onChange={(e) => handleInputChange(e.target.value)}
                  onKeyDown={handleKeyDown}
                  // Stay editable while it still holds text that didn't fit, so it can be cleared.
                  disabled={isSaving || (isFull && input === '')}
                  aria-label="Добавить слово-исключение"
                  aria-invalid={inputError !== null}
                  placeholder={
                    isFull
                      ? 'Достигнут лимит слов'
                      : draft.length === 0
                        ? 'Например: курю — и нажмите Enter'
                        : 'Ещё слово…'
                  }
                  className="flex-1 min-w-32 bg-transparent text-xs px-1 py-0.5 focus:outline-none disabled:cursor-not-allowed"
                />
              </div>

              {(inputError || saveError) && (
                <p role="alert" className="text-[11px] text-rose-600 flex items-center gap-1">
                  <AlertCircle className="w-3 h-3 shrink-0" /> {inputError ?? saveError}
                </p>
              )}

              <div className="flex flex-wrap items-center justify-between gap-2 text-[11px] text-zinc-400">
                <span>
                  Enter или запятая — добавить слово ·{' '}
                  <span className={isFull ? 'text-amber-600 font-semibold' : ''}>
                    {draft.length}/{maxWords}
                  </span>
                </span>
                <div className="flex items-center gap-1.5">
                  {justSaved && !isDirty && (
                    <span className="flex items-center gap-1 text-emerald-600">
                      <Check className="w-3 h-3" /> Сохранено
                    </span>
                  )}
                  {isDirty && (
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      onClick={handleCancel}
                      disabled={isSaving}
                    >
                      Отменить
                    </Button>
                  )}
                  <Button
                    type="button"
                    variant="primary"
                    size="sm"
                    onClick={() => void handleSave()}
                    isLoading={isSaving}
                    disabled={!canSave}
                  >
                    Сохранить
                  </Button>
                </div>
              </div>
            </>
          )}
        </div>
      )}
    </div>
  );
};
