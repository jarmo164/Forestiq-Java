"use client";

import { GeospatialOperationsWorkspace } from "@/components/admin/geospatial-operations";
import { IntegrationOperationsTools as LegacyIntegrationOperationsTools } from "./integration-operations";

/** Preserve all existing integration/import tools and prepend the geospatial control plane. */
export function IntegrationOperationsTools() {
  return (
    <div className="space-y-6">
      <GeospatialOperationsWorkspace />
      <LegacyIntegrationOperationsTools />
    </div>
  );
}
