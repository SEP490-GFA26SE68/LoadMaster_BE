# syntax=docker/dockerfile:1
# OptimizeService (FastAPI, Python 3.12), user không phải root. Mô hình AI của gói ULTIMATE (torch CPU,
# vài GB) chỉ cài khi build với --build-arg INSTALL_AI=true; thiếu nó thì gói ULTIMATE chạy heuristic.
# Build:  docker build -t optimize-service ./OptimizeService

FROM python:3.12-slim
ARG INSTALL_AI=false
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    TZ=Asia/Ho_Chi_Minh \
    APP_ENV=production
WORKDIR /app
COPY requirements.txt requirements-ai.txt ./
RUN pip install -r requirements.txt \
    && if [ "$INSTALL_AI" = "true" ]; then pip install -r requirements-ai.txt; fi
RUN groupadd --system optimize && useradd --system --gid optimize --home-dir /app --no-create-home optimize
# Đường dẫn mô hình (app/ml/models) là tương đối nên WORKDIR phải là /app
COPY --chown=optimize:optimize app/ app/
USER optimize
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD python -c "import urllib.request, sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=4).status == 200 else 1)"
# Số worker theo WEB_CONCURRENCY của uvicorn (mặc định 1)
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
