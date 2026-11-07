import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { App } from "./App";
import type { Device, Hotel, Session, TelemetrySummary } from "./api";

const owner: Session = {
  user: { id: "owner-1", email: "owner@example.test", platform_owner: true },
  csrf_token: "fixture-csrf",
  requires_password: false,
  requires_mfa: false,
};
const hotel: Hotel = {
  id: "hotel-1",
  name: "Synthetic Hotel",
  timezone: "Europe/Madrid",
  config_version: 1,
  role: "owner",
};
const device: Device = {
  id: "device-1",
  hotel_id: "hotel-1",
  name: "Reception fixture",
  status: "active",
  connectivity: "offline",
  last_seen: 1000,
  app_version: "fixture-version",
  mode: "reception",
  mic_state: "on",
  config_version: 1,
  desired_config_version: 2,
  update_state: "confirmation_required",
  health: { microphone_permission: true },
};
const reportedSummary: TelemetrySummary = {
  window_days: 14,
  window_start: 1780000000,
  window_end: 1781209600,
  window_basis: "received_at",
  event_counts: { session_started: 17, session_ended: 16, audio_error: 2 },
  timings: {
    native_audio_start_ms: {
      sample_count: 127,
      mean_ms: 125.4,
      min_ms: 100,
      max_ms: 150,
    },
  },
  timing_event_limit: 1000,
  sampled_timing_event_count: 1000,
  total_timing_event_count: 1200,
  truncated: true,
};
function res(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}
type Overrides = (
  url: URL,
  init?: RequestInit,
) => Response | Promise<Response> | undefined;
function backend(session: Session | null = owner, override?: Overrides) {
  const fetcher = vi.fn(
    async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(String(input), "https://console.example.test");
      const custom = override?.(url, init);
      if (custom !== undefined) return custom;
      const path = url.pathname;
      if (path === "/api/v2/auth/session")
        return session
          ? res(session)
          : res({ detail: "Sign in required" }, 401);
      if (path === "/api/v2/hotels")
        return res({ items: [hotel], next_offset: null });
      if (path === "/api/v2/devices")
        return res({ items: [device], next_offset: null });
      if (path === "/api/v2/devices/device-1")
        return res({ ...device, commands: [], recent_events: [] });
      if (path === "/api/v2/hotels/hotel-1/config")
        return res({
          version: 1,
          config: {
            name: "Synthetic Hotel",
            facts: "Breakfast at seven.",
            local_pack: [],
          },
        });
      if (path === "/api/v2/hotels/hotel-1/members") return res({ items: [] });
      return res({ items: [], next_offset: null });
    },
  );
  vi.stubGlobal("fetch", fetcher);
  return fetcher;
}
beforeEach(() => {
  window.history.replaceState(null, "", "/");
  localStorage.clear();
  sessionStorage.clear();
});
afterEach(() => vi.unstubAllGlobals());

