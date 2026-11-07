"""Managed identity with opaque, revocable server-side browser sessions.

Supabase tokens are verified through its authenticated user endpoint and encrypted
on the server. They are never returned to portal JavaScript. Local roles are read
from the current database, not user-editable provider metadata.
"""

from __future__ import annotations

import base64
import hashlib
import json
import re
import secrets
import threading
import time
from dataclasses import dataclass, field
from typing import Literal
from urllib.parse import urlsplit

import httpx
from cryptography.fernet import Fernet, InvalidToken
from fastapi import APIRouter, HTTPException, Request, Response
from pydantic import BaseModel, ConfigDict, Field, field_validator
from sqlalchemy import Float, ForeignKey, Integer, String, Text, delete, select, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Mapped, mapped_column

from .db import Base
from .models import User
from .settings import Settings


COOKIE_NAME = "manz_session"
COOKIE_PATH = "/api/v2"


@dataclass(frozen=True)
class Principal:
    id: str
    email: str
    platform_owner: bool = False


@dataclass(frozen=True)
class IdentitySession:
    id: str
    email: str
    access_token: str = field(repr=False)
    refresh_token: str = field(repr=False)
    expires_at: float
    aal: str = "aal1"
    mfa_enabled: bool = False


class WebSession(Base):
    __tablename__ = "web_sessions"
    sid_hash: Mapped[str] = mapped_column(String(64), primary_key=True)
    subject: Mapped[str] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    csrf_hash: Mapped[str] = mapped_column(String(64))
    encrypted_credentials: Mapped[str] = mapped_column(Text)
    expires_at: Mapped[float] = mapped_column(Float, index=True)
    provider_expires_at: Mapped[float] = mapped_column(Float)
    revision: Mapped[int] = mapped_column(Integer, default=1)
    flow: Mapped[str] = mapped_column(String(16), default="login")
    assurance_level: Mapped[str] = mapped_column(String(8), default="aal1")


class AuthRateBucket(Base):
    __tablename__ = "auth_rate_buckets"
    id: Mapped[str] = mapped_column(String(64), primary_key=True)
    count: Mapped[int] = mapped_column(Integer, default=0)
    expires_at: Mapped[float] = mapped_column(Float, index=True)


class IdentityError(Exception):
    """Safe identity error with no provider response body or credentials."""

    def __init__(self, status: int = 401, detail: str = "Authentication failed", *, provider_status: int | None = None):
        self.status = status
        self.detail = detail
        self.provider_status = provider_status
        super().__init__(detail)


