import type { Metadata } from "next";
import "maplibre-gl/dist/maplibre-gl.css";
import "./globals.css";
import { AppProviders } from "@/components/providers";

export const metadata: Metadata = {
  title: "ForestIQ",
  description: "Metsaomanike CRM ja georuumiline töölaud",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="et">
      <body>
        <AppProviders>{children}</AppProviders>
      </body>
    </html>
  );
}
