"""Isolated managed-auth, cookie, recovery and authorization boundary tests."""

import base64
import json
import time

import httpx
import pytest
from cryptography.fernet import Fernet
from fastapi import Depends, FastAPI
from fastapi.testclient import TestClient
from sqlalchemy import select, update

from control.auth import (AuthService, COOKIE_NAME, IdentityError, IdentitySession,
                          SupabaseIdentityProvider, WebSession, _digest)
from control.db import Base, make_engine, make_session_factory
from control.models import User
from control.settings import Settings


ORIGIN = "https://console.test"
HEADERS = {"Origin": ORIGIN, "Content-Type": "application/json"}


class FakeIdentity:
    def __init__(self):
        self.people = {"manager@example.test": "manager", "owner@example.test": "owner",
                       "stranger@example.test": "stranger"}
        self.refreshes = 0
        self.recoveries = []
        self.passwords = []
        self.logouts = []
        self.invites = []
        self.ttl = 3600
        self.owner_aal = "aal2"
        self.factors = []
        self.enrollments = 0
        self.mfa_verifies = 0

    def identity(self, email="manager@example.test"):
        return IdentitySession(self.people[email], email, "private-access-" + self.people[email],
                               "private-refresh-" + self.people[email], time.time() + self.ttl,
                               aal=self.owner_aal if email == "owner@example.test" else "aal1")

    def login(self, email, password):
        if password != "correct-password" or email not in self.people:
            raise IdentityError()
        return self.identity(email)

    def refresh(self, token):
        self.refreshes += 1
        return self.identity()

    def verify(self, token, kind):
        if token != "valid-token-hash":
            raise IdentityError()
        return self.identity()

    def recover(self, email, redirect):
        self.recoveries.append((email, redirect))

    def password(self, token, password):
        self.passwords.append((token, password))

    def logout(self, token):
        self.logouts.append(token)

    def invite(self, email, redirect):
        self.invites.append((email, redirect))
        return "new-subject"

    def resolve_existing_user(self, subject, email):
        if self.people.get(email) != subject:
            raise IdentityError(409, "Existing account identity must be reviewed")
        return subject

    def mfa_factors(self, token):
        return list(self.factors)

    def mfa_enroll(self, token):
        self.enrollments += 1
        factor = {"id": "factor-own", "factor_type": "totp", "status": "unverified", "friendly_name": "Authenticator"}
        self.factors = [factor]
        return {"id": "factor-own", "type": "totp", "totp": {"secret": "SECRET-TOTP-FIXTURE",
                "uri": "otpauth://totp/Manzanilla:test?secret=FIXTURE", "qr_code": "data:image/svg+xml;base64,PHN2Zy8+"}}

    def mfa_challenge(self, token, factor):
        return {"id": "challenge-own", "expires_at": time.time() + 120}

    def mfa_verify(self, token, factor, challenge, code):
        self.mfa_verifies += 1
        if code != "123456":
            raise IdentityError()
        self.factors[0]["status"] = "verified"
        self.owner_aal = "aal2"
        return self.identity("owner@example.test")


@pytest.fixture
def setup_auth():
    settings = Settings(environment="test", public_origin=ORIGIN,
                        session_encryption_key=Fernet.generate_key().decode())
    engine = make_engine(settings.database_url)
    Base.metadata.create_all(engine)
    sessions = make_session_factory(engine)
    with sessions.begin() as db:
        db.add(User(id="manager", email="manager@example.test", enabled=True, platform_owner=False))
        db.add(User(id="owner", email="owner@example.test", enabled=True, platform_owner=True))
    provider = FakeIdentity()
    auth = AuthService(settings, sessions, provider)
    app = FastAPI()
    app.include_router(auth.router, prefix="/api/v2")

    @app.get("/api/v2/private")
    def read_private(principal=Depends(auth.require_user)):
        return {"id": principal.id, "owner": principal.platform_owner}

    @app.post("/api/v2/private")
    def write_private(principal=Depends(auth.require_user)):
        return {"ok": True}

    with TestClient(app, base_url=ORIGIN) as client:
        yield client, auth, sessions, provider
    engine.dispose()


