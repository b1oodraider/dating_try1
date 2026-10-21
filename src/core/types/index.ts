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
  /** The face did not match the owner's verified photos: only the owner (and moderators) see it until a moderator checks it. */
  underReview: boolean;
}

/** Relationship goal from the reference list (GET /goals). */
export interface Goal {
  code: string;
  label: string;
  sortOrder: number;
}

export type PublicationStatus = 'DRAFT' | 'PUBLISHED' | 'HIDDEN';

/** Fields that must be filled before the profile can be published. */
export type PublishRequirement = 'displayName' | 'birthDate' | 'gender' | 'seeking' | 'city' | 'photos';

export type Gender = 'MALE' | 'FEMALE';

export interface MyProfile {
  id: string;
  displayName: string | null;
  /** Computed by the server from birthDate. */
  age: number | null;
  /** Locked after the first publish. */
  gender: Gender | null;
  /** Who I look for. */
  seeking: Gender | null;
  /** yyyy-MM-dd; locked after the first publish. */
  birthDate: string | null;
  /** Ages I accept (both ways); ageTo null = no upper limit. */
  ageFrom: number;
  ageTo: number | null;
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
  /** How other users see my profile (public cosmetic slots). */
  decorations: Decorations;
  heightCm: number | null;
  /** Sorted by sortOrder, at most 3. */
  goals: Goal[];
  /** I have premium right now (premium badge). */
  premium: boolean;
  /** Selfie checked by a moderator. */
  verified?: boolean;
  /** Completeness tips weighted to 100%. */
  tips?: ProfileTip[];
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
  decorations: Decorations;
  heightCm: number | null;
  goals: Goal[];
  /** The owner has premium right now (premium badge). */
  premium: boolean;
  /** Selfie checked by a moderator. */
  verified?: boolean;
  /** Match with the viewer by cards (0..100, null if fewer than 2 shared categories). */
  compatibility?: number | null;
  /** Whether the profile is currently boosted in discovery. */
  boosted?: boolean;
}

export type AccountStatus = 'ACTIVE' | 'SUSPENDED' | 'DELETED';

export interface Account {
  id: string;
  phoneMasked: string;
  status: AccountStatus;
  createdAt: string;
  /** QA account: isolated in discovery, may use the testing API (test panel). */
  testAccount: boolean;
  /** Staff roles, e.g. ['ADMIN', 'MODERATOR', 'CONTENT_EDITOR', 'SUPPORT']. */
  roles?: string[];
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
  /** Premium (ADVANCED_FILTERS): height range in cm; profiles without height do not match. */
  minHeight?: number;
  maxHeight?: number;
  /** Premium (ADVANCED_FILTERS): the profile has at least one of these goals. */
  goalCodes?: string[];
  page?: number;
  limit?: number;
}

/** PUT /me/profile body: every field optional, omitted = unchanged. */
export interface ProfileUpdate {
  displayName?: string;
  /** Legacy: an approximate birth date when birthDate is absent. */
  age?: number;
  gender?: Gender;
  seeking?: Gender;
  /** yyyy-MM-dd, 18..100 years. */
  birthDate?: string;
  ageFrom?: number;
  ageTo?: number;
  clearAgeTo?: boolean;
  city?: string;
  /** "" clears. */
  aboutText?: string;
  tagCodes?: string[];
  /** "" clears; any other value sets a 24h expiry. */
  dailyStatusText?: string;
  /** 120..230 cm. */
  heightCm?: number;
  /** true removes the height (400 fieldErrors.heightCm if sent together with heightCm). */
  clearHeight?: boolean;
  /** <= 3 known goal codes (else 400 UNKNOWN_GOAL); [] clears. */
  goalCodes?: string[];
}

/**
 * GET/PUT /me/exclusion-words. Private to the caller: discovery hides every profile whose aboutText or
 * active dailyStatusText contains any of the words in any grammatical form (server-side).
 */
export interface ExclusionWords {
  /** Normalized by the server: trimmed, lowercase, deduplicated, original order kept. */
  words: string[];
  maxWords: number;
  /** false: the list is kept but not applied (premium EXCLUSION_WORDS is not active). */
  active: boolean;
}

/** GET/PUT /me/excluded-goals: profiles having any of these goals are hidden (premium ADVANCED_FILTERS). */
export interface ExcludedGoals {
  goalCodes: string[];
  /** false: the list is kept but not applied (premium is not active). */
  active: boolean;
}

/** Feature codes from Wallet.premiumFeatures; a feature not listed there is free. */
export const PREMIUM_FEATURE = {
  ADVANCED_FILTERS: 'ADVANCED_FILTERS',
  EXCLUSION_WORDS: 'EXCLUSION_WORDS',
} as const;

