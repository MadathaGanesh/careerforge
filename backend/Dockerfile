FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends fonts-dejavu-core && rm -rf /var/lib/apt/lists/*
COPY pom.xml .
RUN mvn -B -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -B verify
FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl fonts-dejavu-core texlive-latex-base texlive-latex-recommended texlive-latex-extra lmodern && rm -rf /var/lib/apt/lists/*
ENV LATEX_COMMAND=/usr/bin/pdflatex \
    PDF_FONT_PATH=/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf
RUN test -r "$PDF_FONT_PATH" && "$LATEX_COMMAND" --version >/dev/null && \
    for file in article.cls fullpage.sty titlesec.sty hyperref.sty enumitem.sty tabularx.sty lmodern.sty glyphtounicode.tex; do \
      kpsewhich "$file" >/dev/null || { echo "Missing LaTeX dependency: $file" >&2; exit 1; }; \
    done
RUN useradd --system --uid 10001 appuser
WORKDIR /app
COPY --from=build /app/target/careerforge-api-0.1.0.jar /app/api.jar
RUN mkdir /app/storage && chown -R appuser /app
USER appuser
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=45s --retries=6 \
  CMD curl --fail --silent --max-time 4 "http://127.0.0.1:${PORT:-8080}/actuator/health" >/dev/null
ENTRYPOINT ["java","-jar","/app/api.jar"]

