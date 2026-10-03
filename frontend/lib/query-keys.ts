import type { QueryKey } from "@tanstack/react-query";

export const queryKeys = {
  owners: {
    all: ["owners"] as const,
    list: (search: string, page: number, size: number) => ["owners", search, page, size] as const,
    detail: (ownerId: string) => ["owner", ownerId] as const,
  },
  myWork: {
    all: ["my-work"] as const,
    page: (search: string, page: number, size: number) => ["my-work", search, page, size] as const,
  },
  salesWorkspace: {
    all: ["sales-workspace"] as const,
    queue: ["sales-workspace", "queue"] as const,
  },
  deal: {
    owner: (ownerId: string) => ["deals", "owner", ownerId] as const,
    brief: (ownerId: string) => ["deal-brief", ownerId] as const,
    evaluationQueue: ["evaluation", "queue"] as const,
  },
  reminders: {
    all: ["reminders"] as const,
  },
} as const;

export function dealWorkflowInvalidationKeys(ownerId?: string): QueryKey[] {
  const keys: QueryKey[] = [
    queryKeys.salesWorkspace.all,
    queryKeys.myWork.all,
    queryKeys.owners.all,
    queryKeys.deal.evaluationQueue,
  ];

  if (ownerId) {
    keys.push(
      queryKeys.owners.detail(ownerId),
      queryKeys.deal.owner(ownerId),
      queryKeys.deal.brief(ownerId),
    );
  }

  return keys;
}