def login(client, email="manager@example.test"):
    response = client.post("/api/v2/auth/login", json={"email": email, "password": "correct-password"}, headers=HEADERS)
    assert response.status_code == 200, response.text
    return response.json()["csrf_token"]


def mutation_headers(csrf):
    return {**HEADERS, "X-CSRF-Token": csrf}


def test_login_is_opaque_http_only_cookie_and_encrypted_server_storage(setup_auth):
    client, auth, sessions, _ = setup_auth
    csrf = login(client)
    cookie = client.cookies.get(COOKIE_NAME)
    response = client.get("/api/v2/auth/session")
    assert response.json() == {"user": {"id": "manager", "email": "manager@example.test", "platform_owner": False},
                               "csrf_token": csrf, "requires_password": False, "requires_mfa": False}
    assert response.headers["cache-control"] == "no-store"
    assert "private-access" not in response.text and "private-refresh" not in response.text
    with sessions() as db:
        row = db.execute(select(WebSession)).scalar_one()
        assert row.sid_hash == _digest(cookie) and row.csrf_hash == _digest(csrf)
        assert "private-access" not in row.encrypted_credentials
        assert csrf not in row.encrypted_credentials
        assert cookie not in row.encrypted_credentials
    first = client.post("/api/v2/auth/login", json={"email": "manager@example.test", "password": "correct-password"}, headers=HEADERS)
    assert "HttpOnly" in first.headers["set-cookie"]
    assert "Secure" in first.headers["set-cookie"]
    assert "SameSite=lax" in first.headers["set-cookie"]
    assert client.cookies.get(COOKIE_NAME) != cookie


def test_login_requires_origin_json_and_does_not_accept_role_arguments(setup_auth):
    client, _, _, _ = setup_auth
    body = {"email": "manager@example.test", "password": "correct-password"}
    assert client.post("/api/v2/auth/login", json=body).status_code == 403
    assert client.post("/api/v2/auth/login", json=body, headers={**HEADERS, "Origin": "https://attacker.test"}).status_code == 403
    assert client.post("/api/v2/auth/login", json={**body, "platform_owner": True}, headers=HEADERS).status_code == 422
    assert client.post("/api/v2/auth/login", data=body, headers={"Origin": ORIGIN}).status_code == 422


def test_private_mutations_require_current_csrf_and_origin(setup_auth):
    client, _, _, _ = setup_auth
    csrf = login(client)
    assert client.post("/api/v2/private", json={}, headers=HEADERS).status_code == 403
    assert client.post("/api/v2/private", json={}, headers=mutation_headers("wrong")).status_code == 403
    assert client.post("/api/v2/private", json={}, headers={**mutation_headers(csrf), "Origin": "https://attacker.test"}).status_code == 403
    assert client.post("/api/v2/private", json={}, headers=mutation_headers(csrf)).status_code == 200


def test_unknown_managed_user_cannot_self_register(setup_auth):
    client, _, sessions, _ = setup_auth
    response = client.post("/api/v2/auth/login", json={"email": "stranger@example.test", "password": "correct-password"}, headers=HEADERS)
    assert response.status_code == 403
    with sessions() as db:
        assert db.get(User, "stranger") is None
        assert db.execute(select(WebSession)).first() is None


def test_disable_or_demote_is_effective_without_cookie_expiry(setup_auth):
    client, _, sessions, _ = setup_auth
    login(client, "owner@example.test")
    assert client.get("/api/v2/private").json()["owner"] is True
    with sessions.begin() as db:
        db.execute(update(User).where(User.id == "owner").values(platform_owner=False))
    assert client.get("/api/v2/private").json()["owner"] is False
    with sessions.begin() as db:
        db.execute(update(User).where(User.id == "owner").values(enabled=False))
    assert client.get("/api/v2/private").status_code == 403


