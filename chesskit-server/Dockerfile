# Build stage
FROM node:20-slim AS builder

WORKDIR /app

# Copy package files
COPY package*.json ./
COPY tsconfig.json ./

# Install dependencies
RUN npm ci

# Copy source code
COPY src ./src

# Build TypeScript
RUN npm run build

# Production stage
FROM node:20-slim

WORKDIR /app

# Install wget for downloading stockfish and necessary libraries
RUN apt-get update && \
    apt-get install -y wget && \
    rm -rf /var/lib/apt/lists/*

# Copy package files and install production dependencies only
COPY package*.json ./
RUN npm ci --only=production

# Copy built files from builder
COPY --from=builder /app/dist ./dist

# Create engines directory and download Stockfish for Linux
RUN mkdir -p engines && \
    wget -q https://github.com/official-stockfish/Stockfish/releases/download/sf_16/stockfish-ubuntu-x86-64-modern.tar -O /tmp/stockfish.tar && \
    tar -xf /tmp/stockfish.tar -C /tmp && \
    mv /tmp/stockfish/stockfish-ubuntu-x86-64-modern engines/stockfish && \
    chmod +x engines/stockfish && \
    rm -rf /tmp/stockfish.tar /tmp/stockfish

# Set environment variables
ENV NODE_ENV=production
ENV PORT=8080
ENV HOST=0.0.0.0
ENV ENGINE_PATH=./engines/stockfish

# Expose port
EXPOSE 8080

# Start the server
CMD ["node", "dist/index.js"]