/** Error body returned by every failing API call. */
export interface ApiErrorBody {
  code: string;
  message: string;
  traceId: string;
  fieldErrors?: Record<string, string>;
  /** Machine-readable context, e.g. { feature } for PREMIUM_REQUIRED, { itemCode, available } for INSUFFICIENT_BALANCE. */
  details?: Record<string, unknown>;
}

// ---------------------------------------------------------------------------
// Economy: decorations, wallet, cosmetics, achievements, testing API
// ---------------------------------------------------------------------------

export type Rarity = 'COMMON' | 'RARE' | 'EPIC' | 'LEGENDARY';

/** Known slots; the server may add more, clients ignore unknown ones. */
export type CosmeticSlot = 'PHOTO_FRAME' | 'PROFILE_STATUS' | 'CHAT_WALLPAPER';

/** An equipped cosmetic as other users see it. Render generically from attributes, ignore unknown keys. */
export interface Decoration {
  code: string;
  title: string;
  rarity: Rarity | null;
  attributes: Record<string, unknown>;
}

/** slot -> decoration; only public slots; may be {}. */
export type Decorations = Partial<Record<string, Decoration>>;

export interface WalletItem {
  code: string;
  kind: 'CONSUMABLE' | 'COSMETIC';
  /** SUPERLIKE | GACHA_TICKET | BOOST | STICKER_PACK | CHAT_WALLPAPER | PROFILE_STATUS | PHOTO_FRAME | ... */
  category: string;
  title: string;
  rarity: Rarity | string | null;
  equipSlot: string | null;
  attributes: Record<string, unknown>;
  /** Total units (cosmetics: 1). */
  quantity: number;
  /** Part of quantity from the premium allowance (spent first). */
  periodicQuantity: number;
}

export interface Wallet {
  premium: { active: boolean; until: string | null };
  premiumFeatures: string[];
  /** Only quantity > 0; consumables first. */
  items: WalletItem[];
  /** slot -> itemCode, including private slots (CHAT_WALLPAPER). */
  equipped: Partial<Record<string, string>>;
}

export type AchievementReward =
  | { type: 'ITEM'; itemCode: string; quantity: number; days?: null }
  | { type: 'PREMIUM_DAYS'; days: number; itemCode?: null; quantity?: null };

export interface Achievement {
  code: string;
  title: string;
  description: string | null;
  completed: boolean;
  completedAt: string | null;
  rewards: AchievementReward[];
}

/** Returned by every /testing/* endpoint. */
export interface TestingState {
  accountId: string;
  /** How far this account's economy clock is moved forward. */
  clockOffsetDays: number;
  /** "Now" used for premium expiry and allowance periods of this account. */
  economyNow: string;
  wallet: Wallet;
  achievements: Achievement[];
}

export interface ProfileTip {
  code: string;
  text: string;
  weight: number;
  done: boolean;
  requiredToPublish: boolean;
}

// ---------------------------------------------------------------------------
// Reactions & Matches
// ---------------------------------------------------------------------------

export interface ReactionResult {
  matched: boolean;
  matchId?: string;
  /** The like waits until my selfie is verified. */
  queued?: boolean;
}

export type MessageType = 'TEXT' | 'STICKER';

export interface Message {
  id: string;
  matchId: string;
  mine: boolean;
  type: MessageType;
  text: string | null;
  stickerPackCode: string | null;
  sticker: string | null;
  clientMessageId: string | null;
  createdAt: string;
}

export interface Match {
  id: string;
  createdAt: string;
  profile: PublicProfile;
  lastMessage: Message | null;
  unreadCount: number;
  myLastReadMessageId: string | null;
  otherLastReadMessageId: string | null;
  otherLastReadAt: string | null;
}

export interface ReadState {
  matchId: string;
  lastReadMessageId: string;
  lastReadAt: string;
  unreadCount: number;
}

export interface ReceivedLike {
  profile: PublicProfile;
  superlike: boolean;
  likedAt: string;
}

export interface ReceivedLikesCount {
  count: number;
  superlikeCount: number;
}

// ---------------------------------------------------------------------------
// Boost
// ---------------------------------------------------------------------------

export interface BoostHistoryEntry {
  id: string;
  startsAt: string;
  endsAt: string;
  impressions: number;
}

export interface BoostStatus {
  active: boolean;
  startsAt: string | null;
  endsAt: string | null;
  impressions: number;
  available: number;
  durationMinutes: number;
  now: string;
  history: BoostHistoryEntry[];
}

// ---------------------------------------------------------------------------
// Gacha
// ---------------------------------------------------------------------------

