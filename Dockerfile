# Use Java 17
FROM eclipse-temurin:17-jdk-jammy

# Set working directory
WORKDIR /app

# Copy gradle files
COPY . .

# Build the application
RUN ./gradlew clean build -x test

# Expose port (Render provides PORT env var)
EXPOSE 8080

# Run the app
CMD ["sh", "-c", "java -jar build/libs/*.jar"]
