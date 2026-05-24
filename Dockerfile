FROM nginx:alpine
COPY index.html /usr/share/nginx/html/index.html
EXPOSE 80
ENV PORT=80 HOSTNAME=0.0.0.0
