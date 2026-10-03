import { readFile, readdir } from "node:fs/promises";
import { resolve } from "node:path";

const root = resolve(import.meta.dirname, "..");

const policies = [
  {
    file: "lib/api/workdesk.ts",
    maxApiFetch: 0,
    maxDirectFetch: 0,
    reason: "My Work is fully represented in the generated OpenAPI contract.",
  },
  {
    file: "lib/api/owners.ts",
    maxApiFetch: 0,
    maxDirectFetch: 1,
    reason: "Only the CSV download remains a specialized direct-fetch exception; owner JSON calls use generated transport.",
  },
  {
    file: "lib/api/deals.ts",
    maxApiFetch: 0,
    maxDirectFetch: 0,
    reason: "Deal JSON endpoints are represented in the refreshed generated OpenAPI contract.",
  },
  {
    file: "lib/api/workbasket-handoffs.ts",
    maxApiFetch: 0,
    maxDirectFetch: 0,
    reason: "Workbasket handoff JSON endpoints are represented in the refreshed generated OpenAPI contract.",
  },
  {
    file: "lib/api/decision-support.ts",
    maxApiFetch: 0,
    maxDirectFetch: 0,
    reason: "Decision support and workbasket JSON endpoints use generated transport.",
  },
  {
    file: "lib/api/owner-cursor.ts",
    maxApiFetch: 0,
    maxDirectFetch: 0,
    reason: "Owner cursor uses generated OpenAPI query and response types.",
  },
  {
    file: "lib/api/owner-360.ts",
    maxApiFetch: 0,
    maxDirectFetch: 0,
    reason: "Owner 360 JSON endpoints use generated transport.",
  },
  {
    file: "lib/api/ownership-relations.ts",
    maxApiFetch: 0,
    maxDirectFetch: 0,
    reason: "Ownership relation JSON endpoints use generated transport.",
  },
  {
    file: "lib/api/ownership-relations-cursor.ts",
    maxApiFetch: 0,
    maxDirectFetch: 0,
    reason: "Ownership relation cursor uses generated transport.",
  },
  {
    file: "lib/api/deal-work.ts",
    maxApiFetch: 0,
    maxDirectFetch: 0,
    reason: "Deal work JSON endpoints use generated transport.",
  },
  {
    file: "lib/api/commercial-deals.ts",
    maxApiFetch: 0,
    maxDirectFetch: 0,
    reason: "Commercial deal JSON endpoints use generated transport.",
  },
];


const protectedRawPath = /\/v1\/(?:owners(?:\/|$)|deals(?:\/|$)|deal-work(?:\/|$)|ownership-relations(?:\/|$)|decision-support(?:\/|$))/;

async function enforceDomainWideRawUrlBoundary() {
  const apiDir = resolve(root, "lib/api");
  const files = (await readdir(apiDir)).filter((file) => file.endsWith(".ts"));
  for (const file of files) {
    const source = await readFile(resolve(apiDir, file), "utf8");
    const manualCalls = [
      ...source.matchAll(/\bapiFetch(?:<[^>]+>)?\s*\(\s*("[^"]+"|\`[^\`]+\`)/g),
    ].map((match) => match[1]);

    for (const call of manualCalls) {
      if (protectedRawPath.test(call)) {
        console.error(
          `Protected owner/deal/workbasket domain uses a raw apiFetch URL in lib/api/${file}: ${call}`,
        );
        failed = true;
      }
    }

    const directCalls = [
      ...source.matchAll(/(^|[^A-Za-z])fetch\s*\(\s*("[^"]+"|\`[^\`]+\`)/gm),
    ].map((match) => match[2]);

    for (const call of directCalls) {
      const isOwnerImportCsvException =
        file === "owners.ts" &&
        call.includes("/v1/owners/imports/batches/") &&
        call.includes("/errors.csv");
      if (protectedRawPath.test(call) && !isOwnerImportCsvException) {
        console.error(
          `Protected owner/deal/workbasket domain uses a raw fetch URL in lib/api/${file}: ${call}`,
        );
        failed = true;
      }
    }
  }
}

function count(source, pattern) {
  return [...source.matchAll(pattern)].length;
}

let failed = false;

for (const policy of policies) {
  const source = await readFile(resolve(root, policy.file), "utf8");
  const apiFetchCalls =
    count(source, /\bapiFetch\s*</g) +
    count(source, /\bapiFetch\s*\(/g);
  const directFetchCalls = count(source, /(^|[^A-Za-z])fetch\s*\(/gm);

  console.log(
    `${policy.file}: apiFetch=${apiFetchCalls}/${policy.maxApiFetch}, direct fetch=${directFetchCalls}/${policy.maxDirectFetch}`,
  );

  if (apiFetchCalls > policy.maxApiFetch || directFetchCalls > policy.maxDirectFetch) {
    console.error(
      `API transport boundary regression in ${policy.file}. ${policy.reason}`,
    );
    failed = true;
  }
}

await enforceDomainWideRawUrlBoundary();

const generatedOpenApi = await readFile(resolve(root, "lib/generated/openapi.d.ts"), "utf8");
for (const schemaName of ["DealWorkLoseDealRequest", "CommercialLoseDealRequest"]) {
  if (!generatedOpenApi.includes(`${schemaName}: {`)) {
    console.error(`Generated OpenAPI contract is missing the disambiguated schema ${schemaName}.`);
    failed = true;
  }
}

if (failed) {
  console.error(
    "Use apiTransport/generated OpenAPI paths for new JSON calls. Reduce exception counts when endpoints are migrated.",
  );
  process.exit(1);
}
