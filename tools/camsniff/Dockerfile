FROM node:20-alpine
RUN apk add --no-cache iptables conntrack-tools
COPY sniff.js /sniff.js
CMD ["node", "/sniff.js"]