class SupabaseIdentityProvider:
    """Bounded synchronous HTTP calls, used only from FastAPI's worker threads."""

    def __init__(self, settings: Settings, client: httpx.Client | None = None):
        self.settings = settings
        self.client = client
        self._client_lock = threading.Lock()

    def close(self) -> None:
        if self.client is not None:
            self.client.close()

    def _request(self, method: str, path: str, *, body=None, token=None, admin=False, params=None):
        if not self.settings.supabase_url or not self.settings.supabase_publishable_key:
            raise IdentityError(503, "Authentication is not configured")
        key = self.settings.supabase_secret_key if admin else self.settings.supabase_publishable_key
        if not key:
            raise IdentityError(503, "Authentication is not configured")
        headers = {"apikey": key, "Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        elif admin and key.startswith("eyJ"):
            # Legacy service_role JWTs need Bearer; modern API keys use apikey.
            headers["Authorization"] = "Bearer " + key
        try:
            if self.client is None:
                with self._client_lock:
                    if self.client is None:
                        self.client = httpx.Client(timeout=httpx.Timeout(10, connect=5), follow_redirects=False)
            result = self.client.request(method, self.settings.supabase_url + "/auth/v1" + path,
                                         json=body, headers=headers, params=params)
        except httpx.HTTPError as exc:
            raise IdentityError(503, "Authentication provider unavailable") from exc
        if result.status_code >= 500 or result.status_code == 429:
            raise IdentityError(503, "Authentication provider unavailable")
        if result.status_code not in {200, 201, 204}:
            raise IdentityError(401 if not admin else 400,
                                "Authentication failed" if not admin else "Invitation could not be created",
                                provider_status=result.status_code)
        if result.status_code == 204 or not result.content:
            return {}
        if len(result.content) > 1_048_576:
            raise IdentityError(503, "Invalid authentication provider response")
        try:
            payload = result.json()
            if not isinstance(payload, dict):
                raise ValueError()
            return payload
        except (ValueError, TypeError) as exc:
            raise IdentityError(503, "Invalid authentication provider response") from exc

    def _session(self, payload: dict) -> IdentitySession:
        access = payload.get("access_token")
        refresh = payload.get("refresh_token")
        ttl = payload.get("expires_in")
        if not isinstance(access, str) or not 1 <= len(access) <= 16384 or not isinstance(refresh, str):
            raise IdentityError(503, "Invalid authentication provider response")
        if not refresh or len(refresh) > 16384 or not isinstance(ttl, (int, float)) or not 0 < ttl <= 86400:
            raise IdentityError(503, "Invalid authentication provider response")
        # Do not merely decode a JWT or trust the token response's user_metadata.
        user = self._request("GET", "/user", token=access)
        subject, email = user.get("id"), user.get("email")
        if (not isinstance(subject, str) or not 1 <= len(subject) <= 128 or
                not isinstance(email, str) or "@" not in email or len(email) > 320 or
                not (user.get("email_confirmed_at") or user.get("confirmed_at"))):
            raise IdentityError(401, "Verified email required")
        # The exact token has just been authenticated by /user. Only then read
        # its provider-signed assurance claim; never infer assurance from metadata.
        aal = "aal1"
        try:
            claims = json.loads(base64.urlsafe_b64decode(access.split(".")[1] + "==="))
            if isinstance(claims, dict) and claims.get("sub") == subject and claims.get("aal") == "aal2":
                aal = "aal2"
        except (IndexError, ValueError, TypeError, UnicodeError):
            pass
        factors = user.get("factors", [])
        mfa_enabled = isinstance(factors, list) and any(isinstance(f, dict) and f.get("status") == "verified" for f in factors)
        return IdentitySession(subject, email.lower(), access, refresh, time.time() + ttl, aal=aal, mfa_enabled=mfa_enabled)

    def login(self, email: str, password: str) -> IdentitySession:
        return self._session(self._request("POST", "/token", params={"grant_type": "password"},
                                           body={"email": email, "password": password}))

    def refresh(self, refresh_token: str) -> IdentitySession:
        return self._session(self._request("POST", "/token", params={"grant_type": "refresh_token"},
                                           body={"refresh_token": refresh_token}))

    def verify(self, token_hash: str, verification_type: str) -> IdentitySession:
        return self._session(self._request("POST", "/verify",
                                           body={"token_hash": token_hash, "type": verification_type}))

    def recover(self, email: str, redirect_to: str) -> None:
        try:
            self._request("POST", "/recover", params={"redirect_to": redirect_to}, body={"email": email})
        except IdentityError as exc:
            if exc.status == 503:
                raise
            # Prevent account enumeration from provider 4xx error details/statuses.

    def invite(self, email: str, redirect_to: str) -> str:
        user = self._request("POST", "/invite", admin=True, params={"redirect_to": redirect_to}, body={"email": email})
        subject = user.get("id") or (user.get("user") or {}).get("id")
        if not isinstance(subject, str) or not 1 <= len(subject) <= 128:
            raise IdentityError(503, "Invalid authentication provider response")
        return subject

    def resolve_existing_user(self, subject: str, email: str) -> str:
        """Revalidate an existing managed identity before granting another hotel.

        Finding a local row by email alone is insufficient: the managed account
        may have changed its email, been removed, or never confirmed ownership.
        """
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", subject):
            raise IdentityError(409, "Existing account identity must be reviewed")
        try:
            payload = self._request("GET", f"/admin/users/{subject}", admin=True)
        except IdentityError as exc:
            if exc.provider_status == 404:
                raise IdentityError(409, "Existing account identity must be reviewed") from exc
            raise IdentityError(503, "Managed identity verification unavailable") from exc
        user = payload.get("user", payload)
        if (not isinstance(user, dict) or user.get("id") != subject or
                not isinstance(user.get("email"), str) or
                user["email"].strip().lower() != email.strip().lower() or
                not (user.get("email_confirmed_at") or user.get("confirmed_at")) or
                user.get("deleted_at")):
            raise IdentityError(409, "Existing account identity must be reviewed")
        return subject

    def password(self, access_token: str, password: str) -> None:
        self._request("PUT", "/user", token=access_token, body={"password": password})

    def logout(self, access_token: str) -> None:
        try:
            self._request("POST", "/logout", params={"scope": "local"}, token=access_token)
        except IdentityError:
            # The local opaque session is already revoked, even if the provider is down.
            pass

    def mfa_factors(self, access_token: str) -> list[dict]:
        user = self._request("GET", "/user", token=access_token)
        factors = user.get("factors", [])
        if not isinstance(factors, list):
            raise IdentityError(503, "Invalid authentication provider response")
        return [{"id": f["id"], "factor_type": "totp", "status": f["status"],
                 "friendly_name": str(f.get("friendly_name") or "Authenticator")[:100]}
                for f in factors if isinstance(f, dict) and f.get("factor_type") == "totp"
                and isinstance(f.get("id"), str) and re.fullmatch(r"[A-Za-z0-9_-]{1,128}", f["id"])
                and f.get("status") in {"verified", "unverified"}]

    def mfa_enroll(self, access_token: str) -> dict:
        result = self._request("POST", "/factors", token=access_token,
                               body={"factor_type": "totp", "friendly_name": "Manzanilla"})
        factor_id, totp = result.get("id"), result.get("totp")
        if (not isinstance(factor_id, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", factor_id)
                or not isinstance(totp, dict) or not isinstance(totp.get("secret"), str)
                or not 1 <= len(totp["secret"]) <= 256 or not isinstance(totp.get("uri"), str)
                or not totp["uri"].startswith("otpauth://totp/") or len(totp["uri"]) > 4096):
            raise IdentityError(503, "Invalid authentication provider response")
        qr = totp.get("qr_code", "")
        if not isinstance(qr, str) or len(qr) > 100000:
            raise IdentityError(503, "Invalid authentication provider response")
        if qr and not qr.startswith("data:image/svg+xml;base64,"):
            qr = "data:image/svg+xml;base64," + base64.b64encode(qr.encode()).decode()
        return {"id": factor_id, "type": "totp", "totp": {"secret": totp["secret"], "uri": totp["uri"], "qr_code": qr}}

    def mfa_challenge(self, access_token: str, factor_id: str) -> dict:
        return self._request("POST", f"/factors/{factor_id}/challenge", token=access_token, body={})

    def mfa_verify(self, access_token: str, factor_id: str, challenge_id: str, code: str) -> IdentitySession:
        return self._session(self._request("POST", f"/factors/{factor_id}/verify", token=access_token,
                                           body={"challenge_id": challenge_id, "code": code}))


class StrictBody(BaseModel):
    model_config = ConfigDict(extra="forbid")


class EmailBody(StrictBody):
    email: str = Field(min_length=3, max_length=320)

    @field_validator("email")
    @classmethod
    def validate_email(cls, value):
        value = value.strip().lower()
        if "@" not in value or any(c.isspace() for c in value):
            raise ValueError("Enter a valid email address")
        return value


class LoginBody(EmailBody):
    password: str = Field(min_length=1, max_length=1024)


class VerifyBody(StrictBody):
    token_hash: str = Field(min_length=10, max_length=1024)
    type: Literal["invite", "recovery"]


class PasswordBody(StrictBody):
    password: str = Field(min_length=12, max_length=128)


class MfaFactorBody(StrictBody):
    factor_id: str = Field(pattern=r"^[A-Za-z0-9_-]{1,128}$")


class MfaVerifyBody(MfaFactorBody):
    challenge_id: str = Field(pattern=r"^[A-Za-z0-9_-]{1,128}$")
    code: str = Field(pattern=r"^[0-9]{6}$")


class EmptyBody(StrictBody):
    pass


def _digest(value: str) -> str:
    return hashlib.sha256(value.encode()).hexdigest()


class AuthService:
    def __init__(self, settings: Settings, session_factory, identity_provider=None):
        self.settings = settings
        self.sessions = session_factory
        self.provider = identity_provider or SupabaseIdentityProvider(settings)
        self.cipher = Fernet(settings.session_encryption_key.encode())
        self.router = APIRouter(prefix="/auth", tags=["accounts"])
        self._register_routes()

    def _provider(self, function, *args):
        try:
            return function(*args)
        except IdentityError as exc:
            raise HTTPException(exc.status, exc.detail) from exc

    def _origin(self, request: Request):
        if request.headers.get("origin") != self.settings.public_origin:
            raise HTTPException(403, "Same-origin request required")
        if request.headers.get("content-type", "").split(";", 1)[0].strip().lower() != "application/json":
            raise HTTPException(415, "JSON request required")

    def _rate(self, request: Request, purpose: str, identity: str = "", *, limit=10, window=900):
        now = time.time()
        ip = request.client.host if request.client else "unknown"
        keys = [(purpose + ":ip:" + ip, max(limit * 4, 20))]
        if identity:
            keys.append((purpose + ":identity:" + identity.lower(), limit))
        for key, ceiling in keys:
            bucket_id = _digest(key + ":" + str(int(now // window)))
            with self.sessions.begin() as db:
                db.execute(delete(AuthRateBucket).where(AuthRateBucket.expires_at < now))
                value = db.execute(update(AuthRateBucket).where(AuthRateBucket.id == bucket_id)
                                   .values(count=AuthRateBucket.count + 1).returning(AuthRateBucket.count)).scalar_one_or_none()
                if value is None:
                    try:
                        with db.begin_nested():
                            db.add(AuthRateBucket(id=bucket_id, count=1, expires_at=(int(now // window) + 1) * window))
                            db.flush()
                        value = 1
                    except IntegrityError:
                        value = db.execute(update(AuthRateBucket).where(AuthRateBucket.id == bucket_id)
                                           .values(count=AuthRateBucket.count + 1)
                                           .returning(AuthRateBucket.count)).scalar_one()
            if value > ceiling:
                raise HTTPException(429, "Too many requests; try again later", headers={"Retry-After": str(window)})

    def _decode(self, row: WebSession) -> dict:
        try:
            data = json.loads(self.cipher.decrypt(row.encrypted_credentials.encode()))
            if not all(isinstance(data.get(k), str) and data[k] for k in ("access_token", "refresh_token", "csrf_token")):
                raise ValueError()
            return data
        except (InvalidToken, ValueError, TypeError, UnicodeError) as exc:
            raise HTTPException(401, "Session unavailable; sign in again") from exc

    def _csrf(self, request: Request, row: WebSession):
        self._origin(request)
        token = request.headers.get("x-csrf-token", "")
        if not token or not secrets.compare_digest(_digest(token), row.csrf_hash):
            raise HTTPException(403, "CSRF token required")

    def _load(self, request: Request, *, refresh=True, allow_setup=False):
        sid = request.cookies.get(COOKIE_NAME, "")
        if not 32 <= len(sid) <= 128:
            raise HTTPException(401, "Sign in required")
        with self.sessions.begin() as db:
            row = db.execute(select(WebSession).where(WebSession.sid_hash == _digest(sid)).with_for_update()).scalar_one_or_none()
            if row is None or row.expires_at <= time.time():
                raise HTTPException(401, "Session expired; sign in again")
            user = db.get(User, row.subject)
            if user is None or not user.enabled:
                raise HTTPException(403, "Account access unavailable")
            if row.flow != "login" and not allow_setup:
                raise HTTPException(403, "Set a password before continuing")
            if request.method not in {"GET", "HEAD", "OPTIONS"}:
                self._csrf(request, row)
            data = self._decode(row)
            data["_flow"] = row.flow
            if refresh and row.provider_expires_at <= time.time() + 30:
                renewed = self._provider(self.provider.refresh, data["refresh_token"])
                if renewed.id != row.subject:
                    raise HTTPException(401, "Session identity changed; sign in again")
                data.update(access_token=renewed.access_token, refresh_token=renewed.refresh_token)
                data["mfa_enabled"] = renewed.mfa_enabled
                row.encrypted_credentials = self.cipher.encrypt(json.dumps(data).encode()).decode()
                row.provider_expires_at = renewed.expires_at
                row.revision += 1
                row.assurance_level = renewed.aal
            data["_aal"] = row.assurance_level
            data["_requires_mfa"] = bool((user.platform_owner or data.get("mfa_enabled")) and row.assurance_level != "aal2")
            principal = Principal(user.id, user.email, bool(user.platform_owner))
            sid_hash = row.sid_hash
        # Commit a successfully rotated provider refresh token even when the
        # resulting assurance requires step-up; otherwise denial would roll back
        # the new token and leave a consumed refresh credential in the database.
        if data["_requires_mfa"] and not allow_setup:
            raise HTTPException(403, "MFA verification required")
        return principal, data, sid_hash

    def require_user(self, request: Request) -> Principal:
        principal, _, _ = self._load(request)
        return principal

    def _session_body(self, principal: Principal, csrf: str, *, requires_password=False, requires_mfa=False):
        return {"user": {"id": principal.id, "email": principal.email,
                         "platform_owner": principal.platform_owner}, "csrf_token": csrf,
                "requires_password": requires_password, "requires_mfa": requires_mfa}

    def _establish(self, identity: IdentitySession, request: Request, response: Response, *, flow="login"):
        now = time.time()
        sid, csrf = secrets.token_urlsafe(48), secrets.token_urlsafe(32)
        with self.sessions.begin() as db:
            db.execute(delete(WebSession).where(WebSession.expires_at <= now))
            user = db.get(User, identity.id)
            if user is None and self.settings.bootstrap_owner_sub and secrets.compare_digest(identity.id, self.settings.bootstrap_owner_sub):
                user = User(id=identity.id, email=identity.email, enabled=True, platform_owner=True)
                db.add(user)
                db.flush()
            if user is None or not user.enabled:
                raise HTTPException(403, "An administrator invitation is required")
            user.email = identity.email
            # Replace any browser's previous opaque session; never retain a fixed cookie.
            old_sid = request.cookies.get(COOKIE_NAME, "")
            if old_sid:
                db.execute(delete(WebSession).where(WebSession.sid_hash == _digest(old_sid)))
            if flow == "recovery":
                db.execute(delete(WebSession).where(WebSession.subject == identity.id))
            data = {"access_token": identity.access_token, "refresh_token": identity.refresh_token,
                    "csrf_token": csrf, "mfa_enabled": identity.mfa_enabled}
            db.add(WebSession(sid_hash=_digest(sid), subject=identity.id, csrf_hash=_digest(csrf),
                              encrypted_credentials=self.cipher.encrypt(json.dumps(data).encode()).decode(),
                              expires_at=now + self.settings.session_ttl_seconds,
                              provider_expires_at=identity.expires_at, revision=1, flow=flow,
                              assurance_level=identity.aal))
            principal = Principal(user.id, user.email, bool(user.platform_owner))
        response.set_cookie(COOKIE_NAME, sid, max_age=self.settings.session_ttl_seconds, path=COOKIE_PATH,
                            httponly=True, secure=self.settings.cookie_secure, samesite="lax")
        response.headers["Cache-Control"] = "no-store"
        return self._session_body(principal, csrf, requires_password=flow != "login",
                                  requires_mfa=(principal.platform_owner or identity.mfa_enabled) and identity.aal != "aal2")

    def invite(self, email: str, redirect_to: str) -> str:
        origin = urlsplit(self.settings.public_origin)
        target = urlsplit(redirect_to)
        if (target.scheme, target.netloc) != (origin.scheme, origin.netloc) or target.username or target.password:
            raise HTTPException(400, "Invalid invitation redirect")
        return self._provider(self.provider.invite, email, redirect_to)

    def resolve_existing_user(self, subject: str, email: str) -> str:
        resolved = self._provider(self.provider.resolve_existing_user, subject, email)
        if resolved != subject:
            raise HTTPException(409, "Existing account identity must be reviewed")
        return resolved

    def _register_routes(self):
        @self.router.post("/login")
        def login(body: LoginBody, request: Request, response: Response):
            self._origin(request)
            self._rate(request, "login", body.email)
            identity = self._provider(self.provider.login, body.email, body.password)
            return self._establish(identity, request, response)

        @self.router.get("/session")
        def session(request: Request, response: Response):
            principal, data, _ = self._load(request, allow_setup=True)
            response.headers["Cache-Control"] = "no-store"
            return self._session_body(principal, data["csrf_token"], requires_password=data["_flow"] != "login",
                                      requires_mfa=data["_requires_mfa"])

        @self.router.post("/logout")
        def logout(request: Request, response: Response):
            self._origin(request)
            sid = request.cookies.get(COOKIE_NAME, "")
            provider_token = None
            if sid:
                with self.sessions.begin() as db:
                    row = db.get(WebSession, _digest(sid))
                    if row is not None:
                        self._csrf(request, row)
                        try:
                            provider_token = self._decode(row)["access_token"]
                        except HTTPException:
                            pass
                        db.delete(row)
            if provider_token:
                # Revoked locally before any outbound call, including provider outage.
                try:
                    self.provider.logout(provider_token)
                except IdentityError:
                    pass
            response.delete_cookie(COOKIE_NAME, path=COOKIE_PATH, secure=self.settings.cookie_secure,
                                   httponly=True, samesite="lax")
            response.headers["Cache-Control"] = "no-store"
            return {"ok": True}

        @self.router.post("/recover")
        def recover(body: EmailBody, request: Request, response: Response):
            self._origin(request)
            self._rate(request, "recover", body.email, limit=5, window=3600)
            self._provider(self.provider.recover, body.email, self.settings.public_origin + "/")
            response.headers["Cache-Control"] = "no-store"
            return {"ok": True}

        @self.router.post("/verify")
        def verify(body: VerifyBody, request: Request, response: Response):
            self._origin(request)
            self._rate(request, "verify", limit=10)
            identity = self._provider(self.provider.verify, body.token_hash, body.type)
            return self._establish(identity, request, response, flow=body.type)

        @self.router.post("/password")
        def password(body: PasswordBody, request: Request, response: Response):
            principal, data, _ = self._load(request, allow_setup=True)
            if data["_flow"] == "login" and data["_requires_mfa"]:
                raise HTTPException(403, "MFA verification required")
            self._rate(request, "password", principal.id, limit=5)
            self._provider(self.provider.password, data["access_token"], body.password)
            with self.sessions.begin() as db:
                db.execute(delete(WebSession).where(WebSession.subject == principal.id))
            response.delete_cookie(COOKIE_NAME, path=COOKIE_PATH, secure=self.settings.cookie_secure,
                                   httponly=True, samesite="lax")
            response.headers["Cache-Control"] = "no-store"
            return {"ok": True, "reauthenticate": True}

        def mfa_context(request: Request):
            principal, data, sid_hash = self._load(request, allow_setup=True)
            if data["_flow"] != "login":
                raise HTTPException(403, "Set a password before continuing")
            return principal, data, sid_hash

        @self.router.get("/mfa/factors")
        def mfa_factors(request: Request, response: Response):
            _, data, _ = mfa_context(request)
            factors = self._provider(self.provider.mfa_factors, data["access_token"])
            response.headers["Cache-Control"] = "no-store"
            return {"items": factors}

        @self.router.post("/mfa/enroll")
        def mfa_enroll(body: EmptyBody, request: Request, response: Response):
            principal, data, sid_hash = mfa_context(request)
            self._rate(request, "mfa_enroll", principal.id, limit=5)
            factors = self._provider(self.provider.mfa_factors, data["access_token"])
            if data["_aal"] != "aal2" and any(f.get("status") == "verified" for f in factors):
                raise HTTPException(403, "Verify the existing authenticator before adding another")
            with self.sessions.begin() as db:
                row = db.execute(select(WebSession).where(WebSession.sid_hash == sid_hash).with_for_update()).scalar_one_or_none()
                if row is None or row.expires_at <= time.time():
                    raise HTTPException(401, "Session expired; sign in again")
                stored = self._decode(row)
                pending = stored.get("mfa_enrollment")
                if (isinstance(pending, dict) and pending.get("expires_at", 0) > time.time()
                        and any(f.get("id") == pending["value"].get("id") and f.get("status") == "unverified" for f in factors)):
                    value = pending["value"]
                else:
                    value = self._provider(self.provider.mfa_enroll, data["access_token"])
                    stored["mfa_enrollment"] = {"value": value, "expires_at": time.time() + 600}
                    row.encrypted_credentials = self.cipher.encrypt(json.dumps(stored).encode()).decode()
                    row.revision += 1
            response.headers["Cache-Control"] = "no-store"
            return value

        @self.router.post("/mfa/challenge")
        def mfa_challenge(body: MfaFactorBody, request: Request, response: Response):
            principal, data, sid_hash = mfa_context(request)
            self._rate(request, "mfa_challenge", principal.id, limit=10, window=300)
            factors = self._provider(self.provider.mfa_factors, data["access_token"])
            if not any(f.get("id") == body.factor_id for f in factors):
                raise HTTPException(403, "Authenticator unavailable")
            result = self._provider(self.provider.mfa_challenge, data["access_token"], body.factor_id)
            challenge_id, expires = result.get("id"), result.get("expires_at")
            if (not isinstance(challenge_id, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", challenge_id)
                    or not isinstance(expires, (int, float)) or not time.time() < expires <= time.time() + 900):
                raise HTTPException(503, "Invalid authentication provider response")
            expires = min(expires, time.time() + 300)
            with self.sessions.begin() as db:
                row = db.execute(select(WebSession).where(WebSession.sid_hash == sid_hash).with_for_update()).scalar_one_or_none()
                if row is None or row.expires_at <= time.time():
                    raise HTTPException(401, "Session expired; sign in again")
                stored = self._decode(row)
                stored["mfa_challenge"] = {"id": challenge_id, "factor_id": body.factor_id, "expires_at": expires}
                row.encrypted_credentials = self.cipher.encrypt(json.dumps(stored).encode()).decode()
                row.revision += 1
            response.headers["Cache-Control"] = "no-store"
            return {"id": challenge_id, "expires_at": expires}

        @self.router.post("/mfa/verify")
        def mfa_verify(body: MfaVerifyBody, request: Request, response: Response):
            principal, data, sid_hash = mfa_context(request)
            self._rate(request, "mfa_verify", principal.id, limit=5, window=300)
            with self.sessions.begin() as db:
                row = db.execute(select(WebSession).where(WebSession.sid_hash == sid_hash).with_for_update()).scalar_one_or_none()
                if row is None or row.expires_at <= time.time():
                    raise HTTPException(401, "Session expired; sign in again")
                stored = self._decode(row)
                pending = stored.get("mfa_challenge")
                if (not isinstance(pending, dict) or pending.get("expires_at", 0) <= time.time()
                        or pending.get("id") != body.challenge_id or pending.get("factor_id") != body.factor_id):
                    raise HTTPException(400, "Authenticator challenge expired; try again")
                # Consume once before verification. A failed code needs a fresh
                # challenge, and retries cannot replay a completed step-up.
                stored.pop("mfa_challenge", None)
                row.encrypted_credentials = self.cipher.encrypt(json.dumps(stored).encode()).decode()
                row.revision += 1
            identity = self._provider(self.provider.mfa_verify, data["access_token"], body.factor_id,
                                      body.challenge_id, body.code)
            if identity.id != principal.id or identity.aal != "aal2":
                raise HTTPException(401, "MFA verification failed")
            return self._establish(identity, request, response)
