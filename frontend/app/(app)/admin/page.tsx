"use client";

import { useEffect, useMemo, useState } from "react";
import Link from "next/link";
import {
  useAdminPrepData, useAdminSearch, useAssignWork, useOwnerStatusDefinitions,
  useSaveOwnerStatusDefinition, useDeleteOwnerStatusDefinition,
  useAdminUsers, useCreateAdminUser, useUpdateAdminUserRoles, useDeleteAdminUser, useUserStatistics,
  useIntegrationJobs, useStartIntegrationJob,
} from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Badge } from "@/components/ui/badge";
import { ConfirmActionDialog } from "@/components/ui/confirm-action-dialog";
import { IntegrationOperationsTools } from "@/components/admin/integration-operations";
import { OwnershipRelationsWorkspace } from "@/components/admin/ownership-relations";

const ALL_ROLES = ["ORG_ADMIN", "CRM_MANAGER", "EVALUATOR", "CALLER", "VIEWER"];

export default function AdminPage() {
  const { hasRole } = useAuth();
  const canManageOwnership = hasRole("ORG_ADMIN") || hasRole("CRM_MANAGER");
  const [activeTab, setActiveTab] = useState<"workdesk" | "users" | "statuses" | "statistics" | "integrations" | "ownership">("workdesk");

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-2xl font-bold">Haldus</h1>
        <Button asChild variant="outline"><Link href="/admin/contracts">Ettevõtted ja lepingumallid</Link></Button>
      </div>
      <div className="flex flex-wrap gap-2 border-b pb-2">
        <Button variant={activeTab === "workdesk" ? "default" : "ghost"} size="sm" onClick={() => setActiveTab("workdesk")}>Admini töölaud</Button>
        <Button variant={activeTab === "users" ? "default" : "ghost"} size="sm" onClick={() => setActiveTab("users")}>Kasutajad</Button>
        <Button variant={activeTab === "statuses" ? "default" : "ghost"} size="sm" onClick={() => setActiveTab("statuses")}>Omaniku staatused</Button>
        <Button variant={activeTab === "statistics" ? "default" : "ghost"} size="sm" onClick={() => setActiveTab("statistics")}>Kasutajastatistika</Button>
        {canManageOwnership && <Button variant={activeTab === "ownership" ? "default" : "ghost"} size="sm"
          onClick={() => setActiveTab("ownership")}>Omaniku–katastri seosed</Button>}
        {hasRole("ORG_ADMIN") && <Button variant={activeTab === "integrations" ? "default" : "ghost"} size="sm"
          onClick={() => setActiveTab("integrations")}>Andmeallikad ja sünkroonimine</Button>}
      </div>
      {activeTab === "workdesk" && <AdminWorkdesk />}
      {activeTab === "users" && <UserManagement />}
      {activeTab === "statuses" && <StatusManagement />}
      {activeTab === "statistics" && <UserStatistics />}
      {activeTab === "ownership" && canManageOwnership && <OwnershipRelationsWorkspace />}
      {activeTab === "integrations" && <div className="space-y-6"><IntegrationOperationsTools /><IntegrationOperations /></div>}
    </div>
  );
}

