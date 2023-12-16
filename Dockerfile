FROM eclipse-temurin:20-jdk-jammy as build
WORKDIR /source
COPY ./source/ ./
RUN chmod +x ./make.sh ./makejar.sh && ./make.sh && ./makejar.sh

FROM openjdk:8-jre-alpine as app

WORKDIR /hath
COPY --from=build /source/build/HentaiAtHome.jar ./
COPY ./hath_run.sh ./

VOLUME ["/hath/cache", "/hath/data", "/hath/download", "/hath/log", "/hath/tmp"]

CMD ["/hath/hath_run.sh"]