def test_expired_session_is_rejected(setup_auth):
    client, _, sessions, _ = setup_auth
    login(client)
    with sessions.begin() as db:
        db.execute(update(WebSession).values(expires_at=time.time() - 1))
    assert client.get("/api/v2/auth/session").status_code == 401


def test_near_expiry_refreshes_server_credentials_without_exposing_them(setup_auth):
    client, _, sessions, provider = setup_auth
    login(client)
    with sessions.begin() as db:
        db.execute(update(WebSession).values(provider_expires_at=time.time() - 1))
    response = client.get("/api/v2/auth/session")
    assert response.status_code == 200 and provider.refreshes == 1
    assert "private-refresh" not in response.text


def test_refresh_assurance_downgrade_commits_rotated_token_before_denial(setup_auth):
    client, auth, sessions, provider = setup_auth
    login(client, "owner@example.test")
    with sessions.begin() as db:
        db.execute(update(WebSession).values(provider_expires_at=time.time() - 1))
    calls = []
    def refresh(token):
        calls.append(token)
        return IdentitySession("owner", "owner@example.test", "new-access", "new-refresh", time.time() + 3600, aal="aal1")
    provider.refresh = refresh
    assert client.get("/api/v2/private").status_code == 403
    assert client.get("/api/v2/auth/session").json()["requires_mfa"] is True
    assert len(calls) == 1
    with sessions() as db:
        row = db.execute(select(WebSession)).scalar_one()
        assert row.assurance_level == "aal1"
        assert auth._decode(row)["refresh_token"] == "new-refresh"


def test_refresh_subject_change_is_rejected(setup_auth):
    client, _, sessions, provider = setup_auth
    login(client)
    with sessions.begin() as db:
        db.execute(update(WebSession).values(provider_expires_at=time.time() - 1))
    provider.refresh = lambda _: provider.identity("owner@example.test")
    assert client.get("/api/v2/auth/session").status_code == 401


def test_logout_revokes_cookie_before_provider_outage(setup_auth):
    client, _, sessions, provider = setup_auth
    csrf = login(client)
    provider.logout = lambda _: (_ for _ in ()).throw(IdentityError(503))
    assert client.post("/api/v2/auth/logout", json={}, headers=mutation_headers(csrf)).status_code == 200
    assert client.get("/api/v2/auth/session").status_code == 401
    with sessions() as db:
        assert db.execute(select(WebSession)).first() is None


@pytest.mark.parametrize("kind", ["invite", "recovery"])
def test_verified_setup_session_cannot_access_fleet_until_password_set(setup_auth, kind):
    client, _, sessions, provider = setup_auth
    login(client)
    old_cookie = client.cookies.get(COOKIE_NAME)
    result = client.post("/api/v2/auth/verify", json={"token_hash": "valid-token-hash", "type": kind}, headers=HEADERS)
    assert result.status_code == 200 and result.json()["requires_password"] is True
    csrf = result.json()["csrf_token"]
    assert client.cookies.get(COOKIE_NAME) != old_cookie
    assert client.get("/api/v2/private").status_code == 403
    assert client.get("/api/v2/auth/session").json()["requires_password"] is True
    result = client.post("/api/v2/auth/password", json={"password": "a-new-long-password"}, headers=mutation_headers(csrf))
    assert result.status_code == 200 and result.json()["reauthenticate"] is True
    assert provider.passwords == [("private-access-manager", "a-new-long-password")]
    assert client.get("/api/v2/auth/session").status_code == 401
    with sessions() as db:
        assert db.execute(select(WebSession)).first() is None


def test_reset_invalidates_other_local_sessions(setup_auth):
    client, _, sessions, _ = setup_auth
    login(client)
    old_hash = _digest(client.cookies.get(COOKIE_NAME))
    result = client.post("/api/v2/auth/verify", json={"token_hash": "valid-token-hash", "type": "recovery"}, headers=HEADERS)
    assert result.status_code == 200
    with sessions() as db:
        assert db.get(WebSession, old_hash) is None


