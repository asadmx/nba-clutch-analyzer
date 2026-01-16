# Use Java 21 (safe for Spring Boot 3+)
FROM eclipse-temurin:21-jdk-jammy

# Set working directory
WORKDIR /app

# Copy Maven wrapper and config
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .

# Download dependencies (cached layer)
RUN ./mvnw dependency:go-offline

# Copy source code
COPY src src

# Build the application
RUN ./mvnw clean package -DskipTests

# Expose Render's port
EXPOSE 8080

# Run the app (Render injects PORT)
CMD ["java", "-jar", "target/*.jar"]
