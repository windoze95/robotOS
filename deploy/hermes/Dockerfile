# Official upstream multi-architecture image, pinned to a reviewed digest.
ARG HERMES_IMAGE=nousresearch/hermes-agent@sha256:9774f4f39a9bb8c2f68ce728ed5e99ddbad282163be56764afacf88ed952b784
FROM ${HERMES_IMAGE}
ARG VCS_REF=unknown
LABEL org.opencontainers.image.title="Hermes with robotOS adapter" \
      org.opencontainers.image.source="https://github.com/windoze95/robotOS" \
      org.opencontainers.image.revision=$VCS_REF
USER root
COPY server/robotos_mcp /opt/robotos/robotos_mcp
COPY server/hermes/skills/robotos/SKILL.md /opt/robotos/skills/robotos/SKILL.md
COPY deploy/hermes/bootstrap.py /opt/robotos/bootstrap.py
RUN mkdir -p /opt/data && chown 10000:10000 /opt/data \
    && chmod 0700 /opt/data && chmod -R a+rX,go-w /opt/robotos \
    && rm -rf /opt/robotos/robotos_mcp/tests
ENV HERMES_HOME=/opt/data/hermes HOME=/opt/data/hermes PYTHONDONTWRITEBYTECODE=1 \
    API_SERVER_ENABLED=true API_SERVER_HOST=0.0.0.0 API_SERVER_PORT=8642
# Run just the gateway in the foreground. No root s6 supervisor, Docker socket,
# host network, dashboard, or broad host filesystem mounts are needed by robotOS.
USER 10000:10000
WORKDIR /opt/hermes
ENTRYPOINT ["/opt/hermes/.venv/bin/python", "/opt/robotos/bootstrap.py"]
CMD []