def test_invalid_or_unsupported_otp_cannot_create_session(setup_auth):
    client, _, sessions, _ = setup_auth
    assert client.post("/api/v2/auth/verify", json={"token_hash": "invalid-token-hash", "type": "invite"}, headers=HEADERS).status_code == 401
    assert client.post("/api/v2/auth/verify", json={"token_hash": "valid-token-hash", "type": "signup"}, headers=HEADERS).status_code == 422
    with sessions() as db:
        assert db.execute(select(WebSession)).first() is None


def test_login_and_recovery_throttles_are_database_backed(setup_auth):
    client, _, _, provider = setup_auth
    for _ in range(10):
        assert client.post("/api/v2/auth/login", json={"email": "manager@example.test", "password": "wrong"}, headers=HEADERS).status_code == 401
    assert client.post("/api/v2/auth/login", json={"email": "manager@example.test", "password": "correct-password"}, headers=HEADERS).status_code == 429
    for _ in range(5):
        assert client.post("/api/v2/auth/recover", json={"email": "unknown@example.test"}, headers=HEADERS).json() == {"ok": True}
    assert len(provider.recoveries) == 5
    assert client.post("/api/v2/auth/recover", json={"email": "unknown@example.test"}, headers=HEADERS).status_code == 429


def test_invitation_redirect_is_same_origin_only(setup_auth):
    _, auth, _, provider = setup_auth
    assert auth.invite("new@example.test", ORIGIN + "/") == "new-subject"
    with pytest.raises(Exception) as error:
        auth.invite("new@example.test", "https://attacker.test/")
    assert error.value.status_code == 400
    assert len(provider.invites) == 1


def provider_with_handler(handler, **overrides):
    settings = Settings(environment="test", public_origin=ORIGIN, supabase_url="https://project.supabase.co",
                        supabase_publishable_key="test-publishable", supabase_secret_key="test-secret", **overrides)
    return SupabaseIdentityProvider(settings, client=httpx.Client(transport=httpx.MockTransport(handler)))


def test_http_provider_verifies_user_and_ignores_untrusted_role_metadata():
    seen = []
    def handler(request):
        seen.append(request)
        assert request.url.host == "project.supabase.co"
        if request.url.path.endswith("/token"):
            assert request.url.params["grant_type"] == "password"
            return httpx.Response(200, json={"access_token": "jwt-from-managed-service", "refresh_token": "refresh", "expires_in": 300})
        assert request.url.path.endswith("/user")
        assert request.headers["Authorization"] == "Bearer jwt-from-managed-service"
        return httpx.Response(200, json={"id": "manager", "email": "manager@example.test", "email_confirmed_at": "now",
                                         "user_metadata": {"platform_owner": True}})
    result = provider_with_handler(handler).login("manager@example.test", "private-password")
    assert result.id == "manager" and len(seen) == 2
    assert not hasattr(result, "platform_owner")
    assert "jwt-from-managed-service" not in repr(result)


def test_http_provider_rejects_unconfirmed_email_and_sanitizes_failures():
    def handler(request):
        if request.url.path.endswith("/token"):
            return httpx.Response(200, json={"access_token": "access", "refresh_token": "refresh", "expires_in": 300})
        return httpx.Response(200, json={"id": "manager", "email": "manager@example.test", "user_metadata": {"confirmed": True}})
    with pytest.raises(IdentityError, match="Verified email required"):
        provider_with_handler(handler).login("manager@example.test", "private-password")
    provider = provider_with_handler(lambda _: httpx.Response(400, json={"message": "private server detail and token"}))
    with pytest.raises(IdentityError) as error:
        provider.login("manager@example.test", "private-password")
    assert str(error.value) == "Authentication failed"


def test_http_admin_invite_uses_server_key_and_no_browser_bearer():
    def handler(request):
        assert request.url.path == "/auth/v1/invite"
        assert request.headers["apikey"] == "test-secret"
        assert "authorization" not in request.headers
        assert request.url.params["redirect_to"] == ORIGIN + "/"
        return httpx.Response(200, json={"id": "invited-subject"})
    assert provider_with_handler(handler).invite("new@example.test", ORIGIN + "/") == "invited-subject"


