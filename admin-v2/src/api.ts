export interface Session {
  user: { id: string; email: string; platform_owner: boolean };
  csrf_token: string;
  requires_password?: boolean;
  requires_mfa?: boolean;
}

export type HotelRole = "owner" | "manager" | "operator" | "viewer";
export interface Hotel {
  id: string;
  name: string;
  timezone: string;
  organization_id?: string;
  config_version: number;
  role: HotelRole;
}
export interface Command {
  id: string;
  type: string;
  state: string;
  created_at: number;
  expires_at: number;
  result_code?: string | null;
}
export interface Device {
  id: string;
  hotel_id: string;
  name: string;
  status: string;
  connectivity: string;
  last_seen: number | null;
  app_version?: string | null;
  mode?: string | null;
  mic_state?: string | null;
  health?: Record<string, unknown>;
  config_version: number;
  desired_config_version: number;
  update_state?: string | null;
  commands?: Command[];
  recent_events?: DeviceEvent[];
  telemetry_summary?: TelemetrySummary;
}
export interface TelemetrySummary {
  window_days: number;
  window_start: number;
  window_end: number;
  window_basis: "received_at";
  event_counts: {
    session_started: number;
    session_ended: number;
    audio_error: number;
  };
  timings: Record<
    string,
    { sample_count: number; mean_ms: number; min_ms: number; max_ms: number }
  >;
  timing_event_limit: number;
  sampled_timing_event_count: number;
  total_timing_event_count: number;
  truncated: boolean;
}
export interface DeviceEvent {
  id: string;
  type: string;
  occurred_at: number;
  metrics: Record<string, unknown>;
}
export interface Enrollment {
  id?: string;
  request_id?: string;
  user_code: string;
  device_name: string;
  status: string;
  expires_at: number;
}
export interface Alert {
  id: string;
  hotel_id: string;
  device_id?: string;
  kind: string;
  state: string;
  message: string;
  created_at: number;
}
export interface Audit {
  id: string;
  hotel_id?: string | null;
  actor_id: string;
  action: string;
  target_id?: string | null;
  created_at: number;
}
export interface Member {
  id?: string;
  user_id?: string;
  email?: string;
  role: string;
  enabled?: boolean;
}
export interface Account {
  id: string;
  email: string;
  enabled: boolean;
  platform_owner: boolean;
}
export interface ConfigRevision {
  version: number;
  config: Record<string, unknown>;
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

function errorMessage(body: unknown, status: number): string {
  const detail =
    body && typeof body === "object" && "detail" in body
      ? (body as { detail: unknown }).detail
      : null;
  if (typeof detail === "string") return detail;
  if (
    detail &&
    typeof detail === "object" &&
    "message" in detail &&
    typeof detail.message === "string"
  )
    return detail.message;
  if (status === 401) return "Your session has ended. Please sign in again.";
  if (status === 403) return "You do not have permission for this action.";
  if (status === 409)
    return "This information changed. Refresh it before trying again.";
  if (status === 429)
    return "Too many requests. Please wait before trying again.";
  if (status === 422) return "Check the entered information and try again.";
  return "The service could not complete this request. Please try again.";
}

export class ApiClient {
  private csrf = "";
  onUnauthorized?: () => void;
  onAssuranceRequired?: () => void;
  setSession(session: Session | null) {
    this.csrf = session?.csrf_token ?? "";
  }

