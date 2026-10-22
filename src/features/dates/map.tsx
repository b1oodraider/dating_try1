import React from 'react';
import { TileLayer } from 'react-leaflet';
import 'leaflet/dist/leaflet.css';
import type { DateActivity, VenueAd } from '../../core/types';

/** Kinds of activity for a date point and a place (contract `DatePlanItem.activity`, `Venue.category`). */
export const DATE_ACTIVITIES: Record<DateActivity, string> = {
  COFFEE: 'Кофе',
  WALK: 'Прогулка',
  FOOD: 'Еда',
  CINEMA: 'Кино',
  CULTURE: 'Выставка, театр',
  ACTIVE: 'Активный отдых',
  OTHER: 'Другое',
};

/** Moscow centre: where the map opens when the location is unknown. */
export const FALLBACK_CENTER: [number, number] = [55.7558, 37.6173];

/** Map pin colours (circle markers: Leaflet's default icon images break under Vite). */
export const PIN = { picked: '#e11d48', sponsored: '#d97706', plain: '#27272a' };

/**
 * 2GIS raster tiles, the same in the picker and the plan view.
 * shortcut: public tile servers without a key or a contract — fine for testing; before release sign a 2GIS
 * agreement (API key, limits) and put its tile address here.
 */
export const MapTiles: React.FC = () => (
  <TileLayer
    url="https://tile{s}.maps.2gis.com/tiles?x={x}&y={y}&z={z}"
    subdomains="0123"
    maxNativeZoom={18}
    attribution="© 2ГИС"
  />
);

/** «Реклама · ООО «Кафе» · erid 2VtzqX» next to a sponsored place (38-ФЗ). */
export function adText(ad: VenueAd): string {
  return [ad.label || 'Реклама', ad.advertiser, ...(ad.erid ? [`erid ${ad.erid}`] : [])].join(' · ');
}

export const AdLabel: React.FC<{ ad: VenueAd }> = ({ ad }) => (
  <p className="text-[11px] text-zinc-500">{adText(ad)}</p>
);
