import type { components, paths } from "@/lib/generated/openapi";

export { generatedApiClient } from "@/lib/generated/client";
export type {
  ApiHttpMethod,
  ApiPathParameters,
  ApiPathsFor,
  ApiQueryParameters,
  ApiRequestBody,
  ApiRequestOptions,
  ApiSuccessResponse,
} from "@/lib/generated/client";
export type { ApiPath as GeneratedApiPath } from "@/lib/generated/client";

export type ApiSchema<Name extends keyof components["schemas"]> = components["schemas"][Name];

export type ApiResponse<Operation, Status extends number = 200> =
  Operation extends { responses: Record<Status, { content: infer Content }> }
    ? Content[keyof Content]
    : never;

export type ApiRequest<Operation> =
  Operation extends { requestBody: { content: infer Content } }
    ? Content[keyof Content]
    : never;

export type ApiPath<Path extends keyof paths> = paths[Path];
