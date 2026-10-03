import type { ApiPath, ApiResponse, ApiSchema } from "@/lib/api-contract";

/**
 * UI-facing aliases over the generated OpenAPI contract.
 *
 * `RequireKeys` records only which response properties the current UI treats as
 * invariant. Field value types continue to come from the generated backend contract.
 * Domain-specific unions below are deliberate UI refinements, not duplicated wire DTOs.
 */
type RequireKeys<T, K extends keyof T> = T & { [P in K]-?: NonNullable<T[P]> };

// --- Owners and map ---
export type OwnerListItem = RequireKeys<ApiSchema<"OwnerListItem">,
  "id" | "displayName" | "personalOrRegistryCode" | "status" | "parcelCount" | "updatedAt">;
export type MapParcelSummary = RequireKeys<ApiSchema<"ParcelMapSummary">,
  "cadastralCode" | "ownerId" | "ownerName" | "ownerStatus" | "areaHectares" | "marked">;
export type LandParcel = Omit<RequireKeys<ApiSchema<"LandParcel">,
  "id" | "cadastralCode" | "areaHectares">, "centroid"> & {
    centroid?: { longitude?: number; latitude?: number } | null;
    boundaryGeoJson?: { type: string; coordinates: unknown };
  };
export type OwnerActivity = RequireKeys<ApiSchema<"Activity">,
  "id" | "type" | "content" | "createdAt" | "createdBy">;
export type OwnerDetails = Omit<RequireKeys<ApiSchema<"OwnerDetails">,
  "id" | "displayName" | "personalOrRegistryCode" | "status" | "parcelCount" | "updatedAt" |
  "version" | "parcels" | "activities" | "followings">, "parcels" | "activities"> & {
    parcels: LandParcel[];
    activities: OwnerActivity[];
  };

export type PagedResponse<T> = {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};

// --- Reminders, messages, phonebook ---
export type Reminder = RequireKeys<ApiSchema<"ReminderDto">,
  "id" | "text" | "dueTime" | "createdAt" | "creator">;
export type Message = RequireKeys<ApiSchema<"MessageDto">,
  "id" | "message" | "sender" | "recipient" | "createdAt">;
export type PhonebookEntry = RequireKeys<ApiSchema<"PersonsDumpEntry">, "id">;

// --- Contracts ---
export type ContractHistoryItem = RequireKeys<ApiSchema<"ContractHistoryItem">, "id" | "createdAt"> & {
  dealId?: string | null;
  offerEntryId?: string | null;
};
export type CompanyProfile = RequireKeys<ApiSchema<"CompanyProfile">, "id" | "legalName" | "isDefault">;
export type ContractTemplate = RequireKeys<ApiSchema<"ContractTemplate">,
  "id" | "name" | "code" | "documentType" | "htmlTemplate" | "active" | "isDefault" | "updatedAt"> & {
    documentType: "PDF" | "DOCX";
  };
export type SaveCompanyProfile = ApiSchema<"SaveCompanyRequest">;
export type SaveContractTemplate = ApiSchema<"SaveTemplateRequest">;
export type GeneratedContract = RequireKeys<ApiSchema<"GeneratedContract">,
  "contractId" | "historyId" | "fileName" | "templateId" | "companyProfileId"> & {
    dealId?: string | null;
    offerEntryId?: string | null;
  };

// --- Admin ---
export type OwnerStatusDefinition = RequireKeys<ApiSchema<"OwnerStatusDefinition">,
  "id" | "colorHex" | "durationDays" | "isProtected">;
export type AdminPrepData = RequireKeys<ApiSchema<"AdminPrepData">,
  "counties" | "municipalities" | "users" | "ownerTypes" | "statuses">;
export type MaintainableUser = RequireKeys<ApiSchema<"MaintainableUserDto">,
  "id" | "username" | "name" | "roles">;
export type StatusChangeFrame = RequireKeys<ApiSchema<"StatusChangeFrame">, "frameStart" | "count">;
export type UserStatusChangeStats = RequireKeys<ApiSchema<"UserStatusChangeStats">, "userId" | "frames"> & {
  frames: StatusChangeFrame[];
};

// --- Registry and cadastre ---
export type RegistrySyncResult = RequireKeys<ApiSchema<"SyncResult">,
  "registry" | "cadastralCode" | "recordsUpdated" | "message">;
