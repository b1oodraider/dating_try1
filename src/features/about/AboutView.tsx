import React from 'react';
import { ShieldCheck, Cpu, Database, EyeOff, Sparkles, CheckCircle2 } from 'lucide-react';

export const AboutView: React.FC = () => {
  return (
    <div className="max-w-3xl mx-auto space-y-6 pb-16">
      <div className="bg-white p-6 rounded-2xl border border-zinc-200/80 shadow-xs space-y-3">
        <div className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full bg-emerald-50 text-emerald-700 border border-emerald-200 text-xs font-semibold">
          <ShieldCheck className="w-3.5 h-3.5" /> Спецификация закрытого MVP (13.10.2026)
        </div>
        <h1 className="text-2xl font-bold text-zinc-900">
          Концепция сервиса «Dating»
        </h1>
        <p className="text-sm text-zinc-600 leading-relaxed">
          Сервис знакомств для людей с разным темпом и форматом общения. Ядро ценности —
          комфортное сообщество и механики согласования ожиданий от диалога, а не бесконечный свайп
          и удержание пользователя любой ценой.
        </p>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        {/* Module architecture */}
        <div className="bg-white p-5 rounded-2xl border border-zinc-200/80 shadow-xs space-y-3">
          <div className="flex items-center gap-2 text-zinc-900 font-semibold text-sm">
            <Cpu className="w-4 h-4 text-zinc-700" />
            <span>Архитектура модульного монолита</span>
          </div>
          <p className="text-xs text-zinc-500 leading-relaxed">
            Архитектура построена на чётких границах модулей: <strong>auth</strong>, <strong>accounts</strong>, <strong>profiles</strong>, <strong>discovery</strong>, <strong>media</strong>, <strong>productevents</strong>.
          </p>
          <ul className="text-xs text-zinc-600 space-y-1.5 list-disc list-inside">
            <li>Один процесс бэкенда, одна база данных PostgreSQL</li>
            <li>Никаких избыточных Redis, Kafka, Elasticsearch</li>
            <li>Серверная валидация всех прав и доступа к медиа</li>
          </ul>
        </div>

        {/* Functional cards */}
        <div className="bg-white p-5 rounded-2xl border border-zinc-200/80 shadow-xs space-y-3">
          <div className="flex items-center gap-2 text-zinc-900 font-semibold text-sm">
            <Sparkles className="w-4 h-4 text-amber-500" />
            <span>Функциональные карточки</span>
          </div>
          <p className="text-xs text-zinc-500 leading-relaxed">
            Вместо сотен фильтров используются нейтральные карточки предпочтений:
          </p>
          <ul className="text-xs text-zinc-600 space-y-1.5 list-disc list-inside">
            <li><strong>Темп:</strong> асинхронный ответ, живой чат, паузы без обид</li>
            <li><strong>Формат встречи:</strong> прогулка, кофе на 30 мин, совместное дело</li>
            <li><strong>Звонки:</strong> только текст, звонки по согласованию</li>
            <li><strong>Границы:</strong> прямота, отсутствие назойливого смолл-тока</li>
          </ul>
        </div>
      </div>

      {/* Safety & Data retention */}
      <div className="bg-stone-50 border border-stone-200 rounded-2xl p-5 space-y-3">
        <h3 className="text-sm font-semibold text-stone-900 flex items-center gap-1.5">
          <EyeOff className="w-4 h-4 text-stone-600" />
          Принципы конфиденциальности и безопасности
        </h3>
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 text-xs text-stone-600">
          <div className="p-3 bg-white rounded-xl border border-stone-200/60">
            <span className="font-semibold text-stone-800 block mb-1">Без ML и обучения</span>
            Тексты «О себе» не анализируются моделями, не парсятся и не индексируются поисковиками.
          </div>
          <div className="p-3 bg-white rounded-xl border border-stone-200/60">
            <span className="font-semibold text-stone-800 block mb-1">Защита номера</span>
            Номер телефона используется исключительно для одноразового OTP и никогда не раскрывается.
          </div>
          <div className="p-3 bg-white rounded-xl border border-stone-200/60">
            <span className="font-semibold text-stone-800 block mb-1">Полное удаление</span>
            Запрос на удаление аккаунта каскадно уничтожает анкету, карточки, фотографии и сессии.
          </div>
        </div>
      </div>
    </div>
  );
};
