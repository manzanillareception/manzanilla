import { afterEach, describe, expect, it, vi } from "vitest";
import {
  ApiClient,
  ApiError,
  canManage,
  canOperate,
  extractEmailLink,
  relativeTime,
  type Session,
} from "./api";

afterEach(() => vi.unstubAllGlobals());
const session: Session = {
  user: { id: "actor", email: "manager@example.test", platform_owner: false },
  csrf_token: "fixture-csrf",
};
function response(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

describe("same-origin control API", () => {
  it("uses opaque cookies and in-memory CSRF only for mutations", async () => {
    const fetcher = vi
      .fn()
      .mockImplementation(() => Promise.resolve(response({ ok: true })));
    vi.stubGlobal("fetch", fetcher);
    const api = new ApiClient();
    api.setSession(session);
    await api.request("/hotels");
    await api.request("/devices/device-1/commands", "POST", {
      type: "diagnostics",
    });
    expect(fetcher.mock.calls[0][0]).toBe("/api/v2/hotels");
    expect(fetcher.mock.calls[0][1].credentials).toBe("same-origin");
    expect(fetcher.mock.calls[0][1].headers["X-CSRF-Token"]).toBeUndefined();
    expect(fetcher.mock.calls[1][1].headers["X-CSRF-Token"]).toBe(
      "fixture-csrf",
    );
    expect(fetcher.mock.calls[1][1].headers.Authorization).toBeUndefined();
  });
  it("follows every server page before presenting fleet counts", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(
        response({ items: [{ id: "first" }], next_offset: 1 }),
      )
      .mockResolvedValueOnce(
        response({ items: [{ id: "second" }], next_offset: null }),
      );
    vi.stubGlobal("fetch", fetcher);
    expect(await new ApiClient().list("/devices?hotel_id=hotel-1")).toEqual([
      { id: "first" },
      { id: "second" },
    ]);
    expect(fetcher.mock.calls[1][0]).toBe(
      "/api/v2/devices?hotel_id=hotel-1&limit=200&offset=1",
    );
  });
  it("rejects looping cursors rather than silently claiming a complete list", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          response({ items: [{ id: "first" }], next_offset: 0 }),
        ),
    );
    await expect(new ApiClient().list("/devices")).rejects.toThrow(
      "invalid page cursor",
    );
  });
  it("does not treat a network/database failure as an empty fleet", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockRejectedValue(new TypeError("network unavailable")),
    );
    await expect(new ApiClient().list("/devices")).rejects.toMatchObject({
      status: 0,
    });
  });
  it("invalidates an expired signed-in session and drops its CSRF token", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(response({ detail: "expired" }, 401)),
    );
    const api = new ApiClient();
    api.setSession(session);
    api.onUnauthorized = vi.fn(() => api.setSession(null));
    await expect(api.request("/devices")).rejects.toBeInstanceOf(ApiError);
    expect(api.onUnauthorized).toHaveBeenCalledOnce();
  });
  it("never accepts a different origin as an API path", async () => {
    const fetcher = vi.fn();
    vi.stubGlobal("fetch", fetcher);
    await expect(
      new ApiClient().request("https://example.test/leak"),
    ).rejects.toThrow("Invalid API path");
    await expect(
      new ApiClient().request("//example.test/leak"),
    ).rejects.toThrow("Invalid API path");
    expect(fetcher).not.toHaveBeenCalled();
  });
  it("keeps a failed authenticator code recoverable without discarding the partial session", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          response({ detail: "MFA verification failed" }, 401),
        ),
    );
    const api = new ApiClient();
    api.setSession(session);
    api.onUnauthorized = vi.fn();
    await expect(
      api.request("/auth/mfa/verify", "POST", { code: "000000" }),
    ).rejects.toMatchObject({ status: 401 });
    expect(api.onUnauthorized).not.toHaveBeenCalled();
  });
  it("reports an assurance downgrade separately from an invalid session", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          response({ detail: "MFA verification required" }, 403),
        ),
    );
    const api = new ApiClient();
    api.setSession(session);
    api.onUnauthorized = vi.fn();
    api.onAssuranceRequired = vi.fn();
    await expect(api.request("/devices")).rejects.toMatchObject({
      status: 403,
    });
    expect(api.onAssuranceRequired).toHaveBeenCalledOnce();
    expect(api.onUnauthorized).not.toHaveBeenCalled();
  });
  it("clears disabled-account sessions without confusing ordinary scope denials", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(response({ detail: "Hotel access denied" }, 403))
      .mockResolvedValueOnce(
        response({ detail: "Account access unavailable" }, 403),
      );
    vi.stubGlobal("fetch", fetcher);
    const api = new ApiClient();
    api.setSession(session);
    api.onUnauthorized = vi.fn();
    await expect(api.request("/hotels/other")).rejects.toMatchObject({
      status: 403,
    });
    expect(api.onUnauthorized).not.toHaveBeenCalled();
    await expect(api.request("/hotels")).rejects.toMatchObject({ status: 403 });
    expect(api.onUnauthorized).toHaveBeenCalledOnce();
  });
});
describe("presentation permissions and timestamps", () => {
  it("keeps unknown hotel roles read-only", () => {
    expect(canManage(undefined, session)).toBe(false);
    expect(canOperate(undefined, session)).toBe(false);
  });
  it("preserves a leading-zero authenticator or invitation token as a string", () => {
    expect(
      extractEmailLink({
        href: "https://console.example.test/?token_hash=000abc&type=invite",
      }),
    ).toEqual({ token_hash: "000abc", type: "invite" });
  });
  it("does not accept arbitrary email-link types", () => {
    expect(
      extractEmailLink({
        href: "https://console.example.test/?token_hash=x&type=admin",
      }),
    ).toBeNull();
  });
  it("shows never-reported data distinctly from a zero-age heartbeat", () => {
    expect(relativeTime(null)).toBe("Never reported");
    expect(relativeTime(1000, 1000000)).toBe("0s ago");
  });
});
