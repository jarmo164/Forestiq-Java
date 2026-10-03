import {
  generatedApiClient,
  type ApiHttpMethod,
  type ApiPath,
  type ApiPathParameters,
  type ApiPathsFor,
  type ApiQueryParameters,
  type ApiRequestBody,
  type ApiRequestOptions,
  type ApiSuccessResponse,
} from "@/lib/generated/client";

/**
 * Single typed transport entry point for feature API modules.
 *
 * Authentication, cookies, Origin protection, retry behavior and error
 * normalization stay in the existing BFF/apiFetch layer used by the generated
 * client. Feature modules should not construct endpoint URLs for operations
 * that exist in the OpenAPI contract.
 */
export const apiTransport = generatedApiClient;

export type {
  ApiHttpMethod,
  ApiPath,
  ApiPathParameters,
  ApiPathsFor,
  ApiQueryParameters,
  ApiRequestBody,
  ApiRequestOptions,
  ApiSuccessResponse,
};