export interface PityRules {
  epicOrBetterWithin: number | null;
  legendaryWithin: number | null;
  softPityStartsAt: number | null;
  softPityLegendaryWeightStep: number;
}

export interface PityProgress {
  pityGroup: string;
  pullsSinceEpic: number;
  pullsSinceLegendary: number;
  epicGuaranteedIn: number | null;
  legendaryGuaranteedIn: number | null;
  totalPulls: number;
}

export interface PoolEntry {
  entryId: string;
  rarity: Rarity;
  reward: {
    type: 'ITEM' | 'PREMIUM_DAYS';
    item: WalletItem | null;
    quantity: number | null;
    days: number | null;
  };
  weight: number;
  percent: number;
  featured: boolean;
}

export interface Banner {
  code: string;
  title: string;
  description: string | null;
  type: 'PERMANENT' | 'LIMITED';
  startsAt: string | null;
  endsAt: string | null;
  open: boolean;
  attributes: Record<string, unknown>;
  cost: {
    itemCode: string;
    perPull: number;
    multiPullSize: number;
    multiPullCost: number;
  };
  pityRules: PityRules;
  myPity: PityProgress;
  rates: {
    totalWeight: number;
    rarities: Array<{ rarity: Rarity; weight: number; percent: number }>;
  };
  featured: PoolEntry[];
  pool?: PoolEntry[];
}

export interface PullResult {
  index: number;
  entryId: string;
  rarity: Rarity;
  reward: {
    type: 'ITEM' | 'PREMIUM_DAYS';
    item: WalletItem | null;
    quantity: number | null;
    days: number | null;
  };
  isNew: boolean;
  outcome: 'NEW' | 'DUPLICATE_SHARDS';
  shardsGranted: number;
  pityTrigger: 'EPIC_PITY' | 'LEGENDARY_PITY' | 'FORCED' | null;
}

export interface GachaPullResponse {
  requestId: string;
  bannerCode: string;
  count: number;
  replayed: boolean;
  results: PullResult[];
  shardsGranted: number;
  pity: PityProgress;
  costBalance: { itemCode: string; quantity: number };
  shardBalance: { itemCode: string; quantity: number };
}

export interface GachaShardsInfo {
  balance: { itemCode: string; quantity: number };
  ticketItemCode: string;
  shardsPerTicket: number;
  maxTicketsPerExchange: number;
  duplicateShards: Record<Rarity, number>;
}

export interface ShardExchangeResponse {
  applied: boolean;
  tickets: number;
  shardsSpent: number;
  shardBalance: { itemCode: string; quantity: number };
  ticketBalance: { itemCode: string; quantity: number };
}

export interface GachaHistoryItem {
  id: string;
  requestId: string;
  bannerCode: string;
  bannerTitle: string;
  index: number;
  entryId: string;
  rarity: Rarity;
  reward: {
    type: 'ITEM' | 'PREMIUM_DAYS';
    item: WalletItem | null;
    quantity: number | null;
    days: number | null;
  };
  isNew: boolean;
  outcome: string;
  shardsGranted: number;
  pityTrigger: string | null;
  createdAt: string;
}

// ---------------------------------------------------------------------------
// Verification & Reports
// ---------------------------------------------------------------------------

export interface VerificationRequest {
  id: string;
  status: 'AWAITING_SELFIE' | 'PENDING' | 'APPROVED' | 'REJECTED' | 'EXPIRED';
  gesture: string;
  instruction: string;
  expiresAt: string;
  submittedAt?: string | null;
  decidedAt?: string | null;
  rejectReason?: string | null;
}

export type ReportReason =
  | 'SPAM'
  | 'FAKE_PROFILE'
  | 'INAPPROPRIATE_CONTENT'
  | 'HARASSMENT'
  | 'SCAM'
  | 'UNDERAGE'
  | 'NOT_RESPONDING'
  | 'OTHER';

export interface ReportResponse {
  id: string;
  createdAt: string;
  status: 'NEW' | 'DISMISSED';
  note: string | null;
}

/** GET /me/restrictions item. */
export interface Restriction {
  kind: 'LIKES' | 'MESSAGES' | 'HIDDEN' | 'SUSPENDED' | 'VERIFICATION_REQUIRED';
  reason: string;
  startsAt: string;
  endsAt: string | null;
}


// ---------------------------------------------------------------------------
// Staff (/admin/**): see docs/openapi.yaml
// ---------------------------------------------------------------------------

export type StaffRole = 'ADMIN' | 'MODERATOR' | 'CONTENT_EDITOR' | 'SUPPORT';
export type RestrictionKind = Restriction['kind'];

