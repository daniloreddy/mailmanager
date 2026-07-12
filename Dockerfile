FROM python:3.12-slim AS builder

WORKDIR /app

# git required by pip to fetch the redberry-webkit dependency (git+https:// pin in requirements.txt)
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    git \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --user --no-cache-dir -r requirements.txt

FROM python:3.12-slim

RUN useradd --create-home --home-dir /home/appuser --shell /usr/sbin/nologin appuser
ENV HOME=/home/appuser
WORKDIR /app

COPY --from=builder /root/.local /root/.local
ENV PATH=/root/.local/bin:$PATH
ENV PYTHONUNBUFFERED=1

COPY app/ ./app/
COPY static/ ./static/
COPY scripts/ ./scripts/

RUN mkdir -p /app/data \
    && chown -R appuser:appuser /app /home/appuser

USER appuser

EXPOSE 8080

CMD ["sh", "-c", "uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8080}"]
