"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { ArrowLeft, Plus, Save } from "lucide-react";
import { useCompanyProfiles, useContractTemplatePlaceholders, useContractTemplates, useSaveCompanyProfile, useSaveContractTemplate } from "@/lib/api";
import type { SaveCompanyProfile, SaveContractTemplate } from "@/lib/types";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";

const emptyCompany: SaveCompanyProfile = {
  legalName: "", registryCode: "", vatCode: "", address: "", email: "", phone: "", website: "",
  bankName: "", iban: "", bic: "", representativeName: "", representativeRole: "", isDefault: false,
};
const emptyTemplate: SaveContractTemplate = {
  companyProfileId: undefined, name: "", code: "", description: "", htmlTemplate: "", active: true, isDefault: false,
};

export default function ContractConfigurationPage() {
  const companies = useCompanyProfiles();
  const templates = useContractTemplates();
  const placeholders = useContractTemplatePlaceholders();
  const saveCompany = useSaveCompanyProfile();
  const saveTemplate = useSaveContractTemplate();
  const [activeTab, setActiveTab] = useState<"company" | "template">("company");
  const [companyId, setCompanyId] = useState<string | undefined>();
  const [templateId, setTemplateId] = useState<string | undefined>();
  const [company, setCompany] = useState<SaveCompanyProfile>(emptyCompany);
  const [template, setTemplate] = useState<SaveContractTemplate>(emptyTemplate);

  useEffect(() => {
    if (!companyId && companies.data?.length) selectCompany(companies.data[0].id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [companies.data, companyId]);
  useEffect(() => {
    if (!templateId && templates.data?.length) selectTemplate(templates.data[0].id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [templates.data, templateId]);

  function selectCompany(id: string) {
    const item = companies.data?.find(c => c.id === id);
    if (!item) return;
    setCompanyId(id);
    const { id: _, logoObjectKey: __, ...data } = item;
    setCompany(data);
  }
  function selectTemplate(id: string) {
    const item = templates.data?.find(t => t.id === id);
    if (!item) return;
    setTemplateId(id);
    const { id: _, documentType: __, updatedAt: ___, ...data } = item;
    setTemplate(data);
  }
  function updateCompany<K extends keyof SaveCompanyProfile>(key: K, value: SaveCompanyProfile[K]) { setCompany(prev => ({ ...prev, [key]: value })); }
  function updateTemplate<K extends keyof SaveContractTemplate>(key: K, value: SaveContractTemplate[K]) { setTemplate(prev => ({ ...prev, [key]: value })); }

  return (
    <div className="space-y-6">
      <div className="flex items-center gap-3">
        <Button asChild variant="ghost" size="icon" aria-label="Tagasi haldusesse"><Link href="/admin"><ArrowLeft className="h-4 w-4" /></Link></Button>
        <div><h1 className="text-2xl font-bold">Ettevõtted ja lepingumallid</h1><p className="text-sm text-muted-foreground">Halda PDF-lepingute väljastaja andmeid ja HTML-malle.</p></div>
      </div>
      <div className="flex gap-2 border-b">
        <Button variant={activeTab === "company" ? "default" : "ghost"} onClick={() => setActiveTab("company")}>Ettevõtteprofiilid</Button>
        <Button variant={activeTab === "template" ? "default" : "ghost"} onClick={() => setActiveTab("template")}>Lepingumallid</Button>
      </div>

      {activeTab === "company" && (
        <div className="grid gap-6 lg:grid-cols-[280px_1fr]">
          <Card><CardHeader><CardTitle className="text-base">Profiilid</CardTitle></CardHeader><CardContent className="space-y-2">
            <Button className="w-full justify-start" variant="outline" onClick={() => { setCompanyId(undefined); setCompany(emptyCompany); }}><Plus className="mr-2 h-4 w-4" />Uus ettevõte</Button>
            {companies.data?.map(item => <Button key={item.id} className="w-full justify-start" variant={companyId === item.id ? "default" : "ghost"} onClick={() => selectCompany(item.id)}>{item.legalName}</Button>)}
          </CardContent></Card>
          <Card><CardHeader><CardTitle>{companyId ? "Muuda ettevõtteprofiili" : "Uus ettevõtteprofiil"}</CardTitle></CardHeader><CardContent className="grid gap-4 md:grid-cols-2">
            <LabeledInput label="Juriidiline nimi *" value={company.legalName ?? ""} onChange={v => updateCompany("legalName", v)} />
            <LabeledInput label="Registrikood" value={company.registryCode ?? ""} onChange={v => updateCompany("registryCode", v)} />
            <LabeledInput label="KMKR number" value={company.vatCode ?? ""} onChange={v => updateCompany("vatCode", v)} />
            <LabeledInput label="Aadress" value={company.address ?? ""} onChange={v => updateCompany("address", v)} />
            <LabeledInput label="E-post" value={company.email ?? ""} onChange={v => updateCompany("email", v)} />
            <LabeledInput label="Telefon" value={company.phone ?? ""} onChange={v => updateCompany("phone", v)} />
            <LabeledInput label="Veebileht" value={company.website ?? ""} onChange={v => updateCompany("website", v)} />
            <LabeledInput label="Pank" value={company.bankName ?? ""} onChange={v => updateCompany("bankName", v)} />
            <LabeledInput label="IBAN" value={company.iban ?? ""} onChange={v => updateCompany("iban", v)} />
            <LabeledInput label="BIC" value={company.bic ?? ""} onChange={v => updateCompany("bic", v)} />
            <LabeledInput label="Esindaja nimi" value={company.representativeName ?? ""} onChange={v => updateCompany("representativeName", v)} />
            <LabeledInput label="Esindaja amet" value={company.representativeRole ?? ""} onChange={v => updateCompany("representativeRole", v)} />
            <label className="flex items-center gap-2 text-sm md:col-span-2"><input type="checkbox" checked={company.isDefault} onChange={e => updateCompany("isDefault", e.target.checked)} />Kasuta vaikimisi lepingu ostjana</label>
            <div className="md:col-span-2"><Button onClick={() => saveCompany.mutate({ id: companyId, data: company })} disabled={!company.legalName || saveCompany.isPending}><Save className="mr-2 h-4 w-4" />Salvesta ettevõte</Button></div>
          </CardContent></Card>
        </div>
      )}

      {activeTab === "template" && (
        <div className="grid gap-6 lg:grid-cols-[280px_1fr]">
          <Card><CardHeader><CardTitle className="text-base">Mallid</CardTitle></CardHeader><CardContent className="space-y-2">
            <Button className="w-full justify-start" variant="outline" onClick={() => { setTemplateId(undefined); setTemplate(emptyTemplate); }}><Plus className="mr-2 h-4 w-4" />Uus mall</Button>
            {templates.data?.map(item => <Button key={item.id} className="w-full justify-start" variant={templateId === item.id ? "default" : "ghost"} onClick={() => selectTemplate(item.id)}>{item.name}</Button>)}
          </CardContent></Card>
          <div className="space-y-6">
            <Card><CardHeader><CardTitle>{templateId ? "Muuda lepingumalli" : "Uus lepingumall"}</CardTitle></CardHeader><CardContent className="space-y-4">
              <div className="grid gap-4 md:grid-cols-2">
                <LabeledInput label="Malli nimi *" value={template.name ?? ""} onChange={v => updateTemplate("name", v)} />
                <LabeledInput label="Tehniline kood *" value={template.code ?? ""} onChange={v => updateTemplate("code", v)} />
                <label className="grid gap-1 text-sm"><span className="font-medium">Seotud ettevõte</span><select className="h-10 rounded-md border bg-background px-3" value={template.companyProfileId ?? ""} onChange={e => updateTemplate("companyProfileId", e.target.value || undefined)}><option value="">Kasuta vaikimisi ettevõtet</option>{companies.data?.map(c => <option value={c.id} key={c.id}>{c.legalName}</option>)}</select></label>
                <LabeledInput label="Kirjeldus" value={template.description ?? ""} onChange={v => updateTemplate("description", v)} />
              </div>
              <label className="grid gap-1 text-sm"><span className="font-medium">HTML-mall *</span><textarea className="min-h-[420px] w-full rounded-md border bg-background p-3 font-mono text-xs leading-5" value={template.htmlTemplate ?? ""} onChange={e => updateTemplate("htmlTemplate", e.target.value)} /></label>
              <div className="flex flex-wrap gap-4 text-sm"><label className="flex items-center gap-2"><input type="checkbox" checked={template.active ?? false} onChange={e => updateTemplate("active", e.target.checked)} />Aktiivne</label><label className="flex items-center gap-2"><input type="checkbox" checked={template.isDefault ?? false} onChange={e => updateTemplate("isDefault", e.target.checked)} />Vaikimisi mall</label></div>
              <Button onClick={() => saveTemplate.mutate({ id: templateId, data: template })} disabled={!template.name || !template.code || !template.htmlTemplate || saveTemplate.isPending}><Save className="mr-2 h-4 w-4" />Salvesta mall</Button>
            </CardContent></Card>
            <Card><CardHeader><CardTitle className="text-base">Lubatud asendusväljad</CardTitle></CardHeader><CardContent><div className="flex flex-wrap gap-2">{placeholders.data?.placeholders.map(item => <code className="rounded bg-muted px-2 py-1 text-xs" key={item}>{item}</code>)}</div></CardContent></Card>
          </div>
        </div>
      )}
    </div>
  );
}

function LabeledInput({ label, value, onChange }: { label: string; value: string; onChange: (value: string) => void }) {
  return <label className="grid gap-1 text-sm"><span className="font-medium">{label}</span><Input value={value} onChange={e => onChange(e.target.value)} /></label>;
}
