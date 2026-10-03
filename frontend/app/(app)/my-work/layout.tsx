import { WorkbasketHandoffInbox } from "@/components/work/workbasket-handoff-inbox";

export default function MyWorkLayout({ children }: { children: React.ReactNode }) {
  return <div className="space-y-6"><WorkbasketHandoffInbox />{children}</div>;
}
