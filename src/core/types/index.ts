export type AccountStatus = 'ACTIVE' | 'SUSPENDED' | 'DELETION_PENDING' | 'DELETED';

export interface Account {
  id: string;
  phoneMasked: string; // e.g. +7 (999) ***-**-42 (never log or expose raw phone publicly)
  status: AccountStatus;
  phoneVerifiedAt: string;
  createdAt: string;
  updatedAt: string;
}

export type ProfileVisibilityStatus = 'PUBLIC' | 'HIDDEN';
export type ProfilePublicationStatus = 'DRAFT' | 'PUBLISHED' | 'HIDDEN';

export interface CommunicationTag {
  id: string;
  code: string;
  category: 'pace' | 'format' | 'calls' | 'social' | 'boundaries';
  categoryLabel: string;
  label: string;
  description: string;
  active: boolean;
  sortOrder: number;
}

export interface ProfilePhoto {
  id: string;
  profileId: string;
  storageKey: string;
  url: string; // Authorized URL or data URL
  mimeType: string;
  sizeBytes: number;
  sortOrder: number;
  isPrimary: boolean;
  createdAt: string;
}

export interface DailyStatus {
  text: string;
  expiresAt: string; // 24h expiration
}

export interface Profile {
  id: string;
  accountId: string;
  displayName: string;
  age: number; // 18+
  city: string;
  aboutText?: string;
  publicationStatus: ProfilePublicationStatus;
  visibilityStatus: ProfileVisibilityStatus;
  tags: CommunicationTag[];
  photos: ProfilePhoto[];
  dailyStatus?: DailyStatus | null;
  completenessPercent: number;
  createdAt: string;
  updatedAt: string;
}

export interface Session {
  id: string;
  accountId: string;
  expiresAt: string;
}

export interface AuthChallenge {
  challengeId: string;
  expiresAt: string;
  maskedPhone: string;
  devMockCode?: string; // only provided in dev/test environment
}

export interface DiscoveryFilterParams {
  selectedTagCodes?: string[];
  tagFilterLogic?: 'AND' | 'OR';
  city?: string;
  minAge?: number;
  maxAge?: number;
  page?: number;
  limit?: number;
}

export interface ApiError {
  code: string;
  message: string;
  traceId?: string;
  fieldErrors?: Record<string, string>;
}

export interface ProductEvent {
  eventId: string;
  eventType: string;
  schemaVersion: number;
  occurredAt: string;
  attributes: Record<string, string | number | boolean>;
}
