import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  overview: vi.fn(),
  refetchOverview: vi.fn(),
}));

const query = <T,>(data: T) => ({
  data,
  error: null,
  isError: false,
  isPending: false,
  refetch: vi.fn(),
});

const mutation = () => ({
  error: null,
  isError: false,
  isPending: false,
  mutate: vi.fn(),
});

vi.mock("next/link", () => ({
  default: ({ href, children }: { href: string; children: React.ReactNode }) => <a href={href}>{children}</a>,
}));

vi.mock("lucide-react", () => {
  const Icon = () => <span aria-hidden="true" />;
  return {
    ArrowLeft: Icon,
    Database: Icon,
    Edit2: Icon,
    Eye: Icon,
    EyeOff: Icon,
    MapPin: Icon,
    Phone: Icon,
    Plus: Icon,
    Save: Icon,
    ShieldAlert: Icon,
    UserRound: Icon,
    X: Icon,
  };
});

vi.mock("@/components/map/parcel-map", () => ({
  ParcelMap: () => <div aria-label="Kinnistute kaart" />,
}));
vi.mock("@/components/owners/deal-case-panel", () => ({ DealCasePanel: () => <div /> }));
vi.mock("@/components/owners/inheritance-case-panel", () => ({ InheritanceCasePanel: () => <div /> }));
vi.mock("@/components/owners/ownership-transition-panel", () => ({ OwnershipTransitionPanel: () => <div /> }));
vi.mock("@/components/owners/owner-status-transition-select", () => ({
  OwnerStatusTransitionSelect: () => <select aria-label="Uus staatus" />,
  ownerStatusLabel: (status: string) => status,
}));

vi.mock("@/components/ui/badge", () => ({
  Badge: ({ children }: { children: React.ReactNode }) => <span>{children}</span>,
}));
vi.mock("@/components/ui/button", () => ({
  Button: ({ asChild, children, ...props }: React.ButtonHTMLAttributes<HTMLButtonElement> & { asChild?: boolean }) =>
    asChild ? <>{children}</> : <button {...props}>{children}</button>,
}));
vi.mock("@/components/ui/card", () => ({
  Card: ({ children }: { children: React.ReactNode }) => <section>{children}</section>,
  CardContent: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  CardHeader: ({ children }: { children: React.ReactNode }) => <header>{children}</header>,
  CardTitle: ({ children }: { children: React.ReactNode }) => <h2>{children}</h2>,
}));
vi.mock("@/components/ui/input", () => ({
  Input: (props: React.InputHTMLAttributes<HTMLInputElement>) => <input {...props} />,
}));

vi.mock("@/lib/auth", () => ({
  useAuth: () => ({ username: "viewer", hasRole: () => false }),
}));

vi.mock("@/lib/api", () => ({
  useOwner360Overview: () => mocks.overview(),
  useOwner360Relations: () => query({ content: [], offset: 0, limit: 200, hasMore: false }),
  useOwner360Map: () => query([]),
  useOwner360History: () => query({ content: [], offset: 0, limit: 100, hasMore: false }),
  useOwner360Workflow: () => query(undefined),
  usePortfolioBrief: () => query(undefined),
  useOwner: () => query(undefined),
  useAllowedOwnerStatusTransitions: () => query([]),
  useUpdateOwner: () => mutation(),
  useChangeStatus: () => mutation(),
  useFollowOwner: () => mutation(),
  useUnfollowOwner: () => mutation(),
  useAddOwnerNote: () => mutation(),
  useCreateOwnershipRelation: () => mutation(),
  useEndOwnershipRelation: () => mutation(),
}));

import { Owner360Workspace } from "@/components/owners/owner-360-workspace";

describe("Owner360Workspace", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.overview.mockReturnValue({
      ...query({
        owner: {
          id: "owner-1",
          displayName: "Mari Mets",
          sensitiveDetailsVisible: false,
          status: "NEW",
          assignedTo: null,
          phone: null,
          email: null,
          address: null,
          info: null,
        },
        portfolio: {
          activeParcelCount: 2,
          historicalParcelCount: 1,
          totalAreaHectares: 12.5,
          forestAreaHectares: 8,
          knownVolumeCubicMeters: null,
          volumeKnownParcelCount: null,
          activeNotificationCount: 0,
        },
        signals: [],
        sources: [{
          source: "Metsaregister",
          observedAt: null,
          freshness: "STALE",
          available: true,
          note: "Viimane edukas uuendus on aegunud.",
        }],
        sections: { portfolio: { state: "ERROR", errorCode: "PORTFOLIO_UNAVAILABLE" } },
        generatedAt: "2026-09-16T04:00:00Z",
      }),
      refetch: mocks.refetchOverview,
    });
  });

  it("renders permission-aware summary, unknown metrics and independent section failure", () => {
    const html = renderToStaticMarkup(<Owner360Workspace ownerId="owner-1" />);

    expect(html).toContain("Mari Mets");
    expect(html).toContain("Identifikaator piiratud");
    expect(html).toContain("Puuduv väärtus ei ole 0");
    expect(html).toContain("Osa ülevaatest ei ole praegu saadaval");
    expect(html).toContain("Metsaregister");
    expect(html).toContain("STALE");
    expect(html).toContain("Kontaktandmed on sinu rollile piiratud");
  });

  it("renders a retryable overview error without exposing a broken workspace", () => {
    mocks.overview.mockReturnValue({
      data: undefined,
      error: new Error("owner overview unavailable"),
      isError: true,
      isPending: false,
      refetch: mocks.refetchOverview,
    });

    const html = renderToStaticMarkup(<Owner360Workspace ownerId="owner-1" />);

    expect(html).toContain("Omaniku ülevaate laadimine ebaõnnestus");
    expect(html).toContain("owner overview unavailable");
    expect(html).toContain("Proovi uuesti");
  });
});
