"use client";

import { useEffect, useState } from "react";
import { BellRing, KeyRound, UserRound } from "lucide-react";
import { useAuth } from "@/lib/auth";
import { useNotificationPreference, useUpdateNotificationPreference } from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";

export default function MyProfilePage() {
  const { username, roles, accountManagement } = useAuth();
  const preference = useNotificationPreference();
  const updatePreference = useUpdateNotificationPreference();
  const [leadMinutes, setLeadMinutes] = useState(60);
  const [inAppEnabled, setInAppEnabled] = useState(true);
  const [emailEnabled, setEmailEnabled] = useState(false);
  const [emailAddress, setEmailAddress] = useState("");

  useEffect(() => {
    if (!preference.data) return;
    setLeadMinutes(preference.data.leadMinutes);
    setInAppEnabled(preference.data.inAppEnabled);
    setEmailEnabled(preference.data.emailEnabled);
    setEmailAddress(preference.data.emailAddress ?? "");
  }, [preference.data]);

  const saveNotifications = () => updatePreference.mutate({
    leadMinutes,
    inAppEnabled,
    emailEnabled,
    emailAddress: emailAddress.trim() || undefined,
  });

  return (
    <div className="max-w-2xl space-y-6">
      <div>
        <h1 className="flex items-center gap-2 text-2xl font-bold"><UserRound className="h-6 w-6" />Minu konto</h1>
        <p className="mt-1 text-sm text-muted-foreground">ForestIQ kasutab konto identiteedi ja parooli haldamiseks Keycloaki turvalist OIDC kontokeskust.</p>
      </div>
      <Card>
        <CardHeader>
          <CardTitle>Profiil</CardTitle>
          <p className="text-sm text-muted-foreground">Sisselogitud kasutaja ja rakenduse õigused.</p>
        </CardHeader>
        <CardContent className="space-y-4">
          <div>
            <p className="text-sm text-muted-foreground">Kasutajanimi</p>
            <p className="font-medium">{username ?? "—"}</p>
          </div>
          <div>
            <p className="mb-2 text-sm text-muted-foreground">Rollid</p>
            <div className="flex flex-wrap gap-2">
              {roles.length > 0 ? roles.map(role => <Badge key={role} variant="outline">{role}</Badge>) : <span className="text-sm text-muted-foreground">Rakenduse rolle pole määratud.</span>}
            </div>
          </div>
          <Button onClick={() => void accountManagement()}><KeyRound className="mr-2 h-4 w-4" />Halda profiili ja parooli</Button>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2"><BellRing className="h-5 w-5" />Tegevuste teavitused</CardTitle>
          <p className="text-sm text-muted-foreground">Vali, kui vara ForestIQ annab tähtajast märku. Teavituses ei saadeta kliendi ega kinnistu tundlikke detaile.</p>
        </CardHeader>
        <CardContent className="space-y-4">
          {preference.isLoading && <p className="text-sm text-muted-foreground">Laadin eelistusi...</p>}
          <label className="grid gap-1 text-sm">
            <span className="font-medium">Eelteavituse aeg minutites</span>
            <Input type="number" min={0} max={10080} value={leadMinutes} onChange={event => setLeadMinutes(Math.max(0, Math.min(10080, Number(event.target.value) || 0)))} />
            <span className="text-xs text-muted-foreground">0 = teavita tähtajal, 60 = tund varem, 1440 = päev varem.</span>
          </label>
          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={inAppEnabled} onChange={event => setInAppEnabled(event.target.checked)} />
            Rakendusesisene teavitus
          </label>
          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={emailEnabled} onChange={event => setEmailEnabled(event.target.checked)} />
            E-posti teavitus
          </label>
          {emailEnabled && (
            <label className="grid gap-1 text-sm">
              <span className="font-medium">Teavituste e-post</span>
              <Input type="email" value={emailAddress} onChange={event => setEmailAddress(event.target.value)} placeholder="nimi@example.com" />
            </label>
          )}
          <Button onClick={saveNotifications} disabled={updatePreference.isPending || (emailEnabled && !emailAddress.trim())}>Salvesta teavitused</Button>
          {updatePreference.isSuccess && <p className="text-sm text-emerald-700">Teavituseelistused salvestatud.</p>}
          {updatePreference.isError && <p className="text-sm text-destructive">{updatePreference.error.message}</p>}
        </CardContent>
      </Card>
    </div>
  );
}