  async request<T>(
    path: string,
    method = "GET",
    body?: unknown,
    signal?: AbortSignal,
  ): Promise<T> {
    if (!path.startsWith("/") || path.startsWith("//"))
      throw new Error("Invalid API path");
    const headers: Record<string, string> = { Accept: "application/json" };
    if (method !== "GET") {
      headers["Content-Type"] = "application/json";
      if (this.csrf) headers["X-CSRF-Token"] = this.csrf;
    }
    const controller = new AbortController();
    const externalAbort = () => controller.abort();
    if (signal?.aborted) externalAbort();
    signal?.addEventListener("abort", externalAbort, { once: true });
    const timeout = setTimeout(
      () => controller.abort(),
      path.startsWith("/auth/") ? 30000 : 12000,
    );
    try {
      const response = await fetch("/api/v2" + path, {
        method,
        headers,
        credentials: "same-origin",
        cache: "no-store",
        body: method === "GET" ? undefined : JSON.stringify(body ?? {}),
        signal: controller.signal,
      });
      const content =
        response.status === 204
          ? null
          : await response.json().catch((error) => {
              if (controller.signal.aborted) throw error;
              return null;
            });
      if (!response.ok) {
        const accountUnavailable =
          response.status === 403 &&
          content?.detail === "Account access unavailable";
        const failedMfaCode =
          response.status === 401 && path === "/auth/mfa/verify";
        if (
          this.csrf &&
          response.status === 403 &&
          content?.detail === "MFA verification required"
        )
          this.onAssuranceRequired?.();
        if (
          this.csrf &&
          ((response.status === 401 && !failedMfaCode) || accountUnavailable)
        )
          this.onUnauthorized?.();
        throw new ApiError(
          response.status,
          errorMessage(content, response.status),
        );
      }
      if (content === null && response.status !== 204)
        throw new ApiError(502, "The service returned an unreadable response.");
      return content as T;
    } catch (error) {
      if (error instanceof ApiError) throw error;
      if (signal?.aborted) throw error;
      if (controller.signal.aborted)
        throw new ApiError(
          408,
          "The request timed out. Refresh to check its outcome before trying a change again.",
        );
      throw new ApiError(
        0,
        "Cannot reach the hotel service. Check your connection and try again.",
      );
    } finally {
      clearTimeout(timeout);
      signal?.removeEventListener("abort", externalAbort);
    }
  }

  async list<T>(path: string, signal?: AbortSignal): Promise<T[]> {
    const items: T[] = [];
    let offset = 0;
    for (let page = 0; page < 100; page++) {
      const separator = path.includes("?") ? "&" : "?";
      const result = await this.request<{
        items: T[];
        next_offset?: number | null;
      }>(
        `${path}${separator}limit=200&offset=${offset}`,
        "GET",
        undefined,
        signal,
      );
      if (!Array.isArray(result.items))
        throw new ApiError(502, "The service returned an unreadable list.");
      items.push(...result.items);
      if (result.next_offset == null) return items;
      if (
        !Number.isSafeInteger(result.next_offset) ||
        result.next_offset <= offset
      )
        throw new ApiError(502, "The service returned an invalid page cursor.");
      offset = result.next_offset;
    }
    throw new ApiError(
      413,
      "This view contains too many records. Select a hotel to narrow the view.",
    );
  }
}

export function timestamp(value: number | null | undefined): string {
  return value == null
    ? "Not reported"
    : new Date(value * 1000).toLocaleString();
}
export function relativeTime(
  value: number | null | undefined,
  now = Date.now(),
): string {
  if (value == null) return "Never reported";
  const seconds = Math.max(0, Math.floor(now / 1000 - value));
  if (seconds < 60) return `${seconds}s ago`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)}h ago`;
  return `${Math.floor(seconds / 86400)}d ago`;
}
export function canManage(hotel: Hotel | undefined, session: Session) {
  return (
    session.user.platform_owner ||
    hotel?.role === "owner" ||
    hotel?.role === "manager"
  );
}
export function canOperate(hotel: Hotel | undefined, session: Session) {
  return canManage(hotel, session) || hotel?.role === "operator";
}
export function extractEmailLink(
  location: Pick<Location, "href">,
): { token_hash: string; type: "invite" | "recovery" } | null {
  const url = new URL(location.href);
  const token = url.searchParams.get("token_hash");
  const type = url.searchParams.get("type");
  if (!token || (type !== "invite" && type !== "recovery")) return null;
  return { token_hash: token, type };
}
