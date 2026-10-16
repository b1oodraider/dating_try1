import React, { createContext, useContext, useState, useCallback } from 'react';
import { AlertCircle, CheckCircle2, Info, X } from 'lucide-react';

export type ToastType = 'info' | 'success' | 'error';

interface ToastItem {
  id: string;
  message: string;
  type: ToastType;
}

interface ToastContextValue {
  showToast: (message: string, type?: ToastType) => void;
}

const ToastContext = createContext<ToastContextValue>({
  showToast: () => {},
});

export const useToast = () => useContext(ToastContext);

// Global fallback for standalone calls if needed
let globalShowToast: ((msg: string, type?: ToastType) => void) | null = null;
export const notify = (message: string, type: ToastType = 'info') => {
  if (globalShowToast) {
    globalShowToast(message, type);
  } else {
    console.log(`[Toast ${type}] ${message}`);
  }
};

export const ToastProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [toasts, setToasts] = useState<ToastItem[]>([]);

  const removeToast = useCallback((id: string) => {
    setToasts((prev) => prev.filter((t) => t.id !== id));
  }, []);

  const showToast = useCallback(
    (message: string, type: ToastType = 'info') => {
      const id = crypto.randomUUID();
      setToasts((prev) => [...prev.slice(-4), { id, message, type }]);
      setTimeout(() => {
        removeToast(id);
      }, 4000);
    },
    [removeToast]
  );

  globalShowToast = showToast;

  return (
    <ToastContext.Provider value={{ showToast }}>
      {children}
      <div className="fixed bottom-5 right-5 z-50 flex flex-col gap-2 max-w-sm w-full pointer-events-none px-4">
        {toasts.map((toast) => (
          <div
            key={toast.id}
            className={`pointer-events-auto flex items-start gap-3 p-3.5 rounded-xl shadow-lg border text-sm transition-all animate-in slide-in-from-bottom-2 ${
              toast.type === 'error'
                ? 'bg-red-50 text-red-900 border-red-200 dark:bg-red-950/90 dark:text-red-200 dark:border-red-900'
                : toast.type === 'success'
                ? 'bg-emerald-50 text-emerald-900 border-emerald-200 dark:bg-emerald-950/90 dark:text-emerald-200 dark:border-emerald-900'
                : 'bg-zinc-900 text-white border-zinc-800 dark:bg-zinc-100 dark:text-zinc-900 dark:border-zinc-300'
            }`}
          >
            {toast.type === 'error' && <AlertCircle className="w-5 h-5 text-red-600 shrink-0 mt-0.5" />}
            {toast.type === 'success' && <CheckCircle2 className="w-5 h-5 text-emerald-600 shrink-0 mt-0.5" />}
            {toast.type === 'info' && <Info className="w-5 h-5 text-sky-500 shrink-0 mt-0.5" />}
            <div className="flex-1 font-medium leading-snug">{toast.message}</div>
            <button
              onClick={() => removeToast(toast.id)}
              className="text-current opacity-60 hover:opacity-100 p-0.5"
            >
              <X className="w-4 h-4" />
            </button>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
};
