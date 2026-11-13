FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /source
COPY ./source/ ./
RUN chmod +x ./make.sh ./makejar.sh && ./make.sh && ./makejar.sh

FROM eclipse-temurin:21-jre-jammy AS app
ARG VCS_REF=unknown
LABEL org.opencontainers.image.version="1.6.6" \
      org.opencontainers.image.revision="$VCS_REF" \
      org.opencontainers.image.source="https://github.com/yuanqiuye/EH_hath"
WORKDIR /hath
COPY --from=build /source/build/HentaiAtHome.jar ./
COPY ./hath_run.sh ./
RUN chmod 755 ./hath_run.sh
VOLUME ["/hath/cache", "/hath/data", "/hath/download", "/hath/log", "/hath/tmp"]
CMD ["/hath/hath_run.sh"]