function IntegrationOperations() {
  const jobs = useIntegrationJobs();
  const start = useStartIntegrationJob();

  const formatDate = (value?: string) => value ? new Date(value).toLocaleString("et-EE") : "—";
  const formatDuration = (seconds?: number) => {
    if (seconds === undefined || seconds === null || seconds < 0) return "—";
    if (seconds >= 86_400) return `${Math.floor(seconds / 86_400)} p ${Math.floor((seconds % 86_400) / 3_600)} h`;
    if (seconds >= 3_600) return `${Math.floor(seconds / 3_600)} h ${Math.floor((seconds % 3_600) / 60)} min`;
    if (seconds >= 60) return `${Math.floor(seconds / 60)} min`;
    return `${seconds} s`;
  };
  const metrics = (value?: string) => {
    if (!value) return "—";
    try {
      const parsed = JSON.parse(value) as Record<string, unknown>;
      const entries = Object.entries(parsed);
      return entries.length ? entries.map(([key, metric]) => `${key}: ${String(metric)}`).join(" · ") : "—";
    } catch { return "—"; }
  };

  return (
    <Card>
      <CardHeader><CardTitle>Andmeallikad ja sünkroonimine</CardTitle></CardHeader>
      <CardContent className="space-y-4">
        <p className="text-sm text-muted-foreground">
          Tööd käivituvad tavaliselt ajakava alusel. Käsitsi käivitamist kasuta taastamiseks või kontrollitud esmakäivituseks.
          Tervise olek võrdleb viimast kinnitatud õnnestumist, cursor-lag&apos;i ja backlog&apos;i iga andmevoo värskuse SLO-ga.
        </p>
        {jobs.isLoading && <p className="text-sm text-muted-foreground">Laadin andmeallikate olekut...</p>}
        {jobs.isError && <p className="text-sm text-destructive">Andmeallikate oleku laadimine ebaõnnestus.</p>}
        {jobs.data && <Table>
          <TableHeader><TableRow>
            <TableHead>Teenus</TableHead><TableHead>Seadistus</TableHead><TableHead>Tervis</TableHead><TableHead>Ajakava</TableHead>
            <TableHead>Viimane tulemus</TableHead><TableHead>Viimane õnnestumine</TableHead>
            <TableHead>Mõõdikud</TableHead><TableHead></TableHead>
          </TableRow></TableHeader>
          <TableBody>
            {jobs.data.map(job => {
              const running = Boolean(job.currentRun);
              const canStart = job.manualSupported && job.configurationState !== "MISCONFIGURED" && !running;
              const healthVariant = job.health.state === "HEALTHY" ? "default" :
                job.health.state === "DEGRADED" || job.health.state === "STALE" ? "warning" : "outline";
              return <TableRow key={job.key}>
                <TableCell><div className="font-medium">{job.displayName}</div>
                  <div className="text-xs text-muted-foreground">{job.provider} · {job.purpose}</div></TableCell>
                <TableCell><Badge variant={job.configurationState === "ENABLED" ? "default" : "outline"}>
                  {job.configurationState === "ENABLED" ? "Aktiivne" : job.configurationState === "DISABLED" ? "Ajakava väljas" : "Seadistamata"}
                </Badge></TableCell>
                <TableCell className="max-w-sm text-xs">
                  <Badge variant={healthVariant}>{job.health.state}</Badge>
                  <div className="mt-1">{job.health.reason}</div>
                  <div className="mt-1 text-muted-foreground">
                    SLO {formatDuration(job.health.freshnessSloSeconds)} · viimane edu {formatDuration(job.health.latestSuccessAgeSeconds)}
                    {job.health.failureStreak > 0 ? ` · veajärjestus ${job.health.failureStreak}` : ""}
                    {job.health.backlogRemaining ? " · backlog" : ""}
                    {job.health.cursorLagSeconds !== undefined ? ` · cursor ${formatDuration(job.health.cursorLagSeconds)}` : ""}
                  </div>
                </TableCell>
                <TableCell className="text-sm">{job.schedule}</TableCell>
                <TableCell className="text-sm">
                  {running ? <Badge variant="warning">{job.currentRun?.state}</Badge> :
                    job.latestRun ? <><Badge variant={job.latestRun.state === "SUCCEEDED" ? "default" : "outline"}>{job.latestRun.state}</Badge>
                      <div className="mt-1 text-xs text-muted-foreground">{formatDate(job.latestRun.finishedAt || job.latestRun.queuedAt)}</div>
                      {(job.latestRun.errorSummary || job.latestRun.message) && <div className="mt-1 max-w-xs text-xs">{job.latestRun.errorSummary || job.latestRun.message}</div>}
                    </> : "Pole veel käivitatud"}
                </TableCell>
                <TableCell className="text-xs">{formatDate(job.latestSuccess?.finishedAt)}</TableCell>
                <TableCell className="max-w-xs text-xs">{metrics(job.latestRun?.metrics)}</TableCell>
                <TableCell><Button size="sm" variant="outline" disabled={!canStart || start.isPending}
                  title={job.configurationState === "MISCONFIGURED" ? "Ühenduse seadistus puudub" : running ? "Töö juba käib" : ""}
                  onClick={() => {
                    const highImpact = job.key === "OWNER_CADASTRE_RELATIONS";
                    if (!highImpact || window.confirm("Käivitan täieliku omaniku–katastri snapshoti. Jätkan?")) {
                      start.mutate({ key: job.key });
                    }
                  }}>Käivita sünkroonimine</Button></TableCell>
              </TableRow>;
            })}
          </TableBody>
        </Table>}
      </CardContent>
    </Card>
  );
}

