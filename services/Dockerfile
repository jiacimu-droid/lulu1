FROM python:3.12-slim
RUN apt-get update && apt-get install -y --no-install-recommends libreoffice-impress libreoffice-writer fonts-noto-cjk && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY lulu_service.py .
RUN useradd -m -u 10001 lulu && mkdir /data && chown lulu:lulu /data
USER lulu
VOLUME /data
EXPOSE 8080
CMD ["python", "lulu_service.py"]
