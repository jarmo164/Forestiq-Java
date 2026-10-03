"use client";

import { useEffect, useMemo, useState } from "react";
import { Download, FilePlus, Plus, Trash2 } from "lucide-react";
import {
  useCompanyProfiles,
  useContractHistory,
  useContractTemplates,
  useDealContractDraft,
  useDealContractVersions,
  useDownloadContract,
  useGenerateContract,
  type ContractChecklist,
} from "@/lib/api";
import { removeRowAt, restoreRemovedRow, type RemovedRow } from "@/lib/contracts/form-utils";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { UndoToast } from "@/components/ui/undo-toast";

type ParcelForm = { cadastralCode: string; address: string; areaHectares: string };
const emptyParcel = (): ParcelForm => ({ cadastralCode: "", address: "", areaHectares: "" });
const initialChecklist: ContractChecklist = {
  partiesConfirmed: false, parcelsConfirmed: false, priceConfirmed: false,
  ibanConfirmed: false, acceptedOfferConfirmed: false, templateConfirmed: false,
};

export default function ContractsPage() {
  const [cadastre, setCadastre] = useState("");
  const [buyer, setBuyer] = useState("");
  const [sellerFilter, setSellerFilter] = useState("");
  const { data: contracts, isLoading } = useContractHistory({ cadastre, buyer, seller: sellerFilter });
  const { data: companies } = useCompanyProfiles();
  const { data: templates } = useContractTemplates();
  const generate = useGenerateContract();
  const download = useDownloadContract();

  const [sourceDealId, setSourceDealId] = useState<string>();
  const [appliedDealId, setAppliedDealId] = useState<string>();
  const draft = useDealContractDraft(sourceDealId);
  const versions = useDealContractVersions(sourceDealId);

  const [templateId, setTemplateId] = useState("");
  const [companyProfileId, setCompanyProfileId] = useState("");
  const [contractNumber, setContractNumber] = useState("");
  const [contractDate, setContractDate] = useState(new Date().toISOString().slice(0, 10));
  const [place, setPlace] = useState("Tallinn");
  const [price, setPrice] = useState("");
  const [terms, setTerms] = useState("");
  const [sellerName, setSellerName] = useState("");
  const [sellerCode, setSellerCode] = useState("");
  const [sellerAddress, setSellerAddress] = useState("");
  const [sellerIban, setSellerIban] = useState("");
  const [parcels, setParcels] = useState<ParcelForm[]>([emptyParcel()]);
  const [removedParcel, setRemovedParcel] = useState<RemovedRow<ParcelForm>>();
  const [checklist, setChecklist] = useState<ContractChecklist>(initialChecklist);
  const [exceptionReason, setExceptionReason] = useState("");
  const [changeReason, setChangeReason] = useState("");

  useEffect(() => {
    const dealId = new URLSearchParams(window.location.search).get("dealId");
    setSourceDealId(dealId || undefined);
  }, []);

  useEffect(() => {
    if (!draft.data || appliedDealId === draft.data.dealId) return;
    setPrice(String(draft.data.acceptedPrice));
    setTerms(draft.data.acceptedTerms ?? "");
    setSellerName(draft.data.seller.name);
    setSellerCode(draft.data.seller.code ?? "");
    setSellerAddress(draft.data.seller.address ?? "");
    setSellerIban(draft.data.seller.iban ?? "");
    setParcels(draft.data.parcels.map(parcel => ({ cadastralCode: parcel.cadastralCode, address: parcel.address ?? "", areaHectares: parcel.areaHectares == null ? "" : String(parcel.areaHectares) })));
    setRemovedParcel(undefined);
    setChecklist(current => ({
      ...current,
      partiesConfirmed: true,
      parcelsConfirmed: true,
      priceConfirmed: true,
      acceptedOfferConfirmed: true,
      ibanConfirmed: Boolean(draft.data.seller.iban),
    }));
    setAppliedDealId(draft.data.dealId);
  }, [appliedDealId, draft.data]);

  useEffect(() => {
    if (!draft.data || companyProfileId || !companies?.length) return;
    const defaultCompany = companies.find(company => company.isDefault);
    if (defaultCompany) setCompanyProfileId(defaultCompany.id);
  }, [companies, companyProfileId, draft.data]);

  useEffect(() => {
    if (!templates?.length) return;
    const resolved = templateId ? templates.find(t => t.id === templateId) : templates.find(t => t.isDefault && t.active);
    setChecklist(current => ({ ...current, templateConfirmed: Boolean(resolved) }));
  }, [templateId, templates]);

  const updateParcel = (index: number, key: keyof ParcelForm, value: string) => setParcels(current => current.map((parcel, parcelIndex) => parcelIndex === index ? { ...parcel, [key]: value } : parcel));
  const removeParcel = (index: number) => setParcels(current => {
    const result = removeRowAt(current, index);
    setRemovedParcel(result.removed);
    return result.items;
  });
  const undoParcelRemoval = () => {
    if (!removedParcel) return;
    setParcels(current => restoreRemovedRow(current, removedParcel));
    setRemovedParcel(undefined);
  };
  const validParcels = parcels.filter(parcel => parcel.cadastralCode.trim());
  const sourceBlocked = !sourceDealId || Boolean(draft.isError) || !draft.data;
  const latestVersion = versions.data?.[0];
  const nextVersion = latestVersion ? latestVersion.versionNo + 1 : 1;
  const unchecked = useMemo(() => Object.entries(checklist).filter(([key, value]) => key !== "exceptionReason" && value === false).length, [checklist]);

  const handleGenerate = () => {
    if (!draft.data || !contractNumber || !sellerName || !validParcels.length || sourceBlocked) return;
    generate.mutate({
      templateId: templateId || undefined,
      companyProfileId: companyProfileId || undefined,
      contractNumber, contractDate, place,
      price: price ? Number(price) : undefined,
      terms: terms || undefined,
      seller: { name: sellerName, code: sellerCode || undefined, address: sellerAddress || undefined, iban: sellerIban || undefined },
      parcels: validParcels.map(parcel => ({ cadastralCode: parcel.cadastralCode, address: parcel.address || undefined, areaHectares: parcel.areaHectares ? Number(parcel.areaHectares) : undefined })),
      dealId: draft.data.dealId,
      offerEntryId: draft.data.offerEntryId,
      checklist: { ...checklist, exceptionReason: exceptionReason || undefined },
      changeReason: nextVersion > 1 ? changeReason || undefined : undefined,
      replacesContractId: nextVersion > 1 ? latestVersion?.contractId : undefined,
    });
  };

  const toggle = (key: keyof ContractChecklist) => setChecklist(current => ({ ...current, [key]: !current[key] }));

  return (
    <div className="space-y-8">
      <div><h1 className="text-2xl font-bold">Lepingud</h1><p className="text-sm text-muted-foreground">Kontrollitud ja versioonitud PDF-lepingud võidetud tehingu aktsepteeritud pakkumisest.</p></div>

      <Card><CardHeader><CardTitle className="flex items-center gap-2"><FilePlus className="h-5 w-5" />Koosta kontrollitud PDF-leping {sourceDealId && <Badge variant="outline">v{nextVersion}</Badge>}</CardTitle></CardHeader><CardContent className="space-y-5">
        {!sourceDealId && <div className="rounded-md border bg-muted/40 p-3 text-sm">Lepingu koostamine algab võidetud tehingust. Ava lepingute vaade tehingu <code>?dealId=…</code> lingist.</div>}
        {sourceDealId && draft.isLoading && <div className="rounded-md border bg-muted/40 p-3 text-sm">Laadin võidetud tehingu kokkuleppe andmeid…</div>}
        {sourceDealId && draft.isError && <div className="rounded-md border border-destructive/30 bg-destructive/5 p-3 text-sm text-destructive">Selle tehingu põhjal ei saa lepingut luua: {draft.error.message}</div>}
        {draft.data && <div className="rounded-md border border-primary/30 bg-primary/5 p-3 text-sm"><p className="font-medium">Eeltäidetud aktsepteeritud pakkumisest</p><p className="mt-1 text-xs text-muted-foreground">Hind, tingimused, müüja ja kinnistud peavad jääma kokku lepitud lähteandmetega kooskõlla. Lähteandmete snapshot salvestatakse lepinguversiooni auditis.</p></div>}

        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
          <SelectField label="Lepingumall" value={templateId} onChange={setTemplateId}><option value="">Kasuta vaikimisi malli</option>{templates?.filter(template => template.active).map(template => <option key={template.id} value={template.id}>{template.name}</option>)}</SelectField>
          <SelectField label="Ostja ettevõte" value={companyProfileId} onChange={setCompanyProfileId}><option value="">Kasuta malliga seotud / vaikimisi ettevõtet</option>{companies?.map(company => <option key={company.id} value={company.id}>{company.legalName}</option>)}</SelectField>
          <LabeledInput label="Lepingu number *" value={contractNumber} onChange={setContractNumber} />
          <LabeledInput label="Kuupäev" type="date" value={contractDate} onChange={setContractDate} />
          <LabeledInput label="Koht" value={place} onChange={setPlace} />
          <LabeledInput label="Hind (€)" type="number" value={price} onChange={setPrice} />
        </div>
        <LabeledTextarea label="Tingimused" value={terms} onChange={setTerms} />

        <div className="border-t pt-4"><p className="mb-3 font-medium">Müüja</p><div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
          <LabeledInput label="Nimi *" value={sellerName} onChange={setSellerName} /><LabeledInput label="Isiku- või registrikood" value={sellerCode} onChange={setSellerCode} /><LabeledInput label="Aadress" value={sellerAddress} onChange={setSellerAddress} /><LabeledInput label="IBAN" value={sellerIban} onChange={value => { setSellerIban(value); if (value.trim()) setChecklist(current => ({ ...current, ibanConfirmed: true })); }} />
        </div></div>

        <div className="border-t pt-4"><div className="mb-3 flex items-center justify-between gap-3"><p className="font-medium">Kinnistud</p><Button type="button" size="sm" variant="outline" onClick={() => setParcels(current => [...current, emptyParcel()])}><Plus className="mr-1 h-4 w-4" />Lisa kinnistu</Button></div>
          <div className="space-y-3">{parcels.map((parcel, index) => {
            const parcelName = parcel.cadastralCode.trim() || `rida ${index + 1}`;
            const removeLabel = `Eemalda kinnistu ${parcelName}`;
            return <div key={index} className="grid gap-3 rounded-md border p-3 md:grid-cols-[1fr_1.5fr_0.7fr_auto]">
              <LabeledInput label={`Katastritunnus ${index + 1} *`} value={parcel.cadastralCode} onChange={value => updateParcel(index, "cadastralCode", value)} /><LabeledInput label="Aadress" value={parcel.address} onChange={value => updateParcel(index, "address", value)} /><LabeledInput label="Pindala (ha)" type="number" value={parcel.areaHectares} onChange={value => updateParcel(index, "areaHectares", value)} /><div className="flex items-end"><Button type="button" size="icon" variant="ghost" aria-label={removeLabel} title={removeLabel} disabled={parcels.length === 1} onClick={() => removeParcel(index)}><Trash2 className="h-4 w-4" /></Button></div>
            </div>;
          })}</div>
          {removedParcel && <div className="mt-3"><UndoToast message={`Kinnistu ${removedParcel.item.cadastralCode || `rida ${removedParcel.index + 1}`} eemaldati vormilt.`} onUndo={undoParcelRemoval} onDismiss={() => setRemovedParcel(undefined)} /></div>}
        </div>

        <Card><CardHeader><CardTitle>Lepingu kontrollnimekiri</CardTitle></CardHeader><CardContent className="space-y-3">
          <CheckRow checked={checklist.partiesConfirmed} onChange={() => toggle("partiesConfirmed")} label="Osapooled kontrollitud" auto={Boolean(draft.data)} />
          <CheckRow checked={checklist.parcelsConfirmed} onChange={() => toggle("parcelsConfirmed")} label="Kinnistud vastavad tehingule" auto={Boolean(draft.data)} />
          <CheckRow checked={checklist.priceConfirmed} onChange={() => toggle("priceConfirmed")} label="Hind vastab aktsepteeritud pakkumisele" auto={Boolean(draft.data)} />
          <CheckRow checked={checklist.ibanConfirmed} onChange={() => toggle("ibanConfirmed")} label="IBAN kontrollitud" auto={Boolean(draft.data?.seller.iban)} />
          <CheckRow checked={checklist.acceptedOfferConfirmed} onChange={() => toggle("acceptedOfferConfirmed")} label="Aktsepteeritud pakkumine kontrollitud" auto={Boolean(draft.data)} />
          <CheckRow checked={checklist.templateConfirmed} onChange={() => toggle("templateConfirmed")} label="Õige lepingumall kontrollitud" auto={checklist.templateConfirmed} />
          {unchecked > 0 && <div className="rounded-md border border-amber-300 bg-amber-50 p-3"><p className="text-sm font-medium">{unchecked} kohustuslikku kontrolli on kinnitamata.</p><p className="text-xs text-muted-foreground">Genereerimine on lubatud ainult põhjendatud erandiga; põhjus talletatakse muutmatus auditis.</p><textarea className="mt-2 min-h-20 w-full rounded-md border bg-white p-2 text-sm" value={exceptionReason} onChange={e => setExceptionReason(e.target.value)} placeholder="Erandi põhjendus" /></div>}
          {nextVersion > 1 && <label className="grid gap-1 text-sm"><span className="font-medium">Versiooni v{nextVersion} muutmise põhjus *</span><textarea className="min-h-20 rounded-md border p-2" value={changeReason} onChange={e => setChangeReason(e.target.value)} /><span className="text-xs text-muted-foreground">Asendab lepingut {latestVersion?.contractId} (v{latestVersion?.versionNo}). Varasem PDF jääb alles.</span></label>}
        </CardContent></Card>

        <div className="flex flex-wrap items-center gap-3"><Button onClick={handleGenerate} disabled={!draft.data || !contractNumber || !sellerName || !validParcels.length || generate.isPending || (unchecked > 0 && !exceptionReason.trim()) || (nextVersion > 1 && !changeReason.trim())}>Genereeri PDF v{nextVersion}</Button>
          {generate.data && <Button variant="outline" size="sm" onClick={() => download.mutate({ contractId: generate.data!.contractId, fileName: generate.data!.fileName })}><Download className="mr-2 h-4 w-4" />Laadi alla {generate.data.fileName}</Button>}
          {generate.isError && <span className="text-sm text-destructive">{generate.error.message}</span>}
        </div>
      </CardContent></Card>

      {sourceDealId && <Card><CardHeader><CardTitle>Lepingu versiooniajalugu</CardTitle></CardHeader><CardContent className="space-y-3">{versions.data?.map(version => <div key={version.contractId} className="rounded-md border p-3"><div className="flex flex-wrap items-center gap-2"><Badge>v{version.versionNo}</Badge><code className="text-xs">{version.contractId}</code></div><p className="mt-1 text-sm">{version.changeReason || "Algne leping"}</p><p className="text-xs text-muted-foreground">{new Date(version.createdAt).toLocaleString("et-EE")} · {version.createdBy} · mall {version.templateSha256.slice(0, 12)}…</p><Button className="mt-2" size="sm" variant="outline" onClick={() => download.mutate({ contractId: version.contractId, fileName: `leping-v${version.versionNo}.pdf` })}>Laadi see versioon alla</Button></div>)}{versions.data?.length === 0 && <p className="text-sm text-muted-foreground">Sellel tehingul pole veel genereeritud lepinguversioone.</p>}</CardContent></Card>}

      <section className="space-y-4"><h2 className="text-lg font-semibold">Lepingute ajalugu</h2><div className="flex flex-wrap gap-3"><Input placeholder="Katastritunnus" value={cadastre} onChange={event => setCadastre(event.target.value)} className="w-48" /><Input placeholder="Ostja" value={buyer} onChange={event => setBuyer(event.target.value)} className="w-48" /><Input placeholder="Müüja" value={sellerFilter} onChange={event => setSellerFilter(event.target.value)} className="w-48" /></div>{isLoading && <p className="text-muted-foreground">Laadimine...</p>}
        <Table><TableHeader><TableRow><TableHead>Lepingu nr</TableHead><TableHead>Müüjad</TableHead><TableHead>Ostja</TableHead><TableHead>Allikas</TableHead><TableHead>Kuupäev</TableHead></TableRow></TableHeader><TableBody>{contracts?.map(contract => <TableRow key={contract.id}><TableCell>{contract.contractNo || "—"}</TableCell><TableCell>{contract.sellers || "—"}</TableCell><TableCell>{contract.buyer || "—"}</TableCell><TableCell>{contract.dealId ? <span className="text-xs">Deal {contract.dealId.slice(0, 8)}</span> : <span className="text-xs text-muted-foreground">Käsitsi</span>}</TableCell><TableCell>{new Date(contract.createdAt).toLocaleDateString("et-EE")}</TableCell></TableRow>)}</TableBody></Table>
      </section>
    </div>
  );
}

