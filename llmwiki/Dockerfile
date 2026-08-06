FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY app/bootstrap/pom.xml app/bootstrap/
COPY app/web/pom.xml app/web/
COPY app/common/dal/pom.xml app/common/dal/
COPY app/common/util/pom.xml app/common/util/
COPY app/common/service/facade/pom.xml app/common/service/facade/
COPY app/common/service/integration/pom.xml app/common/service/integration/
COPY app/domain/model/pom.xml app/domain/model/
COPY app/domain/service/pom.xml app/domain/service/
COPY app/biz/service/pom.xml app/biz/service/
COPY app/test/pom.xml app/test/
RUN mvn dependency:go-offline -B || true

COPY . .
RUN mvn clean package -Dmaven.test.skip=true -B

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

RUN apk add --no-cache python3 py3-pip py3-numpy && \
    pip3 install --no-cache-dir --break-system-packages \
      PyMuPDF pymupdf4llm python-docx openpyxl python-pptx pypdf pdfplumber pandas

COPY --from=build /app/target/boot/*.jar app.jar
COPY ./tools ./tools

ENV JAVA_OPTS="-Xms512m -Xmx1024m -XX:MetaspaceSize=128m -XX:MaxMetaspaceSize=256m"
ENV SPRING_PROFILES_ACTIVE=prod

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /app/app.jar"]