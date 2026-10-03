import { describe, expect, it } from "vitest";
import { dealWorkflowInvalidationKeys, queryKeys } from "@/lib/query-keys";

describe("deal workflow query keys", () => {
  it("includes all owner and workflow caches", () => {
    const ownerId = "owner-1";
    const keys = dealWorkflowInvalidationKeys(ownerId);

    expect(keys).toContainEqual(queryKeys.salesWorkspace.all);
    expect(keys).toContainEqual(queryKeys.myWork.all);
    expect(keys).toContainEqual(queryKeys.owners.all);
    expect(keys).toContainEqual(queryKeys.owners.detail(ownerId));
    expect(keys).toContainEqual(queryKeys.deal.owner(ownerId));
    expect(keys).toContainEqual(queryKeys.deal.brief(ownerId));
    expect(keys).toContainEqual(queryKeys.deal.evaluationQueue);
  });
});
