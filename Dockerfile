FROM python:3.11-slim AS builder

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --user --no-cache-dir -r requirements.txt

FROM python:3.11-slim

WORKDIR /app

COPY --from=builder /root/.local /root/.local
ENV PATH=/root/.local/bin:$PATH
ENV PYTHONUNBUFFERED=1

COPY . .

RUN mkdir -p data logs
VOLUME /app/data
VOLUME /app/logs

ENV SPAMASSASSIN_HOST=spamassassin

EXPOSE 8080

ENTRYPOINT ["python", "main.py"]