@pytest.mark.parametrize("wrapped", [False, True])
def test_existing_user_resolution_requires_current_confirmed_provider_identity(wrapped):
    def handler(request):
        assert request.url.path == "/auth/v1/admin/users/existing-user"
        assert request.headers["apikey"] == "test-secret"
        assert "authorization" not in request.headers
        user = {"id": "existing-user", "email": "manager@example.test", "email_confirmed_at": "now"}
        return httpx.Response(200, json={"user": user} if wrapped else user)
    provider = provider_with_handler(handler)
    assert provider.resolve_existing_user("existing-user", "manager@example.test") == "existing-user"


@pytest.mark.parametrize("user", [
    {"id": "different-user", "email": "manager@example.test", "email_confirmed_at": "now"},
    {"id": "existing-user", "email": "changed@example.test", "email_confirmed_at": "now"},
    {"id": "existing-user", "email": "manager@example.test", "user_metadata": {"confirmed": True}},
    {"id": "existing-user", "email": "manager@example.test", "email_confirmed_at": "now", "deleted_at": "now"},
])
def test_existing_user_resolution_never_links_stale_unverified_or_other_identity(user):
    provider = provider_with_handler(lambda _: httpx.Response(200, json=user))
    with pytest.raises(IdentityError) as error:
        provider.resolve_existing_user("existing-user", "manager@example.test")
    assert error.value.status == 409


@pytest.mark.parametrize("status,expected", [(404, 409), (401, 503), (403, 503), (429, 503), (500, 503)])
def test_existing_user_resolution_distinguishes_missing_identity_from_provider_failure(status, expected):
    provider = provider_with_handler(lambda _: httpx.Response(status, json={"private": "provider details"}))
    with pytest.raises(IdentityError) as error:
        provider.resolve_existing_user("existing-user", "manager@example.test")
    assert error.value.status == expected
    assert "provider details" not in str(error.value)


def test_auth_service_rejects_mismatched_existing_identity_adapter(setup_auth):
    _, auth, _, provider = setup_auth
    assert auth.resolve_existing_user("manager", "manager@example.test") == "manager"
    provider.resolve_existing_user = lambda *args: "other-subject"
    with pytest.raises(Exception) as error:
        auth.resolve_existing_user("manager", "manager@example.test")
    assert error.value.status_code == 409


def test_missing_provider_configuration_fails_closed_without_network():
    provider = SupabaseIdentityProvider(Settings(environment="test"), client=httpx.Client(transport=httpx.MockTransport(lambda _: pytest.fail("unexpected network"))))
    with pytest.raises(IdentityError) as error:
        provider.login("manager@example.test", "private-password")
    assert error.value.status == 503


def test_bootstrap_creates_only_explicit_subject_and_never_regrants_existing_user(setup_auth):
    client, auth, sessions, provider = setup_auth
    # Existing manager remains unprivileged even if the bootstrap setting matches.
    auth.settings = Settings(environment="test", public_origin=ORIGIN, bootstrap_owner_sub="manager",
                             session_encryption_key=auth.settings.session_encryption_key)
    login(client)
    assert client.get("/api/v2/private").json()["owner"] is False
    # A different first trusted subject can be provisioned without email-based grants.
    auth.settings = Settings(environment="test", public_origin=ORIGIN, bootstrap_owner_sub="stranger",
                             session_encryption_key=auth.settings.session_encryption_key)
    login(client, "stranger@example.test")
    assert client.get("/api/v2/private").status_code == 403
    assert client.get("/api/v2/auth/session").json()["requires_mfa"] is True
    with sessions() as db:
        assert db.get(User, "stranger").platform_owner


