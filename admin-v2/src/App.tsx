import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type FormEvent,
  type ReactNode,
  type InputHTMLAttributes,
} from "react";
import {
  ApiClient,
  ApiError,
  canManage,
  canOperate,
  extractEmailLink,
  relativeTime,
  timestamp,
  type Alert,
  type Audit,
  type Command,
  type ConfigRevision,
  type Device,
  type Enrollment,
  type Hotel,
  type Member,
  type Account,
  type Session,
  type TelemetrySummary,
} from "./api";

type View =
  | "fleet"
  | "hotels"
  | "pairing"
  | "settings"
  | "alerts"
  | "activity"
  | "account";
type Snapshot = {
  hotels: Hotel[];
  devices: Device[];
  enrollments: Enrollment[];
  alerts: Alert[];
  audit: Audit[];
};
const views: { id: View; label: string; mark: string }[] = [
  { id: "fleet", label: "Devices", mark: "◫" },
  { id: "hotels", label: "Hotels & people", mark: "⌂" },
  { id: "pairing", label: "Pair devices", mark: "⊕" },
  { id: "settings", label: "Hotel settings", mark: "⚙" },
  { id: "alerts", label: "Alerts", mark: "△" },
  { id: "activity", label: "Activity", mark: "≡" },
];
const emptySnapshot: Snapshot = {
  hotels: [],
  devices: [],
  enrollments: [],
  alerts: [],
  audit: [],
};
const healthLabels: Record<string, string> = {
  battery_percent: "Battery (%)",
  charging: "Charging",
  network_type: "Network",
  storage_available_bytes: "Free storage (bytes)",
  microphone_permission: "Microphone permission",
  audio_released: "Audio released",
  connection_ready_ms: "Connection ready (ms)",
  native_audio_start_ms: "Audio startup (ms)",
  pcm_arrival_to_playback_head_ms: "Audio arrival to playback (ms)",
  queued_audio_ms: "Queued audio (ms)",
  max_upload_queue_audio_ms: "Maximum upload queue (ms)",
  max_local_receive_dispatch_ms: "Maximum dispatch delay (ms)",
};
const eventMetricLabels: Record<string, string> = {
  ...healthLabels,
  stream_start_to_first_output_event_ms: "Stream start to output (ms)",
  pcm_arrival_to_first_write_ms: "Audio arrival to write (ms)",
  interruption_flush_ms: "Interruption flush (ms)",
  session_duration_ms: "Session duration (ms)",
  tool_duration_ms: "Tool duration (ms)",
  committed_task_count: "Committed task count",
  success: "Success",
};
function readable(value: unknown): string {
  if (value == null || value === "") return "Not reported";
  if (typeof value === "boolean") return value ? "Yes" : "No";
  if (typeof value === "number" || typeof value === "string")
    return String(value).replaceAll("_", " ");
  return "Not reported";
}
function message(error: unknown) {
  return error instanceof Error
    ? error.message
    : "Something went wrong. Please try again.";
}
function Badge({ value }: { value?: string | null }) {
  const tone = [
    "online",
    "active",
    "succeeded",
    "applied",
    "installed",
    "acknowledged",
  ].includes(value ?? "")
    ? "good"
    : ["offline", "failed", "revoked", "reconciliation_required"].includes(
          value ?? "",
        )
      ? "bad"
      : [
            "stale",
            "deferred",
            "pending",
            "open",
            "confirmation_required",
          ].includes(value ?? "")
        ? "warn"
        : "neutral";
  return (
    <span className={`badge ${tone}`}>
      <span aria-hidden="true" />
      {readable(value)}
    </span>
  );
}
function Brand() {
  return (
    <div className="brand">
      <span className="brand-mark" aria-hidden="true">
        m
      </span>
      <div>
        Manzanilla<small>Hotel management</small>
      </div>
    </div>
  );
}
function Empty({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className="empty">
      <span className="empty-mark" aria-hidden="true">
        ◇
      </span>
      <h3>{title}</h3>
      {children && <p>{children}</p>}
    </div>
  );
}
function Form({
  children,
  onSubmit,
  button = "Save",
  className = "",
  submitAllowed = true,
}: {
  children: ReactNode;
  onSubmit: (data: FormData) => Promise<void>;
  button?: string;
  className?: string;
  submitAllowed?: boolean;
}) {
  const [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy || !submitAllowed) return;
    setBusy(true);
    setError("");
    const form = event.currentTarget;
    try {
      await onSubmit(new FormData(form));
    } catch (e) {
      setError(message(e));
    } finally {
      setBusy(false);
    }
  }
  return (
    <form className={className} onSubmit={submit}>
      <fieldset disabled={busy}>{children}</fieldset>
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
      {submitAllowed && (
        <button className="primary" type="submit" disabled={busy}>
          {busy ? "Working…" : button}
        </button>
      )}
    </form>
  );
}
function Field({
  label,
  name,
  type = "text",
  required = true,
  children,
  ...rest
}: Omit<InputHTMLAttributes<HTMLInputElement>, "children"> & {
  label: string;
  name: string;
  children?: ReactNode;
}) {
  return (
    <label className="field">
      <span>{label}</span>
      {children ?? (
        <input name={name} type={type} required={required} {...rest} />
      )}
    </label>
  );
}