export type RegistryFullSync = ApiResponse<ApiPath<"/api/v1/registry/cadastres/{cadastralCode}/refresh-all">["post"]>;
export type RegistryHealth = RequireKeys<ApiSchema<"RegistryHealth">,
  "maaametCadastreWfs" | "metsaregisterNotificationsWfs" | "metsaregisterCompartmentsWfs" | "rikConfigured">;
export type CadastreLabels = RequireKeys<ApiSchema<"CadastreLabelsResponse">, "setLabels" | "availableLabels">;
export type CadastreEvaluation = RequireKeys<ApiSchema<"CadastreEvaluation">, "ourPrice" | "ownerPrice">;
export type CadastreNotification = RequireKeys<ApiSchema<"CadastreNotificationDto">, "id" | "cadastralCode" | "archived">;
export type ForestRegistryFeature = RequireKeys<ApiSchema<"ForestFeatureDto">, "id" | "cadastralCode">;
export type CadastreSubPart = RequireKeys<ApiSchema<"SubPartDto">, "id" | "cadastralCode">;

// --- Personal sales workspace ---
type GeneratedSalesTask = RequireKeys<ApiSchema<"SalesTask">,
  "ownerId" | "ownerName" | "personalOrRegistryCode" | "status" | "priorityReason" | "priorityScore" | "updatedAt">;
export type SalesTask = Omit<GeneratedSalesTask, "priorityReason"> & {
  priorityReason: "OVERDUE_FOLLOW_UP" | "DUE_TODAY" | "FIRST_CONTACT" | "STALE_OPPORTUNITY" | "ACTIVE_FOLLOW_UP";
};
export type SalesOutcome = NonNullable<ApiSchema<"OutcomeRequest">["outcome"]>;
export type SalesOutcomeResult = RequireKeys<ApiSchema<"OutcomeResult">, "status">;

// --- Sales management ---
export type TeamMemberPerformance = RequireKeys<ApiSchema<"TeamMemberPerformance">,
  "subject" | "assignedOwners" | "activeOpportunities" | "statusChangesLast7Days" | "overdueReminders">;
type GeneratedIntervention = RequireKeys<ApiSchema<"InterventionItem">,
  "ownerId" | "ownerName" | "status" | "reason">;
export type SalesInterventionItem = Omit<GeneratedIntervention, "reason"> & {
  reason: "UNASSIGNED_LEAD" | "STALE_OPPORTUNITY";
};
export type SalesManagementOverview = RequireKeys<ApiSchema<"SalesManagementOverview">,
  "totalOwners" | "assignedOwners" | "unassignedLeads" | "staleOpportunities" | "overdueReminders" |
  "salesActivitiesLast7Days" | "team" | "interventions"> & {
    team: TeamMemberPerformance[];
    interventions: SalesInterventionItem[];
  };

// --- Inheritance workflow ---
export type InheritanceHeir = RequireKeys<ApiSchema<"InheritanceHeir">,
  "id" | "displayName" | "contactStatus" | "source">;
export type InheritanceEvent = RequireKeys<ApiSchema<"InheritanceEvent">,
  "id" | "type" | "description" | "createdAt" | "createdBy">;
export type InheritanceCase = RequireKeys<ApiSchema<"InheritanceCaseDetails">,
  "id" | "ownerId" | "status" | "startedAt" | "heirs" | "events"> & {
    heirs: InheritanceHeir[];
    events: InheritanceEvent[];
  };
export type InheritanceWorkspace = RequireKeys<ApiSchema<"InheritanceWorkspace">, "ownerId"> & {
  inheritanceCase?: InheritanceCase | null;
};
export type OfficialInheritanceNoticeCheck = RequireKeys<ApiSchema<"OfficialNoticeCheckResult">,
  "sourceAvailable" | "noticeFound" | "message"> & {
    inheritanceCase?: InheritanceCase | null;
  };
export type StartInheritanceCase = ApiSchema<"StartInheritanceCaseRequest">;
export type SaveInheritanceHeir = ApiSchema<"SaveInheritanceHeirRequest">;

