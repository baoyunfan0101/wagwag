.PHONY: help services services-stop install backend mobile web check check-backend check-mobile

help:
	@echo 'services       Start local PostGIS, SeaweedFS, and Redis'
	@echo 'services-stop  Stop local services without removing data'
	@echo 'install        Install mobile dependencies from the lockfile'
	@echo 'backend        Run the dev API with backend/.env (separate terminal)'
	@echo 'mobile / web   Start Expo or its web preview (separate terminal)'
	@echo 'check          Run backend tests, mobile typecheck, and mobile tests'

services:
	docker compose up -d postgres seaweedfs redis

services-stop:
	docker compose stop postgres seaweedfs redis

install:
	cd mobile && npm ci

backend:
	cd backend && set -a && { test ! -f .env || . ./.env; } && set +a && SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run

mobile:
	cd mobile && npm start

web:
	cd mobile && npm run web

check: check-backend check-mobile

check-backend:
	cd backend && ./mvnw test

check-mobile:
	cd mobile && npm run typecheck && npm test