def prepare_owner_mfa(setup_auth, *, verified=False):
    client, auth, sessions, provider = setup_auth
    provider.owner_aal = "aal1"
    if verified:
        provider.factors = [{"id": "factor-own", "factor_type": "totp", "status": "verified", "friendly_name": "Authenticator"}]
    csrf = login(client, "owner@example.test")
    assert client.get("/api/v2/private").status_code == 403
    assert client.get("/api/v2/auth/session").json()["requires_mfa"] is True
    return client, auth, sessions, provider, csrf


def test_owner_mfa_setup_verification_rotates_cookie_and_unlocks_fleet(setup_auth):
    client, _, _, provider, csrf = prepare_owner_mfa(setup_auth)
    old_cookie = client.cookies.get(COOKIE_NAME)
    setup = client.post("/api/v2/auth/mfa/enroll", json={}, headers=mutation_headers(csrf))
    assert setup.status_code == 200 and setup.json()["id"] == "factor-own"
    assert setup.headers["cache-control"] == "no-store"
    # A lost enrollment reply / reload can recover this session's pending setup.
    repeated = client.post("/api/v2/auth/mfa/enroll", json={}, headers=mutation_headers(csrf))
    assert repeated.json() == setup.json() and provider.enrollments == 1
    assert client.get("/api/v2/auth/mfa/factors").json()["items"][0]["status"] == "unverified"
    challenge = client.post("/api/v2/auth/mfa/challenge", json={"factor_id": "factor-own"}, headers=mutation_headers(csrf))
    assert challenge.status_code == 200
    verified = client.post("/api/v2/auth/mfa/verify", json={"factor_id": "factor-own", "challenge_id": challenge.json()["id"], "code": "123456"}, headers=mutation_headers(csrf))
    assert verified.status_code == 200 and verified.json()["requires_mfa"] is False
    assert client.cookies.get(COOKIE_NAME) != old_cookie
    assert verified.json()["csrf_token"] != csrf
    assert client.get("/api/v2/private").json()["owner"] is True
    assert "SECRET-TOTP-FIXTURE" not in verified.text


def test_existing_mfa_cannot_be_bypassed_by_enrolling_another_factor(setup_auth):
    client, _, _, provider, csrf = prepare_owner_mfa(setup_auth, verified=True)
    result = client.post("/api/v2/auth/mfa/enroll", json={}, headers=mutation_headers(csrf))
    assert result.status_code == 403 and provider.enrollments == 0
    assert client.post("/api/v2/auth/password", json={"password": "another-long-password"}, headers=mutation_headers(csrf)).status_code == 403


def test_mfa_challenge_is_bound_to_session_factor_and_consumed_on_failure(setup_auth):
    client, _, _, provider, csrf = prepare_owner_mfa(setup_auth, verified=True)
    assert client.post("/api/v2/auth/mfa/challenge", json={"factor_id": "foreign-factor"}, headers=mutation_headers(csrf)).status_code == 403
    assert client.post("/api/v2/auth/mfa/verify", json={"factor_id": "factor-own", "challenge_id": "arbitrary", "code": "123456"}, headers=mutation_headers(csrf)).status_code == 400
    assert provider.mfa_verifies == 0
    challenge = client.post("/api/v2/auth/mfa/challenge", json={"factor_id": "factor-own"}, headers=mutation_headers(csrf)).json()
    body = {"factor_id": "factor-own", "challenge_id": challenge["id"], "code": "000000"}
    assert client.post("/api/v2/auth/mfa/verify", json=body, headers=mutation_headers(csrf)).status_code == 401
    body["code"] = "123456"
    assert client.post("/api/v2/auth/mfa/verify", json=body, headers=mutation_headers(csrf)).status_code == 400
    assert provider.mfa_verifies == 1