export function App({ client }: { client?: ApiClient }) {
  const api = useRef(client ?? new ApiClient()).current;
  const [session, setSession] = useState<Session | null | undefined>(undefined);
  const [authMode, setAuthMode] = useState<"login" | "recover" | "password">(
    "login",
  );
  const [authError, setAuthError] = useState(""),
    [notice, setNotice] = useState("");
  const [view, setView] = useState<View>("fleet"),
    [hotelId, setHotelId] = useState("");
  const [snapshot, setSnapshot] = useState<Snapshot>(emptySnapshot),
    [loaded, setLoaded] = useState(false);
  const [loading, setLoading] = useState(false),
    [error, setError] = useState(""),
    [lastRefresh, setLastRefresh] = useState<number | null>(null);
  const [selectedDevice, setSelectedDevice] = useState<string | null>(null);
  const [unavailable, setUnavailable] = useState<Set<View>>(new Set());
  const fetchGeneration = useRef(0);
  const assuranceRefresh = useRef(false);
  const changeSession = useCallback(
    (next: Session | null) => {
      fetchGeneration.current++;
      setSnapshot(emptySnapshot);
      setLoaded(false);
      setLoading(Boolean(next));
      setError("");
      setLastRefresh(null);
      api.setSession(next);
      setSession(next);
      if (next?.requires_password) setAuthMode("password");
    },
    [api],
  );
  const closeDevice = useCallback(() => setSelectedDevice(null), []);
  const chooseHotel = useCallback((id: string) => {
    setHotelId(id);
    setSelectedDevice(null);
    setNotice("");
    setLoaded(false);
  }, []);

  useEffect(() => {
    let alive = true;
    api.onUnauthorized = () => {
      changeSession(null);
      setAuthMode("login");
      setAuthError("Your session has ended. Please sign in again.");
      setSnapshot(emptySnapshot);
      setLoaded(false);
    };
    api.onAssuranceRequired = () => {
      if (assuranceRefresh.current) return;
      assuranceRefresh.current = true;
      const generation = ++fetchGeneration.current;
      setSnapshot(emptySnapshot);
      setSelectedDevice(null);
      setLoaded(false);
      setAuthError("");
      setSession((current) =>
        current ? { ...current, requires_mfa: true } : current,
      );
      void api
        .request<Session>("/auth/session")
        .then((next) => {
          if (alive && generation === fetchGeneration.current)
            changeSession(next);
        })
        .catch((e) => {
          if (alive && generation === fetchGeneration.current)
            setAuthError(message(e));
        })
        .finally(() => {
          assuranceRefresh.current = false;
        });
    };
    const link = extractEmailLink(window.location);
    const url = new URL(window.location.href);
    const hasLink =
      url.searchParams.has("token_hash") || /access_token=/.test(url.hash);
    if (hasLink) {
      url.searchParams.delete("token_hash");
      url.searchParams.delete("type");
      url.hash = "";
      window.history.replaceState(null, "", url.pathname + url.search);
    }
    if (hasLink && !link) {
      changeSession(null);
      setAuthError(
        "This setup link is incomplete or unsupported. Request a new invitation or recovery email.",
      );
      return;
    }
    const start = link
      ? api.request<Session>("/auth/verify", "POST", link)
      : api.request<Session>("/auth/session");
    start
      .then((next) => {
        if (alive) {
          changeSession(next);
          if (link) setAuthMode("password");
        }
      })
      .catch((e) => {
        if (!alive) return;
        changeSession(null);
        if (link || !(e instanceof ApiError && e.status === 401))
          setAuthError(message(e));
      });
    return () => {
      alive = false;
      api.onUnauthorized = undefined;
      api.onAssuranceRequired = undefined;
    };
  }, [api, changeSession]);

  const refresh = useCallback(
    async (signal?: AbortSignal) => {
      const generation = ++fetchGeneration.current;
      setLoading(true);
      const endpoints = [
        "/hotels",
        "/devices" +
          (hotelId ? "?hotel_id=" + encodeURIComponent(hotelId) : ""),
        "/enrollments",
        "/alerts",
        "/audit",
      ];
      const responses = await Promise.allSettled(
        endpoints.map((path) => api.list<unknown>(path, signal)),
      );
      if (signal?.aborted || generation !== fetchGeneration.current) return;
      const required = responses
        .slice(0, 2)
        .find((r) => r.status === "rejected");
      if (required?.status === "rejected") {
        setError(message(required.reason));
        setLoading(false);
        return;
      }
      const items = (i: number) => {
        const result = responses[i];
        return result.status === "fulfilled" ? result.value : [];
      };
      setUnavailable(
        new Set(
          (
            [
              ["pairing", 2],
              ["alerts", 3],
              ["activity", 4],
            ] as [View, number][]
          )
            .filter(([, i]) => responses[i].status === "rejected")
            .map(([v]) => v),
        ),
      );
      setSnapshot({
        hotels: items(0) as Hotel[],
        devices: items(1) as Device[],
        enrollments: items(2) as Enrollment[],
        alerts: items(3) as Alert[],
        audit: items(4) as Audit[],
      });
      const optional = responses
        .slice(2)
        .find(
          (r) =>
            r.status === "rejected" &&
            !(r.reason instanceof ApiError && r.reason.status === 403),
        );
      setError(
        optional?.status === "rejected"
          ? "Some management data could not refresh. " +
              message(optional.reason)
          : "",
      );
      setLoaded(true);
      setLastRefresh(Date.now());
      setLoading(false);
    },
    [api, hotelId],
  );
  useEffect(() => {
    if (!session || authMode === "password" || session.requires_mfa) return;
    const controller = new AbortController();
    void refresh(controller.signal);
    const interval = window.setInterval(() => {
      if (!document.hidden) void refresh(controller.signal);
    }, 15000);
    return () => {
      controller.abort();
      fetchGeneration.current++;
      window.clearInterval(interval);
    };
  }, [session, authMode, refresh]);

  async function changed(text: string) {
    setNotice(text);
    await refresh();
  }
  async function logout() {
    try {
      await api.request("/auth/logout", "POST");
      changeSession(null);
      setSnapshot(emptySnapshot);
      setLoaded(false);
      setHotelId("");
      setAuthMode("login");
      setNotice("");
    } catch (e) {
      setError(message(e));
    }
  }
  if (session === undefined)
    return (
      <main className="auth-shell">
        <Brand />
        <p className="loading" role="status">
          Checking your session…
        </p>
      </main>
    );
  if (!session || authMode === "password")
    return (
      <main className="auth-shell">
        <div className="auth-card">
          <Brand />
          <div className="eyebrow">MANZANILLA CONSOLE</div>
          <h1>
            {authMode === "password"
              ? "Choose your password"
              : authMode === "recover"
                ? "Reset your password"
                : "Welcome back"}
          </h1>
          <p className="lead">
            {authMode === "password"
              ? "Create a password for your hotel management account."
              : authMode === "recover"
                ? "We’ll send a recovery link if this email has an account."
                : "Sign in to look after your hotels and reception devices."}
          </p>
          {authError && (
            <div className="error" role="alert">
              {authError}
            </div>
          )}
          {notice && (
            <div className="notice" role="status">
              {notice}
            </div>
          )}
          {authMode === "login" && (
            <Form
              button="Sign in"
              onSubmit={async (data) => {
                setAuthError("");
                const next = await api.request<Session>("/auth/login", "POST", {
                  email: data.get("email"),
                  password: data.get("password"),
                });
                changeSession(next);
                setNotice("");
              }}
            >
              <Field
                label="Email address"
                name="email"
                type="email"
                autoComplete="username"
                maxLength={254}
              />
              <Field
                label="Password"
                name="password"
                type="password"
                autoComplete="current-password"
                maxLength={1024}
              />
            </Form>
          )}
          {authMode === "recover" && (
            <Form
              button="Send recovery link"
              onSubmit={async (data) => {
                await api.request("/auth/recover", "POST", {
                  email: data.get("email"),
                });
                setNotice(
                  "If an account exists, a recovery link will arrive by email.",
                );
              }}
            >
              <Field
                label="Email address"
                name="email"
                type="email"
                autoComplete="email"
                maxLength={254}
              />
            </Form>
          )}
          {authMode === "password" && (
            <Form
              button="Save password"
              onSubmit={async (data) => {
                if (data.get("password") !== data.get("confirmation"))
                  throw new Error("The passwords do not match.");
                await api.request("/auth/password", "POST", {
                  password: data.get("password"),
                });
                changeSession(null);
                setAuthMode("login");
                setNotice("Your password is saved. Sign in to continue.");
              }}
            >
              <Field
                label="New password"
                name="password"
                type="password"
                autoComplete="new-password"
                minLength={12}
                maxLength={128}
              />
              <Field
                label="Confirm password"
                name="confirmation"
                type="password"
                autoComplete="new-password"
                minLength={12}
                maxLength={128}
              />
              <p className="muted">
                Use at least 12 characters. A password manager can help.
              </p>
            </Form>
          )}
          {authMode !== "password" && (
            <button
              className="text-button"
              onClick={() => {
                setAuthMode(authMode === "recover" ? "login" : "recover");
                setAuthError("");
                setNotice("");
              }}
            >
              {authMode === "recover" ? "Back to sign in" : "Forgot password?"}
            </button>
          )}
          <p className="auth-footer">
            Accounts are invitation only. Ask your Manzanilla administrator for
            access.
          </p>
        </div>
      </main>
    );

  if (session.requires_mfa)
    return (
      <MfaSetup
        api={api}
        onVerified={changeSession}
        onLogout={() => void logout()}
        email={session.user.email}
        sessionError={authError}
      />
    );
  const selectedHotel = snapshot.hotels.find((h) => h.id === hotelId);
  const hotels = new Map(snapshot.hotels.map((h) => [h.id, h]));
  const alerts = snapshot.alerts.filter(
    (a) => !hotelId || a.hotel_id === hotelId,
  );
  const title =
    view === "account"
      ? "Your account"
      : views.find((v) => v.id === view)!.label;
  return (
    <div className="shell">
      <aside className="sidebar" inert={!!selectedDevice}>
        <Brand />
        <div className="workspace-label">WORKSPACE</div>
        <nav aria-label="Management navigation">
          {views.map((item) => (
            <button
              key={item.id}
              className={view === item.id ? "nav-item active" : "nav-item"}
              aria-current={view === item.id ? "page" : undefined}
              onClick={() => {
                setView(item.id);
                setNotice("");
              }}
            >
              <span aria-hidden="true">{item.mark}</span>
              {item.label}
              {item.id === "alerts" &&
                alerts.some((a) => a.state === "open") && (
                  <span className="nav-dot" aria-label="Open alerts" />
                )}
            </button>
          ))}
        </nav>
        <div className="sidebar-footer">
          <span className="private-note">Private hotel workspace</span>
          <button
            className={
              view === "account" ? "account-button active" : "account-button"
            }
            onClick={() => setView("account")}
          >
            <span className="avatar">
              {session.user.email.slice(0, 1).toUpperCase()}
            </span>
            <span>
              {session.user.email}
              <small>
                {session.user.platform_owner ? "Platform owner" : "Hotel team"}
              </small>
            </span>
          </button>
          <button className="text-button logout" onClick={() => void logout()}>
            Sign out
          </button>
        </div>
      </aside>
      <div className="workspace" inert={!!selectedDevice}>
        <header className="topbar">
          <span>
            Reception operations <span className="slash">/</span>{" "}
            <strong>{title}</strong>
          </span>
          <div className="topbar-actions">
            <span className="refresh-state" role="status">
              {loading
                ? "Refreshing…"
                : lastRefresh
                  ? "Updated " + new Date(lastRefresh).toLocaleTimeString()
                  : "Not yet refreshed"}
            </span>
            <button
              className="secondary small"
              disabled={loading}
              onClick={() => void refresh()}
            >
              Refresh
            </button>
          </div>
        </header>
        <main className="main">
          <div className="page-heading">
            <div>
              <p className="eyebrow">
                {selectedHotel?.name ?? "YOUR WORKSPACE"}
              </p>
              <h1>{title}</h1>
              <p className="lead">
                {view === "fleet"
                  ? "A clear view of every reception device you can manage."
                  : view === "hotels"
                    ? "Hotel workspaces and the people who look after them."
                    : view === "pairing"
                      ? "Approve a device once. It stays enrolled independently of staff sign-in."
                      : view === "settings"
                        ? "Publish approved public hotel information to your devices."
                        : view === "alerts"
                          ? "Health issues that need your team’s attention."
                          : view === "activity"
                            ? "A record of management actions in your workspace."
                            : "Manage your sign-in details."}
              </p>
            </div>
            {view !== "account" && (
              <label className="hotel-picker">
                <span>Hotel</span>
                <select
                  aria-label="Filter by hotel"
                  value={hotelId}
                  onChange={(event) => chooseHotel(event.target.value)}
                >
                  <option value="">All authorized hotels</option>
                  {snapshot.hotels.map((h) => (
                    <option key={h.id} value={h.id}>
                      {h.name}
                    </option>
                  ))}
                </select>
              </label>
            )}
          </div>
          {notice && (
            <div className="notice dismissible" role="status">
              <span>{notice}</span>
              <button
                aria-label="Dismiss notification"
                onClick={() => setNotice("")}
              >
                ×
              </button>
            </div>
          )}
          {error && (
            <div className="error" role="alert">
              {error}
              {loaded && (
                <span> The last received information remains visible.</span>
              )}
            </div>
          )}
          {!loaded && loading && (
            <div className="loading" role="status">
              Loading your workspace…
            </div>
          )}
          {!loaded && !loading && view !== "account" && (
            <section className="panel">
              <Empty title="Workspace could not load">
                Use Refresh to try again. Unavailable data is not an empty
                fleet.
              </Empty>
            </section>
          )}
          {loaded && view === "fleet" && (
            <Fleet
              devices={snapshot.devices}
              hotels={hotels}
              loaded={loaded}
              onSelect={setSelectedDevice}
            />
          )}
          {loaded && view === "hotels" && (
            <Hotels
              api={api}
              session={session}
              hotels={snapshot.hotels}
              selected={selectedHotel}
              onSelect={chooseHotel}
              changed={changed}
            />
          )}
          {loaded && view === "pairing" && !unavailable.has("pairing") && (
            <Pairing
              api={api}
              session={session}
              hotels={snapshot.hotels}
              selected={selectedHotel}
              enrollments={snapshot.enrollments}
              changed={changed}
            />
          )}
          {loaded && view === "settings" && (
            <Settings
              api={api}
              session={session}
              hotel={selectedHotel}
              changed={changed}
            />
          )}
          {loaded && view === "alerts" && !unavailable.has("alerts") && (
            <section className="panel">
              <div className="panel-heading">
                <h2>Health alerts</h2>
                <span>
                  {alerts.filter((a) => a.state === "open").length} open
                </span>
              </div>
              {alerts.length === 0 ? (
                <Empty title="No alerts reported">
                  Issues will appear here when the service detects them.
                </Empty>
              ) : (
                <ul className="records">
                  {alerts.map((alert) => (
                    <li key={alert.id}>
                      <div>
                        <div className="record-title">
                          {alert.message || readable(alert.kind)}
                        </div>
                        <p>
                          {hotels.get(alert.hotel_id)?.name ?? alert.hotel_id} ·{" "}
                          {timestamp(alert.created_at)}
                        </p>
                      </div>
                      <div className="record-actions">
                        <Badge value={alert.state} />
                        {alert.state === "open" &&
                          canOperate(hotels.get(alert.hotel_id), session) && (
                            <button
                              className="secondary small"
                              onClick={async () => {
                                try {
                                  await api.request(
                                    "/alerts/" +
                                      encodeURIComponent(alert.id) +
                                      "/acknowledge",
                                    "POST",
                                  );
                                  await changed("Alert acknowledged.");
                                } catch (e) {
                                  setError(message(e));
                                }
                              }}
                            >
                              Acknowledge
                            </button>
                          )}
                      </div>
                    </li>
                  ))}
                </ul>
              )}
            </section>
          )}
          {loaded && view === "activity" && !unavailable.has("activity") && (
            <Activity
              audit={snapshot.audit.filter(
                (a) => !hotelId || a.hotel_id === hotelId,
              )}
              hotels={hotels}
            />
          )}
          {loaded && unavailable.has(view) && (
            <section className="panel">
              <Empty title="This view is unavailable">
                Your role may not permit this view, or the service could not
                return it. Refresh to try again.
              </Empty>
            </section>
          )}
          {view === "account" && (
            <section className="panel narrow">
              <h2>Sign-in details</h2>
              <p className="muted">{session.user.email}</p>
              <Form
                button="Change password"
                onSubmit={async (data) => {
                  if (data.get("password") !== data.get("confirmation"))
                    throw new Error("The passwords do not match.");
                  await api.request("/auth/password", "POST", {
                    password: data.get("password"),
                  });
                  changeSession(null);
                  setAuthMode("login");
                  setNotice("Password changed. Sign in again to continue.");
                }}
              >
                <Field
                  label="New password"
                  name="password"
                  type="password"
                  autoComplete="new-password"
                  minLength={12}
                  maxLength={128}
                />
                <Field
                  label="Confirm password"
                  name="confirmation"
                  type="password"
                  autoComplete="new-password"
                  minLength={12}
                  maxLength={128}
                />
                <p className="muted">
                  At least 12 characters. Changing your password ends your
                  portal sessions.
                </p>
              </Form>
            </section>
          )}
          <footer className="page-footer">
            Health metadata only. Guest conversations and private shift notes
            are not shown here.
          </footer>
        </main>
      </div>
      {selectedDevice && (
        <DeviceDetail
          id={selectedDevice}
          api={api}
          hotels={hotels}
          session={session}
          onClose={closeDevice}
          changed={changed}
        />
      )}
    </div>
  );
}

