"use client";

import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { RefreshCw, Users } from "lucide-react";
import { useCadastreFullRegistryRefresh, useCadastreRegistryRefresh, useOwnershipRelationsCursor } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";

function ParcelOwnershipContext({ cadastralCode }: { cadastralCode: string }) {
  const ownership = useOwnershipRelationsCursor(cadastralCode, "true", "", null, 50);
  const activeOwners = ownership.data?.content.filter(relation => relation.cadastralCode === cadastralCode && relation.active) ?? [];

  if (ownership.isLoading) {
    return <span className="text-xs text-muted-foreground">Kontrollin omanikke...</span>;
  }
  if (ownership.isError) {
    return <span className="text-xs text-destructive">Omanike infot ei õnnestunud laadida.</span>;
  }
  if (activeOwners.length === 0) {
    return <span className="text-xs text-muted-foreground">Aktiivseid omanikke ei leitud.</span>;
  }

  return (
    <div className="flex flex-wrap items-center gap-1 text-xs text-muted-foreground">
      <Users className="h-3.5 w-3.5" />
      <span>Aktiivseid omanikke:</span>
      <Badge variant={activeOwners.length > 1 ? "secondary" : "outline"}>{activeOwners.length}</Badge>
      <span>{activeOwners.map(relation => relation.ownerName).join(", ")}</span>
    </div>
  );
}

export function ParcelRegistryActions({ cadastralCode, onMapDataChanged }: { cadastralCode: string; onMapDataChanged?: () => void }) {
  const refresh = useCadastreRegistryRefresh();
  const refreshAll = useCadastreFullRegistryRefresh();
  const queryClient = useQueryClient();
  const { hasRole } = useAuth();
  const [message, setMessage] = useState<string>();
  const [showOwners, setShowOwners] = useState(false);
  const canRefresh = hasRole("ORG_ADMIN", "CRM_MANAGER", "EVALUATOR");
  const canReadOwnership = hasRole("ORG_ADMIN", "CRM_MANAGER", "EVALUATOR");

  const refreshVisibleData = () => {
    void queryClient.invalidateQueries({ queryKey: ["cadastre", cadastralCode] });
    void queryClient.invalidateQueries({ queryKey: ["map", "parcel-summary", cadastralCode] });
    onMapDataChanged?.();
  };

  const run = (registry: "maaamet" | "notifications" | "plan") => {
    setMessage(undefined);
    refresh.mutate({ cadastralCode, registry }, {
      onSuccess: result => {
        setMessage(result.message);
        refreshVisibleData();
      },
      onError: error => setMessage(error.message),
    });
  };

  const runAll = () => {
    setMessage(undefined);
    refreshAll.mutate({ cadastralCode }, {
      onSuccess: result => {
        const parts = [result.cadastre, result.forestNotifications, result.forestPlan];
        const failed = parts.filter(part => part.success === false);
        setMessage(failed.length === 0
          ? `Ametlikud andmed värskendatud: katastri ${result.cadastre.recordsUpdated}, teatisi ${result.forestNotifications.recordsUpdated}, eraldisi ${result.forestPlan.recordsUpdated}.`
          : `Osaline värskendus: ${failed.map(part => part.registry).join(", ")}. ${failed.map(part => part.message).join(" ")}`);
        refreshVisibleData();
      },
      onError: error => setMessage(error.message),
    });
  };

  if (!canRefresh && !canReadOwnership) return null;

  return (
    <div className="mt-2 space-y-1.5">
      <div className="flex flex-wrap items-center gap-1">
        {canReadOwnership && <Button type="button" variant="ghost" size="sm" className="h-7 px-2 text-xs"
          onClick={() => setShowOwners(value => !value)}><Users className="mr-1 h-3 w-3" />{showOwners ? "Peida omanikud" : "Omanikud"}</Button>}
        {canRefresh && <>
          <Button type="button" size="sm" className="h-7 px-2 text-xs" disabled={refresh.isPending || refreshAll.isPending} onClick={runAll}><RefreshCw className="mr-1 h-3 w-3" />Värskenda kõik</Button>
          <Button type="button" variant="ghost" size="sm" className="h-7 px-2 text-xs" disabled={refresh.isPending || refreshAll.isPending} onClick={() => run("maaamet")}><RefreshCw className="mr-1 h-3 w-3" />Maa-amet</Button>
          <Button type="button" variant="ghost" size="sm" className="h-7 px-2 text-xs" disabled={refresh.isPending || refreshAll.isPending} onClick={() => run("notifications")}>Teatised</Button>
          <Button type="button" variant="ghost" size="sm" className="h-7 px-2 text-xs" disabled={refresh.isPending || refreshAll.isPending} onClick={() => run("plan")}>Metsaplaan</Button>
        </>}
        {message && <span className="text-xs text-muted-foreground">{message}</span>}
      </div>
      {canReadOwnership && showOwners && <ParcelOwnershipContext cadastralCode={cadastralCode} />}
    </div>
  );
}
