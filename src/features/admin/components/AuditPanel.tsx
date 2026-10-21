import React, { useState } from 'react';
import { api } from '../../../core/api/client';
import { cardClass, formatTs, inputClass, LoadState, useLoad } from './shared';

const ENTITY_TYPES = [
  'ACCOUNT_ROLE',
  'TAG',
  'TAG_CATEGORY',
  'GOAL',
  'CATALOG_ITEM',
  'AUTHOR',
  'ACHIEVEMENT',
  'PREMIUM_COMPOSITION',
  'GACHA_BANNER',
  'REPORT',
  'ACCOUNT_RESTRICTION',
  'SANCTION_POLICY',
];

const json = (v: unknown) => (v === null || v === undefined ? '—' : JSON.stringify(v, null, 2));

export const AuditPanel: React.FC = () => {
  const [entityType, setEntityType] = useState('');
  const [entityId, setEntityId] = useState('');
  const [appliedId, setAppliedId] = useState('');
  const audit = useLoad(
    (signal) => api.admin.getAudit({ entityType: entityType || undefined, entityId: appliedId || undefined }, signal),
    [entityType, appliedId]
  );

  return (
    <div className="space-y-3">
      <form
        className="flex flex-wrap gap-2"
        onSubmit={(e) => {
          e.preventDefault();
          setAppliedId(entityId.trim());
        }}
      >
        <select className={`${inputClass} w-auto`} value={entityType} onChange={(e) => setEntityType(e.target.value)}>
          <option value="">Все объекты</option>
          {ENTITY_TYPES.map((t) => (
            <option key={t} value={t}>
              {t}
            </option>
          ))}
        </select>
        <input
          className={`${inputClass} flex-1`}
          placeholder="ID объекта (Enter)"
          value={entityId}
          onChange={(e) => setEntityId(e.target.value)}
        />
      </form>
      <LoadState state={audit} empty="Записей нет." isEmpty={(d) => d.length === 0}>
        {(items) => (
          <ul className="space-y-2">
            {items.map((a) => (
              <li key={a.id} className={cardClass}>
                <div className="flex flex-wrap items-center justify-between gap-2 text-xs">
                  <span className="font-semibold text-zinc-900">
                    {a.action} · {a.entityType}
                  </span>
                  <span className="text-zinc-500">{formatTs(a.createdAt)}</span>
                </div>
                <div className="text-[11px] text-zinc-500 break-all">
                  Объект: {a.entityId} · Кто: {a.actorAccountId}
                </div>
                <details className="text-[11px]">
                  <summary className="cursor-pointer text-zinc-600">До / после</summary>
                  <div className="grid grid-cols-1 sm:grid-cols-2 gap-2 mt-2">
                    <pre className="p-2 bg-zinc-50 rounded-lg overflow-x-auto max-h-64">{json(a.before)}</pre>
                    <pre className="p-2 bg-zinc-50 rounded-lg overflow-x-auto max-h-64">{json(a.after)}</pre>
                  </div>
                </details>
              </li>
            ))}
          </ul>
        )}
      </LoadState>
    </div>
  );
};
