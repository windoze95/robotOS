# Build from the repository root. Multi-architecture upstream index is pinned.
FROM python:3.14.4-slim-trixie@sha256:2ca02f32b4d9d893863367ce07ec1972819f476dd38d8612f2a9cb6a41cbb727
ARG VCS_REF=unknown
LABEL org.opencontainers.image.title="robotOS hub" \
      org.opencontainers.image.source="https://github.com/windoze95/robotOS" \
      org.opencontainers.image.revision=$VCS_REF
ENV PYTHONPATH=/opt/robotos PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1 \
    ROBOTOS_HUB_DATA_DIR=/var/lib/robotos/data ROBOTOS_HUB_PORT=8787
RUN groupadd --gid 10001 robotos && useradd --uid 10001 --gid 10001 --no-create-home robotos \
    && install -d -m 0700 -o robotos -g robotos /var/lib/robotos
COPY server/robotos_hub /opt/robotos/robotos_hub
COPY tools/hub-backup.py /opt/robotos/hub-backup.py
COPY deploy/hub/healthcheck.py /opt/robotos/healthcheck.py
RUN find /opt/robotos -type d -name __pycache__ -prune -exec rm -rf '{}' + \
    && rm -rf /opt/robotos/robotos_hub/tests
USER 10001:10001
WORKDIR /opt/robotos
EXPOSE 8787
STOPSIGNAL SIGTERM
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 CMD ["python", "/opt/robotos/healthcheck.py", "ready"]
ENTRYPOINT ["python", "-m", "robotos_hub"]