function CheckRow({ checked, onChange, label, auto }: { checked: boolean; onChange: () => void; label: string; auto: boolean }) { return <label className="flex items-center justify-between gap-3 rounded-md border p-3 text-sm"><span><input className="mr-2" type="checkbox" checked={checked} onChange={onChange} />{label}</span><Badge variant="outline">{auto ? "Automaatkontroll" : "Kasutaja kinnitus"}</Badge></label>; }
function LabeledInput({ label, value, onChange, type = "text" }: { label: string; value: string; onChange: (value: string) => void; type?: string }) { return <label className="grid gap-1 text-sm"><span className="font-medium">{label}</span><Input type={type} value={value} onChange={event => onChange(event.target.value)} /></label>; }
function LabeledTextarea({ label, value, onChange }: { label: string; value: string; onChange: (value: string) => void }) { return <label className="grid gap-1 text-sm"><span className="font-medium">{label}</span><textarea className="min-h-[120px] rounded-md border bg-background p-3 text-sm" value={value} onChange={event => onChange(event.target.value)} /></label>; }
function SelectField({ label, value, onChange, children }: { label: string; value: string; onChange: (value: string) => void; children: React.ReactNode }) { return <label className="grid gap-1 text-sm"><span className="font-medium">{label}</span><select className="h-10 rounded-md border bg-background px-3 text-sm" value={value} onChange={event => onChange(event.target.value)}>{children}</select></label>; }