/* ==================== Admin Workdesk ==================== */
function AdminWorkdesk() {
  const { data: prepData } = useAdminPrepData();
  const adminSearch = useAdminSearch();
  const assignWork = useAssignWork();
  const [selectedOwners, setSelectedOwners] = useState<string[]>([]);
  const [assignee, setAssignee] = useState("");
  const [searchStatuses, setSearchStatuses] = useState<string[]>([]);
  const [reassign, setReassign] = useState(false);
  const [forestAreaMin, setForestAreaMin] = useState("");
  const [county, setCounty] = useState("");
  const [mustHavePhone, setMustHavePhone] = useState(true);
  const [newNotifications, setNewNotifications] = useState(false);
  const [changedCompartments, setChangedCompartments] = useState(false);
  const [ownershipTransitions, setOwnershipTransitions] = useState(false);
  const [portfolioOwnershipAdded, setPortfolioOwnershipAdded] = useState(false);
  const [anyDataSignal, setAnyDataSignal] = useState(false);
  const [mustBeUnassigned, setMustBeUnassigned] = useState(true);

  const handleSearch = () => {
    adminSearch.mutate({
      statuses: searchStatuses.length > 0 ? searchStatuses : undefined,
      counties: county ? [county] : undefined,
      forestAreaMin: forestAreaMin ? Number(forestAreaMin) : undefined,
      mustHavePhone: mustHavePhone || undefined,
      mustHaveNewForestNotification: newNotifications || undefined,
      mustHaveChangedForestPlan: changedCompartments || undefined,
      mustHaveOwnershipTransition: ownershipTransitions || undefined,
      mustHavePortfolioOwnershipAdded: portfolioOwnershipAdded || undefined,
      mustHaveAnyDataSignal: anyDataSignal || undefined,
      mustBeUnassigned: mustBeUnassigned || undefined,
      maxResults: 100,
    });
  };
  const handleAssign = () => {
    if (!assignee || selectedOwners.length === 0) return;
    assignWork.mutate({ assignee, owners: selectedOwners, reassign }, {
      onSuccess: () => { setSelectedOwners([]); setAssignee(""); handleSearch(); },
    });
  };
  const toggleOwner = (id: string) => {
    setSelectedOwners(prev => prev.includes(id) ? prev.filter(o => o !== id) : [...prev, id]);
  };

  return (
    <Card>
      <CardHeader><CardTitle>Admini töölaud</CardTitle></CardHeader>
      <CardContent className="space-y-4">
        <div className="flex flex-wrap gap-3">
          {prepData?.statuses && (
            <select className="h-10 rounded-md border bg-background px-3 text-sm" value="" onChange={e => {
              if (e.target.value && !searchStatuses.includes(e.target.value)) setSearchStatuses([...searchStatuses, e.target.value]);
            }}>
              <option value="">Lisa staatuse filter...</option>
              {prepData.statuses.map(s => <option key={s} value={s}>{s}</option>)}
            </select>
          )}
          {searchStatuses.map(s => (
            <Badge key={s} variant="secondary" className="cursor-pointer" onClick={() => setSearchStatuses(searchStatuses.filter(x => x !== s))}>{s} x</Badge>
          ))}
          <select className="h-10 rounded-md border bg-background px-3 text-sm" value={county} onChange={event => setCounty(event.target.value)}><option value="">Kõik maakonnad</option>{prepData?.counties.map(value => <option value={value} key={value}>{value}</option>)}</select>
          <Input className="w-40" type="number" min="0" step="0.1" placeholder="Min metsaala ha" value={forestAreaMin} onChange={event => setForestAreaMin(event.target.value)} />
          <Button onClick={handleSearch} disabled={adminSearch.isPending}>Koosta andmepõhine töövalik</Button>
        </div>
        <div className="flex flex-wrap gap-4 rounded-md border bg-muted/30 p-3 text-sm">
          <label className="flex items-center gap-2"><input type="checkbox" checked={mustHavePhone} onChange={event => setMustHavePhone(event.target.checked)} />Ainult telefoniga</label>
          <label className="flex items-center gap-2"><input type="checkbox" checked={newNotifications} onChange={event => setNewNotifications(event.target.checked)} />Uus metsateatis</label>
          <label className="flex items-center gap-2"><input type="checkbox" checked={changedCompartments} onChange={event => setChangedCompartments(event.target.checked)} />Uus või muutunud eraldis</label>
          <label className="flex items-center gap-2"><input type="checkbox" checked={ownershipTransitions} onChange={event => setOwnershipTransitions(event.target.checked)} />Weaseli omandimuutus</label>
          <label className="flex items-center gap-2"><input type="checkbox" checked={portfolioOwnershipAdded} onChange={event => setPortfolioOwnershipAdded(event.target.checked)} />MetsIS uus omandisuhe</label>
          <label className="flex items-center gap-2"><input type="checkbox" checked={anyDataSignal} onChange={event => setAnyDataSignal(event.target.checked)} />Kõik uued andmesignaalid</label>
          <label className="flex items-center gap-2"><input type="checkbox" checked={mustBeUnassigned} onChange={event => setMustBeUnassigned(event.target.checked)} />Ainult veel määramata</label>
        </div>
        <div className="flex flex-wrap gap-3">
          {prepData?.users && (
            <select className="h-10 rounded-md border bg-background px-3 text-sm" value={assignee} onChange={e => setAssignee(e.target.value)}>
              <option value="">Vali kasutaja...</option>
              {prepData.users.map(u => <option key={u.id} value={u.id}>{u.name}</option>)}
            </select>
          )}
          <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={reassign} onChange={event => setReassign(event.target.checked)} />Ümbermäära olemasolev töö ilma staatust muutmata</label>
          <Button onClick={handleAssign} disabled={selectedOwners.length === 0 || !assignee} variant="outline">
            {reassign ? "Määra ümber" : "Määra"} ({selectedOwners.length})
          </Button>
        </div>
        {prepData && (
          <div className="flex flex-wrap gap-4 text-sm text-muted-foreground">
            <span>Maakonnad: {prepData.counties.length}</span>
            <span>Vallad: {prepData.municipalities.length}</span>
            <span>Omaniku tüübid: {prepData.ownerTypes.length}</span>
            <span>Staatused: {prepData.statuses.length}</span>
          </div>
        )}
        {adminSearch.data && (
          <Table>
            <TableHeader>
              <TableRow><TableHead className="w-10"></TableHead><TableHead>Nimi</TableHead><TableHead>Andmesignaal</TableHead><TableHead>Kinnistuid</TableHead><TableHead>Staatus</TableHead><TableHead>Määratud</TableHead><TableHead>Telefon</TableHead></TableRow>
            </TableHeader>
            <TableBody>
              {adminSearch.data.map(o => (
                <TableRow key={o.id} className="cursor-pointer" onClick={() => toggleOwner(o.id)}>
                  <TableCell><input type="checkbox" checked={selectedOwners.includes(o.id)} readOnly aria-label={`Vali ${o.displayName}`} /></TableCell>
                  <TableCell><Link href={`/owners/${o.id}`} className="text-primary hover:underline" onClick={e => e.stopPropagation()}>{o.displayName}</Link></TableCell>
                  <TableCell>{o.dataSignals?.length ? <div className="flex flex-wrap gap-1">{o.dataSignals.map(signal => <Badge key={signal} variant="warning">{signal === "NEW_FOREST_NOTIFICATION" ? "Uus metsateatis" : signal === "NEW_FOREST_COMPARTMENT" ? "Muutunud eraldis" : signal === "DECEASED_OWNER" ? "Pärimisteade" : signal === "OWNERSHIP_TRANSITION" ? "Omandimuutus" : signal === "PORTFOLIO_OWNERSHIP_ADDED" ? "MetsIS uus omandisuhe" : signal}</Badge>)}</div> : <span className="text-muted-foreground">—</span>}</TableCell>
                  <TableCell>{o.parcelCount}</TableCell>
                  <TableCell><Badge variant="outline">{o.status}</Badge></TableCell>
                  <TableCell>{o.assignedTo || "—"}</TableCell>
                  <TableCell>{o.phone || "—"}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
        {adminSearch.data?.length === 0 && <p className="text-sm text-muted-foreground">Omanikke ei leitud.</p>}
      </CardContent>
    </Card>
  );
}

/* ==================== User Management ==================== */
function UserManagement() {
  const { subject } = useAuth();
  const { data: users, isLoading } = useAdminUsers();
  const createUser = useCreateAdminUser();
  const updateRoles = useUpdateAdminUserRoles();
  const deleteUser = useDeleteAdminUser();
  const [newUsername, setNewUsername] = useState("");
  const [newName, setNewName] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [newRoles, setNewRoles] = useState<string[]>([]);
  const [roleChange, setRoleChange] = useState<{ userId: string; userName: string; role: string; roles: string[] } | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<{ userId: string; userName: string } | null>(null);

  const handleCreate = () => {
    if (!newUsername || !newName || newPassword.length < 12) return;
    createUser.mutate({ username: newUsername, name: newName, password: newPassword, roles: newRoles }, {
      onSuccess: () => { setNewUsername(""); setNewName(""); setNewPassword(""); setNewRoles([]); },
    });
  };

  const toggleRole = (userId: string, role: string, currentRoles: string[]) => {
    const updated = currentRoles.includes(role) ? currentRoles.filter(r => r !== role) : [...currentRoles, role];
    const user = users?.find(candidate => candidate.id === userId);
    if (user) {
      updateRoles.reset();
      setRoleChange({ userId, userName: user.name || user.username, role, roles: updated });
    }
  };

  return (
    <div className="space-y-6">
      <Card>
        <CardHeader><CardTitle>Uus kasutaja</CardTitle></CardHeader>
        <CardContent className="space-y-4">
          <div className="grid gap-4 md:grid-cols-2">
            <Input placeholder="Kasutajanimi" value={newUsername} onChange={e => setNewUsername(e.target.value)} />
            <Input placeholder="Täisnimi" value={newName} onChange={e => setNewName(e.target.value)} />
            <Input type="password" placeholder="Ajutine parool (vähemalt 12 märki)" value={newPassword} onChange={e => setNewPassword(e.target.value)} />
            <div className="flex flex-wrap gap-2">
              {ALL_ROLES.map(role => (
                <label key={role} className="flex items-center gap-1 text-xs">
                  <input type="checkbox" checked={newRoles.includes(role)} onChange={() => setNewRoles(prev => prev.includes(role) ? prev.filter(r => r !== role) : [...prev, role])} />
                  {role}
                </label>
              ))}
            </div>
          </div>
          <p className="text-xs text-muted-foreground">Uuel kasutajal nõutakse esimesel sisselogimisel ajutise parooli muutmist.</p>
          <Button onClick={handleCreate} disabled={!newUsername || !newName || newPassword.length < 12 || createUser.isPending}>Lisa kasutaja</Button>
          {createUser.isError && <p className="text-sm text-destructive">{createUser.error.message}</p>}
        </CardContent>
      </Card>
      <Card>
        <CardHeader><CardTitle>Kasutajad</CardTitle></CardHeader>
        <CardContent>
          {isLoading && <p className="text-sm text-muted-foreground">Laadimine...</p>}
          {users && (
            <Table>
              <TableHeader>
                <TableRow><TableHead>Kasutajanimi</TableHead><TableHead>Nimi</TableHead><TableHead>Rollid</TableHead><TableHead></TableHead></TableRow>
              </TableHeader>
              <TableBody>
                {users.map(user => (
                  <TableRow key={user.id}>
                    <TableCell className="font-medium">{user.username}</TableCell>
                    <TableCell>{user.name}</TableCell>
                    <TableCell>
                      <div className="flex flex-wrap gap-1">
                        {ALL_ROLES.map(role => (
                          <button key={role} type="button" disabled={user.id === subject} onClick={() => toggleRole(user.id, role, user.roles)} className="disabled:cursor-not-allowed" title={user.id === subject ? "Enda rolle ei saa siin muuta." : undefined}>
                            <Badge variant={user.roles.includes(role) ? "default" : "outline"} className="cursor-pointer text-xs">{role}</Badge>
                          </button>
                        ))}
                      </div>
                    </TableCell>
                    <TableCell>
                      <Button variant="ghost" size="sm" className="text-destructive" disabled={user.id === subject} onClick={() => { deleteUser.reset(); setDeleteTarget({ userId: user.id, userName: user.name || user.username }); }}>Kustuta</Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
          {users?.length === 0 && <p className="text-sm text-muted-foreground">Kasutajaid pole.</p>}
        </CardContent>
      </Card>
      <ConfirmActionDialog open={Boolean(roleChange)} title="Kas kinnitada rollimuutus?" description={roleChange ? <>Muudad kasutaja <strong>{roleChange.userName}</strong> rolli <strong>{roleChange.role}</strong>. Uus rollide komplekt jõustub järgmisel seansikontrollil.</> : ""} confirmLabel="Kinnita rollimuutus" pending={updateRoles.isPending} error={updateRoles.isError ? updateRoles.error.message : undefined} onConfirm={() => { if (roleChange) updateRoles.mutate({ userId: roleChange.userId, roles: roleChange.roles }, { onSuccess: () => setRoleChange(null) }); }} onCancel={() => { if (!updateRoles.isPending) { setRoleChange(null); updateRoles.reset(); } }} />
      <ConfirmActionDialog open={Boolean(deleteTarget)} title="Kas kustutada kasutaja?" description={deleteTarget ? <>Kustutad kasutaja <strong>{deleteTarget.userName}</strong>. Enne kustutamist kontrollib ForestIQ, et kasutajal poleks ümber määramata aktiivseid töid.</> : ""} confirmLabel="Kustuta kasutaja" destructive pending={deleteUser.isPending} error={deleteUser.isError ? deleteUser.error.message : undefined} onConfirm={() => { if (deleteTarget) deleteUser.mutate(deleteTarget.userId, { onSuccess: () => setDeleteTarget(null) }); }} onCancel={() => { if (!deleteUser.isPending) { setDeleteTarget(null); deleteUser.reset(); } }} />
    </div>
  );
}

/* ==================== Status Management ==================== */
function UserStatistics() {
  const { data: users } = useAdminUsers();
  const { data: statuses } = useOwnerStatusDefinitions();
  const [selectedUsers, setSelectedUsers] = useState<string[]>([]);
  const [fromStatuses, setFromStatuses] = useState<string[]>([]);
  const [toStatuses, setToStatuses] = useState<string[]>([]);
  const [from, setFrom] = useState(() => new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString().slice(0, 16));
  const [to, setTo] = useState(() => new Date().toISOString().slice(0, 16));
  const [granularity, setGranularity] = useState("hour");

  useEffect(() => {
    if (users && selectedUsers.length === 0) setSelectedUsers(users.map(user => user.id));
  }, [users, selectedUsers.length]);

  const params = useMemo(() => ({
    userIds: selectedUsers,
    fromStatuses,
    toStatuses,
    granularity,
    from: new Date(from).toISOString(),
    to: new Date(to).toISOString(),
  }), [from, fromStatuses, granularity, selectedUsers, to, toStatuses]);
  const statistics = useUserStatistics(params);
  const userNames = new Map((users ?? []).map(user => [user.id, user.name]));
  const totals = new Map((statistics.data ?? []).map(item => [item.userId, item.frames.reduce((sum, frame) => sum + (frame.count ?? 0), 0)]));
  const toggle = (value: string, values: string[], setValues: (next: string[]) => void) =>
    setValues(values.includes(value) ? values.filter(item => item !== value) : [...values, value]);

  return (
    <Card>
      <CardHeader><CardTitle>Kasutajate staatusemuudatused</CardTitle></CardHeader>
      <CardContent className="space-y-5">
        <div className="grid gap-4 lg:grid-cols-3">
          <label className="text-sm">Alates<input className="mt-1 h-10 w-full rounded-md border bg-background px-3" type="datetime-local" value={from} onChange={event => setFrom(event.target.value)} /></label>
          <label className="text-sm">Kuni<input className="mt-1 h-10 w-full rounded-md border bg-background px-3" type="datetime-local" value={to} onChange={event => setTo(event.target.value)} /></label>
          <label className="text-sm">Koondamine<select className="mt-1 h-10 w-full rounded-md border bg-background px-3" value={granularity} onChange={event => setGranularity(event.target.value)}><option value="hour">Tund</option><option value="day">Päev</option><option value="week">Nädal</option><option value="month">Kuu</option></select></label>
        </div>
        <div><p className="mb-2 text-sm font-medium">Kasutajad</p><div className="flex flex-wrap gap-2">{(users ?? []).map(user => <Badge key={user.id} variant={selectedUsers.includes(user.id) ? "default" : "outline"} className="cursor-pointer" onClick={() => toggle(user.id, selectedUsers, setSelectedUsers)}>{user.name}</Badge>)}</div></div>
        <div className="grid gap-4 lg:grid-cols-2">
          <div><p className="mb-2 text-sm font-medium">Algstaatus (valikuline)</p><div className="flex flex-wrap gap-2">{(statuses ?? []).map(status => <Badge key={status.id} variant={fromStatuses.includes(status.id) ? "default" : "outline"} className="cursor-pointer" onClick={() => toggle(status.id, fromStatuses, setFromStatuses)}>{status.id}</Badge>)}</div></div>
          <div><p className="mb-2 text-sm font-medium">Lõppstaatus (valikuline)</p><div className="flex flex-wrap gap-2">{(statuses ?? []).map(status => <Badge key={status.id} variant={toStatuses.includes(status.id) ? "default" : "outline"} className="cursor-pointer" onClick={() => toggle(status.id, toStatuses, setToStatuses)}>{status.id}</Badge>)}</div></div>
        </div>
        {statistics.isLoading && <p className="text-sm text-muted-foreground">Statistika laadimine...</p>}
        {statistics.isError && <p className="text-sm text-destructive">{statistics.error.message}</p>}
        {statistics.data && <Table><TableHeader><TableRow><TableHead>Kasutaja</TableHead><TableHead>Muudatusi</TableHead><TableHead>Ajaraamid</TableHead></TableRow></TableHeader><TableBody>{statistics.data.map(item => <TableRow key={item.userId}><TableCell className="font-medium">{userNames.get(item.userId) ?? item.userId}</TableCell><TableCell>{totals.get(item.userId) ?? 0}</TableCell><TableCell>{item.frames.map(frame => `${frame.frameStart ? new Intl.DateTimeFormat("et-EE", { dateStyle: "short", timeStyle: "short" }).format(new Date(frame.frameStart)) : "—"}: ${frame.count ?? 0}`).join(" · ") || "—"}</TableCell></TableRow>)}</TableBody></Table>}
      </CardContent>
    </Card>
  );
}

function StatusManagement() {
  const { data: statusDefs } = useOwnerStatusDefinitions();
  const saveStatus = useSaveOwnerStatusDefinition();
  const deleteStatus = useDeleteOwnerStatusDefinition();
  const [newId, setNewId] = useState("");
  const [newColor, setNewColor] = useState("cccccc");
  const [newDays, setNewDays] = useState(0);

  const handleSave = () => {
    if (!newId) return;
    saveStatus.mutate({ id: newId, colorHex: newColor, durationDays: newDays }, {
      onSuccess: () => { setNewId(""); setNewColor("cccccc"); setNewDays(0); },
    });
  };

  return (
    <div className="space-y-6">
      <Card>
        <CardHeader><CardTitle>Lisa / muuda staatust</CardTitle></CardHeader>
        <CardContent className="space-y-4">
          <div className="grid gap-4 md:grid-cols-4">
            <Input placeholder="Staatuse ID (nt CONTACTED)" value={newId} onChange={e => setNewId(e.target.value)} />
            <div className="flex items-center gap-2">
              <label className="text-sm">Värv:</label>
              <input type="color" value={`#${newColor}`} onChange={e => setNewColor(e.target.value.replace("#", ""))} className="h-10 w-14 cursor-pointer" />
            </div>
            <Input type="number" placeholder="Päevi otsingust väljas" value={newDays} onChange={e => setNewDays(Number(e.target.value))} />
            <Button onClick={handleSave} disabled={!newId || saveStatus.isPending}>Salvesta</Button>
          </div>
        </CardContent>
      </Card>
      <Card>
        <CardHeader><CardTitle>Omaniku staatused</CardTitle></CardHeader>
        <CardContent>
          {statusDefs && statusDefs.length > 0 ? (
            <Table>
              <TableHeader>
                <TableRow><TableHead>ID</TableHead><TableHead>Värv</TableHead><TableHead>Päevi otsingust väljas</TableHead><TableHead>Kaitstud</TableHead><TableHead></TableHead></TableRow>
              </TableHeader>
              <TableBody>
                {statusDefs.map(s => (
                  <TableRow key={s.id}>
                    <TableCell className="font-medium">{s.id}</TableCell>
                    <TableCell><span className="inline-block h-5 w-5 rounded border" style={{ backgroundColor: `#${s.colorHex}` }} /></TableCell>
                    <TableCell>{s.durationDays}</TableCell>
                    <TableCell>{s.isProtected ? "Jah" : "Ei"}</TableCell>
                    <TableCell>
                      {!s.isProtected && (
                        <Button variant="ghost" size="sm" className="text-destructive" onClick={() => {
                          if (confirm(`Kas oled kindel, et soovid kustutada staatuse "${s.id}"?`)) deleteStatus.mutate(s.id);
                        }}>Kustuta</Button>
                      )}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          ) : (
            <p className="text-muted-foreground">Staatusi definitsioone pole veel lisatud.</p>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
