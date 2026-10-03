FROM python:3.13-slim

ARG FORESTIQ_RELEASE_ID=dev
ARG FORESTIQ_COMMIT_SHA=unknown

ENV FORESTIQ_RELEASE_ID=$FORESTIQ_RELEASE_ID \
    FORESTIQ_COMMIT_SHA=$FORESTIQ_COMMIT_SHA \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
      gdal-bin \
      libgdal-dev \
    && rm -rf /var/lib/apt/lists/*

COPY pyproject.toml README.md ./
COPY src ./src

RUN pip install --no-cache-dir .

RUN useradd --create-home --shell /usr/sbin/nologin forestiq
USER forestiq

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD python -m forestiq_ingest.healthcheck --component ${FORESTIQ_INGEST_COMPONENT:-worker} --max-age-seconds 120

CMD ["python", "-m", "forestiq_ingest.worker"]
