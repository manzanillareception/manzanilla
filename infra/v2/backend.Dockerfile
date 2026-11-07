# syntax=docker/dockerfile:1
FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
COPY requirements.txt requirements-control.txt ./
RUN --mount=type=secret,id=proxy_ca \
    if [ -s /run/secrets/proxy_ca ]; then export PIP_CERT=/run/secrets/proxy_ca; fi; \
    pip install --no-cache-dir -r requirements-control.txt \
    && useradd --uid 10001 --create-home --shell /usr/sbin/nologin manzanilla
COPY control ./control
COPY scripts/control_*.py ./scripts/
USER 10001:10001
EXPOSE 8080
CMD ["uvicorn", "control.app:app", "--host", "0.0.0.0", "--port", "8080", "--workers", "1", "--no-access-log", "--proxy-headers", "--forwarded-allow-ips", "*"]
