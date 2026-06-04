FROM python:3.11-slim as builder

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --user --no-cache-dir -r requirements.txt

FROM python:3.11-slim

WORKDIR /app

# Copy installed packages
COPY --from=builder /root/.local /root/.local
ENV PATH=/root/.local/bin:$PATH
ENV PYTHONUNBUFFERED=1

# Copy application code
COPY . .

# Create data directory and volume
RUN mkdir -p data
VOLUME /app/data

# Default mode is headless
ENV MAILMANAGER_MODE=headless
ENV SPAMASSASSIN_HOST=spamassassin

ENTRYPOINT ["python", "main.py"]
