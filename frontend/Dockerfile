# 前端构建 + Nginx 运行（构建上下文为 ./frontend）
FROM node:22-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
# nginx:alpine 启动时自动对 /etc/nginx/templates/*.template 做 envsubst -> /etc/nginx/conf.d/
COPY docker/nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
ENV CAYLEYWCS_BACKEND_HOST=backend \
    CAYLEYWCS_BACKEND_PORT=20021 \
    CAYLEYWCS_NGINX_PORT=80
EXPOSE 80