// --- Deal workflow ---
export type DealParcel = RequireKeys<ApiSchema<"DealParcel">, "id" | "cadastralCode" | "areaHectares">;
export type DealEvent = RequireKeys<ApiSchema<"DealEvent">, "id" | "type" | "description" | "createdAt" | "createdBy">;
type GeneratedDealEvaluation = RequireKeys<ApiSchema<"DealEvaluation">, "id" | "revision" | "status" | "createdBy">;
export type DealEvaluation = Omit<GeneratedDealEvaluation, "status"> & {
  status: "NEEDS_DATA" | "SUBMITTED" | "APPROVED";
};
type GeneratedDealCase = RequireKeys<ApiSchema<"DealDetails">,
  "id" | "ownerId" | "saleSubject" | "stage" | "salesSubject" | "nextAction" | "createdAt" |
  "parcels" | "events">;
export type DealCase = Omit<GeneratedDealCase, "saleSubject" | "parcels" | "latestEvaluation" | "events"> & {
  saleSubject: "FOREST" | "LAND" | "BOTH";
  parcels: DealParcel[];
  latestEvaluation?: DealEvaluation | null;
  events: DealEvent[];
};
export type BriefParcel = RequireKeys<ApiSchema<"BriefParcel">, "id" | "cadastralCode" | "areaHectares" | "updatedAt">;
export type CallBrief = Omit<RequireKeys<ApiSchema<"CallBrief">,
  "ownerId" | "ownerName" | "ownerStatus" | "parcels" | "deals">, "parcels" | "deals"> & {
    parcels: BriefParcel[];
    deals: DealCase[];
  };
type GeneratedCreateDeal = ApiSchema<"CreateDealRequest">;
export type CreateDeal = Omit<GeneratedCreateDeal, "saleSubject"> & {
  saleSubject: "FOREST" | "LAND" | "BOTH";
  parcelIds: string[];
};
type GeneratedSubmitEvaluation = ApiSchema<"SubmitEvaluationRequest">;
export type SubmitDealEvaluation = Omit<GeneratedSubmitEvaluation, "status"> & {
  status: "NEEDS_DATA" | "SUBMITTED" | "APPROVED";
};
export type EvaluationTask = RequireKeys<ApiSchema<"EvaluationTask">,
  "dealId" | "ownerId" | "ownerName" | "saleSubject" | "salesSubject" | "stage" | "parcelCount">;

// --- Registry freshness and synchronization ---
export type RegistrySourceFreshness = RequireKeys<ApiSchema<"SourceFreshness">,
  "registry" | "label" | "maxAgeHours" | "totalParcels" | "freshParcels" | "staleParcels" | "failuresLast24Hours">;
export type RegistryStaleParcel = RequireKeys<ApiSchema<"StaleParcel">, "cadastralCode" | "staleSources">;
export type RegistryFreshnessDashboard = RequireKeys<ApiSchema<"RegistryFreshnessDashboard">,
  "generatedAt" | "automaticRefreshEnabled" | "automaticRefreshDelayMs" | "automaticRefreshBatchSize" |
  "sources" | "staleParcels" | "openDataSignals"> & {
    sources: RegistrySourceFreshness[];
    staleParcels: RegistryStaleParcel[];
  };
export type RegistryRecoveryRun = RequireKeys<ApiSchema<"RecoveryRun">,
  "trigger" | "started" | "considered" | "successful" | "failed" | "message">;

type GeneratedOwnershipTransitionEvent = RequireKeys<ApiSchema<"OwnershipTransitionEvent">,
  "weaselTransitionId" | "cadastralCode" | "transitionAt" | "oldOwnerCount" | "newOwnerCount" | "relationshipDirection" | "importedAt">;
export type OwnershipTransitionEvent = Omit<GeneratedOwnershipTransitionEvent, "relationshipDirection"> & {
  relationshipDirection: "OUTGOING" | "INCOMING" | "MIXED" | "UNKNOWN";
};
export type OwnershipTransitionSyncRun = ApiResponse<ApiPath<"/api/v1/ownership-transitions/sync">["post"]>;
export type MetsisPortfolioSyncStatus = RequireKeys<ApiSchema<"SyncStatus">, "configured">;
export type MetsisPortfolioSyncResult = ApiResponse<ApiPath<"/api/v1/metsis-portfolio/sync">["post"]>;
