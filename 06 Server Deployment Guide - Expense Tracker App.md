# Expense Tracker App — Server Deployment Guide

One Oracle Cloud Always Free VM runs everything: PostgreSQL, FastAPI (via systemd + Uvicorn), and nginx as the HTTPS front door. GitHub Actions deploys to it over SSH on every push to `main`.

## 1. Provision the VM

- Create an Oracle Cloud account, go to Compute → Instances → Create Instance
- Shape: **Ampere A1 (ARM)**, Always Free eligible — 4 OCPU / 24GB RAM is the free allowance
- Image: Ubuntu 22.04 LTS
- Generate and download an SSH key pair during creation; save the private key securely, it's the only way in
- Note the public IP once the instance is running

## 2. Network & firewall

- In the VM's attached Virtual Cloud Network (VCN) security list, open ingress ports: **22** (SSH, ideally restricted to your own IP), **80** (HTTP, for Certbot's verification), **443** (HTTPS)
- On the VM itself, enable `ufw` and allow the same three ports:

```
sudo ufw allow 22
sudo ufw allow 80
sudo ufw allow 443
sudo ufw enable
```

## 3. Base OS setup

```
sudo apt update && sudo apt upgrade -y
sudo adduser deploy
sudo usermod -aG sudo deploy
```

Switch to the `deploy` user for everything from here on — avoid running the app as root.

## 4. PostgreSQL

```
sudo apt install -y postgresql postgresql-contrib
sudo -u postgres psql -c "CREATE USER expense_app WITH PASSWORD '<strong-password>';"
sudo -u postgres psql -c "CREATE DATABASE expense_tracker OWNER expense_app;"
```

Postgres listens on localhost only by default — leave it that way, FastAPI connects via `127.0.0.1`, never expose 5432 externally.

## 5. Python & FastAPI

```
sudo apt install -y python3-pip python3-venv
git clone <your-repo-url> /home/deploy/expense-backend
cd /home/deploy/expense-backend
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt
```

Create `/home/deploy/expense-backend/.env` (never committed to git) with the database URL, JWT secret, and Firebase credentials.

## 6. systemd service

Create `/etc/systemd/system/expense-backend.service`:

```
[Unit]
Description=Expense Tracker FastAPI
After=network.target postgresql.service

[Service]
User=deploy
WorkingDirectory=/home/deploy/expense-backend
EnvironmentFile=/home/deploy/expense-backend/.env
ExecStart=/home/deploy/expense-backend/venv/bin/uvicorn app.main:app --host 127.0.0.1 --port 8000
Restart=always

[Install]
WantedBy=multi-user.target
```

```
sudo systemctl enable expense-backend
sudo systemctl start expense-backend
```

systemd restarts the app automatically on crash or reboot.

## 7. nginx reverse proxy

```
sudo apt install -y nginx
```

`/etc/nginx/sites-available/expense-backend`:

```
server {
    listen 80;
    server_name <your-domain-or-ip>;
    location / {
        proxy_pass http://127.0.0.1:8000;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
}
```

```
sudo ln -s /etc/nginx/sites-available/expense-backend /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl reload nginx
```

## 8. SSL via Certbot

```
sudo apt install -y certbot python3-certbot-nginx
sudo certbot --nginx -d <your-domain>
```

Certbot edits the nginx config to serve HTTPS and sets up auto-renewal via a systemd timer — no manual renewal needed. (Requires an actual domain name pointed at the VM's IP; a bare IP address can't get a Let's Encrypt certificate.)

## 9. CI/CD — GitHub Actions deploy

`.github/workflows/deploy.yml`, triggered on push to `main`:

```
name: Deploy
on:
  push:
    branches: [main]
jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: appleboy/ssh-action@v1
        with:
          host: ${{ secrets.VM_HOST }}
          username: deploy
          key: ${{ secrets.VM_SSH_KEY }}
          script: |
            cd /home/deploy/expense-backend
            git pull
            source venv/bin/activate
            pip install -r requirements.txt
            sudo systemctl restart expense-backend
```

`VM_HOST` and `VM_SSH_KEY` are stored as GitHub repo secrets, never committed.

## 10. Backups

A simple daily cron job dumps the database, since there's no managed-backup service on a self-hosted VM:

```
0 2 * * * pg_dump -U expense_app expense_tracker > /home/deploy/backups/db-$(date +\%F).sql
```

Keep a rolling window (e.g. delete dumps older than 14 days) so backups don't quietly fill the VM's disk.