function Fleet({
  devices,
  hotels,
  loaded,
  onSelect,
}: {
  devices: Device[];
  hotels: Map<string, Hotel>;
  loaded: boolean;
  onSelect: (id: string) => void;
}) {
  const [query, setQuery] = useState(""),
    [status, setStatus] = useState("");
  const filtered = devices.filter(
    (d) =>
      (!status || d.connectivity === status) &&
      `${d.name} ${d.id} ${hotels.get(d.hotel_id)?.name ?? ""}`
        .toLowerCase()
        .includes(query.toLowerCase()),
  );
  return (
    <>
      <div className="summary-grid">
        <div>
          <span>Enrolled devices</span>
          <strong>
            {loaded
              ? devices.filter((d) => d.status !== "revoked").length
              : "—"}
          </strong>
        </div>
        <div>
          <span>Recently heard</span>
          <strong>
            {loaded
              ? devices.filter(
                  (d) => d.status === "active" && d.connectivity === "online",
                ).length
              : "—"}
          </strong>
        </div>
        <div>
          <span>Stale or offline</span>
          <strong>
            {loaded
              ? devices.filter(
                  (d) =>
                    d.status === "active" &&
                    ["stale", "offline"].includes(d.connectivity),
                ).length
              : "—"}
          </strong>
        </div>
      </div>
      <section className="panel">
        <div className="panel-heading">
          <h2>Reception devices</h2>
          <div className="table-filters">
            <input
              type="search"
              aria-label="Search devices"
              placeholder="Search devices or hotels"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
            />
            <select
              aria-label="Filter by connectivity"
              value={status}
              onChange={(e) => setStatus(e.target.value)}
            >
              <option value="">All connections</option>
              {["online", "stale", "offline", "unknown"].map((v) => (
                <option key={v}>{v}</option>
              ))}
            </select>
          </div>
        </div>
        {filtered.length === 0 ? (
          <Empty
            title={
              devices.length === 0 ? "No devices yet" : "No matching devices"
            }
          >
            {devices.length === 0
              ? "Pair your first reception device to start receiving health reports."
              : "Try another search or connection filter."}
          </Empty>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Device</th>
                  <th>Connection</th>
                  <th>Voice / microphone</th>
                  <th>App version</th>
                  <th>Last seen</th>
                </tr>
              </thead>
              <tbody>
                {filtered.map((d) => (
                  <tr key={d.id}>
                    <td>
                      <button
                        className="device-link"
                        onClick={() => onSelect(d.id)}
                      >
                        {d.name}
                      </button>
                      <small>
                        {hotels.get(d.hotel_id)?.name ?? d.hotel_id}{" "}
                        {d.status !== "active" && <>· {d.status}</>}
                      </small>
                    </td>
                    <td>
                      <Badge value={d.connectivity} />
                    </td>
                    <td>
                      {readable(d.mode)}
                      <small>Mic: {readable(d.mic_state)}</small>
                    </td>
                    <td>{d.app_version || "Not reported"}</td>
                    <td>
                      <span title={timestamp(d.last_seen)}>
                        {relativeTime(d.last_seen)}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <p className="panel-note">
          Connection means recent contact with the service. It does not confirm
          working audio.
        </p>
      </section>
    </>
  );
}

function Hotels({
  api,
  session,
  hotels,
  selected,
  onSelect,
  changed,
}: {
  api: ApiClient;
  session: Session;
  hotels: Hotel[];
  selected?: Hotel;
  onSelect: (id: string) => void;
  changed: (text: string) => Promise<void>;
}) {
  const [members, setMembers] = useState<Member[]>([]),
    [error, setError] = useState("");
  useEffect(() => {
    setMembers([]);
    setError("");
    if (!selected) return;
    const c = new AbortController();
    api
      .request<{ items: Member[] }>(
        "/hotels/" + encodeURIComponent(selected.id) + "/members",
        "GET",
        undefined,
        c.signal,
      )
      .then((r) => setMembers(r.items))
      .catch((e) => {
        if (!c.signal.aborted) setError(message(e));
      });
    return () => c.abort();
  }, [api, selected?.id]);
  return (
    <div className="content-grid">
      <section className="panel">
        <div className="panel-heading">
          <h2>Your hotels</h2>
          <span>{hotels.length} authorized</span>
        </div>
        {hotels.length === 0 ? (
          <Empty title="No hotel assigned">
            Ask your administrator for a hotel invitation, or create your first
            property if you are the platform owner.
          </Empty>
        ) : (
          <ul className="records">
            {hotels.map((h) => (
              <li key={h.id}>
                <div>
                  <button
                    className="device-link"
                    onClick={() => onSelect(h.id)}
                  >
                    {h.name}
                  </button>
                  <p>
                    {h.timezone} · {readable(h.role)}
                  </p>
                </div>
                <button
                  className="secondary small"
                  onClick={() => onSelect(h.id)}
                >
                  {selected?.id === h.id ? "Selected" : "Select"}
                </button>
              </li>
            ))}
          </ul>
        )}
        {session.user.platform_owner && (
          <div className="section-divider">
            <h3>Create a hotel</h3>
            <Form
              button="Create hotel"
              onSubmit={async (d) => {
                const h = await api.request<Hotel>("/hotels", "POST", {
                  name: d.get("name"),
                  timezone: d.get("timezone"),
                });
                onSelect(h.id);
                await changed("Hotel created.");
              }}
            >
              <Field label="Hotel name" name="name" maxLength={100} />
              <Field
                label="Timezone"
                name="timezone"
                defaultValue="Europe/Madrid"
                placeholder="Europe/Madrid"
                maxLength={64}
              />
            </Form>
          </div>
        )}
      </section>
      <section className="panel">
        {!selected ? (
          <Empty title="Select a hotel">
            Choose a property to see its team and manage invitations.
          </Empty>
        ) : (
          <>
            <h2>{selected.name} team</h2>
            {error && (
              <p className="error" role="alert">
                {error}
              </p>
            )}
            {members.length === 0 ? (
              <p className="muted">No team members returned.</p>
            ) : (
              <TeamMembers
                api={api}
                session={session}
                hotel={selected}
                members={members}
                reload={async () =>
                  setMembers(
                    await api.list<Member>(
                      "/hotels/" + encodeURIComponent(selected.id) + "/members",
                    ),
                  )
                }
              />
            )}
            {canManage(selected, session) && (
              <div className="section-divider">
                <h3>Invite a team member</h3>
                <p className="muted">
                  They’ll choose their own password through an invitation email.
                </p>
                <Form
                  button="Send invitation"
                  onSubmit={async (d) => {
                    const result = await api.request<{
                      status: "invited" | "assigned";
                    }>(
                      "/hotels/" +
                        encodeURIComponent(selected.id) +
                        "/invitations",
                      "POST",
                      { email: d.get("email"), role: d.get("role") },
                    );
                    await changed(
                      result.status === "assigned"
                        ? "Existing account added to this hotel. No invitation email was sent."
                        : "Invitation sent. The recipient can finish setup from their email.",
                    );
                    const r = await api.request<{ items: Member[] }>(
                      "/hotels/" + encodeURIComponent(selected.id) + "/members",
                    );
                    setMembers(r.items);
                  }}
                >
                  <Field
                    label="Email address"
                    name="email"
                    type="email"
                    autoComplete="email"
                    maxLength={254}
                  />
                  <Field label="Role" name="role">
                    <select name="role" defaultValue="operator">
                      <option value="manager">Hotel manager</option>
                      <option value="operator">Hotel operator</option>
                      <option value="viewer">Read-only viewer</option>
                    </select>
                  </Field>
                </Form>
              </div>
            )}
          </>
        )}
      </section>
      {session.user.platform_owner && <Accounts api={api} session={session} />}
    </div>
  );
}

function TeamMembers({
  api,
  session,
  hotel,
  members,
  reload,
}: {
  api: ApiClient;
  session: Session;
  hotel: Hotel;
  members: Member[];
  reload: () => Promise<void>;
}) {
  const [error, setError] = useState(""),
    [busy, setBusy] = useState<string | null>(null);
  async function change(member: Member, role?: string) {
    const id = member.user_id ?? member.id;
    if (!id) return;
    if (
      !window.confirm(
        role
          ? `Change ${member.email ?? "this person"} to ${role}?`
          : `Remove ${member.email ?? "this person"} from ${hotel.name}?`,
      )
    )
      return;
    setBusy(id);
    setError("");
    try {
      await api.request(
        "/hotels/" +
          encodeURIComponent(hotel.id) +
          "/members/" +
          encodeURIComponent(id),
        role ? "PATCH" : "DELETE",
        role ? { role } : {},
      );
      await reload();
    } catch (e) {
      setError(message(e));
    } finally {
      setBusy(null);
    }
  }
  return (
    <>
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
      <ul className="records compact">
        {members.map((m, i) => {
          const id = m.user_id ?? m.id;
          return (
            <li key={id ?? i}>
              <div>
                <span className="record-title">
                  {m.email ?? id ?? "Team member"}
                </span>
                <p>
                  {readable(m.role)}
                  {id === session.user.id ? " · You" : ""}
                </p>
              </div>
              {canManage(hotel, session) &&
                id &&
                id !== session.user.id &&
                m.role !== "owner" && (
                  <div className="member-actions">
                    <select
                      aria-label={"Role for " + (m.email ?? id)}
                      disabled={busy === id}
                      value={m.role}
                      onChange={(e) => void change(m, e.target.value)}
                    >
                      <option value="manager">Manager</option>
                      <option value="operator">Operator</option>
                      <option value="viewer">Viewer</option>
                    </select>
                    <button
                      className="text-button"
                      disabled={busy === id}
                      onClick={() => void change(m)}
                    >
                      Remove access
                    </button>
                  </div>
                )}
            </li>
          );
        })}
      </ul>
    </>
  );
}

function Accounts({ api, session }: { api: ApiClient; session: Session }) {
  const [accounts, setAccounts] = useState<Account[]>([]),
    [error, setError] = useState(""),
    [busy, setBusy] = useState<string | null>(null);
  useEffect(() => {
    const c = new AbortController();
    api
      .list<Account>("/users", c.signal)
      .then(setAccounts)
      .catch((e) => {
        if (!c.signal.aborted) setError(message(e));
      });
    return () => c.abort();
  }, [api]);
  async function toggle(account: Account) {
    if (
      !window.confirm(
        `${account.enabled ? "Disable" : "Enable"} portal access for ${account.email}?`,
      )
    )
      return;
    setBusy(account.id);
    setError("");
    try {
      await api.request("/users/" + encodeURIComponent(account.id), "PATCH", {
        enabled: !account.enabled,
      });
      setAccounts(await api.list<Account>("/users"));
    } catch (e) {
      setError(message(e));
    } finally {
      setBusy(null);
    }
  }
  return (
    <section className="panel platform-accounts">
      <div className="panel-heading">
        <h2>Platform accounts</h2>
        <span>Owner access</span>
      </div>
      <p className="muted">
        Disabling an account blocks its portal access. Hotel device enrollment
        remains independent.
      </p>
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
      {accounts.length === 0 ? (
        <p className="muted">No accounts returned.</p>
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Account</th>
                <th>Access</th>
                <th>Action</th>
              </tr>
            </thead>
            <tbody>
              {accounts.map((account) => (
                <tr key={account.id}>
                  <td>
                    {account.email}
                    <small>
                      {account.platform_owner ? "Platform owner" : "Hotel team"}
                      {account.id === session.user.id ? " · You" : ""}
                    </small>
                  </td>
                  <td>
                    <Badge value={account.enabled ? "active" : "disabled"} />
                  </td>
                  <td>
                    {account.id !== session.user.id ? (
                      <button
                        className={
                          account.enabled ? "danger small" : "secondary small"
                        }
                        disabled={busy === account.id}
                        onClick={() => void toggle(account)}
                      >
                        {account.enabled ? "Disable account" : "Enable account"}
                      </button>
                    ) : (
                      <span className="muted">Current account</span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

function Pairing({
  api,
  session,
  hotels,
  selected,
  enrollments,
  changed,
}: {
  api: ApiClient;
  session: Session;
  hotels: Hotel[];
  selected?: Hotel;
  enrollments: Enrollment[];
  changed: (text: string) => Promise<void>;
}) {
  const assignable = hotels.filter((h) => canManage(h, session));
  const pending = enrollments.filter((e) =>
    ["pending", "approved"].includes(e.status),
  );
  async function approve(hotel: string, code: string) {
    await api.request("/enrollments/approve", "POST", {
      hotel_id: hotel,
      user_code: code.replace(/[\s-]/g, "").toUpperCase(),
    });
    await changed(
      "Pairing approved. The device must save its credential and activate before enrollment is complete.",
    );
  }
  return (
    <div className="content-grid">
      <section className="panel">
        <h2>Approve a reception device</h2>
        <p className="muted">
          Open Fleet setup in the device’s protected staff settings. Enter its
          pairing code and confirm the intended hotel.
        </p>
        {assignable.length === 0 ? (
          <Empty title="Manager access needed">
            Your account cannot pair devices to a hotel.
          </Empty>
        ) : (
          <Form
            button="Approve device"
            onSubmit={async (d) =>
              approve(
                String(d.get("hotel_id")),
                String(d.get("user_code") ?? ""),
              )
            }
          >
            <Field
              label="Pairing code shown on device"
              name="user_code"
              minLength={8}
              maxLength={12}
              autoComplete="off"
            />
            <Field label="Assign to hotel" name="hotel_id">
              <select
                name="hotel_id"
                defaultValue={
                  selected && canManage(selected, session)
                    ? selected.id
                    : assignable[0]?.id
                }
              >
                {assignable.map((h) => (
                  <option value={h.id} key={h.id}>
                    {h.name}
                  </option>
                ))}
              </select>
            </Field>
          </Form>
        )}
        <p className="panel-note">
          Each device gets its own credential. Staff passwords and provider keys
          are not entered here.
        </p>
      </section>
      <section className="panel">
        <div className="panel-heading">
          <h2>Pairing requests</h2>
          <span>{pending.length} pending</span>
        </div>
        {pending.length === 0 ? (
          <Empty title="No pending requests">
            Requests permitted for your account appear here. You can also enter
            the pairing code directly from the physical device.
          </Empty>
        ) : (
          <ul className="records">
            {pending.map((e) => (
              <li key={e.request_id ?? e.id}>
                <div>
                  <span className="record-title">{e.device_name}</span>
                  <p className="pair-code">{e.user_code}</p>
                  <small>Expires {timestamp(e.expires_at)}</small>
                </div>
                <Badge value={e.status} />
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}

function Settings({
  api,
  session,
  hotel,
  changed,
}: {
  api: ApiClient;
  session: Session;
  hotel?: Hotel;
  changed: (text: string) => Promise<void>;
}) {
  const [revision, setRevision] = useState<ConfigRevision | null>(null),
    [error, setError] = useState("");
  useEffect(() => {
    setRevision(null);
    setError("");
    if (!hotel) return;
    const c = new AbortController();
    api
      .request<ConfigRevision>(
        "/hotels/" + encodeURIComponent(hotel.id) + "/config",
        "GET",
        undefined,
        c.signal,
      )
      .then((r) => setRevision(r))
      .catch((e) => {
        if (!c.signal.aborted) setError(message(e));
      });
    return () => c.abort();
  }, [api, hotel?.id]);
  if (!hotel)
    return (
      <section className="panel">
        <Empty title="Select a hotel">
          Choose one hotel above to inspect its published configuration.
        </Empty>
      </section>
    );
  const editable = canManage(hotel, session);
  return (
    <section className="panel">
      <div className="panel-heading">
        <h2>{hotel.name}</h2>
        <span>Published version {revision?.version ?? "—"}</span>
      </div>
      <p className="muted">
        Approved public hotel facts only. Never add API keys, passwords, access
        codes or private guest information.
      </p>
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
      {revision && (
        <Form
          key={hotel.id + ":" + revision.version}
          button="Publish settings"
          submitAllowed={editable}
          onSubmit={async (data) => {
            const value = (key: string) => String(data.get(key) ?? "").trim();
            const latitude = value("latitude"),
              longitude = value("longitude");
            if (Boolean(latitude) !== Boolean(longitude))
              throw new Error(
                "Enter both latitude and longitude, or leave both empty.",
              );
            if (
              latitude &&
              (!Number.isFinite(Number(latitude)) ||
                !Number.isFinite(Number(longitude)))
            )
              throw new Error("Coordinates must be numbers.");
            const availability = value("availability");
            const config = {
              ...revision.config,
              name: value("name"),
              address: value("address"),
              area: value("area"),
              opening_hours: value("opening_hours"),
              facts: value("facts"),
              latitude: latitude ? Number(latitude) : null,
              longitude: longitude ? Number(longitude) : null,
              availability,
              availability_updated_at_ms:
                availability !== String(revision.config.availability ?? "")
                  ? availability
                    ? Date.now()
                    : 0
                  : (revision.config.availability_updated_at_ms ?? 0),
            };
            const r = await api.request<ConfigRevision>(
              "/hotels/" + encodeURIComponent(hotel.id) + "/config",
              "PUT",
              { expected_version: revision.version, config },
            );
            setRevision(r);
            await changed(
              "Settings published. Device application remains pending until its receipt arrives.",
            );
          }}
        >
          <div className="form-grid">
            <Field
              label="Public hotel name"
              name="name"
              maxLength={160}
              defaultValue={String(revision.config.name ?? hotel.name)}
              readOnly={!editable}
            />
            <Field
              label="Address"
              name="address"
              required={false}
              defaultValue={String(revision.config.address ?? "")}
              maxLength={500}
              readOnly={!editable}
            />
            <Field
              label="Area / neighbourhood"
              name="area"
              required={false}
              defaultValue={String(revision.config.area ?? "")}
              maxLength={250}
              readOnly={!editable}
            />
            <Field
              label="Opening / reception hours"
              name="opening_hours"
              required={false}
              defaultValue={String(revision.config.opening_hours ?? "")}
              maxLength={1000}
              readOnly={!editable}
            />
            <div className="coordinate-row">
              <Field
                label="Hotel latitude"
                name="latitude"
                type="number"
                step="any"
                min={-90}
                max={90}
                required={false}
                defaultValue={
                  revision.config.latitude == null
                    ? ""
                    : String(revision.config.latitude)
                }
                readOnly={!editable}
              />
              <Field
                label="Hotel longitude"
                name="longitude"
                type="number"
                step="any"
                min={-180}
                max={180}
                required={false}
                defaultValue={
                  revision.config.longitude == null
                    ? ""
                    : String(revision.config.longitude)
                }
                readOnly={!editable}
              />
            </div>
          </div>
          <label className="field">
            <span>Approved hotel information</span>
            <textarea
              name="facts"
              rows={8}
              maxLength={12000}
              defaultValue={String(revision.config.facts ?? "")}
              readOnly={!editable}
              placeholder="Check-in instructions, public services, breakfast hours and contact details."
            />
          </label>
          <Field
            label="Staff-confirmed room availability"
            name="availability"
            required={false}
            defaultValue={String(revision.config.availability ?? "")}
            maxLength={1000}
            readOnly={!editable}
          />
          <p className="panel-note">
            Availability remains staff-supplied information; it is not a live
            reservation-system connection. Existing reviewed local-place records
            are preserved.
          </p>
          {!editable && (
            <p className="muted">
              Your role can view these settings. A manager publishes changes.
            </p>
          )}
        </Form>
      )}
    </section>
  );
}

type MfaFactor = {
  id: string;
  factor_type: string;
  status: string;
  friendly_name?: string;
};
type MfaEnrollment = {
  id: string;
  type: string;
  totp: { qr_code: string; secret: string; uri: string };
};
function MfaSetup({
  api,
  onVerified,
  onLogout,
  email,
  sessionError,
}: {
  api: ApiClient;
  onVerified: (session: Session) => void;
  onLogout: () => void;
  email: string;
  sessionError?: string;
}) {
  const [factors, setFactors] = useState<MfaFactor[]>([]),
    [factorId, setFactorId] = useState(""),
    [enrollment, setEnrollment] = useState<MfaEnrollment | null>(null);
  const [loading, setLoading] = useState(true),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false);
  useEffect(() => {
    const c = new AbortController();
    api
      .request<{ items: MfaFactor[] }>(
        "/auth/mfa/factors",
        "GET",
        undefined,
        c.signal,
      )
      .then((r) => {
        const all = r.items.filter((f) => f.factor_type === "totp");
        setFactors(all);
        setFactorId(
          all.find((f) => f.status === "verified")?.id ?? all[0]?.id ?? "",
        );
      })
      .catch((e) => {
        if (!c.signal.aborted) setError(message(e));
      })
      .finally(() => {
        if (!c.signal.aborted) setLoading(false);
      });
    return () => c.abort();
  }, [api]);
  async function enroll() {
    setBusy(true);
    setError("");
    try {
      const r = await api.request<MfaEnrollment>(
        "/auth/mfa/enroll",
        "POST",
        {},
      );
      setEnrollment(r);
      setFactorId(r.id);
    } catch (e) {
      setError(message(e));
    } finally {
      setBusy(false);
    }
  }
  const verified = factors.filter((f) => f.status === "verified");
  return (
    <main className="auth-shell">
      <div className="auth-card">
        <Brand />
        <div className="eyebrow">OWNER VERIFICATION</div>
        <h1>
          {verified.length
            ? "Verify your sign-in"
            : "Protect your owner account"}
        </h1>
        <p className="lead">
          {verified.length
            ? "Enter the code from your authenticator to open fleet management."
            : "Set up an authenticator before managing devices across hotels."}
        </p>
        <p className="muted identifier">{email}</p>
        {sessionError && (
          <p className="error" role="alert">
            {sessionError}
          </p>
        )}
        {error && (
          <p className="error" role="alert">
            {error}
          </p>
        )}
        {loading ? (
          <p role="status">Loading verification options…</p>
        ) : (
          <>
            {!verified.length && !enrollment && (
              <button
                className="primary"
                disabled={busy}
                onClick={() => void enroll()}
              >
                {busy ? "Preparing…" : "Create authenticator setup code"}
              </button>
            )}
            {enrollment && (
              <div className="mfa-setup">
                {/^data:image\/svg\+xml;base64,[A-Za-z0-9+/=]+$/.test(
                  enrollment.totp.qr_code,
                ) && (
                  <img
                    className="mfa-qr"
                    src={enrollment.totp.qr_code}
                    alt="Authenticator setup QR code"
                  />
                )}
                <p className="muted">
                  Scan with your authenticator, or enter this setup key
                  manually.
                </p>
                <code className="mfa-secret">{enrollment.totp.secret}</code>
              </div>
            )}
            {factorId && (
              <Form
                button="Verify and continue"
                onSubmit={async (data) => {
                  const challenge = await api.request<{
                    id: string;
                    expires_at: number;
                  }>("/auth/mfa/challenge", "POST", { factor_id: factorId });
                  const next = await api.request<Session>(
                    "/auth/mfa/verify",
                    "POST",
                    {
                      factor_id: factorId,
                      challenge_id: challenge.id,
                      code: String(data.get("code")).trim(),
                    },
                  );
                  setEnrollment(null);
                  onVerified(next);
                }}
              >
                {verified.length > 1 && (
                  <label className="field">
                    <span>Authenticator</span>
                    <select
                      value={factorId}
                      onChange={(e) => setFactorId(e.target.value)}
                    >
                      {verified.map((f) => (
                        <option key={f.id} value={f.id}>
                          {f.friendly_name ?? "Authenticator"}
                        </option>
                      ))}
                    </select>
                  </label>
                )}
                <Field
                  label="Six-digit authenticator code"
                  name="code"
                  inputMode="numeric"
                  pattern="[0-9]{6}"
                  minLength={6}
                  maxLength={6}
                  autoComplete="one-time-code"
                />
              </Form>
            )}
          </>
        )}
        <button className="text-button" onClick={onLogout}>
          Sign out
        </button>
        <p className="auth-footer">
          Lost your authenticator? Use your organization’s verified owner
          recovery process. Hotel data remains protected while verification is
          pending.
        </p>
      </div>
    </main>
  );
}

function Activity({
  audit,
  hotels,
}: {
  audit: Audit[];
  hotels: Map<string, Hotel>;
}) {
  return (
    <section className="panel">
      <div className="panel-heading">
        <h2>Management history</h2>
        <span>Most recent records</span>
      </div>
      {audit.length === 0 ? (
        <Empty title="No activity returned">
          Audited management actions will appear here.
        </Empty>
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Action</th>
                <th>Hotel</th>
                <th>Actor</th>
                <th>Time</th>
              </tr>
            </thead>
            <tbody>
              {audit.map((a) => (
                <tr key={a.id}>
                  <td>
                    {readable(a.action)}
                    {a.target_id && <small>{a.target_id}</small>}
                  </td>
                  <td>
                    {a.hotel_id
                      ? (hotels.get(a.hotel_id)?.name ?? a.hotel_id)
                      : "Platform"}
                  </td>
                  <td className="identifier">{a.actor_id}</td>
                  <td>{timestamp(a.created_at)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

function DeviceDetail({
  id,
  api,
  hotels,
  session,
  onClose,
  changed,
}: {
  id: string;
  api: ApiClient;
  hotels: Map<string, Hotel>;
  session: Session;
  onClose: () => void;
  changed: (text: string) => Promise<void>;
}) {
  const [device, setDevice] = useState<Device | null>(null),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false);
  const closeRef = useRef<HTMLButtonElement>(null);
  const operationKeys = useRef(new Map<string, string>());
  const load = useCallback(
    async (signal?: AbortSignal) => {
      try {
        const d = await api.request<Device>(
          "/devices/" + encodeURIComponent(id),
          "GET",
          undefined,
          signal,
        );
        if (!signal?.aborted) {
          setDevice(d);
          setError("");
        }
      } catch (e) {
        if (!signal?.aborted) setError(message(e));
      }
    },
    [api, id],
  );
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    closeRef.current?.focus();
    const c = new AbortController();
    void load(c.signal);
    const timer = window.setInterval(() => {
      if (!document.hidden) void load(c.signal);
    }, 15000);
    function key(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
      if (e.key === "Tab") {
        const nodes = Array.from(
          document.querySelectorAll<HTMLElement>(
            ".detail-panel button:not(:disabled),.detail-panel input,.detail-panel select,.detail-panel a",
          ),
        );
        const first = nodes[0],
          last = nodes[nodes.length - 1];
        if (e.shiftKey && document.activeElement === first) {
          e.preventDefault();
          last?.focus();
        } else if (!e.shiftKey && document.activeElement === last) {
          e.preventDefault();
          first?.focus();
        }
      }
    }
    document.addEventListener("keydown", key);
    return () => {
      c.abort();
      window.clearInterval(timer);
      document.removeEventListener("keydown", key);
      previous?.focus();
    };
  }, [load, onClose]);
  const hotel = device ? hotels.get(device.hotel_id) : undefined;
  async function command(type: string) {
    setBusy(true);
    setError("");
    const key = operationKeys.current.get(type) ?? crypto.randomUUID();
    operationKeys.current.set(type, key);
    try {
      await api.request(
        "/devices/" + encodeURIComponent(id) + "/commands",
        "POST",
        { type, arguments: {}, idempotency_key: key },
      );
      operationKeys.current.delete(type);
      await load();
      await changed(
        "Command queued. The device will report whether it is received, deferred or completed.",
      );
    } catch (e) {
      setError(message(e) + " Check command history before retrying.");
    } finally {
      setBusy(false);
    }
  }
  return (
    <div
      className="detail-backdrop"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <aside
        className="detail-panel"
        role="dialog"
        aria-modal="true"
        aria-labelledby="device-detail-title"
      >
        <header className="detail-heading">
          <div>
            <p className="eyebrow">DEVICE DETAILS</p>
            <h2 id="device-detail-title">
              {device?.name ?? "Reception device"}
            </h2>
            <p>{hotel?.name ?? device?.hotel_id}</p>
          </div>
          <button
            className="icon-button"
            ref={closeRef}
            onClick={onClose}
            aria-label="Close device details"
          >
            ×
          </button>
        </header>
        {error && (
          <p className="error" role="alert">
            {error}
          </p>
        )}
        {!device ? (
          <p className="loading">Loading device…</p>
        ) : (
          <>
            <div className="detail-status">
              <Badge value={device.connectivity} />
              <Badge value={device.status} />
              <span>Last seen {relativeTime(device.last_seen)}</span>
            </div>
            <dl className="detail-grid">
              <div>
                <dt>Voice mode</dt>
                <dd>{readable(device.mode)}</dd>
              </div>
              <div>
                <dt>Microphone</dt>
                <dd>{readable(device.mic_state)}</dd>
              </div>
              <div>
                <dt>App version</dt>
                <dd>{device.app_version || "Not reported"}</dd>
              </div>
              <div>
                <dt>Update state</dt>
                <dd>{readable(device.update_state)}</dd>
              </div>
              <div>
                <dt>Applied configuration</dt>
                <dd>{device.config_version}</dd>
              </div>
              <div>
                <dt>Desired configuration</dt>
                <dd>{device.desired_config_version}</dd>
              </div>
            </dl>
            <h3>Reported health</h3>
            <dl className="health-list">
              {Object.entries(healthLabels)
                .filter(([key]) => device.health?.[key] !== undefined)
                .map(([key, label]) => (
                  <div key={key}>
                    <dt>{label}</dt>
                    <dd>{readable(device.health?.[key])}</dd>
                  </div>
                ))}
            </dl>
            {!Object.keys(device.health ?? {}).some(
              (key) => key in healthLabels,
            ) && (
              <p className="muted">
                No supported health measurements reported yet.
              </p>
            )}
            <p className="panel-note">
              Timing describes the reported measurement boundary. It is not an
              acoustic quality score.
            </p>
            <ReportedActivity summary={device.telemetry_summary} />
            {canOperate(hotel, session) && device.status === "active" && (
              <div className="section-divider">
                <h3>Device actions</h3>
                <div className="button-row">
                  <button
                    className="secondary"
                    disabled={busy}
                    onClick={() => void command("refresh_config")}
                  >
                    Refresh settings
                  </button>
                  <button
                    className="secondary"
                    disabled={busy}
                    onClick={() => void command("check_update")}
                  >
                    Check for update
                  </button>
                  <button
                    className="secondary"
                    disabled={busy}
                    onClick={() => void command("diagnostics")}
                  >
                    Request diagnostics
                  </button>
                </div>
                <p className="muted">
                  Actions may be deferred while the device is busy. Checking for
                  an update does not confirm installation.
                </p>
              </div>
            )}
            <div className="section-divider">
              <h3>Recent commands</h3>
              {device.commands?.length ? (
                <ul className="records compact">
                  {device.commands.map((c: Command) => (
                    <li key={c.id}>
                      <div>
                        <span>{readable(c.type)}</span>
                        <small>
                          {timestamp(c.created_at)}
                          {c.result_code ? " · " + readable(c.result_code) : ""}
                        </small>
                      </div>
                      <Badge value={c.state} />
                    </li>
                  ))}
                </ul>
              ) : (
                <p className="muted">No commands returned.</p>
              )}
            </div>
            <div className="section-divider">
              <h3>Recent events and measurements</h3>
              {device.recent_events?.length ? (
                <ul className="records compact">
                  {device.recent_events.map((e) => (
                    <li key={e.id}>
                      <div>
                        <span>{readable(e.type)}</span>
                        <small>{timestamp(e.occurred_at)}</small>
                        {Object.entries(e.metrics ?? {})
                          .filter(([key]) => key in eventMetricLabels)
                          .map(([key, value]) => (
                            <small key={key}>
                              {eventMetricLabels[key]}: {readable(value)}
                            </small>
                          ))}
                      </div>
                    </li>
                  ))}
                </ul>
              ) : (
                <p className="muted">No diagnostic events returned.</p>
              )}
            </div>
            {canManage(hotel, session) && device.status !== "revoked" && (
              <div className="section-divider">
                <h3>Enrollment</h3>
                <p className="muted identifier">{device.id}</p>
                <button
                  className="danger"
                  disabled={busy}
                  onClick={async () => {
                    if (
                      !window.confirm(
                        "Revoke this device’s access to the management service? Its local data will not be deleted.",
                      )
                    )
                      return;
                    setBusy(true);
                    try {
                      await api.request(
                        "/devices/" + encodeURIComponent(id) + "/revoke",
                        "POST",
                      );
                      await load();
                      await changed(
                        "Device enrollment revoked. This does not revoke provider keys in older APKs.",
                      );
                    } catch (e) {
                      setError(message(e));
                    } finally {
                      setBusy(false);
                    }
                  }}
                >
                  Revoke enrollment
                </button>
              </div>
            )}
          </>
        )}
      </aside>
    </div>
  );
}

function ReportedActivity({ summary }: { summary?: TelemetrySummary }) {
  const timings = Object.entries(summary?.timings ?? {}).filter(
    ([key, value]) => key in eventMetricLabels && value.sample_count > 0,
  );
  const number = (value: number) =>
    value.toLocaleString(undefined, { maximumFractionDigits: 1 });
  return (
    <section className="section-divider" aria-label="Reported activity">
      <h3>Reported activity</h3>
      {!summary ? (
        <p className="muted">Activity summary has not been reported.</p>
      ) : (
        <>
          <p className="muted">
            Reports received in the past {summary.window_days} days. Counts
            cover retained device reports, not unique guests or completed
            conversations.
          </p>
          <p className="muted">
            {timestamp(summary.window_start)} – {timestamp(summary.window_end)}
          </p>
          <dl className="detail-grid">
            <div>
              <dt>Session-start reports</dt>
              <dd>{summary.event_counts.session_started.toLocaleString()}</dd>
            </div>
            <div>
              <dt>Session-end reports</dt>
              <dd>{summary.event_counts.session_ended.toLocaleString()}</dd>
            </div>
            <div>
              <dt>Audio-error reports</dt>
              <dd>{summary.event_counts.audio_error.toLocaleString()}</dd>
            </div>
          </dl>
          <h3>Reported timing samples</h3>
          <p className="muted">
            {summary.truncated
              ? "Partial timing summary: latest "
              : "Based on "}
            {summary.sampled_timing_event_count.toLocaleString()} of{" "}
            {summary.total_timing_event_count.toLocaleString()} timing reports;
            up to {summary.timing_event_limit.toLocaleString()} recent reports
            are included. Each measurement has its own sample count.
          </p>
          {timings.length ? (
            <div className="table-wrap">
              <table aria-label="Reported timing samples">
                <thead>
                  <tr>
                    <th>Measurement</th>
                    <th>Samples</th>
                    <th>Mean (ms)</th>
                    <th>Minimum (ms)</th>
                    <th>Maximum (ms)</th>
                  </tr>
                </thead>
                <tbody>
                  {timings.map(([metric, sample]) => (
                    <tr key={metric}>
                      <td>{eventMetricLabels[metric].replace(" (ms)", "")}</td>
                      <td>{sample.sample_count.toLocaleString()}</td>
                      <td>{number(sample.mean_ms)}</td>
                      <td>{number(sample.min_ms)}</td>
                      <td>{number(sample.max_ms)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <p className="muted">No timing samples reported for this window.</p>
          )}
          <p className="panel-note">
            Timings describe device-reported boundaries; they do not measure
            acoustic latency or conversation quality.
          </p>
        </>
      )}
    </section>
  );
}
