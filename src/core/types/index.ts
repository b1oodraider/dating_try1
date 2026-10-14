// Types mirror docs/api-contract.md (API v1). Keep them in sync with the contract.

export type TagCategory = 'pace' | 'format' | 'calls' | 'boundaries' | 'social';

export interface Tag {
  code: string;
  category: TagCategory;
  categoryLabel: string;
  label: string;
  description: string;
  sortOrder: number;
}

export interface Photo {
  id: string;
  /** Relative signed URL (valid ~1h), usable directly in <img src> without Authorization. */
  url: string;
  mimeType: string;
  sizeBytes: number;
  sortOrder: number;
  isPrimary: boolean;
  createdAt: string;
}

export type PublicationStatus = 'DRAFT' | 'PUBLISHED' | 'HIDDEN';

/** Fields that must be filled before the profile can be published. */
export type PublishRequirement = 'displayName' | 'age' | 'city' | 'photos';

export interface MyProfile {
  id: string;
  displayName: string | null;
  age: number | null;
  city: string | null;
  aboutText: string | null;
  publicationStatus: PublicationStatus;
  dailyStatusText: string | null;
  /** Already null when the status has expired. */
  dailyStatusExpiresAt: string | null;
  tags: Tag[];
  photos: Photo[];
  completenessPercent: number;
  missingForPublish: PublishRequirement[];
  createdAt: string;
  updatedAt: string;
}

export interface PublicProfile {
  id: string;
  displayName: string;
  age: number;
  city: string;
  aboutText: string | null;
  dailyStatusText: string | null;
  tags: Tag[];
  photos: Photo[];
}

export type AccountStatus = 'ACTIVE' | 'SUSPENDED' | 'DELETED';

export interface Account {
  id: string;
  phoneMasked: string;
  status: AccountStatus;
  createdAt: string;
}

export interface AuthChallenge {
  challengeId: string;
  expiresAt: string;
  maskedPhone: string;
  /** Present only when the server runs in OTP mock mode. */
  devMockCode?: string;
}

export interface AuthToken {
  token: string;
  expiresAt: string;
  accountId: string;
}

export interface DiscoveryPage {
  items: PublicProfile[];
  page: number;
  limit: number;
  totalElements: number;
  totalPages: number;
}

export type TagLogic = 'AND' | 'OR';

export interface DiscoveryQuery {
  city?: string;
  minAge?: number;
  maxAge?: number;
  tagCodes?: string[];
  tagLogic?: TagLogic;
  page?: number;
  limit?: number;
}

/** PUT /me/profile body: every field optional, omitted = unchanged. */
export interface ProfileUpdate {
  displayName?: string;
  age?: number;
  city?: string;
  /** "" clears. */
  aboutText?: string;
  tagCodes?: string[];
  /** "" clears; any other value sets a 24h expiry. */
  dailyStatusText?: string;
}

/** Error body returned by every failing API call. */
export interface ApiErrorBody {
  code: string;
  message: string;
  traceId: string;
  fieldErrors?: Record<string, string>;
}
