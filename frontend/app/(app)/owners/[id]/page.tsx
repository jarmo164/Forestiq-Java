"use client";

import { useParams } from "next/navigation";
import { Owner360Workspace } from "@/components/owners/owner-360-workspace";

export default function OwnerDetailPage() {
  const { id } = useParams<{ id: string }>();
  return <Owner360Workspace ownerId={id} />;
}