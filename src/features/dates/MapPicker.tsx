import React, { useEffect, useRef, useState } from 'react';
import { CircleMarker, MapContainer, useMapEvents } from 'react-leaflet';
import type { LatLng, Map as LeafletMap } from 'leaflet';
import { api, getErrorMessage } from '../../core/api/client';
import type { Venue } from '../../core/types';
import { Modal } from '../../shared/components/Modal';
import { Button } from '../../shared/components/Button';
import { AdLabel, FALLBACK_CENTER, MapTiles, PIN } from './map';

/** What was picked on the map: a curated place, or any point with a name typed by hand. */
export interface PlacePick {
  name: string;
  address: string | null;
  latitude: number;
  longitude: number;
  venue: Venue | null;
}

const MapEvents: React.FC<{ onTap: (p: LatLng) => void; onMoveEnd: (c: LatLng) => void }> = ({ onTap, onMoveEnd }) => {
  useMapEvents({
    click: (e) => onTap(e.latlng),
    moveend: (e) => onMoveEnd(e.target.getCenter()),
  });
  return null;
};

/** Picks a place for a date point: curated places around (sponsored ones with their ad label) or a tap anywhere. */
export const MapPicker: React.FC<{ initial: PlacePick | null; onClose: () => void; onPick: (p: PlacePick) => void }> = ({
  initial,
  onClose,
  onPick,
}) => {
  const [venues, setVenues] = useState<Venue[]>([]);
  const [venue, setVenue] = useState<Venue | null>(initial?.venue ?? null);
  const [point, setPoint] = useState<[number, number] | null>(initial ? [initial.latitude, initial.longitude] : null);
  const [name, setName] = useState(initial && !initial.venue ? initial.name : '');
  const [error, setError] = useState<string | null>(null);
  const mapRef = useRef<LeafletMap | null>(null);
  const loading = useRef<AbortController | null>(null);

  const loadVenues = (lat: number, lon: number) => {
    loading.current?.abort();
    const ctrl = new AbortController();
    loading.current = ctrl;
    api
      .getVenues(lat, lon, ctrl.signal)
      .then(setVenues)
      .catch((e: unknown) => {
        if (!ctrl.signal.aborted) setError(getErrorMessage(e));
      });
  };

  useEffect(() => {
    const start = point ?? FALLBACK_CENTER;
    loadVenues(start[0], start[1]);
    // My position only when the permission is already granted (no prompt), as on mobile.
    if (!initial && navigator.permissions && navigator.geolocation) {
      navigator.permissions
        .query({ name: 'geolocation' })
        .then((status) => {
          if (status.state !== 'granted') return;
          navigator.geolocation.getCurrentPosition((pos) =>
            mapRef.current?.setView([pos.coords.latitude, pos.coords.longitude], 14)
          );
        })
        .catch(() => {});
    }
    return () => loading.current?.abort();
  }, []);

  const done = () => {
    if (!point) return;
    const placeName = venue?.name ?? name.trim();
    if (!placeName) {
      setError('Назовите место');
      return;
    }
    onPick({ name: placeName, address: venue?.address ?? null, latitude: point[0], longitude: point[1], venue });
  };

  return (
    <Modal isOpen onClose={onClose} title="Место встречи" maxWidth="xl">
      <div className="h-80 rounded-xl overflow-hidden border border-zinc-200 mb-3">
        <MapContainer ref={mapRef} center={point ?? FALLBACK_CENTER} zoom={14} className="h-full w-full">
          <MapTiles />
          <MapEvents
            onTap={(p) => {
              setVenue(null);
              setPoint([p.lat, p.lng]);
            }}
            onMoveEnd={(c) => loadVenues(c.lat, c.lng)}
          />
          {venues.map((v) => (
            <CircleMarker
              key={v.id}
              center={[v.latitude, v.longitude]}
              radius={10}
              bubblingMouseEvents={false}
              pathOptions={{
                color: v.id === venue?.id ? PIN.picked : v.ad ? PIN.sponsored : PIN.plain,
                fillOpacity: 0.8,
              }}
              eventHandlers={{
                click: () => {
                  setVenue(v);
                  setPoint([v.latitude, v.longitude]);
                },
              }}
            />
          ))}
          {point && !venue && (
            <CircleMarker center={point} radius={10} pathOptions={{ color: PIN.picked, fillOpacity: 0.8 }} />
          )}
        </MapContainer>
      </div>

      {venues.some((v) => v.ad) && (
        <p className="text-[11px] text-zinc-500 mb-2">
          <span className="inline-block w-2 h-2 rounded-full mr-1" style={{ background: PIN.sponsored }} />
          рекламные места
        </p>
      )}

      <div className="space-y-1 mb-3 text-sm">
        {venue ? (
          <>
            <p className="font-semibold text-zinc-900">{venue.name}</p>
            {venue.address && <p className="text-zinc-700">{venue.address}</p>}
            {venue.description && <p className="text-xs text-zinc-500">{venue.description}</p>}
            {venue.ad && <AdLabel ad={venue.ad} />}
          </>
        ) : point ? (
          <input
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            maxLength={120}
            placeholder="Как назвать место"
            className="w-full px-3.5 py-2 text-sm bg-zinc-50 border border-zinc-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-zinc-900"
          />
        ) : (
          <p className="text-zinc-500">Выберите место на карте или нажмите в любую точку</p>
        )}
        {error && <p className="text-rose-600">{error}</p>}
      </div>
      <Button className="w-full" disabled={!point} onClick={done}>
        Выбрать
      </Button>
    </Modal>
  );
};