def test_expired_mfa_challenge_and_cross_site_requests_are_denied(setup_auth):
    client, auth, sessions, provider, csrf = prepare_owner_mfa(setup_auth, verified=True)
    assert client.post("/api/v2/auth/mfa/challenge", json={"factor_id": "factor-own"}, headers=HEADERS).status_code == 403
    client.post("/api/v2/auth/mfa/challenge", json={"factor_id": "factor-own"}, headers=mutation_headers(csrf))
    with sessions.begin() as db:
        row = db.get(WebSession, _digest(client.cookies.get(COOKIE_NAME)))
        data = auth._decode(row)
        data["mfa_challenge"]["expires_at"] = time.time() - 1
        row.encrypted_credentials = auth.cipher.encrypt(__import__("json").dumps(data).encode()).decode()
    assert client.post("/api/v2/auth/mfa/verify", json={"factor_id": "factor-own", "challenge_id": "challenge-own", "code": "123456"}, headers=mutation_headers(csrf)).status_code == 400
    assert provider.mfa_verifies == 0


def test_mfa_wrong_identity_or_aal_cannot_upgrade_owner(setup_auth):
    client, _, _, provider, csrf = prepare_owner_mfa(setup_auth, verified=True)
    challenge = client.post("/api/v2/auth/mfa/challenge", json={"factor_id": "factor-own"}, headers=mutation_headers(csrf)).json()
    provider.mfa_verify = lambda *args: provider.identity("manager@example.test")
    result = client.post("/api/v2/auth/mfa/verify", json={"factor_id": "factor-own", "challenge_id": challenge["id"], "code": "123456"}, headers=mutation_headers(csrf))
    assert result.status_code == 401
    assert client.get("/api/v2/private").status_code == 403


def test_http_totp_contract_and_assurance_require_verified_user_endpoint():
    def token(aal):
        payload = base64.urlsafe_b64encode(json.dumps({"sub": "owner", "aal": aal}).encode()).decode().rstrip("=")
        return "eyJhbGciOiJSUzI1NiJ9." + payload + ".provider-signature"

    factors = []
    verified_tokens = []
    def handler(request):
        path = request.url.path
        if path == "/auth/v1/user":
            access = request.headers["Authorization"].removeprefix("Bearer ")
            assert access in {token("aal1"), token("aal2")}
            verified_tokens.append(access)
            return httpx.Response(200, json={"id": "owner", "email": "owner@example.test",
                                             "email_confirmed_at": "now", "factors": factors})
        if path == "/auth/v1/token":
            return httpx.Response(200, json={"access_token": token("aal1"), "refresh_token": "refresh", "expires_in": 3600})
        assert request.headers["Authorization"] == "Bearer " + token("aal1")
        body = json.loads(request.content)
        if path == "/auth/v1/factors":
            assert body == {"factor_type": "totp", "friendly_name": "Manzanilla"}
            factors.append({"id": "factor-provider", "factor_type": "totp", "status": "unverified"})
            return httpx.Response(200, json={"id": "factor-provider", "totp": {"secret": "TESTSECRET",
                    "uri": "otpauth://totp/Manzanilla:test?secret=TESTSECRET", "qr_code": "<svg/>"}})
        if path == "/auth/v1/factors/factor-provider/challenge":
            return httpx.Response(200, json={"id": "challenge-provider", "expires_at": time.time() + 120})
        assert path == "/auth/v1/factors/factor-provider/verify"
        assert body == {"challenge_id": "challenge-provider", "code": "123456"}
        factors[0]["status"] = "verified"
        return httpx.Response(200, json={"access_token": token("aal2"), "refresh_token": "new-refresh", "expires_in": 3600})

    provider = provider_with_handler(handler)
    initial = provider.login("owner@example.test", "private-password")
    assert initial.aal == "aal1" and initial.mfa_enabled is False
    enrolled = provider.mfa_enroll(initial.access_token)
    assert enrolled["totp"]["qr_code"].startswith("data:image/svg+xml;base64,")
    assert provider.mfa_factors(initial.access_token)[0]["status"] == "unverified"
    challenge = provider.mfa_challenge(initial.access_token, enrolled["id"])
    verified = provider.mfa_verify(initial.access_token, enrolled["id"], challenge["id"], "123456")
    assert verified.aal == "aal2" and verified.mfa_enabled is True
    assert token("aal2") in verified_tokens
    provider.close()