describe("hotel management flows", () => {
  it("shows report counts and bounded timing statistics with actual sample counts", async () => {
    backend(owner, (url) =>
      url.pathname === "/api/v2/devices/device-1"
        ? res({ ...device, telemetry_summary: reportedSummary })
        : undefined,
    );
    render(<App />);
    await userEvent.click(
      await screen.findByRole("button", { name: "Reception fixture" }),
    );
    const section = await screen.findByRole("region", {
      name: "Reported activity",
    });
    expect(
      within(section).getByText(/Reports received in the past 14 days/),
    ).toBeInTheDocument();
    expect(
      within(section).getByText("Session-start reports").nextElementSibling,
    ).toHaveTextContent("17");
    expect(
      within(section).getByText("Session-end reports").nextElementSibling,
    ).toHaveTextContent("16");
    expect(
      within(section).getByText("Audio-error reports").nextElementSibling,
    ).toHaveTextContent("2");
    expect(
      within(section).getByText(
        /Partial timing summary: latest 1,000 of 1,200 timing reports/,
      ),
    ).toBeInTheDocument();
    const table = within(section).getByRole("table", {
      name: "Reported timing samples",
    });
    const cells = within(within(table).getAllByRole("row")[1]).getAllByRole(
      "cell",
    );
    expect(cells.map((cell) => cell.textContent)).toEqual([
      "Audio startup",
      "127",
      "125.4",
      "100",
      "150",
    ]);
    expect(
      within(section).getByText(/do not measure acoustic latency/),
    ).toBeInTheDocument();
  });
  it("does not imply zero latency when the reporting window has no timing samples", async () => {
    backend(owner, (url) =>
      url.pathname === "/api/v2/devices/device-1"
        ? res({
            ...device,
            telemetry_summary: {
              ...reportedSummary,
              event_counts: {
                session_started: 0,
                session_ended: 0,
                audio_error: 0,
              },
              timings: {},
              sampled_timing_event_count: 0,
              total_timing_event_count: 0,
              truncated: false,
            },
          })
        : undefined,
    );
    render(<App />);
    await userEvent.click(
      await screen.findByRole("button", { name: "Reception fixture" }),
    );
    const section = await screen.findByRole("region", {
      name: "Reported activity",
    });
    expect(
      within(section).getByText("No timing samples reported for this window."),
    ).toBeInTheDocument();
    expect(within(section).queryByRole("table")).not.toBeInTheDocument();
    expect(
      within(section).queryByText(/Partial timing summary/),
    ).not.toBeInTheDocument();
  });
  it("shows independent connectivity and microphone observations", async () => {
    backend();
    render(<App />);
    await screen.findByRole("button", { name: "Reception fixture" });
    expect(screen.getByRole("cell", { name: "offline" })).toBeInTheDocument();
    expect(screen.getByText("Mic: on")).toBeInTheDocument();
    await userEvent.click(
      screen.getByRole("button", { name: "Reception fixture" }),
    );
    const dialog = await screen.findByRole("dialog");
    await within(dialog).findByText("confirmation required");
    expect(within(dialog).getByText("Microphone")).toBeInTheDocument();
    expect(within(dialog).getByText("on")).toBeInTheDocument();
  });
  it("keeps viewer accounts from displaying management controls", async () => {
    const viewer = { ...owner, user: { ...owner.user, platform_owner: false } };
    backend(viewer, (url) =>
      url.pathname === "/api/v2/hotels"
        ? res({ items: [{ ...hotel, role: "viewer" }], next_offset: null })
        : undefined,
    );
    render(<App />);
    await screen.findByRole("button", { name: "Reception fixture" });
    await userEvent.click(
      screen.getByRole("button", { name: "Reception fixture" }),
    );
    await screen.findByRole("dialog");
    expect(
      screen.queryByRole("button", { name: "Request diagnostics" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Revoke enrollment" }),
    ).not.toBeInTheDocument();
  });
  it("does not display an unavailable fleet as zero devices", async () => {
    backend(owner, (url) =>
      url.pathname === "/api/v2/devices"
        ? res({ detail: "Database unavailable" }, 503)
        : undefined,
    );
    render(<App />);
    await screen.findByText("Workspace could not load");
    expect(screen.queryByText("No devices yet")).not.toBeInTheDocument();
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Database unavailable",
    );
  });
  it("signs in without putting authentication data in browser storage", async () => {
    const fetcher = backend(null, (url, init) =>
      url.pathname === "/api/v2/auth/login" && init?.method === "POST"
        ? res(owner)
        : undefined,
    );
    render(<App />);
    await screen.findByRole("heading", { name: "Welcome back" });
    await userEvent.type(
      screen.getByLabelText("Email address"),
      "owner@example.test",
    );
    await userEvent.type(
      screen.getByLabelText("Password"),
      "synthetic-password",
    );
    await userEvent.click(screen.getByRole("button", { name: "Sign in" }));
    await screen.findByRole("button", { name: "Reception fixture" });
    const login = fetcher.mock.calls.find(([path]) =>
      String(path).endsWith("/auth/login"),
    )!;
    expect(JSON.parse(String(login[1]?.body))).toEqual({
      email: "owner@example.test",
      password: "synthetic-password",
    });
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
  });
  it("consumes and scrubs an invitation link before choosing a password", async () => {
    window.history.replaceState(
      null,
      "",
      "/?token_hash=synthetic-invite&type=invite",
    );
    const fetcher = backend(null, (url, init) =>
      url.pathname === "/api/v2/auth/verify"
        ? res({ ...owner, requires_password: true })
        : url.pathname === "/api/v2/auth/password" && init?.method === "POST"
          ? res({ ok: true, reauthenticate: true })
          : undefined,
    );
    render(<App />);
    await screen.findByRole("heading", { name: "Choose your password" });
    expect(window.location.search).toBe("");
    await userEvent.type(
      screen.getByLabelText("New password"),
      "new-synthetic-password",
    );
    await userEvent.type(
      screen.getByLabelText("Confirm password"),
      "new-synthetic-password",
    );
    await userEvent.click(
      screen.getByRole("button", { name: "Save password" }),
    );
    await screen.findByRole("heading", { name: "Welcome back" });
    const change = fetcher.mock.calls.find(([path]) =>
      String(path).endsWith("/auth/password"),
    )!;
    expect((change[1]?.headers as Record<string, string>)["X-CSRF-Token"]).toBe(
      "fixture-csrf",
    );
    expect(screen.getByRole("status")).toHaveTextContent(
      "Your password is saved",
    );
  });
  it("requires owner MFA before requesting fleet data and preserves leading-zero codes", async () => {
    const pending = { ...owner, requires_mfa: true };
    const fetcher = backend(pending, (url) => {
      if (url.pathname === "/api/v2/auth/mfa/factors")
        return res({
          items: [{ id: "factor-1", factor_type: "totp", status: "verified" }],
        });
      if (url.pathname === "/api/v2/auth/mfa/challenge")
        return res({ id: "challenge-1", expires_at: 9999999999 });
      if (url.pathname === "/api/v2/auth/mfa/verify") return res(owner);
    });
    render(<App />);
    await screen.findByLabelText("Six-digit authenticator code");
    expect(
      fetcher.mock.calls.some(([path]) => String(path).includes("/hotels")),
    ).toBe(false);
    await userEvent.type(
      screen.getByLabelText("Six-digit authenticator code"),
      "012345",
    );
    await userEvent.click(
      screen.getByRole("button", { name: "Verify and continue" }),
    );
    await screen.findByRole("button", { name: "Reception fixture" });
    const verify = fetcher.mock.calls.find(([path]) =>
      String(path).endsWith("/auth/mfa/verify"),
    )!;
    expect(JSON.parse(String(verify[1]?.body))).toMatchObject({
      factor_id: "factor-1",
      challenge_id: "challenge-1",
      code: "012345",
    });
  });
  it("pairs using the physical code without requiring an internal request ID", async () => {
    const fetcher = backend(owner, (url, init) =>
      url.pathname === "/api/v2/enrollments/approve" && init?.method === "POST"
        ? res({ status: "approved" })
        : undefined,
    );
    render(<App />);
    await screen.findByRole("button", { name: "Reception fixture" });
    await userEvent.click(screen.getByRole("button", { name: /Pair devices/ }));
    await userEvent.type(
      screen.getByLabelText("Pairing code shown on device"),
      "abcd1234",
    );
    await userEvent.click(
      screen.getByRole("button", { name: "Approve device" }),
    );
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(([path]) =>
          String(path).endsWith("/enrollments/approve"),
        ),
      ).toBe(true),
    );
    const approval = fetcher.mock.calls.find(([path]) =>
      String(path).endsWith("/enrollments/approve"),
    )!;
    expect(JSON.parse(String(approval[1]?.body))).toEqual({
      hotel_id: "hotel-1",
      user_code: "ABCD1234",
    });
    expect(
      screen.queryByLabelText("Request ID shown on device"),
    ).not.toBeInTheDocument();
  });
  it("publishes version-checked public facts through structured fields", async () => {
    let payload: Record<string, unknown> | undefined;
    backend(owner, (url, init) => {
      if (
        url.pathname === "/api/v2/hotels/hotel-1/config" &&
        init?.method === "PUT"
      ) {
        payload = JSON.parse(String(init.body));
        return res({
          version: 2,
          config: (payload as { config: unknown }).config,
        });
      }
    });
    render(<App />);
    await screen.findByRole("button", { name: "Reception fixture" });
    await userEvent.selectOptions(
      screen.getByLabelText("Filter by hotel"),
      "hotel-1",
    );
    await screen.findByRole("button", { name: "Reception fixture" });
    await userEvent.click(
      screen.getByRole("button", { name: /Hotel settings/ }),
    );
    await screen.findByLabelText("Approved hotel information");
    await userEvent.clear(screen.getByLabelText("Approved hotel information"));
    await userEvent.type(
      screen.getByLabelText("Approved hotel information"),
      "Breakfast is now at eight.",
    );
    await userEvent.click(
      screen.getByRole("button", { name: "Publish settings" }),
    );
    await waitFor(() => expect(payload).toBeDefined());
    expect(payload).toMatchObject({
      expected_version: 1,
      config: { facts: "Breakfast is now at eight.", local_pack: [] },
    });
    expect(
      (payload as { config: Record<string, unknown> }).config,
    ).not.toHaveProperty("timezone");
    expect(
      (payload as { config: Record<string, unknown> }).config,
    ).not.toHaveProperty("default_language");
    expect(
      (payload as { config: Record<string, unknown> }).config,
    ).not.toHaveProperty("supported_languages");
  });
  it("lets an owner disable another account without exposing self-disable", async () => {
    vi.stubGlobal(
      "confirm",
      vi.fn(() => true),
    );
    let enabled = true;
    const fetcher = backend(owner, (url, init) => {
      if (url.pathname === "/api/v2/users" && init?.method === "GET")
        return res({
          items: [
            { ...owner.user, enabled: true },
            {
              id: "staff-2",
              email: "staff@example.test",
              platform_owner: false,
              enabled,
            },
          ],
          next_offset: null,
        });
      if (
        url.pathname === "/api/v2/users/staff-2" &&
        init?.method === "PATCH"
      ) {
        enabled = false;
        return res({ ok: true });
      }
    });
    render(<App />);
    await screen.findByRole("button", { name: "Reception fixture" });
    await userEvent.click(
      screen.getByRole("button", { name: /Hotels & people/ }),
    );
    await screen.findByRole("heading", { name: "Platform accounts" });
    const disable = await screen.findByRole("button", {
      name: "Disable account",
    });
    expect(
      screen.getAllByRole("button", { name: "Disable account" }),
    ).toHaveLength(1);
    await userEvent.click(disable);
    await screen.findByRole("button", { name: "Enable account" });
    const call = fetcher.mock.calls.find(([path]) =>
      String(path).endsWith("/users/staff-2"),
    )!;
    expect(JSON.parse(String(call[1]?.body))).toEqual({ enabled: false });
  });
  it("removes staff hotel access with the versioned scoped endpoint", async () => {
    vi.stubGlobal(
      "confirm",
      vi.fn(() => true),
    );
    let members = [
      { user_id: "staff-2", email: "staff@example.test", role: "operator" },
    ];
    const fetcher = backend(owner, (url, init) => {
      if (url.pathname === "/api/v2/hotels/hotel-1/members")
        return res({ items: members, next_offset: null });
      if (
        url.pathname === "/api/v2/hotels/hotel-1/members/staff-2" &&
        init?.method === "DELETE"
      ) {
        members = [];
        return res({ status: "removed" });
      }
    });
    render(<App />);
    await screen.findByRole("button", { name: "Reception fixture" });
    await userEvent.selectOptions(
      screen.getByLabelText("Filter by hotel"),
      "hotel-1",
    );
    await screen.findByRole("button", { name: "Reception fixture" });
    await userEvent.click(
      screen.getByRole("button", { name: /Hotels & people/ }),
    );
    await userEvent.click(
      await screen.findByRole("button", { name: "Remove access" }),
    );
    await screen.findByText("No team members returned.");
    const call = fetcher.mock.calls.find(([path]) =>
      String(path).endsWith("/members/staff-2"),
    )!;
    expect(call[1]?.method).toBe("DELETE");
  });
  it("reports existing-account assignment without claiming to send an invitation", async () => {
    backend(owner, (url, init) =>
      url.pathname === "/api/v2/hotels/hotel-1/invitations" &&
      init?.method === "POST"
        ? res({
            status: "assigned",
            user_id: "staff-2",
            hotel_id: "hotel-1",
            role: "operator",
          })
        : undefined,
    );
    render(<App />);
    await screen.findByRole("button", { name: "Reception fixture" });
    await userEvent.selectOptions(
      screen.getByLabelText("Filter by hotel"),
      "hotel-1",
    );
    await screen.findByRole("button", { name: "Reception fixture" });
    await userEvent.click(
      screen.getByRole("button", { name: /Hotels & people/ }),
    );
    await userEvent.type(
      await screen.findByLabelText("Email address"),
      "existing@example.test",
    );
    await userEvent.click(
      screen.getByRole("button", { name: "Send invitation" }),
    );
    await screen.findByText(
      "Existing account added to this hotel. No invitation email was sent.",
    );
    expect(
      screen.queryByText(
        "Invitation sent. The recipient can finish setup from their email.",
      ),
    ).not.toBeInTheDocument();
  });
  it("refreshes a downgraded owner session and returns to MFA without logging out", async () => {
    let locked = false,
      sessionReads = 0;
    const fetcher = backend(owner, (url) => {
      if (url.pathname === "/api/v2/auth/session") {
        sessionReads++;
        return res(
          locked
            ? { ...owner, csrf_token: "rotated-csrf", requires_mfa: true }
            : owner,
        );
      }
      if (url.pathname === "/api/v2/auth/mfa/factors")
        return res({
          items: [{ id: "factor-1", factor_type: "totp", status: "verified" }],
        });
      if (url.pathname === "/api/v2/auth/mfa/challenge")
        return res({ id: "challenge-2", expires_at: 9999999999 });
      if (url.pathname === "/api/v2/auth/mfa/verify") {
        locked = false;
        return res({ ...owner, csrf_token: "verified-csrf" });
      }
      if (locked && !url.pathname.startsWith("/api/v2/auth/"))
        return res({ detail: "MFA verification required" }, 403);
    });
    render(<App />);
    await screen.findByRole("button", { name: "Reception fixture" });
    locked = true;
    await userEvent.click(screen.getByRole("button", { name: "Refresh" }));
    await screen.findByLabelText("Six-digit authenticator code");
    await waitFor(() => expect(sessionReads).toBeGreaterThan(1));
    expect(
      screen.queryByRole("button", { name: "Reception fixture" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("heading", { name: "Welcome back" }),
    ).not.toBeInTheDocument();
    await userEvent.type(
      screen.getByLabelText("Six-digit authenticator code"),
      "012345",
    );
    await userEvent.click(
      screen.getByRole("button", { name: "Verify and continue" }),
    );
    await screen.findByRole("button", { name: "Reception fixture" });
    const verify = fetcher.mock.calls.find(([path]) =>
      String(path).endsWith("/auth/mfa/verify"),
    )!;
    expect((verify[1]?.headers as Record<string, string>)["X-CSRF-Token"]).toBe(
      "rotated-csrf",
    );
    expect(
      fetcher.mock.calls.some(([path]) =>
        String(path).endsWith("/auth/logout"),
      ),
    ).toBe(false);
  });
});
