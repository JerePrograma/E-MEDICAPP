import type { Branch, Product } from '../catalog/types';

export type Chain = { id: string; name: string; active: boolean; version: number };
export type Plan = {
  id: string;
  name: string;
  minBranches: number;
  maxBranches: number | null;
  symbolicUnits: string | null;
  monthlyPrice: string | null;
  currency: string | null;
  active: boolean;
  version: number;
};
export type Subscription = {
  id: string;
  planId: string;
  chainId: string | null;
  branchId: string | null;
  branchIds: string[];
  monthlyPrice: string | null;
  currency: string | null;
  status: string;
  startsAt: string;
  paidThroughAt: string | null;
  version: number;
};
export type Member = {
  id: string;
  userId: string;
  userName: string;
  email: string;
  branchId: string;
  role: string;
  active: boolean;
  coldChainCapable: boolean;
  restrictedCapable: boolean;
  capabilityEvidenceDocumentId: string | null;
  capabilityVerifiedAt: string | null;
  capabilityVerifiedBy: string | null;
  version: number;
};
export type UserOption = { id: string; name: string; email: string; active: boolean };
export type Settings = {
  version: number;
  currency: string;
  quoteMinutes: number;
  reservationMinutes: number;
  graceDays: number;
  highValueThreshold: string;
  maxUploadMb: number;
  deliveryEnabled: boolean;
  pickupEnabled: boolean;
  fallbackEnabled: boolean;
  messages: Record<string, unknown>;
};
export type Overview = {
  products: Product[];
  chains: Chain[];
  branches: Branch[];
  plans: Plan[];
  subscriptions: Subscription[];
  memberships: Member[];
  users: UserOption[];
  config: Settings;
};
export type ProductInput = Omit<Product, 'id'>;
export type ChainInput = Omit<Chain, 'id'>;
export type BranchInput = Omit<Branch, 'id' | 'subscriptionEligible' | 'subscriptionState'>;
export type PlanInput = Omit<Plan, 'id'>;
export type MemberInput = Pick<
  Member,
  | 'userId'
  | 'branchId'
  | 'role'
  | 'active'
  | 'coldChainCapable'
  | 'restrictedCapable'
  | 'capabilityEvidenceDocumentId'
  | 'version'
>;
export type SubscriptionInput = Omit<Subscription, 'id' | 'paidThroughAt' | 'startsAt'> & {
  startsAt: string | null;
  periodStartsAt: string | null;
  periodEndsAt: string | null;
  paymentReference: string | null;
  evidenceDocumentId: string | null;
};