export interface AdminAccount {
  accountId: string;
  phoneMasked: string;
  status: 'ACTIVE' | 'SUSPENDED' | 'DELETION_PENDING' | 'DELETED';
  testAccount: boolean;
  roles: string[];
  createdAt: string;
}

export interface AdminRoles {
  available: string[];
  accounts: AdminAccount[];
}

export interface AuditEntry {
  id: string;
  actorAccountId: string;
  action: string;
  entityType: string;
  entityId: string;
  before: unknown;
  after: unknown;
  createdAt: string;
}

export type ModerationStatus = 'NEW' | 'IN_REVIEW' | 'RESOLVED' | 'DISMISSED';
export type ModerationDecision = 'ACCEPTED' | 'RECLASSIFIED' | 'REJECTED';

export interface ModerationReport {
  id: string;
  status: ModerationStatus;
  reason: ReportReason;
  finalReason: ReportReason | null;
  decision: ModerationDecision | null;
  sanctioned: boolean;
  autoDismissed: boolean;
  priority: number;
  priorityReasons: string[];
  reportedRiskScore: number | null;
  comment: string | null;
  messageId: string | null;
  messageSnapshot: string | null;
  reporterAccountId: string;
  reportedAccountId: string;
  reportedProfileId: string;
  reportedDisplayName: string | null;
  reportedConfirmedViolations: number;
  createdAt: string;
  moderatorAccountId: string | null;
  moderatorNote: string | null;
  decidedAt: string | null;
}

export interface ReportDecisionRequest {
  decision: ModerationDecision;
  reason?: ReportReason;
  note?: string;
  sanction?: boolean;
  clearAboutText?: boolean;
  clearDailyStatus?: boolean;
}

// Support tickets (docs/support.md)
export type SupportCategory = 'ACCOUNT' | 'PROFILE' | 'LIKES_MATCHES' | 'PAYMENTS' | 'SAFETY' | 'BUG' | 'OTHER';
export type SupportStatus = 'OPEN' | 'ANSWERED' | 'CLOSED';

export interface SupportMessage {
  id: string;
  fromStaff: boolean;
  text: string;
  createdAt: string;
}

/** GET /admin/support/tickets item. */
export interface SupportQueueItem {
  id: string;
  accountId: string;
  profileId: string | null;
  displayName: string | null;
  testAccount: boolean;
  category: SupportCategory;
  status: SupportStatus;
  createdAt: string;
  lastMessageAt: string;
  preview: string | null;
}

/** GET /admin/support/tickets/{id}. */
export interface SupportStaffTicket {
  id: string;
  accountId: string;
  phoneMasked: string;
  profileId: string | null;
  displayName: string | null;
  testAccount: boolean;
  category: SupportCategory;
  status: SupportStatus;
  createdAt: string;
  updatedAt: string;
  lastMessageAt: string;
  messages: SupportMessage[];
}

export interface AdminPage<T> {
  items: T[];
  page: number;
  limit: number;
  totalElements: number;
}

/** GET /admin/photo-reviews item: a verified person's new photo whose face did not match the references. */
export interface PhotoReviewItem {
  photoId: string;
  accountId: string;
  profileId: string;
  displayName: string | null;
  photo: Photo;
  references: Photo[];
  uploadedAt: string;
}

export interface VerificationReview {
  id: string;
  accountId: string;
  profileId: string | null;
  displayName: string | null;
  status: VerificationRequest['status'];
  gesture: string;
  instruction: string;
  selfieAvailable: boolean;
  submittedAt: string | null;
  decidedAt: string | null;
  decidedBy: string | null;
  rejectReason: string | null;
  profilePhotos: Photo[];
}

/** /admin/accounts/{id}/restrictions item (full history row). */
export interface AdminRestriction {
  id: string;
  accountId: string;
  kind: RestrictionKind;
  reason: string;
  source: 'MODERATOR' | 'RULE' | 'ADMIN';
  reportId: string | null;
  createdBy: string | null;
  note: string | null;
  startsAt: string;
  endsAt: string | null;
  liftedAt: string | null;
  liftedBy: string | null;
  liftNote: string | null;
}

export interface RiskSignal {
  code: string;
  weight: number;
  detail: string;
}

export interface Risk {
  accountId: string;
  score: number;
  level: 'LOW' | 'MEDIUM' | 'HIGH';
  signals: RiskSignal[];
  computedAt: string;
}

export interface RiskQueueItem extends Risk {
  profileId: string | null;
  displayName: string | null;
}

export interface Behavior {
  accountId: string;
  computedAt: string;
  /** BehaviorMetrics: counts and rates (rates are null when there is nothing to divide). */
  metrics: Record<string, number | null>;
}
