import React from 'react';
import type { Decorations } from '../../core/types';

// Generic rendering of cosmetics from their free-form `attributes` (docs/api-contract.md, Decoration).
// Unknown slots and keys are ignored; malformed values are skipped instead of breaking the layout.

const HEX_COLOR_RE = /^#[0-9a-fA-F]{6}$/;
const MAX_FRAME_WIDTH = 12;

export function attrString(attributes: Record<string, unknown> | undefined, key: string): string | null {
  const value = attributes?.[key];
  return typeof value === 'string' && value.trim() !== '' ? value : null;
}

export function attrColor(attributes: Record<string, unknown> | undefined, key: string): string | null {
  const value = attrString(attributes, key);
  return value && HEX_COLOR_RE.test(value) ? value : null;
}

export function attrNumber(attributes: Record<string, unknown> | undefined, key: string): number | null {
  const value = attributes?.[key];
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

export function attrStringList(attributes: Record<string, unknown> | undefined, key: string): string[] {
  const value = attributes?.[key];
  return Array.isArray(value) ? value.filter((v): v is string => typeof v === 'string') : [];
}

// ---------------------------------------------------------------------------
// PHOTO_FRAME: { borderColor, borderWidth, glow? }
// ---------------------------------------------------------------------------

export interface PhotoFrame {
  color: string;
  width: number;
  glow: boolean;
}

export function photoFrameFromAttributes(attributes: Record<string, unknown> | undefined): PhotoFrame | null {
  const color = attrColor(attributes, 'borderColor');
  if (!color) return null;
  const width = Math.min(Math.max(attrNumber(attributes, 'borderWidth') ?? 2, 1), MAX_FRAME_WIDTH);
  return { color, width, glow: attributes?.glow === true };
}

export function getPhotoFrame(decorations: Decorations | undefined): PhotoFrame | null {
  return photoFrameFromAttributes(decorations?.PHOTO_FRAME?.attributes);
}

/** Outer glow for the photo container (its own box-shadow is not clipped by its overflow-hidden). */
export function photoFrameOuterStyle(frame: PhotoFrame | null): React.CSSProperties | undefined {
  return frame?.glow ? { boxShadow: `0 0 18px 2px ${frame.color}` } : undefined;
}

/**
 * Frame drawn ABOVE the photo (an inset box-shadow on the container itself would be hidden by the <img>).
 * Place it as the last child of a `relative` photo container.
 */
export const PhotoFrameOverlay: React.FC<{ frame: PhotoFrame | null }> = ({ frame }) => {
  if (!frame) return null;
  const shadows = [`inset 0 0 0 ${frame.width}px ${frame.color}`];
  if (frame.glow) shadows.push(`inset 0 0 18px 2px ${frame.color}`);
  return (
    <div
      aria-hidden="true"
      className="pointer-events-none absolute inset-0"
      style={{ boxShadow: shadows.join(', '), borderRadius: 'inherit' }}
    />
  );
};

// ---------------------------------------------------------------------------
// PROFILE_STATUS: { emoji, label, color }
// ---------------------------------------------------------------------------

export interface ProfileStatus {
  emoji: string | null;
  label: string;
  color: string | null;
}

export function profileStatusFromAttributes(attributes: Record<string, unknown> | undefined): ProfileStatus | null {
  const label = attrString(attributes, 'label');
  if (!label) return null;
  return { emoji: attrString(attributes, 'emoji'), label, color: attrColor(attributes, 'color') };
}

export const StatusPill: React.FC<{ status: ProfileStatus | null; size?: 'sm' | 'md'; className?: string }> = ({
  status,
  size = 'sm',
  className = '',
}) => {
  if (!status) return null;
  const style: React.CSSProperties | undefined = status.color
    ? { color: status.color, backgroundColor: `${status.color}1A`, borderColor: `${status.color}55` }
    : undefined;
  return (
    <span
      title={status.label}
      style={style}
      className={`inline-flex items-center gap-1 rounded-full border font-semibold whitespace-nowrap max-w-full ${
        status.color ? '' : 'bg-zinc-100 text-zinc-700 border-zinc-200'
      } ${size === 'sm' ? 'text-[10px] px-1.5 py-0.5' : 'text-xs px-2 py-0.5'} ${className}`}
    >
      {status.emoji && <span aria-hidden="true">{status.emoji}</span>}
      <span className="truncate">{status.label}</span>
    </span>
  );
};

/** Status pill of a profile's PROFILE_STATUS decoration (nothing when absent or malformed). */
export const ProfileStatusPill: React.FC<{ decorations: Decorations | undefined; size?: 'sm' | 'md'; className?: string }> = ({
  decorations,
  ...rest
}) => <StatusPill status={profileStatusFromAttributes(decorations?.PROFILE_STATUS?.attributes)} {...rest} />;

// ---------------------------------------------------------------------------
// Rarity
// ---------------------------------------------------------------------------

const RARITY_STYLES: Record<string, { label: string; className: string }> = {
  COMMON: { label: 'Обычный', className: 'bg-zinc-100 text-zinc-600 border-zinc-200' },
  RARE: { label: 'Редкий', className: 'bg-sky-50 text-sky-700 border-sky-200' },
  EPIC: { label: 'Эпический', className: 'bg-violet-50 text-violet-700 border-violet-200' },
  LEGENDARY: { label: 'Легендарный', className: 'bg-amber-50 text-amber-700 border-amber-300' },
};

export const RarityBadge: React.FC<{ rarity: string | null | undefined }> = ({ rarity }) => {
  if (!rarity) return null;
  const known = RARITY_STYLES[rarity];
  return (
    <span
      className={`inline-flex items-center text-[10px] font-semibold uppercase tracking-wide px-1.5 py-0.5 rounded-md border ${
        known?.className ?? 'bg-zinc-100 text-zinc-600 border-zinc-200'
      }`}
    >
      {known?.label ?? rarity}
    </span>
  );
};
