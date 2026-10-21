@echo off
cd /d D:\oura_ring
start "" http://127.0.0.1:8765
python -I app\server.py
